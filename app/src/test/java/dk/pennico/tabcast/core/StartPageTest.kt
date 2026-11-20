// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The start page (v0.1.1). Also writes sample pages to build/start-page/ for jstest/shot.mjs screenshots. */
class StartPageTest {
    private val now = 1_794_700_000_000L
    private val tests = listOf(
        StartPage.Site("MP4 file (archive.org)", "https://archive.org/details/BigBuckBunny_124"),
        StartPage.Site("HLS stream (hls.js demo)", "https://hlsjs.video-dev.org/demo/"),
        StartPage.Site("PeerTube video", "https://framatube.org/w/9c9de5e8-0a1e-484a-b099-e80766180a6d"),
        StartPage.Site("Vimeo (needs yt-dlp)", "https://vimeo.com/76979871"),
    )
    private val full = StartPage.Model(
        token = "tok-123",
        sites = listOf(
            StartPage.Site("RaiPlay", "https://www.raiplay.it/"),
            StartPage.Site("DR TV", "https://www.dr.dk/drtv"),
            StartPage.Site("Arte", "https://www.arte.tv/en/"),
            StartPage.Site("YouTube", "https://m.youtube.com/"),
            StartPage.Site("A long site name that goes on & on <b>", "https://video.example.org/some/path"),
        ),
        tests = tests,
        recent = listOf(
            StartPage.Recent("Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
                "https://manifest.googlevideo.com/x", "https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg", now - 20 * 60_000, "Living room TV"),
            StartPage.Recent("big_buck_bunny_720p_surround.mp4", null,
                "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4", null, now - 3 * 3_600_000, "Living room TV"),
        ),
        tv = "Living room TV · FCast Receiver",
        nowPlaying = "Big Buck Bunny 60fps 4K",
        lastPage = StartPage.Site("hls.js demo", "https://hlsjs.video-dev.org/demo/"),
        now = now,
    )

    @Test fun rendersEscapedAndTokenised() {
        val h = StartPage.html(full)
        assertTrue(h.contains("A long site name that goes on &amp; on &lt;b&gt;"))
        assertFalse(h.contains("<b>\"") || h.contains("on & on"))
        assertTrue(h.contains("tabcast://edit?i=4&k=tok-123"))
        assertTrue(h.contains("tabcast://recast?i=1&k=tok-123"))
        assertTrue(h.contains("20 min ago") && h.contains("3 h ago"))
        assertEquals(5, Regex("class=tile ").findAll(h).count())
        File("build/start-page").mkdirs()
        File("build/start-page/full.html").writeText(h)
        File("build/start-page/first-run.html").writeText(StartPage.html(full.copy(sites = emptyList(), recent = emptyList(),
            tv = null, nowPlaying = null, lastPage = null)))
    }

    @Test fun actionsNeedTheToken() {
        assertEquals("edit" to mapOf("i" to "2", "k" to "tok"), StartPage.parseAction("tabcast://edit?i=2&k=tok", "tok"))
        assertEquals("https://x.org/a b", StartPage.parseAction("tabcast://go?k=tok&q=https%3A%2F%2Fx.org%2Fa+b", "tok")?.second?.get("q"))
        assertNull(StartPage.parseAction("tabcast://edit?i=2&k=other", "tok"))
        assertNull(StartPage.parseAction("tabcast://edit?i=2", "tok"))
        assertNull(StartPage.parseAction("https://example.com/?k=tok", "tok"))
    }

    @Test fun relativeTimes() {
        assertEquals("just now", StartPage.ago(now - 10_000, now))
        assertEquals("5 min ago", StartPage.ago(now - 5 * 60_000, now))
        assertEquals("2 d ago", StartPage.ago(now - 49 * 3_600_000L, now))
    }
}
