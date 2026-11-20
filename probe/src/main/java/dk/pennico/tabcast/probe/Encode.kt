// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.probe

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
const val VP8 = MediaFormat.MIMETYPE_VIDEO_VP8

/**
 * Encoded output: counts, and optionally `<base>.h264` (Annex-B) + `<base>.mp4` (H.264), or `<base>.webm` (VP8, probe 2),
 * starting at the first key frame.
 */
class Sink(private val base: File?, private val report: Report, private val vp8: Boolean = false) {
    private var h264: FileOutputStream? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var started = false
    private var lastPts = Long.MIN_VALUE
    var frames = 0; private set
    var keyframes = 0; private set
    var bytes = 0L; private set
    var config: ByteArray? = null
    val nals = ArrayList<Int>()

    fun write(buf: ByteBuffer, info: MediaCodec.BufferInfo, format: MediaFormat?) {
        val data = ByteArray(info.size)
        buf.position(info.offset)
        buf.get(data)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { config = data; return }
        frames++
        bytes += info.size
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (key) keyframes++
        if (!vp8 && nals.size < 24) nals += Calc.nalTypes(data)
        val base = base ?: return
        if (!started) {
            if (!key) return
            started = true
            if (!vp8) h264 = FileOutputStream(File(base.path + ".h264")).also { o -> config?.let { o.write(it) } }
            if (format != null) runCatching {
                muxer = (if (vp8) MediaMuxer(base.path + ".webm", MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
                    else MediaMuxer(base.path + ".mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)).also {
                    track = it.addTrack(format)
                    it.start()
                }
            }.onFailure { report.line("mp4 writer for ${base.name}: $it") }
        }
        h264?.write(data)
        val m = muxer ?: return
        if (info.presentationTimeUs <= lastPts) return
        lastPts = info.presentationTimeUs
        val i = MediaCodec.BufferInfo().apply { set(0, data.size, info.presentationTimeUs, info.flags) }
        runCatching { m.writeSampleData(track, ByteBuffer.wrap(data), i) }.onFailure { report.line("mp4 write: $it"); muxer = null }
    }

    fun close() {
        runCatching { h264?.close() }
        muxer?.let { m -> runCatching { m.stop() }; runCatching { m.release() } }
        muxer = null
    }
}

/**
 * One H.264 (or, probe 2, VP8: [mime]) encoder at w x h with ByteBuffer input (or Surface input for the virtual display test), set up the way the
 * mirroring sender would: CBR, 1 s key frames, no B-frames, real-time priority, SPS/PPS before key frames.
 * Blocking calls: run off the main thread. Every loop has its own deadline.
 */
class Encoder(val name: String, private val report: Report, private val key: String,
              val w: Int = 1280, val h: Int = 720, private val bitrate: Int = 4_000_000, private val fps: Int = 30,
              val mime: String = AVC) {
    val codec: MediaCodec = MediaCodec.createByCodecName(name)
    private val info = MediaCodec.BufferInfo()
    var outFormat: MediaFormat? = null; private set
    var sink = Sink(null, report, mime == VP8)
    var colorFormat = 0; private set
    private var layoutLogged = false
    private val queuedAt = HashMap<Long, Long>()
    var latency = Calc.Samples(); private set
    var convert = Calc.Samples(); private set
    private var nextPts = 0L
    var firstOutputMs = -1.0; private set
    private var startNs = 0L
    private var stride = w
    private var slice = h
    private var planar = false
    private var stagingBuf: ByteBuffer? = null
    /** Our own picture in the codec's layout: the C code never writes into codec memory (probe 2 run 1). */
    private fun staging(): ByteBuffer = stagingBuf?.takeIf { it.capacity() >= stride * slice * 3 / 2 }
        ?: ByteBuffer.allocateDirect(stride * slice * 3 / 2).also { stagingBuf = it }

    /** I420 w x h in [pic] into an Image's planes, whatever their strides (bounds-checked puts). */
    private fun copyPlanes(pic: ByteBuffer, p: Array<android.media.Image.Plane>) {
        fun plane(dst: android.media.Image.Plane, off: Int, pw: Int, ph: Int) {
            val b = dst.buffer
            val row = ByteArray(pw)
            for (j in 0 until ph) {
                pic.position(off + j * pw); pic.get(row)
                val base = j * dst.rowStride
                if (dst.pixelStride == 1) { b.position(base); b.put(row) }
                else for (i in 0 until pw) b.put(base + i * dst.pixelStride, row[i])
            }
        }
        plane(p[0], 0, w, h)
        plane(p[1], w * h, w / 2, h / 2)
        plane(p[2], w * h + w * h / 4, w / 2, h / 2)
    }

    val hardware: Boolean get() = codec.codecInfo.isHardwareAccelerated

    /** Configures and starts; returns the input Surface when [surfaceInput]. */
    fun start(surfaceInput: Boolean): Surface? {
        val caps = codec.codecInfo.getCapabilitiesForType(mime)
        colorFormat = if (surfaceInput) CodecCapabilities.COLOR_FormatSurface else pickColor(caps.colorFormats)
        val cbr = caps.encoderCapabilities.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR)
        fun format(full: Boolean) = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (!full) return@apply
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        try {
            codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            report.put(key, "config", "full (cbr=$cbr)")
        } catch (e: Exception) {
            report.put(key, "configFullError", e.toString())
            report.line("$name: full settings refused ($e), trying the minimal ones")
            codec.reset()
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            report.put(key, "config", "minimal")
        }
        report.put(key, "colorFormat", colorName(colorFormat))
        report.put(key, "inputFormat", runCatching { codec.inputFormat.toString() }.getOrElse { it.toString() })
        if (!surfaceInput) {
            val f = codec.inputFormat
            stride = f.intOr("stride", w).coerceAtLeast(w)
            slice = f.intOr("slice-height", h).coerceAtLeast(h)
            // "Flexible" resolves to a concrete layout in the input format; only plain buffers need to know it.
            val actual = if (colorFormat == CodecCapabilities.COLOR_FormatYUV420Flexible) f.intOr(MediaFormat.KEY_COLOR_FORMAT, colorFormat) else colorFormat
            planar = actual == CodecCapabilities.COLOR_FormatYUV420Planar
            report.put(key, "bufferLayout", "${if (planar) "I420" else "NV12"} stride $stride slice $slice")
        }
        val s = if (surfaceInput) codec.createInputSurface() else null
        codec.start()
        startNs = System.nanoTime()
        return s
    }

    fun requestKeyFrame() = runCatching {
        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    fun resetStats() { latency = Calc.Samples(); convert = Calc.Samples() }

    /**
     * Puts [bmp] into input buffer [idx] at (x, y) (black around it when it is smaller than the frame) and queues it.
     * [image] = through MediaCodec.getInputImage (the codec describes its layout); else the plain buffer with the
     * layout derived from the input format (stride, slice height), as WebRTC does.
     */
    fun queue(idx: Int, bmp: Bitmap, x: Int, y: Int, image: Boolean, pts: Long = nextPts, capturedAt: Long = System.nanoTime()) {
        val bars = bmp.width != w || bmp.height != h
        val img = if (image) runCatching { codec.getInputImage(idx) }.getOrNull() else null
        val ns: Long
        if (img != null) {
            val p = img.planes
            if (!layoutLogged) {
                layoutLogged = true
                report.put(key, "inputImage", "format ${img.format} ${img.width}x${img.height} crop ${img.cropRect.toShortString()} " +
                    "Y ${p[0].rowStride}/${p[0].pixelStride} U ${p[1].rowStride}/${p[1].pixelStride} V ${p[2].rowStride}/${p[2].pixelStride}")
            }
            // Probe 2 run 1: writing straight into c2.android.vp8.encoder's planes crashed (SIGSEGV). The C code now
            // writes an I420 picture of our own; Java copies it into the planes (bounds-checked).
            val pic = ByteBuffer.allocateDirect(w * h * 3 / 2)
            val pu = pic.at(w * h); val pv = pic.at(w * h + w * h / 4)
            if (bars) Native.fillBlack(pic.at(0), w, pu, pv, w / 2, 1, 0, 0, w, h)
            ns = Native.convert(bmp, pic.at(0), w, pu, pv, w / 2, 1, x, y, false)
            if (ns >= 0) copyPlanes(pic, p)
        } else {
            val buf = codec.getInputBuffer(idx) ?: error("no input buffer $idx")
            val ySize = stride * slice
            val st = staging()
            val u = st.at(ySize)
            val v = if (planar) st.at(ySize + (stride / 2) * (slice / 2)) else st.at(ySize + 1)
            val uvStride = if (planar) stride / 2 else stride
            val ups = if (planar) 1 else 2
            if (bars) Native.fillBlack(st.at(0), stride, u, v, uvStride, ups, 0, 0, w, h)
            ns = Native.convert(bmp, st.at(0), stride, u, v, uvStride, ups, x, y, false)
            if (ns >= 0) { buf.clear(); buf.put(st.duplicate().apply { clear(); limit(ySize * 3 / 2) }) }
        }
        if (ns < 0) error("convert failed ($ns) for ${bmp.width}x${bmp.height} into $name")
        convert.add(ns / 1e6)
        queuedAt[pts] = capturedAt
        codec.queueInputBuffer(idx, 0, stride * slice * 3 / 2, pts, 0)
        nextPts = pts + 1_000_000L / fps
    }

    /**
     * Probe 2 (mirroring): converts an RGBA buffer (an ImageReader plane: [rowStride] bytes per row, [pw] x [ph]) into
     * input buffer [idx] at (x, y) and queues it, through the plain buffer layout (as WebRTC does).
     */
    fun queueRgba(idx: Int, rgba: ByteBuffer, rowStride: Int, pw: Int, ph: Int, x: Int, y: Int, pts: Long, capturedAt: Long) {
        val buf = codec.getInputBuffer(idx) ?: error("no input buffer $idx")
        val ySize = stride * slice
        val st = staging()
        val u = st.at(ySize)
        val v = if (planar) st.at(ySize + (stride / 2) * (slice / 2)) else st.at(ySize + 1)
        val uvStride = if (planar) stride / 2 else stride
        val ups = if (planar) 1 else 2
        if (pw != w || ph != h) Native.fillBlack(st.at(0), stride, u, v, uvStride, ups, 0, 0, w, h)
        val ns = Native.convertBuffer(rgba, rowStride, pw, ph, st.at(0), stride, u, v, uvStride, ups, x, y)
        if (ns < 0) error("convert failed ($ns) for ${pw}x$ph into $name")
        buf.clear(); buf.put(st.duplicate().apply { clear(); limit(ySize * 3 / 2) })
        convert.add(ns / 1e6)
        queuedAt[pts] = capturedAt
        codec.queueInputBuffer(idx, 0, stride * slice * 3 / 2, pts, 0)
    }

    /** Takes what is ready (waiting up to [timeoutUs] for the first); returns the number of frames. */
    fun drain(timeoutUs: Long): Int {
        var n = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (n == 0) timeoutUs else 0)
            when {
                i >= 0 -> {
                    val b = codec.getOutputBuffer(i)
                    if (b != null && info.size > 0) {
                        b.limit(info.offset + info.size)
                        sink.write(b, info, outFormat)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        n++
                        if (firstOutputMs < 0) firstOutputMs = (System.nanoTime() - startNs) / 1e6
                        queuedAt.remove(info.presentationTimeUs)?.let { latency.add((System.nanoTime() - it) / 1e6) }
                    }
                    codec.releaseOutputBuffer(i, false)
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outFormat = codec.outputFormat
                    report.put(key, "outputFormat", outFormat.toString())
                }
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return n
                else -> {}
            }
        }
    }

