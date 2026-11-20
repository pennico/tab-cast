// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.probe

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** C side (probe/src/main/jni/probe_jni.c + native/yuv.c). */
object Native {
    init { System.loadLibrary("tcprobe") }

    /**
     * Converts an RGBA_8888 bitmap into YUV 4:2:0 planes (BT.709 limited range) at (x, y) of the destination.
     * The chroma layout is that of an android.media.Image (NV12, NV21 or I420). Returns the conversion time in ns,
     * -1 for a bad bitmap, -2 when the buffers are too small or the layout is invalid.
     */
    external fun convert(bmp: Bitmap, y: ByteBuffer, yStride: Int, u: ByteBuffer, v: ByteBuffer, uvStride: Int,
        uvPixelStride: Int, x: Int, yPos: Int, plainC: Boolean): Long

    /**
     * Probe 2: the same from an RGBA buffer ([src], [srcStride] bytes per row, [w] x [h]; an ImageReader plane), so
     * mirrored frames go to the encoder without a Bitmap copy.
     */
    external fun convertBuffer(src: ByteBuffer, srcStride: Int, w: Int, h: Int, y: ByteBuffer, yStride: Int, u: ByteBuffer,
        v: ByteBuffer, uvStride: Int, uvPixelStride: Int, x: Int, yPos: Int): Long

    external fun fillBlack(y: ByteBuffer, yStride: Int, u: ByteBuffer, v: ByteBuffer, uvStride: Int, uvPixelStride: Int,
        x: Int, yPos: Int, w: Int, h: Int): Boolean

    external fun hasNeon(): Boolean

    /** {mean brightness, dark samples, samples, hash} of a region (every [step]-th pixel), or null. */
    external fun bitmapSig(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int, step: Int): LongArray?

    external fun bufferSig(buf: ByteBuffer, width: Int, height: Int, stride: Int, x: Int, y: Int, w: Int, h: Int, step: Int): LongArray?
}
