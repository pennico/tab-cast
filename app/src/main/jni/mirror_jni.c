// SPDX-License-Identifier: GPL-3.0-or-later
/* JNI glue of "Mirror screen" (0.5.0): a captured RGBA frame (ImageReader plane) -> the VP8 encoder's YUV 4:2:0 input
 * buffer, with native/yuv.c (NEON on the tablet). Same checks as the probe's glue (probe/src/main/jni/probe_jni.c). */
#define _POSIX_C_SOURCE 199309L
#include <jni.h>
#include <time.h>

#include "yuv.h"

static jlong now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (jlong)t.tv_sec * 1000000000LL + t.tv_nsec;
}

static uint8_t *direct(JNIEnv *env, jobject buf, long need) {
    if (!buf) return NULL;
    uint8_t *p = (*env)->GetDirectBufferAddress(env, buf);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buf);
    return p && cap >= need ? p : NULL;
}

static int dst_for(JNIEnv *env, tc_yuv_dst *d, jobject yb, jint ys, jobject ub, jobject vb, jint uvs, jint ups,
                   jint x, jint y, jint w, jint h) {
    if (w <= 0 || h <= 0 || (w | h | x | y) & 1 || x < 0 || y < 0 || ups < 1 || ups > 2 || ys < x + w ||
        uvs < (x + w) / 2 * ups - (ups - 1))
        return 0;
    long ny = (long)(y + h - 1) * ys + x + w;
    long nc = (long)((y + h) / 2 - 1) * uvs + (long)((x + w) / 2 - 1) * ups + 1;
    d->y = direct(env, yb, ny);
    d->u = direct(env, ub, nc);
    d->v = direct(env, vb, nc);
    d->y_stride = ys;
    d->uv_stride = uvs;
    d->uv_pixel_stride = ups;
    return d->y && d->u && d->v;
}

/* RGBA w x h (`ss` bytes per row) into the planes at (x, y). Time in ns; -1 bad source, -2 bad destination. */
static jlong convert(JNIEnv *env, jclass cls, jobject src, jint ss, jint w, jint h, jobject yb, jint ys, jobject ub,
                     jobject vb, jint uvs, jint ups, jint x, jint y) {
    (void)cls;
    if (w <= 0 || h <= 0 || ss < 4 * w) return -1;
    const uint8_t *px = direct(env, src, (long)(h - 1) * ss + 4L * w);
    if (!px) return -1;
    tc_yuv_dst d;
    if (!dst_for(env, &d, yb, ys, ub, vb, uvs, ups, x, y, w, h)) return -2;
    tc_yuv_dst at = tc_yuv_dst_at(&d, x, y);
    jlong t0 = now_ns();
    tc_rgba_to_yuv420(px, (int)ss, (int)w, (int)h, &at);
    return now_ns() - t0;
}

static jboolean fill_black(JNIEnv *env, jclass cls, jobject yb, jint ys, jobject ub, jobject vb, jint uvs, jint ups,
                           jint x, jint y, jint w, jint h) {
    (void)cls;
    tc_yuv_dst d;
    if (!dst_for(env, &d, yb, ys, ub, vb, uvs, ups, x, y, w, h)) return JNI_FALSE;
    tc_yuv420_fill_black(&d, x, y, w, h);
    return JNI_TRUE;
}

static jboolean has_neon(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return tc_yuv_has_neon() ? JNI_TRUE : JNI_FALSE;
}

static const JNINativeMethod methods[] = {
    {"convert", "(Ljava/nio/ByteBuffer;IIILjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIII)J",
     (void *)convert},
    {"fillBlack", "(Ljava/nio/ByteBuffer;ILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIII)Z", (void *)fill_black},
    {"hasNeon", "()Z", (void *)has_neon},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = (*env)->FindClass(env, "dk/pennico/tabcast/mirror/Yuv");
    if (!c || (*env)->RegisterNatives(env, c, methods, sizeof methods / sizeof methods[0]) != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}