    /** As fast as the codec takes input, for [seconds]: throughput. */
    fun burst(frames: List<Bitmap>, seconds: Double, image: Boolean): Map<String, Any?> {
        resetStats()
        val t0 = System.nanoTime()
        val end = t0 + (seconds * 1e9).toLong()
        var fed = 0
        var out = 0
        var cpu0 = android.os.Process.getElapsedCpuTime()
        while (System.nanoTime() < end) {
            val idx = codec.dequeueInputBuffer(2000)
            if (idx >= 0) queue(idx, frames[fed++ % frames.size], 0, 0, image)
            out += drain(0)
        }
        val wall = (System.nanoTime() - t0) / 1e6
        cpu0 = android.os.Process.getElapsedCpuTime() - cpu0
        out += drain(200_000)
        return linkedMapOf("fedFps" to r1(fed * 1000 / wall), "encodedFps" to r1(out * 1000 / wall), "latency" to latency.summary(),
            "convertMs" to convert.summary(), "appCpu%" to Calc.cpuPercent(cpu0, wall.toLong()))
    }

    /** One frame every 1/fps s for [seconds], as live capture would: delay and real frame rate. */
    fun paced(frames: List<Bitmap>, seconds: Double, image: Boolean): Map<String, Any?> {
        resetStats()
        val period = 1_000_000_000L / fps
        val t0 = System.nanoTime()
        val end = t0 + (seconds * 1e9).toLong()
        var next = t0
        var fed = 0
        var noInput = 0
        var out = 0
        val basePts = nextPts
        val bytes0 = sink.bytes
        while (System.nanoTime() < end) {
            val now = System.nanoTime()
            if (now >= next) {
                next += period
                val idx = codec.dequeueInputBuffer(5000)
                if (idx >= 0) queue(idx, frames[fed++ % frames.size], 0, 0, image, basePts + (now - t0) / 1000, now) else noInput++
            }
            out += drain(2000)
        }
        val wall = (System.nanoTime() - t0) / 1e6
        out += drain(300_000)
        val kbps = ((sink.bytes - bytes0) * 8 / wall).roundToInt()
        return linkedMapOf("fedFps" to r1(fed * 1000 / wall), "encodedFps" to r1(out * 1000 / wall), "noInputBuffer" to noInput,
            "latency" to latency.summary(), "convertMs" to convert.summary(), "kbps" to kbps)
    }

