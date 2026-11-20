// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Our own per-site recipes, for sites where catching alone isn't enough. JSON, shipped in assets/site-rules.json and
 * editable in the app (Settings > Site rules). Every field except "match" is optional:
 *
 *   { "name": "Example", "match": "(^|\\.)example\\.com$",        // regex on the page host
 *     "prefer": ["cdn\\.example\\.com/.+\\.m3u8"],                // candidate URL regexes ranked first
 *     "ignore": ["/preroll/", "\\.mp4\\?ad="],                     // candidate URL regexes dropped
 *     "headers": { "Referer": "https://www.example.com/" },      // sent with the stream (TV or relay)
 *     "resolver": "auto" | "ytdlp" | "browser",                  // Share-target route; auto = yt-dlp then browser
 *     "userAgent": "desktop" | "mobile" | "<literal UA>",        // browser UA for this site
 *     "relay": true,                                              // always relay through the tablet
 *     "script": "return [...document.querySelectorAll('[data-hls]')].map(e => e.dataset.hls)",
 *                                                                 // JS (function body) run in every frame of the page;
 *                                                                 // returns a URL or an array of URLs
 *     "ytdlpArgs": ["--extractor-args", "generic:impersonate"],   // extra yt-dlp options for this site
 *     "ytdlpTryFirst": ["--extractor-args", "youtube:player_client=visionos"],
 *                                                                 // options for a quicker first try; if it fails or
 *                                                                 // finds nothing castable, yt-dlp runs without them
 *     "ytdlpAuto": "[?&]v=[\\w-]{11}",                             // regex on the page URL: the browser starts
 *                                                                 // yt-dlp by itself on such pages (video pages)
 *     "ytdlpRewrite": ["^https://(www\\.)?vimeo\\.com/(\\d+)$", "https://player.vimeo.com/video/$2"] }
 *                                                                 // regex + replacement applied to the page URL
 *                                                                 // before yt-dlp sees it
 */
data class SiteRule(
    val name: String,
    val match: Regex,
    val prefer: List<Regex> = emptyList(),
    val ignore: List<Regex> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val resolver: String = "auto",
    val userAgent: String? = null,
    val relay: Boolean? = null,
    val script: String? = null,
    val ytdlpArgs: List<String> = emptyList(),
    val ytdlpRewrite: Pair<Regex, String>? = null,
    val ytdlpTryFirst: List<String> = emptyList(),
    val ytdlpAuto: Regex? = null,
) {
    fun matches(url: String): Boolean = match.containsMatchIn(Urls.host(url))
}

object SiteRules {
    class ParseException(msg: String) : Exception(msg)

    fun parse(json: String): List<SiteRule> {
        val root = try { JSONObject(json) } catch (e: Exception) { throw ParseException("Not valid JSON: ${e.message}") }
        val arr = root.optJSONArray("rules") ?: throw ParseException("Missing \"rules\" array")
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: throw ParseException("rules[$i] is not an object")
            val name = o.optString("name").ifEmpty { "rule ${i + 1}" }
            fun re(s: String, what: String) = try { Regex(s, RegexOption.IGNORE_CASE) } catch (e: Exception) {
                throw ParseException("$name: bad $what regex \"$s\": ${e.message}")
            }
            fun list(key: String): List<String> {
                val a: JSONArray = o.optJSONArray(key) ?: return o.optString(key).takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
            }
            val match = o.optString("match").ifEmpty { throw ParseException("$name: \"match\" is required") }
            val h = o.optJSONObject("headers")
            val resolver = o.optString("resolver", "auto").lowercase()
            if (resolver !in setOf("auto", "ytdlp", "browser")) throw ParseException("$name: resolver must be auto, ytdlp or browser")
            SiteRule(
                name = name,
                match = re(match, "match"),
                prefer = list("prefer").map { re(it, "prefer") },
                ignore = list("ignore").map { re(it, "ignore") },
                headers = h?.keys()?.asSequence()?.associateWith { h.optString(it) } ?: emptyMap(),
                resolver = resolver,
                userAgent = o.optString("userAgent").ifEmpty { null },
                relay = if (o.has("relay")) o.optBoolean("relay") else null,
                script = o.optString("script").ifEmpty { null },
                ytdlpArgs = list("ytdlpArgs"),
                ytdlpTryFirst = list("ytdlpTryFirst"),
                ytdlpAuto = o.optString("ytdlpAuto").ifEmpty { null }?.let { re(it, "ytdlpAuto") },
                ytdlpRewrite = list("ytdlpRewrite").let { rw ->
                    when {
                        rw.isEmpty() -> null
                        rw.size == 2 -> re(rw[0], "ytdlpRewrite") to rw[1]
                        else -> throw ParseException("$name: ytdlpRewrite needs [regex, replacement]")
                    }
                },
            )
        }
    }

    fun forUrl(rules: List<SiteRule>, url: String): List<SiteRule> = rules.filter { it.matches(url) }

    /** The URL to give yt-dlp for [url]: the first matching rule's ytdlpRewrite, if any. */
    fun ytdlpUrl(rules: List<SiteRule>, url: String): String {
        for (r in forUrl(rules, url)) {
            val (re, rep) = r.ytdlpRewrite ?: continue
            if (re.containsMatchIn(url)) return re.replace(url, rep)
        }
        return url
    }

    /** JS object literal for inject.js: [{m: "<host regex>", s: "<function body>"}...] for rules with a script. */
    fun scriptsJson(rules: List<SiteRule>): String {
        val a = JSONArray()
        for (r in rules) if (r.script != null) a.put(JSONObject().put("m", r.match.pattern).put("s", r.script).put("n", r.name))
        return a.toString()
    }
}
