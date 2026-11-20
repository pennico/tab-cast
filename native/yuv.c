// SPDX-License-Identifier: GPL-3.0-or-later
/* RGBA -> YUV 4:2:0, BT.709 limited range. See yuv.h. */
#include "yuv.h"

#include <string.h>

/*
 * Fixed-point BT.709 limited range, coefficients x256 (each row of the chroma matrix sums to 0, luma to 220):
 *   Y = 16 + ((  47 R + 157 G +  16 B + 128) >> 8)
 *   U =      ( -26 R -  86 G + 112 B + 32896) >> 8      32896 = 128 * 256 + 128 (offset + rounding)
 *   V =      ( 112 R - 102 G -  10 B + 32896) >> 8
 * Chroma uses the rounded average of the 2x2 block. The NEON path computes exactly the same integers.
 */
static inline uint8_t luma(int r, int g, int b) { return (uint8_t)(16 + ((47 * r + 157 * g + 16 * b + 128) >> 8)); }
static inline uint8_t cb(int r, int g, int b) { return (uint8_t)((-26 * r - 86 * g + 112 * b + 32896) >> 8); }
static inline uint8_t cr(int r, int g, int b) { return (uint8_t)((112 * r - 102 * g - 10 * b + 32896) >> 8); }

/* Two source rows -> two Y rows + one chroma row, pixels [x0, x1) (both even). */
static void rows_c(const uint8_t *s0, const uint8_t *s1, uint8_t *y0, uint8_t *y1, uint8_t *u, uint8_t *v, int ups,
                   int x0, int x1) {
    for (int x = x0; x < x1; x += 2) {
        const uint8_t *a = s0 + 4 * x, *b = a + 4, *c = s1 + 4 * x, *d = c + 4;
        y0[x] = luma(a[0], a[1], a[2]);
        y0[x + 1] = luma(b[0], b[1], b[2]);
        y1[x] = luma(c[0], c[1], c[2]);
        y1[x + 1] = luma(d[0], d[1], d[2]);
        int r = (a[0] + b[0] + c[0] + d[0] + 2) >> 2;
        int g = (a[1] + b[1] + c[1] + d[1] + 2) >> 2;
        int bl = (a[2] + b[2] + c[2] + d[2] + 2) >> 2;
        int i = (x >> 1) * ups;
        u[i] = cb(r, g, bl);
        v[i] = cr(r, g, bl);
    }
}

void tc_rgba_to_yuv420_c(const uint8_t *rgba, int rgba_stride, int w, int h, const tc_yuv_dst *d) {
    for (int j = 0; j + 1 < h; j += 2) {
        const uint8_t *s0 = rgba + (long)j * rgba_stride;
        rows_c(s0, s0 + rgba_stride, d->y + (long)j * d->y_stride, d->y + (long)(j + 1) * d->y_stride,
               d->u + (long)(j >> 1) * d->uv_stride, d->v + (long)(j >> 1) * d->uv_stride, d->uv_pixel_stride, 0, w);
    }
}

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>

static inline uint8x16_t luma16(uint8x16x4_t p) {
    uint16x8_t lo = vmull_u8(vget_low_u8(p.val[0]), vdup_n_u8(47));
    lo = vmlal_u8(lo, vget_low_u8(p.val[1]), vdup_n_u8(157));
    lo = vmlal_u8(lo, vget_low_u8(p.val[2]), vdup_n_u8(16));
    uint16x8_t hi = vmull_u8(vget_high_u8(p.val[0]), vdup_n_u8(47));
    hi = vmlal_u8(hi, vget_high_u8(p.val[1]), vdup_n_u8(157));
    hi = vmlal_u8(hi, vget_high_u8(p.val[2]), vdup_n_u8(16));
    return vaddq_u8(vcombine_u8(vrshrn_n_u16(lo, 8), vrshrn_n_u16(hi, 8)), vdupq_n_u8(16));
}

enum { NV12, NV21, I420, OTHER };

