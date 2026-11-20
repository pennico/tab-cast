// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core.adblock

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * What the shipped lists do on real pages: the jstest recordings (headless Chromium on public pages, git-ignored, made
 * with jstest/run.sh) replayed through the engine with the request type the app infers. Prints a table for the 0.2.2
 * report. Skipped when there are no recordings.
 */
class AdblockReplayTest {
    @Test fun recordedPages() {
        val out = File("../jstest/out")
        val recs = out.listFiles { f -> f.name.endsWith(".json") && f.name != "expect.json" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no recordings in jstest/out", recs.isNotEmpty())
        val dir = File("src/main/assets/filters")
        val (e, _) = FilterLists.build(FilterLists.parseManifest(File(dir, "lists.txt").readText())) { File(dir, "$it.txt").takeIf { f -> f.exists() }?.bufferedReader() }
        val chromeType = mapOf("script" to T.SCRIPT, "image" to T.IMAGE, "stylesheet" to T.STYLESHEET, "xhr" to T.XHR,
            "fetch" to T.XHR, "media" to T.MEDIA, "font" to T.FONT, "ping" to T.PING, "websocket" to T.WEBSOCKET,
            "document" to T.SUBDOCUMENT, "other" to T.OTHER)
        println("ADREPLAY page | requests | blocked standard | blocked aggressive | type guessed right | top blocked hosts")
        for (f in recs) {
            val j = JSONObject(f.readText())
            val page = j.optString("pageUrl")
            val reqs = j.optJSONArray("requests") ?: continue
            var n = 0; var std = 0; var agg = 0; var right = 0
            val hosts = HashMap<String, Int>()
            for (i in 0 until reqs.length()) {
                val r = reqs.getJSONObject(i)
                val url = r.optString("url")
                if (!url.startsWith("http") || url == page) continue
                n++
                val h = r.optJSONObject("headers")
                val headers = h?.keys()?.asSequence()?.associateWith { h.optString(it) }.orEmpty()
                val type = T.of(url, headers, false)
                if (chromeType[r.optString("type")]?.let { it and type != 0 } == true) right++
                val req = FilterEngine.Request(url, type, page)
                if (e.check(req, FilterEngine.Level.STANDARD) == FilterEngine.Verdict.BLOCK) { std++; hosts.merge(req.host, 1, Int::plus) }
                if (e.check(req, FilterEngine.Level.AGGRESSIVE) == FilterEngine.Verdict.BLOCK) agg++
            }
            val top = hosts.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key} ${it.value}" }
            println("ADREPLAY ${f.nameWithoutExtension} | $n | $std (${if (n > 0) std * 100 / n else 0} %) | $agg | ${if (n > 0) right * 100 / n else 0} % | $top")
        }
    }
}
