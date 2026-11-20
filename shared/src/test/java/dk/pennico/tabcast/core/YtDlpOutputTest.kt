// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** yt-dlp output handling (v0.1.1). The lines are real output of yt-dlp 2026.10.26 (`--dump-single-json --no-quiet`, stderr merged). */
class YtDlpOutputTest {
    private val quick = """
        [youtube] Extracting URL: https://www.youtube.com/watch?v=aqz-KE-bpKQ
        [youtube] aqz-KE-bpKQ: Downloading webpage
        [youtube] aqz-KE-bpKQ: Downloading visionos player API JSON
        [youtube] aqz-KE-bpKQ: Downloading m3u8 information
        [info] aqz-KE-bpKQ: Downloading 1 format(s): 401+251
        {"id": "aqz-KE-bpKQ", "title": "Big Buck Bunny", "extractor": "youtube", "formats": []}
    """.trimIndent()

    @Test fun stepsFromRealLines() {
        val steps = quick.lines().mapNotNull { YtDlpOutput.step(it) }
        assertEquals(listOf(
            "extracting URL: https://www.youtube.com/watch?v=aqz-KE-bpKQ",
            "downloading webpage",
            "downloading visionos player API JSON",
            "downloading m3u8 information",
        ), steps)
        assertEquals("solving the site's JavaScript challenge (slow on this tablet)",
            YtDlpOutput.step("[youtube] [jsc:quickjs] Solving JS challenges using quickjs"))
        assertEquals("downloading the site's player script", YtDlpOutput.step("[youtube] aqz-KE-bpKQ: Downloading player fad7e4e9-main"))
        assertEquals("downloading webpage", YtDlpOutput.step("[youtube:tab] @LofiGirl/live: Downloading webpage"))
        for (l in listOf("", "null", "[debug] Command-line config", "WARNING: Unknown codec iamf", "ERROR: [youtube] x: gone",
            "[download] 10.0% of 5MiB", """{"id": "x"}""")) assertNull(l, YtDlpOutput.step(l))
    }

    @Test fun jsonLineIsTheLastObjectLine() {
        assertEquals("""{"id": "aqz-KE-bpKQ", "title": "Big Buck Bunny", "extractor": "youtube", "formats": []}""", YtDlpOutput.jsonLine(quick))
        assertNull(YtDlpOutput.jsonLine("[youtube] x: Downloading webpage\nERROR: [youtube] x: This video is unavailable\nnull\n"))
        // Real full output, when captured (see YtDlpPickerTest): the picker must read what jsonLine returns.
        val dir = System.getenv("TABCAST_YTJSON")?.let(::File)
        dir?.listFiles { f -> f.name.startsWith("merged-") }?.forEach { f ->
            val r = YtDlpPicker.parse(YtDlpOutput.jsonLine(f.readText()) ?: return@forEach)
            assertTrue(f.name, r.picks.isNotEmpty())
        }
    }

    @Test fun errorText() {
        assertEquals("This video is unavailable",
            YtDlpOutput.error(listOf("[youtube] xxxxxxxxxxx: Downloading webpage", "ERROR: [youtube] xxxxxxxxxxx: This video is unavailable", "null")))
        assertEquals("Unsupported URL: https://example.com/", YtDlpOutput.error(listOf("ERROR: Unsupported URL: https://example.com/")))
        assertNull(YtDlpOutput.error(listOf("[youtube] x: Downloading webpage")))
    }

    @Test fun timeline() {
        var t = 1000L
        val tl = YtDlpOutput.Timeline { t }
        tl.add("start"); t += 2900; tl.add("extracting URL"); t += 4100
        assertEquals("0.0 s start · 2.9 s extracting URL · 7.0 s done", tl.summary())
    }

    @Test fun youtubeRuleTriesVisionosFirst() {
        val json = File("src/main/assets/site-rules.json").readText()
        val yt = SiteRules.forUrl(SiteRules.parse(json), "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        assertEquals(listOf("--extractor-args", "youtube:player_client=visionos"), yt.flatMap { it.ytdlpTryFirst })
        assertTrue(yt.flatMap { it.ytdlpArgs }.isEmpty())
        val auto = yt.mapNotNull { it.ytdlpAuto }
        for (u in listOf("https://m.youtube.com/watch?v=yU8JpuK5NZs", "https://www.youtube.com/watch?app=desktop&v=aqz-KE-bpKQ",
            "https://youtu.be/aqz-KE-bpKQ?si=x", "https://www.youtube.com/shorts/abcdefghijk")) assertTrue(u, auto.any { it.containsMatchIn(u) })
        for (u in listOf("https://m.youtube.com/", "https://www.youtube.com/@LofiGirl", "https://m.youtube.com/results?search_query=x"))
            assertTrue(u, auto.none { it.containsMatchIn(u) })
        val vimeo = SiteRules.forUrl(SiteRules.parse(json), "https://vimeo.com/76979871").mapNotNull { it.ytdlpAuto }
        assertTrue(vimeo.any { it.containsMatchIn("https://vimeo.com/76979871") } && vimeo.none { it.containsMatchIn("https://vimeo.com/watch") })
        val user = SiteRules.parse("""{"rules":[{"match":"x\\.com$","ytdlpTryFirst":"--fast"}]}""")
        assertEquals(listOf("--fast"), user[0].ytdlpTryFirst)
    }
}