void tc_rgba_to_yuv420(const uint8_t *rgba, int rgba_stride, int w, int h, const tc_yuv_dst *d) {
    int ups = d->uv_pixel_stride;
    int mode = ups == 2 && d->v == d->u + 1 ? NV12 : ups == 2 && d->u == d->v + 1 ? NV21 : ups == 1 ? I420 : OTHER;
    int n16 = mode == OTHER ? 0 : w / 16;
    const uint16x8_t k = vdupq_n_u16(32896);
    for (int j = 0; j + 1 < h; j += 2) {
        const uint8_t *s0 = rgba + (long)j * rgba_stride, *s1 = s0 + rgba_stride;
        uint8_t *y0 = d->y + (long)j * d->y_stride, *y1 = y0 + d->y_stride;
        uint8_t *u = d->u + (long)(j >> 1) * d->uv_stride, *v = d->v + (long)(j >> 1) * d->uv_stride;
        for (int i = 0; i < n16; i++) {
            uint8x16x4_t p0 = vld4q_u8(s0 + 64 * i);
            uint8x16x4_t p1 = vld4q_u8(s1 + 64 * i);
            vst1q_u8(y0 + 16 * i, luma16(p0));
            vst1q_u8(y1 + 16 * i, luma16(p1));
            /* 2x2 sums (max 1020) -> rounded averages */
            uint16x8_t r = vrshrq_n_u16(vpadalq_u8(vpaddlq_u8(p0.val[0]), p1.val[0]), 2);
            uint16x8_t g = vrshrq_n_u16(vpadalq_u8(vpaddlq_u8(p0.val[1]), p1.val[1]), 2);
            uint16x8_t b = vrshrq_n_u16(vpadalq_u8(vpaddlq_u8(p0.val[2]), p1.val[2]), 2);
            /* Modular 16-bit arithmetic: the true result lies in 4336..61456, so wrap-around cancels out. */
            uint8x8_t uu = vshrn_n_u16(vmlsq_n_u16(vmlsq_n_u16(vmlaq_n_u16(k, b, 112), r, 26), g, 86), 8);
            uint8x8_t vv = vshrn_n_u16(vmlsq_n_u16(vmlsq_n_u16(vmlaq_n_u16(k, r, 112), g, 102), b, 10), 8);
            if (mode == NV12) {
                uint8x8x2_t t = {{uu, vv}};
                vst2_u8(u + 16 * i, t);
            } else if (mode == NV21) {
                uint8x8x2_t t = {{vv, uu}};
                vst2_u8(v + 16 * i, t);
            } else {
                vst1_u8(u + 8 * i, uu);
                vst1_u8(v + 8 * i, vv);
            }
        }
        rows_c(s0, s1, y0, y1, u, v, ups, 16 * n16, w);
    }
}

int tc_yuv_has_neon(void) { return 1; }

#else

void tc_rgba_to_yuv420(const uint8_t *rgba, int rgba_stride, int w, int h, const tc_yuv_dst *d) {
    tc_rgba_to_yuv420_c(rgba, rgba_stride, w, h, d);
}

int tc_yuv_has_neon(void) { return 0; }

#endif

tc_yuv_dst tc_yuv_dst_at(const tc_yuv_dst *d, int x, int y) {
    tc_yuv_dst r = *d;
    r.y += (long)y * d->y_stride + x;
    long c = (long)(y >> 1) * d->uv_stride + (long)(x >> 1) * d->uv_pixel_stride;
    r.u += c;
    r.v += c;
    return r;
}

void tc_yuv420_fill_black(const tc_yuv_dst *dst, int x, int y, int w, int h) {
    tc_yuv_dst d = tc_yuv_dst_at(dst, x, y);
    for (int j = 0; j < h; j++) memset(d.y + (long)j * d.y_stride, 16, (size_t)w);
    for (int j = 0; j < h / 2; j++) {
        uint8_t *u = d.u + (long)j * d.uv_stride, *v = d.v + (long)j * d.uv_stride;
        for (int i = 0; i < w / 2; i++) u[i * d.uv_pixel_stride] = v[i * d.uv_pixel_stride] = 128;
    }
}
