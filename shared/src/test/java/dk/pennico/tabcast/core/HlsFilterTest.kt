// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.2: YouTube's HLS master (same structure as yt-dlp's visionos answer, 2026-11-14) filtered for a 1080p TV. */
class HlsFilterTest {
    private fun inf(bw: Int, codecs: String, w: Int, h: Int, fps: Int, audio: String) =
        "#EXT-X-STREAM-INF:BANDWIDTH=$bw,CODECS=\"$codecs\",RESOLUTION=${w}x$h,FRAME-RATE=$fps,VIDEO-RANGE=SDR,AUDIO=\"$audio\",CLOSED-CAPTIONS=NONE"

    private val youtube = buildString {
        append("#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        append("#EXT-X-MEDIA:URI=\"https://m.example/a233/index.m3u8\",TYPE=AUDIO,GROUP-ID=\"233\",NAME=\"Default\",DEFAULT=YES,AUTOSELECT=YES\n")
        append("#EXT-X-MEDIA:URI=\"https://m.example/a234/index.m3u8\",TYPE=AUDIO,GROUP-ID=\"234\",NAME=\"Default\",DEFAULT=YES,AUTOSELECT=YES\n")
        for ((i, v) in listOf(
            Triple("avc1.4D4015,mp4a.40.5", 240, 30), Triple("avc1.4D401E,mp4a.40.2", 360, 30), Triple("avc1.4D401F,mp4a.40.2", 480, 30),
            Triple("avc1.4D4020,mp4a.40.2", 720, 60), Triple("avc1.64002A,mp4a.40.2", 1080, 60),
            Triple("vp09.00.40.08,mp4a.40.2", 720, 60), Triple("vp09.00.41.08,mp4a.40.2", 1080, 60),
            Triple("vp09.00.50.08,mp4a.40.2", 1440, 60), Triple("vp09.00.51.08,mp4a.40.2", 2160, 60),
        ).withIndex()) {
            val (c, h, f) = v
            append(inf(100_000 * (i + 1), c, h * 16 / 9, h, f, if (i == 0) "233" else "234")).append('\n')
            append("https://m.example/v$i/$h/index.m3u8\n")
        }
    }

    private fun heights(text: String) = text.lines().filter { it.startsWith("#EXT-X-STREAM-INF") }.map { HlsFilter.parseVariant(it).height }

    @Test fun defaultLimitsKeepH264UpTo1080() {
        val r = HlsFilter.filter(youtube, HlsFilter.Limits())
        assertTrue(r.changed)
        assertEquals(listOf(240, 360, 480, 720, 1080), heights(r.text))
        assertEquals(5, r.kept); assertEquals(4, r.dropped)
        assertTrue(r.text.lines().none { "vp09" in it })
        // Every kept variant still has its URI right after it, and the audio renditions stay.
        val lines = r.text.lines()
        for ((i, l) in lines.withIndex()) if (l.startsWith("#EXT-X-STREAM-INF")) assertTrue(lines[i + 1].startsWith("https://m.example/v"))
        assertEquals(2, lines.count { it.startsWith("#EXT-X-MEDIA") })
        assertTrue(r.note, r.note.contains("best 1080p"))
    }

    @Test fun otherLimits() {
        assertEquals(listOf(240, 360, 480), heights(HlsFilter.filter(youtube, HlsFilter.Limits(1080, 30, true)).text))
        assertEquals(listOf(240, 360, 480, 720, 1080, 720, 1080), heights(HlsFilter.filter(youtube, HlsFilter.Limits(1080, 60, false)).text))
        assertEquals(listOf(240, 360, 480, 720, 1080, 720, 1080, 1440, 2160), heights(HlsFilter.filter(youtube, HlsFilter.Limits(2160, 60, false)).text))
        assertFalse(HlsFilter.filter(youtube, HlsFilter.Limits(2160, 60, false)).changed)
    }

    @Test fun neverLeavesNothing() {
        val vp9Only = "#EXTM3U\n" + inf(1, "vp09.00.51.08", 3840, 2160, 60, "a") + "\nhttps://x/4k.m3u8\n" +
            inf(1, "vp09.00.41.08", 1920, 1080, 60, "a") + "\nhttps://x/hd.m3u8\n"
        val r = HlsFilter.filter(vp9Only, HlsFilter.Limits())
        assertEquals(listOf(1080), heights(r.text))
        assertTrue(r.note, r.note.contains("no H.264"))
        val allBig = "#EXTM3U\n" + inf(1, "avc1.640033", 3840, 2160, 60, "a") + "\nhttps://x/4k.m3u8\n" +
            inf(1, "avc1.640032", 2560, 1440, 60, "a") + "\nhttps://x/qhd.m3u8\n"
        assertEquals(listOf(1440), heights(HlsFilter.filter(allBig, HlsFilter.Limits()).text))
        // A media playlist or a master without attributes passes unchanged.
        assertFalse(HlsFilter.filter("#EXTM3U\n#EXTINF:10,\nseg.ts\n", HlsFilter.Limits()).changed)
        assertFalse(HlsFilter.filter("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n", HlsFilter.Limits()).changed)
    }
}
