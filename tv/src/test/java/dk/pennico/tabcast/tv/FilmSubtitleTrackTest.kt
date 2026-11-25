// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dk.pennico.tabcast.link.LinkClient
import dk.pennico.tabcast.link.Wire
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.5.4: a film's own subtitle track the TV can't show (VobSub: Media3 1.4.1 has no parser for it) must not stop the
 * film. Before, choosing it forced the track on, the player failed, and every retry failed again with the same track.
 * End to end through TvActivity over the real link and FileServer, with a tiny generated Matroska file.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilmSubtitleTrackTest {
    @Before fun noUpdateChecks() { TvUpdates.autoCheck = false }

    private fun pump(maxMs: Long = 8000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    // ------------------------------------------------------------------ a minimal Matroska writer (EBML)
    private fun id(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun size8(n: Long) = ByteArray(8).also { it[0] = 0x01; for (i in 1..7) it[i] = (n shr (8 * (7 - i))).toByte() }
    private fun el(idb: ByteArray, payload: ByteArray) = idb + size8(payload.size.toLong()) + payload
    private fun uint(idb: ByteArray, v: Long): ByteArray {
        var n = 1; while (n < 8 && (v ushr (8 * n)) != 0L) n++
        return el(idb, ByteArray(n) { (v shr (8 * (n - 1 - it))).toByte() })
    }
    private fun str(idb: ByteArray, s: String) = el(idb, s.toByteArray(Charsets.UTF_8))
    private fun cat(vararg parts: ByteArray) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    /** A 10 s Matroska file with one subtitle track [codec] (and [codecPrivate]), one cue at 1-3 s. */
    private fun mkv(codec: String, codecPrivate: ByteArray?, sample: ByteArray): ByteArray {
        val ebml = el(id(0x1A, 0x45, 0xDF, 0xA3), cat(uint(id(0x42, 0x86), 1), uint(id(0x42, 0xF7), 1), uint(id(0x42, 0xF2), 4),
            uint(id(0x42, 0xF3), 8), str(id(0x42, 0x82), "matroska"), uint(id(0x42, 0x87), 4), uint(id(0x42, 0x85), 2)))
        val dur = java.nio.ByteBuffer.allocate(8).putDouble(10_000.0).array()
        val info = el(id(0x15, 0x49, 0xA9, 0x66), cat(uint(id(0x2A, 0xD7, 0xB1), 1_000_000), el(id(0x44, 0x89), dur)))
        val entry = el(id(0xAE), cat(uint(id(0xD7), 1), uint(id(0x73, 0xC5), 1), uint(id(0x83), 0x11), uint(id(0x88), 1),
            str(id(0x22, 0xB5, 0x9C), "ita"), str(id(0x86), codec), codecPrivate?.let { el(id(0x63, 0xA2), it) } ?: ByteArray(0)))
        val tracks = el(id(0x16, 0x54, 0xAE, 0x6B), entry)
        val block = el(id(0xA1), cat(byteArrayOf(0x81.toByte(), 0x03, 0xE8.toByte(), 0x00), sample)) // track 1, +1000 ms
        val group = el(id(0xA0), cat(block, uint(id(0x9B), 2000)))
        val cluster = el(id(0x1F, 0x43, 0xB6, 0x75), cat(uint(id(0xE7), 0), group))
        return cat(ebml, el(id(0x18, 0x53, 0x80, 0x67), cat(info, tracks, cluster)))
    }

    private val vobsubIdx = ("# VobSub index file, v7\nsize: 720x576\npalette: 000000, 828282, 828282, 828282, 828282, 828282, " +
        "828282, ffffff, 828282, bababa, 828282, 828282, 828282, 828282, 828282, 828282\n").toByteArray()

    private fun source(b: ByteArray) = object : dk.pennico.tabcast.link.FileServer.Source {
        override val size = b.size.toLong()
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            val n = minOf(len.toLong(), size - pos).toInt(); if (n <= 0) return -1
            System.arraycopy(b, pos.toInt(), buf, off, n); return n
        }
    }

    private class Tv(val a: TvActivity, val p: dk.pennico.tabcast.link.Peer, val status: CopyOnWriteArrayList<Map<String, String>>)

    private fun pairedTv(): Pair<org.robolectric.android.controller.ActivityController<TvActivity>, Tv> {
        TvActivity.port = 0
        WebRemote.PORT_SETTING = 0
        val c = Robolectric.buildActivity(TvActivity::class.java).setup()
        val a = c.get()
        TvApp.pairing.hidePin()
        val pin = TvApp.pairing.showPin("test")!!
        val p = LinkClient.connect("127.0.0.1", a.linkPort!!, mapOf("name" to "test tablet"))
        val status = CopyOnWriteArrayList<Map<String, String>>()
        val pairRes = CopyOnWriteArrayList<Map<String, String>>()
        p.onMessage = { m -> if (m.type == Wire.STATUS) status += m.kv(); if (m.type == Wire.PAIR_RESULT) pairRes += m.kv() }
        p.start()
        p.sendKv(Wire.PAIR, "pin" to pin, "name" to "test tablet")
        assertTrue(pump { pairRes.isNotEmpty() })
        return c to Tv(a, p, status)
    }

    private fun last(t: Tv, k: String) = t.status.lastOrNull()?.get(k)

    /** Sends [film] as a cast file and waits until the TV lists its subtitle track. */
    private fun castFilm(tv: Tv, server: dk.pennico.tabcast.link.FileServer, film: ByteArray, name: String) {
        val port = server.start()
        val e = server.add(name, "video/x-matroska", source(film))
        tv.p.sendKv(Wire.FILE, "url" to server.url("127.0.0.1:$port", e), "name" to name, "size" to film.size,
            "id" to "$name|${film.size}", "mime" to "video/x-matroska", "rate" to 200)
        assertTrue("the TV lists the film's subtitle track", pump { !last(tv, Wire.Status.S_TRACKS).isNullOrEmpty() })
    }

    /**
     * No player error during [ms] (0.5.3 showed "Trying again" within 3 s and never got further). The 10 s test film
     * may play to its end in Robolectric's clock, so the TV may be back on its start screen by then.
     */
    private fun keepsPlaying(tv: Tv, ms: Long): Boolean {
        val errors = HashSet<String>()
        pump(ms) { last(tv, Wire.Status.ERROR)?.let { errors += it }; false }
        return errors.none { it.startsWith("Trying again") || it.startsWith("The TV can't play") || it.startsWith("TV player") }
    }

    @Test fun choosingAnUnplayableSubtitleTrackIsRefusedAndTheFilmPlaysOn() {
        val (c, tv) = pairedTv()
        val server = dk.pennico.tabcast.link.FileServer()
        castFilm(tv, server, mkv("S_VOBSUB", vobsubIdx, ByteArray(64) { 1 }), "Vobsub film.mkv")
        assertTrue(last(tv, Wire.Status.S_TRACKS)!!.contains("can't play here"))
        tv.p.sendKv(Wire.CMD, "c" to Wire.Cmd.SUBS, "v" to "0")
        assertTrue("the film keeps playing after the unplayable track was chosen", keepsPlaying(tv, 8_000))
        assertFalse("the unplayable track is not selected", last(tv, Wire.Status.S_TRACK) == "0")
        server.stop(); tv.p.close(); c.pause().stop().destroy()
    }

    /** The second line of defence: a subtitle track that fails while playing is turned off, and the film goes on. */
    @Test fun aSubtitleTrackThatFailsWhilePlayingIsTurnedOff() {
        val (c, tv) = pairedTv()
        val server = dk.pennico.tabcast.link.FileServer()
        castFilm(tv, server, mkv("S_VOBSUB", vobsubIdx, ByteArray(64) { 1 }), "Vobsub film 2.mkv")
        // Force the track the way 0.5.3 did, past the new check (as an older tablet's command or a default flag might).
        val tp = TvActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(tv.a) as TvPlayer
        val exo = TvPlayer::class.java.getDeclaredField("player").apply { isAccessible = true }.get(tp) as ExoPlayer
        val g = exo.currentTracks.groups.first { it.type == C.TRACK_TYPE_TEXT }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
        assertTrue("the film keeps playing after the subtitle track failed", keepsPlaying(tv, 10_000))
        assertTrue("subtitles are off", exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        server.stop(); tv.p.close(); c.pause().stop().destroy()
    }

    @Test fun aPlayableSubtitleTrackCanStillBeChosen() {
        val (c, tv) = pairedTv()
        val server = dk.pennico.tabcast.link.FileServer()
        castFilm(tv, server, mkv("S_TEXT/UTF8", null, "A line".toByteArray()), "Text film.mkv")
        tv.p.sendKv(Wire.CMD, "c" to Wire.Cmd.SUBS, "v" to "off")
        assertTrue(pump { last(tv, Wire.Status.S_TRACK) == "-1" || last(tv, Wire.Status.S_TRACK) == null })
        tv.p.sendKv(Wire.CMD, "c" to Wire.Cmd.SUBS, "v" to "0")
        assertTrue("the playable track is selected", pump { last(tv, Wire.Status.S_TRACK) == "0" })
        server.stop(); tv.p.close(); c.pause().stop().destroy()
    }
}