    /** stop() + release() on a side thread: a broken codec can hang there. */
    fun release(): Boolean {
        sink.close()
        val t = Thread({ runCatching { codec.stop() }; runCatching { codec.release() } }, "codec-release")
        t.start()
        t.join(4000)
        if (t.isAlive) report.put(key, "releaseHung", true)
        return !t.isAlive
    }

    companion object {
        private val PREFERRED = listOf(CodecCapabilities.COLOR_FormatYUV420Flexible, CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            CodecCapabilities.COLOR_FormatYUV420Planar)

        fun pickColor(formats: IntArray): Int = PREFERRED.firstOrNull { it in formats } ?: formats.firstOrNull() ?: 0

        fun colorName(c: Int): String = when (c) {
            CodecCapabilities.COLOR_FormatYUV420Flexible -> "YUV420Flexible"
            CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "NV12 (21)"
            CodecCapabilities.COLOR_FormatYUV420Planar -> "I420 (19)"
            CodecCapabilities.COLOR_FormatSurface -> "Surface"
            else -> "0x" + Integer.toHexString(c)
        }

        private fun r1(x: Double) = (x * 10).roundToInt() / 10.0
    }
}

fun MediaFormat.intOr(key: String, def: Int): Int = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def

/** A direct view of this buffer from [offset] (position/limit of the original untouched). */
fun ByteBuffer.at(offset: Int): ByteBuffer = duplicate().apply { clear(); position(offset) }.slice()

