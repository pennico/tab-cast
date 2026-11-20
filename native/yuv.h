// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * RGBA -> YUV 4:2:0 for the "Cast this tab" encoder input (Tab Cast 0.2).
 *
 * Pure C, no Android headers, so it builds and is tested on the build machine with gcc (native/Makefile) and is
 * compiled into the app with the NDK (NEON on armeabi-v7a).
 *
 * Input: RGBA_8888 as Android bitmaps store it in memory (bytes R, G, B, A). Output: BT.709 limited range
 * ("TV range", Y 16..235, U/V 16..240), chroma from the 2x2 average. The chroma layout is described like an
 * android.media.Image: separate U and V pointers, a row stride and a pixel stride, so NV12 (U,V interleaved:
 * v == u + 1, pixel stride 2), NV21 (u == v + 1) and I420 (pixel stride 1) are all the same call.
 *
 * Width and height must be even (1280x720, 960x720, ...).
 */
#ifndef TC_YUV_H
#define TC_YUV_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    uint8_t *y;
    int y_stride;
    uint8_t *u;
    uint8_t *v;
    int uv_stride;
    int uv_pixel_stride; /* 1 = planar (I420), 2 = semi-planar (NV12 / NV21) */
} tc_yuv_dst;

/* Best implementation for this CPU (NEON when compiled for ARM with NEON, else plain C). */
void tc_rgba_to_yuv420(const uint8_t *rgba, int rgba_stride, int w, int h, const tc_yuv_dst *dst);

/* Plain C version, always available (reference and fallback). */
void tc_rgba_to_yuv420_c(const uint8_t *rgba, int rgba_stride, int w, int h, const tc_yuv_dst *dst);

/* 1 if tc_rgba_to_yuv420 uses NEON in this build. */
int tc_yuv_has_neon(void);

/* Fill a w x h area at (x, y) of the destination with black (Y 16, U/V 128): letterbox bars. x, y, w, h even. */
void tc_yuv420_fill_black(const tc_yuv_dst *dst, int x, int y, int w, int h);

/* The destination moved to (x, y): for drawing a smaller picture into a bigger frame. x, y even. */
tc_yuv_dst tc_yuv_dst_at(const tc_yuv_dst *dst, int x, int y);

#ifdef __cplusplus
}
#endif

#endif
