// SPDX-License-Identifier: GPL-3.0-or-later
/* JNI glue of the probe: RGBA bitmap -> YUV planes of an encoder input buffer (native/yuv.c), and cheap picture
 * signatures (mean brightness, dark share, hash of a region) so the Kotlin side can tell whether a copied frame
 * changed and whether the page's video is in it without slow per-pixel Kotlin loops. */
#define _POSIX_C_SOURCE 199309L
#include <android/bitmap.h>
#include <jni.h>
#include <time.h>

#include "yuv.h"

static jlong now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (jlong)t.tv_sec * 1000000000LL + t.tv_nsec;
}

/* Address of a direct buffer if it holds at least `need` bytes, else NULL. */
static uint8_t *direct(JNIEnv *env, jobject buf, long need) {
    if (!buf) return NULL;
    uint8_t *p = (*env)->GetDirectBufferAddress(env, buf);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buf);
    return p && cap >= need ? p : NULL;
}

/* Builds the destination; checks that a w x h picture at (x, y) fits all three buffers. */
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

/* Returns the conversion time in ns; -1 bad bitmap, -2 buffers too small or bad layout. */
static jlong convert(JNIEnv *env, jclass cls, jobject bmp, jobject yb, jint ys, jobject ub, jobject vb, jint uvs,
                     jint ups, jint x, jint y, jboolean plain_c) {
    (void)cls;
    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return -1;
    tc_yuv_dst d;
    if (!dst_for(env, &d, yb, ys, ub, vb, uvs, ups, x, y, (jint)info.width, (jint)info.height)) return -2;
    if (AndroidBitmap_lockPixels(env, bmp, &px) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;
    tc_yuv_dst at = tc_yuv_dst_at(&d, x, y);
    jlong t0 = now_ns();
    if (plain_c)
        tc_rgba_to_yuv420_c(px, (int)info.stride, (int)info.width, (int)info.height, &at);
    else
        tc_rgba_to_yuv420(px, (int)info.stride, (int)info.width, (int)info.height, &at);
    jlong t1 = now_ns();
    AndroidBitmap_unlockPixels(env, bmp);
    return t1 - t0;
}

/* Probe 2: RGBA from a direct buffer (ImageReader plane), w x h with `ss` bytes per row. Time in ns or -1/-2. */
static jlong convert_buffer(JNIEnv *env, jclass cls, jobject src, jint ss, jint w, jint h, jobject yb, jint ys, jobject ub,
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

/* {mean brightness 0..255, dark samples (< 24), samples, FNV-1a hash} of every `step`-th pixel of a region. */
static void signature(const uint8_t *p, long stride, int x, int y, int w, int h, int step, jlong out[4]) {
    unsigned long long sum = 0, dark = 0, n = 0;
    uint32_t hash = 2166136261u;
    for (int j = y; j < y + h; j += step) {
        const uint8_t *row = p + (long)j * stride;
        for (int i = x; i < x + w; i += step) {
            const uint8_t *q = row + 4 * i;
            unsigned l = (2u * q[0] + 5u * q[1] + q[2]) >> 3;
            sum += l;
            dark += l < 24;
            n++;
            for (int k = 0; k < 3; k++) hash = (hash ^ q[k]) * 16777619u;
        }
    }
    out[0] = n ? (jlong)(sum / n) : 0;
    out[1] = (jlong)dark;
    out[2] = (jlong)n;
    out[3] = (jlong)hash;
}

static jlongArray to_array(JNIEnv *env, const jlong v[4]) {
    jlongArray a = (*env)->NewLongArray(env, 4);
    if (a) (*env)->SetLongArrayRegion(env, a, 0, 4, v);
    return a;
}

/* Region clipped to the picture; NULL when it is empty or the bitmap can't be read. */
static jlongArray bitmap_sig(JNIEnv *env, jclass cls, jobject bmp, jint x, jint y, jint w, jint h, jint step) {
    (void)cls;
    AndroidBitmapInfo info;
    void *px;
    if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || step < 1)
        return NULL;
    if (x < 0) { w += x; x = 0; }
    if (y < 0) { h += y; y = 0; }
    if (x + w > (jint)info.width) w = (jint)info.width - x;
    if (y + h > (jint)info.height) h = (jint)info.height - y;
    if (w <= 0 || h <= 0) return NULL;
    if (AndroidBitmap_lockPixels(env, bmp, &px) != ANDROID_BITMAP_RESULT_SUCCESS) return NULL;
    jlong out[4];
    signature(px, info.stride, x, y, w, h, step, out);
    AndroidBitmap_unlockPixels(env, bmp);
    return to_array(env, out);
}

/* The same for an RGBA buffer (ImageReader plane): width/height = picture size, stride = row stride in bytes. */
static jlongArray buffer_sig(JNIEnv *env, jclass cls, jobject buf, jint width, jint height, jint stride, jint x,
                             jint y, jint w, jint h, jint step) {
    (void)cls;
    if (step < 1 || stride < 4 * width) return NULL;
    if (x < 0) { w += x; x = 0; }
    if (y < 0) { h += y; y = 0; }
    if (x + w > width) w = width - x;
    if (y + h > height) h = height - y;
    if (w <= 0 || h <= 0) return NULL;
    const uint8_t *p = direct(env, buf, (long)(y + h - 1) * stride + 4L * (x + w));
    if (!p) return NULL;
    jlong out[4];
    signature(p, stride, x, y, w, h, step, out);
    return to_array(env, out);
}

static const JNINativeMethod methods[] = {
    {"convert", "(Landroid/graphics/Bitmap;Ljava/nio/ByteBuffer;ILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIZ)J",
     (void *)convert},
    {"convertBuffer",
     "(Ljava/nio/ByteBuffer;IIILjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIII)J",
     (void *)convert_buffer},
    {"fillBlack", "(Ljava/nio/ByteBuffer;ILjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIII)Z", (void *)fill_black},
    {"hasNeon", "()Z", (void *)has_neon},
    {"bitmapSig", "(Landroid/graphics/Bitmap;IIIII)[J", (void *)bitmap_sig},
    {"bufferSig", "(Ljava/nio/ByteBuffer;IIIIIIII)[J", (void *)buffer_sig},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = (*env)->FindClass(env, "dk/pennico/tabcast/probe/Native");
    if (!c || (*env)->RegisterNatives(env, c, methods, sizeof methods / sizeof methods[0]) != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}
