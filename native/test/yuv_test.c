// SPDX-License-Identifier: GPL-3.0-or-later
#define _POSIX_C_SOURCE 199309L
/* Host test for native/yuv.c: fixed-point output vs. the BT.709 formulas in floating point, all chroma layouts,
 * letterbox helpers. `make -C native test` (gcc, ASan + UBSan). The NEON path is checked on the tablet itself
 * (the probe compares it with the C path on real frames). */
#include "yuv.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
    fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } } while (0)

static double ref_y(double r, double g, double b) { return 16 + 219 * (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255; }
static double ref_u(double r, double g, double b) { return 128 + 224 * (-0.2126 * r - 0.7152 * g + 0.9278 * b) / 1.8556 / 255; }
static double ref_v(double r, double g, double b) { return 128 + 224 * (0.7874 * r - 0.7152 * g - 0.0722 * b) / 1.5748 / 255; }

static unsigned rnd(void) { static unsigned s = 12345; s = s * 1103515245u + 12345u; return s >> 8; }

/* Converts a random w x h picture into the given layout; checks every sample against the float formulas. */
static void check_layout(const char *name, int w, int h, int ups, int nv21) {
    int stride = 4 * w + 12;            /* padded rows, like a bitmap with row padding */
    uint8_t *rgba = malloc((size_t)stride * h);
    for (int i = 0; i < stride * h; i++) rgba[i] = (uint8_t)rnd();
    int ys = w + 8, uvs = (ups == 2 ? w : w / 2) + 6;
    uint8_t *y = calloc((size_t)ys * h, 1), *c = calloc((size_t)uvs * h, 1);   /* chroma: generous */
    tc_yuv_dst d = { y, ys, c, c + (ups == 2 ? 1 : (size_t)uvs * h / 2), uvs, ups };
    if (nv21) { d.v = c; d.u = c + 1; }
    tc_rgba_to_yuv420(rgba, stride, w, h, &d);
    double worst = 0;
    for (int j = 0; j < h; j++) for (int x = 0; x < w; x++) {
        const uint8_t *p = rgba + j * stride + 4 * x;
        double e = fabs(y[j * ys + x] - ref_y(p[0], p[1], p[2]));
        if (e > worst) worst = e;
    }
    CHECK(worst <= 1.0, "%s luma off by %.2f", name, worst);
    worst = 0;
    for (int j = 0; j < h / 2; j++) for (int x = 0; x < w / 2; x++) {
        double r = 0, g = 0, b = 0;
        for (int k = 0; k < 4; k++) {
            const uint8_t *p = rgba + (2 * j + k / 2) * stride + 4 * (2 * x + k % 2);
            r += p[0] / 4.0; g += p[1] / 4.0; b += p[2] / 4.0;
        }
        double eu = fabs(d.u[j * uvs + x * ups] - ref_u(r, g, b)), ev = fabs(d.v[j * uvs + x * ups] - ref_v(r, g, b));
        if (eu > worst) worst = eu;
        if (ev > worst) worst = ev;
    }
    CHECK(worst <= 1.6, "%s chroma off by %.2f", name, worst);
    printf("%-5s %dx%d ok\n", name, w, h);
    free(rgba); free(y); free(c);
}

static void check_extremes(void) {
    uint8_t px[4 * 4] = {0};
    uint8_t y[4], u[1], v[1];
    tc_yuv_dst d = { y, 2, u, v, 1, 1 };
    struct { uint8_t r, g, b, y, u, v; } t[] = {
        {0, 0, 0, 16, 128, 128}, {255, 255, 255, 235, 128, 128}, {255, 0, 0, 63, 102, 240},
        {0, 255, 0, 172, 42, 26}  /* float 172.6 */, {0, 0, 255, 32, 240, 118},
    };
    for (size_t i = 0; i < sizeof t / sizeof t[0]; i++) {
        for (int k = 0; k < 4; k++) { px[4 * k] = t[i].r; px[4 * k + 1] = t[i].g; px[4 * k + 2] = t[i].b; }
        tc_rgba_to_yuv420_c(px, 8, 2, 2, &d);
        CHECK(y[0] == t[i].y && u[0] == t[i].u && v[0] == t[i].v, "rgb %d,%d,%d -> %d,%d,%d (want %d,%d,%d)",
              t[i].r, t[i].g, t[i].b, y[0], u[0], v[0], t[i].y, t[i].u, t[i].v);
    }
}

static void check_letterbox(void) {
    enum { W = 1280, H = 720, PW = 960 };
    uint8_t *y = malloc(W * H), *c = malloc(W * H / 2);
    memset(y, 0, W * H); memset(c, 0, W * H / 2);
    tc_yuv_dst d = { y, W, c, c + 1, W, 2 };
    tc_yuv420_fill_black(&d, 0, 0, W, H);
    uint8_t *rgba = malloc(4 * PW * H);
    memset(rgba, 255, 4 * PW * H);
    tc_yuv_dst in = tc_yuv_dst_at(&d, (W - PW) / 2, 0);
    tc_rgba_to_yuv420(rgba, 4 * PW, PW, H, &in);
    CHECK(y[0] == 16 && y[159] == 16 && y[160] == 235 && y[1119] == 235 && y[1120] == 16 && y[W * H - 1] == 16,
          "letterbox luma %d %d %d %d %d", y[0], y[159], y[160], y[1119], y[1120]);
    CHECK(c[158] == 128 && c[159] == 128 && c[160] == 128 && c[1118] == 128 && c[1120] == 128, "letterbox chroma");
    free(y); free(c); free(rgba);
}

int main(void) {
    check_extremes();
    check_layout("NV12", 64, 32, 2, 0);
    check_layout("NV21", 64, 32, 2, 1);
    check_layout("I420", 64, 32, 1, 0);
    check_layout("NV12", 1280, 720, 2, 0);
    check_layout("odd16", 70, 6, 2, 0);   /* width not a multiple of 16: scalar tail */
    check_letterbox();
    /* Speed on this machine, for reference only (the tablet number comes from the probe). */
    enum { W = 1280, H = 720 };
    uint8_t *rgba = malloc(4 * W * H), *y = malloc(W * H), *c = malloc(W * H / 2);
    for (int i = 0; i < 4 * W * H; i++) rgba[i] = (uint8_t)rnd();
    tc_yuv_dst d = { y, W, c, c + 1, W, 2 };
    struct timespec a, b;
    clock_gettime(CLOCK_MONOTONIC, &a);
    for (int i = 0; i < 50; i++) tc_rgba_to_yuv420(rgba, 4 * W, W, H, &d);
    clock_gettime(CLOCK_MONOTONIC, &b);
    printf("1280x720: %.2f ms/frame (neon=%d)\n", ((b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6) / 50,
           tc_yuv_has_neon());
    free(rgba); free(y); free(c);
    if (fails) { fprintf(stderr, "%d check(s) failed\n", fails); return 1; }
    puts("all yuv checks passed");
    return 0;
}
