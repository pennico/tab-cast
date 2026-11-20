// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import dk.pennico.tabcast.AppLog
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * 0.5.0: the tablet's mirrored screen on the TV. VP8 frames (Wire.VIDEO) go to a MediaCodec decoder that renders onto
 * the activity's SurfaceView; 20 ms PCM chunks (Wire.AUDIO) go to an AudioTrack at Tab Cast TV's own volume. Low delay:
 * nothing is buffered beyond what the sound needs; the picture is held back by the sound's current delay so both
 * match (A/V by the tablet's capture timestamps). After a gap or at the start the decoder waits for a key frame and
 * asks the tablet for one ([onNeedKey]).
 */
class TvMirror(private val onNeedKey: () -> Unit, private val log: (String) -> Unit = { AppLog.i(it) }) {
    private class Frame(val data: ByteArray, val key: Boolean, val ptsUs: Long)

    private val video = LinkedBlockingDeque<Frame>()
    private val sound = LinkedBlockingDeque<Pair<ByteArray, Long>>()
    @Volatile private var running = true
    @Volatile private var surface: Surface? = null
    @Volatile private var w = 0
    @Volatile private var h = 0
    @Volatile private var rate = 48_000
    @Volatile private var channels = 2
    @Volatile var volume = 1f
        set(v) { field = v.coerceIn(0f, 1f); track?.setVolume(field) }
    @Volatile private var track: AudioTrack? = null
    @Volatile private var written = 0L
    @Volatile var decoded = 0L; private set
    @Volatile var dropped = 0L; private set
    @Volatile var decoderName: String? = null; private set
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    fun configureVideo(width: Int, height: Int) {
        w = width; h = height
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
        videoThread = Thread({ videoLoop(surface!!, w, h) }, "mirror-video").apply { start() }
    }

    private fun videoLoop(s: Surface, width: Int, height: Int) {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_VP8).also {
                it.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP8, width, height).apply {
                    if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }, s, null, 0)
                it.start()
            }
        } catch (e: Exception) { log("mirror: no VP8 decoder ($e)"); return }
        decoderName = codec.name
        log("mirror: decoder ${codec.name} ${width}x$height")
        val info = MediaCodec.BufferInfo()
        var waitKey = true
        onNeedKey()
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val f = video.pollFirst(20, TimeUnit.MILLISECONDS)
                if (f != null) {
                    if (waitKey && !f.key) { dropped++; continue }
                    waitKey = false
                    val i = codec.dequeueInputBuffer(20_000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!
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
        track = t
        t.play()
        val started = SystemClock.elapsedRealtime()
        try {
            while (running) {
                val (pcm, _) = sound.pollFirst(100, TimeUnit.MILLISECONDS) ?: continue
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

    fun stats(): Map<String, Any?> = mapOf("decoded" to decoded, "dropped" to dropped, "decoder" to decoderName,
        "soundDelayMs" to soundDelayUs() / 1000)

    fun release() {
        running = false
        videoThread?.interrupt(); audioThread?.interrupt()
        videoThread?.join(1500); audioThread?.join(1500)
        video.clear(); sound.clear()
    }
}
