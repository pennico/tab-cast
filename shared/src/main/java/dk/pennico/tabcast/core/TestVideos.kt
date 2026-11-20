// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

/**
 * Public sample videos for the TV "Test" button. If one plays, the TV path works and any later failure is about the
 * site. Sample links die (Google's gtv-videos-bucket started answering 403 to everyone in 2026), so the button checks
 * them from the tablet in this order and casts the first that answers.
 */
object TestVideos {
    data class Video(val url: String, val mime: String, val title: String, val thumb: String?)

    val LIST = listOf(
        Video("https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4", "video/mp4",
            "Tab Cast test: Big Buck Bunny (MP4, archive.org)", "https://archive.org/services/img/BigBuckBunny_124"),
        Video("https://archive.org/download/ElephantsDream/ed_1024_512kb.mp4", "video/mp4",
            "Tab Cast test: Elephants Dream (MP4, archive.org)", "https://archive.org/services/img/ElephantsDream"),
        Video("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "application/x-mpegurl",
            "Tab Cast test: Big Buck Bunny (HLS, Mux)", null),
    )

    class Pick(val video: Video?, val failures: List<String>)

    /** The first video whose URL answers 2xx to [check] (HTTP status, or a negative number for a network error). */
    fun firstWorking(list: List<Video> = LIST, check: (String) -> Int): Pick {
        val failures = ArrayList<String>()
        for (v in list) {
            val code = try { check(v.url) } catch (e: Exception) { -1 }
            if (code in 200..299) return Pick(v, failures)
            failures += "${Urls.host(v.url)}: ${if (code < 0) "no answer" else "HTTP $code"}"
        }
        return Pick(null, failures)
    }

    /** Asks for the first KB only, like a player starting up. */
    fun httpCheck(url: String): Int {
        val f = Http.open(url, emptyMap(), range = "bytes=0-1023", connectTimeout = 6000, readTimeout = 6000)
        return try { f.code } finally { try { f.conn.inputStream.close() } catch (_: Exception) {}; f.conn.disconnect() }
    }
}
