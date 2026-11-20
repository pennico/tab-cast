// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.mirror

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import dk.pennico.tabcast.AppLog
import java.nio.ByteBuffer

/** C side (app/src/main/jni/mirror_jni.c + native/yuv.c), loaded only when mirroring starts. */
object Yuv {
    private var loaded = false
    @Synchronized fun load() { if (!loaded) { System.loadLibrary("tcmirror"); loaded = true } }

    external fun convert(src: ByteBuffer, srcStride: Int, w: Int, h: Int, y: ByteBuffer, yStride: Int, u: ByteBuffer,
        v: ByteBuffer, uvStride: Int, uvPixelStride: Int, x: Int, yPos: Int): Long
    external fun fillBlack(y: ByteBuffer, yStride: Int, u: ByteBuffer, v: ByteBuffer, uvStride: Int, uvPixelStride: Int,
        x: Int, yPos: Int, w: Int, h: Int): Boolean
    external fun hasNeon(): Boolean
}

/**
 * 0.5.0: the VP8 encoder of "Mirror screen". Probe 1 found no H.264 encoder an app can use on the Tab S2 (Codec2
 * gralloc import), and OMX.Exynos.VP8.Encoder as the one hardware encoder; c2.android.vp8.encoder (software) is the
 * fallback. ByteBuffer input: the captured RGBA frame is converted in C (NEON) into the codec's own layout (stride,
 * slice height, NV12 or I420 from its input format), as the probe measured. CBR, a key frame every 2 s and on request
 * (the TV asks after a gap), real-time priority where the codec takes it.
 *
 * 0.5.2 (the 0.5.0 app died 3-30 s into mirroring with "Invalid to call at Released state" from
 * dequeueOutputBuffer): the codec runs in async mode on [handler], so Android reports why it ends (onError with a
 * CodecException: reclaimed for another app, out of resources, the component died). Every codec call is guarded; any
 * failure ends this encoder once through [onLost] with the reason, and the caller makes a new one. Nothing here throws
 * after the constructor.
 *
 * [names]: the codecs to try in order (see [hardware] / [software]).
 */
