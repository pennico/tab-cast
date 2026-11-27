// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.link.AnnexB
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * 0.5.0: the tablet's mirrored screen on the TV. VP8 frames (Wire.VIDEO) go to a MediaCodec decoder that renders onto
 * the activity's SurfaceView; 20 ms PCM chunks (Wire.AUDIO) go to an AudioTrack at Tab Cast TV's own volume. Low delay:
 * nothing is buffered beyond what the sound needs; the picture is held back by the sound's current delay so both
 * match (A/V by the tablet's capture timestamps). After a gap or at the start the decoder waits for a key frame and
 * asks the tablet for one ([onNeedKey]).
 *
 * 0.6: also H.264 and HEVC (Tab Cast for Mac: 1080p from the Mac's hardware encoder), Annex-B access units with the
 * parameter sets in each key frame; they go to the decoder once as codec config ([AnnexB.parameterSets]). The
 * hardware decoder is chosen when there is one, in low-latency mode.
 */
class TvMirror(private val onNeedKey: () -> Unit, private val log: (String) -> Unit = { AppLog.i(it) }) {
    private class Frame(val data: ByteArray, val key: Boolean, val ptsUs: Long)

    private val video = LinkedBlockingDeque<Frame>()
    private val sound = LinkedBlockingDeque<Pair<ByteArray, Long>>()
    @Volatile private var running = true
    @Volatile private var surface: Surface? = null
    @Volatile private var w = 0
    @Volatile private var h = 0
    @Volatile private var codecName = "vp8"
    @Volatile private var rate = 48_000
    @Volatile private var channels = 2
    @Volatile var volume = 1f
        set(v) { field = v.coerceIn(0f, 1f); track?.setVolume(field) }
    @Volatile private var track: AudioTrack? = null
    @Volatile private var written = 0L
    @Volatile var decoded = 0L; private set
    @Volatile var dropped = 0L; private set
    /** 20 ms sound chunks skipped to keep the delay short ([SOUND_TARGET_US]). */
    @Volatile var soundSkipped = 0L; private set
    @Volatile var decoderName: String? = null; private set
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    /** [codec]: vp8 (the tablet), h264 or hevc (0.6, the Mac). */
    fun configureVideo(width: Int, height: Int, codec: String = "vp8") {
        w = width; h = height; codecName = codec
        restartVideo()
    }

    fun configureAudio(sampleRate: Int, ch: Int) {
        rate = sampleRate; channels = ch
        if (audioThread == null) audioThread = Thread({ audioLoop() }, "mirror-audio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** The SurfaceView's surface came (or went: null). */
    fun setSurface(s: Surface?) {
        surface = s
        restartVideo()
    }

    fun video(data: ByteArray, key: Boolean, ptsUs: Long) {
        if (!running) return
        video.offer(Frame(data, key, ptsUs))
        // Never more than ~0.3 s behind: drop down to the newest key frame or just start again from one.
        if (video.size > 10) {
            video.clear(); dropped++
            onNeedKey()
        }
    }

    fun audio(pcm: ByteArray, ptsUs: Long) {
        if (!running) return
        sound.offer(pcm to ptsUs)
        while (sound.size > 15) { sound.pollFirst(); dropped++ } // > 0.3 s waiting: drop the oldest
    }

    /** How long the sound written now takes to come out (µs), for holding the picture back. */
    private fun soundDelayUs(): Long {
        val t = track ?: return 0
        val frames = written / (2 * channels)
        val played = t.playbackHeadPosition.toLong() and 0xffffffffL
        return ((frames - played).coerceAtLeast(0) * 1_000_000L / rate).coerceIn(0, 400_000)
    }

    @Synchronized private fun restartVideo() {
        videoThread?.takeIf { it !== Thread.currentThread() }?.let { it.interrupt(); it.join(1500) }
        videoThread = null
        if (!running || surface == null || w <= 0) return
        videoThread = Thread({ videoLoop(surface!!, w, h, codecName) }, "mirror-video").apply { start() }
    }

    private fun videoLoop(s: Surface, width: Int, height: Int, codecId: String) {
        val mime = mimeOf(codecId)
        val annexB = codecId == "h264" || codecId == "hevc"
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // A 1080p key frame can be several hundred kB; some decoders size their input buffers too small otherwise.
            if (annexB) setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 shl 20)
        }
        val codec = try {
            // The hardware decoder when the TV has one for this format (the Chromecast's for H.264/HEVC).
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            (if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)).also {
                it.configure(format, s, null, 0)
                it.start()
            }
        } catch (e: Exception) { log("mirror: no $codecId decoder ($e)"); return }
        decoderName = codec.name
        log("mirror: decoder ${codec.name} ($codecId) ${width}x$height")
        val info = MediaCodec.BufferInfo()
        var waitKey = true
        var configured = !annexB
        onNeedKey()
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val f = video.pollFirst(20, TimeUnit.MILLISECONDS)
                if (f != null) {
                    if (waitKey && !f.key) { dropped++; continue }
                    waitKey = false
                    if (!configured) {
                        // H.264/HEVC: the parameter sets of the first key frame go in once as codec config.
                        val ps = AnnexB.parameterSets(f.data, hevc = codecId == "hevc")
                        if (ps == null) { dropped++; waitKey = true; onNeedKey(); continue }
                        val ci = codec.dequeueInputBuffer(100_000)
                        if (ci < 0) { dropped++; waitKey = true; onNeedKey(); continue }
                        codec.getInputBuffer(ci)!!.apply { clear(); put(ps) }
                        codec.queueInputBuffer(ci, 0, ps.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        configured = true
                    }
                    val i = codec.dequeueInputBuffer(20_000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
                        if (f.data.size > b.capacity()) {
                            // Too big for this decoder's buffer: skip to the next key frame.
                            codec.queueInputBuffer(i, 0, 0, f.ptsUs, 0)
                            log("mirror: a ${f.data.size} B frame doesn't fit the decoder's ${b.capacity()} B buffer")
                            dropped++; waitKey = true; onNeedKey(); continue
                        }
                        b.clear(); b.put(f.data)
                        codec.queueInputBuffer(i, 0, f.data.size, f.ptsUs, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    } else { dropped++; waitKey = true; onNeedKey() }
                }
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    decoded++
                    // Shown when its sound comes out of the speakers.
                    codec.releaseOutputBuffer(o, System.nanoTime() + soundDelayUs() * 1000)
                }
            }
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            log("mirror: decoder error ($e); asking for a key frame")
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
        }
        if (running && !Thread.currentThread().isInterrupted && surface === s) { video.clear(); restartVideo() }
    }

    private fun audioLoop() {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(min, rate / 10 * 2 * channels))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) { log("mirror: no sound output ($e)"); return }
        t.setVolume(volume)
        // Android 12+: start after ~40 ms of sound, not when the whole buffer is full.
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { t.setStartThresholdInFrames(maxOf(min / (2 * channels), rate / 25)) }
        // Never below what this TV's sound hardware needs plus a little room.
        val targetUs = maxOf(SOUND_TARGET_US, min.toLong() / (2 * channels) * 1_000_000L / rate + 20_000L)
        track = t
        t.play()
        val started = SystemClock.elapsedRealtime()
        try {
            while (running) {
                val (pcm, _) = sound.pollFirst(100, TimeUnit.MILLISECONDS) ?: continue
                // 0.6: keep the sound's backlog short. A burst after a network hiccup fills the track's buffer, and a
                // sender whose clock runs a little fast keeps it full: everything (the picture waits for the sound)
                // then stays ~0.1 s late for good. Over the target, skip a chunk (20 ms, rare) to catch up.
                // Only once it plays: before that Android may wait for a full buffer.
                if (t.playbackHeadPosition != 0 && soundDelayUs() > targetUs) { soundSkipped++; continue }
                val n = t.write(pcm, 0, pcm.size)
                if (n > 0) written += n
            }
        } catch (_: InterruptedException) {
        } finally {
            track = null
            runCatching { t.stop() }; t.release()
            log("mirror: sound ended after ${(SystemClock.elapsedRealtime() - started) / 1000} s")
        }
    }

    companion object {
        /** The most sound waiting in the track (µs) before chunks are skipped: room for a few ms of network jitter. */
        const val SOUND_TARGET_US = 50_000L

        fun mimeOf(codec: String): String = when (codec) {
            "h264" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_VP8
        }

        /**
         * 0.6: the mirroring codecs this TV can decode up to [w]x[h] at [fps] ("vp8,h264,hevc"), for HELLO_ACK: the
         * Mac app sends HEVC only to a TV that lists it, else H.264.
         */
        fun decodable(w: Int = 1920, h: Int = 1080, fps: Int = 60): String = listOf("vp8", "h264", "hevc").filter { c ->
            runCatching {
                val f = MediaFormat.createVideoFormat(mimeOf(c), w, h).apply { setInteger(MediaFormat.KEY_FRAME_RATE, fps) }
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null
            }.getOrDefault(false)
        }.joinToString(",")
    }

    fun stats(): Map<String, Any?> = mapOf("decoded" to decoded, "dropped" to dropped, "decoder" to decoderName,
        "soundDelayMs" to soundDelayUs() / 1000, "soundSkipped" to soundSkipped)

    fun release() {
        running = false
        videoThread?.interrupt(); audioThread?.interrupt()
        videoThread?.join(1500); audioThread?.join(1500)
        video.clear(); sound.clear()
    }
}
