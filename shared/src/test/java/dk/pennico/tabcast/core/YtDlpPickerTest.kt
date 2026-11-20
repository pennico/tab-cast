// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class YtDlpPickerTest {
    /** Shaped like yt-dlp output for a site offering HLS (master), DASH, a muxed MP4 and YouTube-style split files. */
    private val sample = """
    {"title":"T","duration":95.5,"thumbnail":"https://i/x.jpg","extractor_key":"Generic","webpage_url":"https://p/v",
     "http_headers":{"User-Agent":"UA","Accept":"*/*","Sec-Fetch-Mode":"navigate"},
     "formats":[
      {"format_id":"sb0","url":"https://p/sb.mhtml","ext":"mhtml","protocol":"mhtml","format_note":"storyboard","vcodec":"none","acodec":"none"},
      {"format_id":"hls-360","url":"https://cdn/360.m3u8","manifest_url":"https://cdn/master.m3u8","protocol":"m3u8_native","ext":"mp4","height":360,"vcodec":"avc1","acodec":"mp4a"},
      {"format_id":"hls-1080","url":"https://cdn/1080.m3u8","manifest_url":"https://cdn/master.m3u8","protocol":"m3u8_native","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none"},
      {"format_id":"dash-v","url":"https://cdn/v.mp4","manifest_url":"https://cdn/manifest.mpd","protocol":"http_dash_segments","ext":"mp4","height":720,"vcodec":"avc1","acodec":"none"},
      {"format_id":"18","url":"https://cdn/muxed.mp4","protocol":"https","ext":"mp4","height":360,"vcodec":"avc1","acodec":"mp4a","http_headers":{"User-Agent":"UA2"}},
      {"format_id":"137","url":"https://cdn/video-only.mp4","protocol":"https","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none"},
      {"format_id":"140","url":"https://cdn/audio.m4a","protocol":"https","ext":"m4a","vcodec":"none","acodec":"mp4a","abr":128},
      {"format_id":"drm","url":"https://cdn/drm.mpd","manifest_url":"https://cdn/drm.mpd","protocol":"http_dash_segments","has_drm":true,"height":2160}
     ]}"""

    @Test fun ordersMasterDashMuxed() {
        val r = YtDlpPicker.parse(sample)
        assertEquals("T", r.title)
        assertEquals(95.5, r.durationSec!!, 0.0)
        val urls = r.picks.map { it.url }
        assertEquals("https://cdn/master.m3u8", urls[0])
        assertEquals(1080, r.picks[0].height)
        assertEquals("https://cdn/manifest.mpd", urls[1])
        assertEquals("https://cdn/muxed.mp4", urls[2])
        assertTrue("video-only must be skipped", "https://cdn/video-only.mp4" !in urls)
        assertTrue("DRM must be skipped", "https://cdn/drm.mpd" !in urls)
        assertTrue(urls.last() == "https://cdn/audio.m4a")
        assertEquals("UA2", r.picks[2].headers["User-Agent"])
        val avi = YtDlpPicker.parse("""{"formats":[{"url":"https://a/x.avi","ext":"avi","protocol":"https","height":720},
            {"url":"https://a/x.mp4","ext":"mp4","protocol":"https","height":360}]}""")
        assertEquals("https://a/x.mp4", avi.best!!.url)
        assertNull(r.picks[0].headers["Sec-Fetch-Mode"])
    }

    @Test fun singleUrlAndPlaylist() {
        val r = YtDlpPicker.parse("""{"_type":"playlist","entries":[{"title":"E","url":"https://h/a.mp4","ext":"mp4","protocol":"https"}]}""")
        assertEquals("https://h/a.mp4", r.best!!.url)
        assertEquals(Kind.MP4, r.best!!.kind)
    }

    /** Opt-in: real yt-dlp --dump-single-json files (TABCAST_YTJSON=dir), not committed (they hold signed URLs). */
    @Test fun realOutputs() {
        val dir = System.getenv("TABCAST_YTJSON")?.let { File(it) }
        assumeTrue(dir != null && dir.isDirectory)
        for (f in dir!!.listFiles { x -> x.name.endsWith(".json") && x.length() > 100 }!!.sortedBy { it.name }) {
            val r = YtDlpPicker.parse(f.readText())
            println("[${f.name}] ${r.extractor} '${r.title}' ${r.durationSec}s live=${r.live}: " +
                r.picks.take(4).joinToString(" | ") { "${it.note} ${it.kind} h=${it.height} score=${it.score} ${Urls.host(it.url)}" })
            assertTrue("${f.name}: nothing castable", r.picks.isNotEmpty())
        }
    }
}