/**
 * Decodes one frame of an encoded .mp4 back with the platform decoder and compares it with the source pictures:
 * proves the colours/layout are right (a wrong plane layout gives a large difference). Writes `<mp4>.png`.
 */
fun decodeCheck(mp4: File, sources: List<Bitmap>, report: Report): Map<String, Any?> {
    if (!mp4.exists()) return mapOf("error" to "no ${mp4.name}")
    val stem = mp4.path.substringBeforeLast('.')
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(mp4.path)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val f = r.getFrameAtTime(dur * 500, MediaMetadataRetriever.OPTION_CLOSEST) ?: return mapOf("error" to "no frame decoded", "durationMs" to dur)
        File("$stem-decoded.png").outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val best = sources.minOf { meanDiff(f, it) }
        mapOf("durationMs" to dur, "frame" to "${f.width}x${f.height}", "meanDiff" to (best * 10).roundToInt() / 10.0,
            "verdict" to if (best < 12) "picture ok" else if (best < 40) "picture differs (check the png)" else "garbled (wrong layout or colours)")
    } catch (e: Exception) {
        report.line("decode check ${mp4.name}: $e")
        mapOf("error" to e.toString())
    } finally {
        runCatching { r.release() }
    }
}

/** Mean absolute RGB difference (0..255) on a 160x90 grid. */
fun meanDiff(a: Bitmap, b: Bitmap): Double {
    val sa = Bitmap.createScaledBitmap(a, 160, 90, true)
    val sb = Bitmap.createScaledBitmap(b, 160, 90, true)
    val pa = IntArray(160 * 90).also { sa.getPixels(it, 0, 160, 0, 0, 160, 90) }
    val pb = IntArray(160 * 90).also { sb.getPixels(it, 0, 160, 0, 0, 160, 90) }
    var sum = 0L
    for (i in pa.indices) {
        val x = pa[i]
        val y = pb[i]
        sum += abs((x shr 16 and 255) - (y shr 16 and 255)) + abs((x shr 8 and 255) - (y shr 8 and 255)) + abs((x and 255) - (y and 255))
    }
    return sum / (pa.size * 3.0)
}