class MirrorEncoder(val w: Int, val h: Int, private val bitrate: Int, val fps: Int, names: List<String>,
                    private val handler: Handler, private val onFrame: (ByteArray, Boolean, Long) -> Unit,
                    private val onLost: (MirrorEncoder, String) -> Unit) {
    val codec: MediaCodec
    val name: String
    /** Which settings the codec took: "full" (CBR + real-time), "realtime" (no CBR) or "minimal". */
    var settings = ""; private set
    private var stride = w
    private var slice = h
    private var planar = false
    /**
     * 0.5.1 (probe 2: c2.android.vp8.encoder made the converter write past the codec's memory, SIGSEGV): the C code
     * writes only into this buffer of our own, sized from the layout; Java then copies it into the codec's input
     * buffer with bounds checks (~1 MB per picture, well under a millisecond).
     */
    private lateinit var staging: ByteBuffer
    /** Input buffers the codec handed us (async mode), used one per picture. */
    private val free = ArrayDeque<Int>()
    @Volatile var lost = false; private set
    @Volatile var frames = 0L; private set
    @Volatile var bytes = 0L; private set
    private var badPictures = 0

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) { if (!lost) free.addLast(index) }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (lost) return
            val out = try {
                val b = c.getOutputBuffer(index)
                val d = if (b != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)
                    ByteArray(info.size).also { b.position(info.offset); b.limit(info.offset + info.size); b.get(it) } else null
                c.releaseOutputBuffer(index, false)
                d
            } catch (e: Exception) { lose("output: ${describe(e)}"); return }
            if (out != null) {
                frames++; bytes += out.size
                onFrame(out, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs)
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (lost) return
            // Transient: Android says the call may work later; the codec is still running.
            if (e.isTransient) { AppLog.i("mirror: encoder $name: passing error (${describe(e)}); carrying on"); return }
            lose(describe(e))
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
    }

    init {
        Yuv.load()
        var made: Pair<MediaCodec, String>? = null
        var why = "no VP8 encoder to try"
        for (n in names) {
            val c = try { MediaCodec.createByCodecName(n) } catch (e: Exception) { why = "$n: ${describe(e)}"; continue }
            try { c.setCallback(callback, handler); configure(c); made = c to n; break } catch (e: Exception) {
                why = "$n: ${describe(e)}"; AppLog.i("mirror: encoder $n refused (${describe(e)}); trying the next"); runCatching { c.release() }
            }
        }
        val (c, n) = made ?: throw IllegalStateException(why)
        codec = c; name = n
        try {
            val f = codec.inputFormat
            stride = f.intOr("stride", w).coerceAtLeast(w)
            slice = f.intOr("slice-height", h).coerceAtLeast(h)
            val color = f.intOr(MediaFormat.KEY_COLOR_FORMAT, -1)
            planar = color == CodecCapabilities.COLOR_FormatYUV420Planar
            staging = ByteBuffer.allocateDirect(stride * slice * 3 / 2)
            codec.start()
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw IllegalStateException("$n didn't start: ${describe(e)}")
        }
        AppLog.i("mirror: encoder $name${if (isHardware(codec.codecInfo)) " (hardware)" else " (software)"} ${w}x$h $fps fps " +
            "${bitrate / 1000} kbit/s, settings $settings, input ${if (planar) "I420" else "NV12"} stride $stride/$slice, NEON ${Yuv.hasNeon()}")
    }

    private fun configure(c: MediaCodec) {
        val caps = c.codecInfo.getCapabilitiesForType(MIME)
        // A ByteBuffer's layout is only defined for the explicit formats (I420, NV12), not for "flexible" (probe 2).
        val color = listOf(CodecCapabilities.COLOR_FormatYUV420Planar, CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            .firstOrNull { it in caps.colorFormats } ?: throw IllegalArgumentException("no I420/NV12 input")
        val cbr = caps.encoderCapabilities.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR)
        // Probe 2: the Exynos encoder refuses a "full" set; 0.5.2 tries without CBR before the minimal one, so the
        // real-time priority stays when it can, and logs which set stuck.
        fun format(tier: String) = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (tier == "minimal") return@apply
            if (tier == "full" && cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        var last: Exception? = null
        for (tier in listOf("full", "realtime", "minimal")) {
            try { c.configure(format(tier), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); settings = tier; last = null; break }
            catch (e: Exception) { last = e; c.reset(); c.setCallback(callback, handler) }
        }
        last?.let { throw it }
        val got = c.inputFormat.intOr(MediaFormat.KEY_COLOR_FORMAT, -1)
        if (got != CodecCapabilities.COLOR_FormatYUV420Planar && got != CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            throw IllegalArgumentException("input layout $got isn't I420 or NV12")
    }

    /**
     * Converts an RGBA picture ([pw] x [ph], [rowStride] bytes per row) into a free input buffer at (x, y), black
     * around it, and queues it. False when the encoder has no free buffer (the frame is skipped: it is still busy),
     * or is gone.
     */
    fun offer(rgba: ByteBuffer, rowStride: Int, pw: Int, ph: Int, x: Int, y: Int, ptsUs: Long): Boolean {
        if (lost) return false
        val idx = free.removeFirstOrNull() ?: return false
        val ySize = stride * slice
        val size = ySize * 3 / 2
        try {
            val buf = codec.getInputBuffer(idx) ?: run { codec.queueInputBuffer(idx, 0, 0, ptsUs, 0); return false }
            if (buf.capacity() < size) { lose("its input buffer holds ${buf.capacity()} B, a picture needs $size"); return false }
            val st = staging
            val u = st.at(ySize)
            val v = if (planar) st.at(ySize + (stride / 2) * (slice / 2)) else st.at(ySize + 1)
            val uvStride = if (planar) stride / 2 else stride
            val ups = if (planar) 1 else 2
            if (pw != w || ph != h) Yuv.fillBlack(st.at(0), stride, u, v, uvStride, ups, 0, 0, w, h)
            val ns = Yuv.convert(rgba, rowStride, pw, ph, st.at(0), stride, u, v, uvStride, ups, x, y)
            if (ns < 0) {
                codec.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                if (badPictures++ % 300 == 0) AppLog.i("mirror: convert failed ($ns) ${pw}x$ph into ${w}x$h (${badPictures}x)")
                return false
            }
            buf.clear()
            buf.put(st.duplicate().apply { clear(); limit(size) })
            codec.queueInputBuffer(idx, 0, size, ptsUs, 0)
            return true
        } catch (e: Exception) { lose("input: ${describe(e)}"); return false }
    }

    fun requestKeyFrame() { if (!lost) runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } }

    fun setBitrate(bps: Int) { if (!lost) runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) } }

    /** Ends this encoder once: logs Android's own codec record (error code, state) and tells [onLost]. */
    private fun lose(why: String) {
        if (lost) return
        lost = true
        free.clear()
        val m = metrics()
        release(wait = false)
        onLost(this, if (m.isEmpty()) why else "$why; codec record: $m")
    }

    /** The codec's metrics (Android 8+): error code and state when it failed, as far as the platform kept them. */
    private fun metrics(): String = runCatching {
        val b = codec.metrics
        b.keySet().filter { k -> listOf("err", "state", "reclaim", "resource", "frames", "lifetime").any { it in k.lowercase() } }
            .sorted().joinToString(", ") { k -> "${k.removePrefix("android.media.mediacodec.")}=${b.get(k)}" }
    }.getOrDefault("").take(400)

    /** stop + release on a side thread (a broken codec can hang there); [wait]: up to 3 s for it. */
    fun release(wait: Boolean = true) {
        lost = true
        val t = Thread({ runCatching { codec.stop() }; runCatching { codec.release() } }, "mirror-codec-release")
        t.start(); if (wait) t.join(3000)
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_VP8
        val PREFERRED = listOf("OMX.Exynos.VP8.Encoder", "c2.android.vp8.encoder", "OMX.google.vp8.encoder")

        private fun encoders() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && MIME in it.supportedTypes }
        private fun ordered(infos: List<MediaCodecInfo>) =
            infos.map { it.name }.let { all -> PREFERRED.filter { it in all } + all.filter { it !in PREFERRED } }

        fun isHardware(i: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29) i.isHardwareAccelerated
            else !i.name.startsWith("OMX.google.") && !i.name.startsWith("c2.android.")

        /** The device's hardware VP8 encoders, preferred first (the tablet: OMX.Exynos.VP8.Encoder). */
        fun hardware(): List<String> = ordered(encoders().filter { isHardware(it) })
        /** The software VP8 encoders (c2.android.vp8.encoder): slower, but they don't need the video chip. */
        fun software(): List<String> = ordered(encoders().filter { !isHardware(it) })

        /** Plain words for a codec failure, with what Android says about it (for the log). */
        fun describe(e: Throwable): String = when (e) {
            is MediaCodec.CodecException -> when (e.errorCode) {
                MediaCodec.CodecException.ERROR_RECLAIMED -> "reclaimed by Android for another app"
                MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE -> "the video chip has no room (insufficient resource)"
                else -> "codec error ${e.errorCode}"
            } + " (${e.diagnosticInfo}, recoverable ${e.isRecoverable}, transient ${e.isTransient}${e.message?.let { ", $it" } ?: ""})"
            is IllegalStateException -> "the codec is gone (${e.message})"
            else -> e.toString()
        }

        private fun MediaFormat.intOr(key: String, def: Int): Int = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def
        private fun ByteBuffer.at(offset: Int): ByteBuffer = duplicate().apply { clear(); position(offset) }.slice()
    }
}
