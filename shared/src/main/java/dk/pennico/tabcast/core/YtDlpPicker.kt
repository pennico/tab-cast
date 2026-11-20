// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Picks castable streams from yt-dlp's `--dump-single-json` output. The TV needs ONE URL that carries both audio and
 * video, so the order is: HLS master playlist (adaptive, all qualities) > DASH MPD > progressive file with audio+video >
 * single HLS variant. Separate video-only / audio-only files (YouTube's adaptive formats) are skipped: the TV can't
 * merge them.
 */
object YtDlpPicker {
    data class Pick(
        val url: String,
        val kind: Kind,
        val mime: String,
        val headers: Map<String, String>,
        val height: Int?,
        val note: String,
        val score: Int,
    )

    data class Result(
        val title: String?,
        val thumb: String?,
        val durationSec: Double?,
        val live: Boolean,
        val extractor: String?,
        val webpageUrl: String?,
        val picks: List<Pick>,
    ) {
        val best: Pick? get() = picks.firstOrNull()
    }

    fun parse(json: String): Result {
        var o = JSONObject(json)
        if (o.optString("_type") == "playlist") {
            val entries = o.optJSONArray("entries")
            val first = (0 until (entries?.length() ?: 0)).asSequence().mapNotNull { entries!!.optJSONObject(it) }.firstOrNull()
                ?: throw IllegalArgumentException("yt-dlp returned an empty playlist")
            o = first
        }
        val topHeaders = headersOf(o.optJSONObject("http_headers"))
        val formats: JSONArray = o.optJSONArray("formats") ?: JSONArray().also { if (o.has("url")) it.put(o) }
        val byUrl = LinkedHashMap<String, Pick>()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val p = pickOf(f, topHeaders) ?: continue
            val old = byUrl[p.url]
            if (old == null || p.score > old.score) byUrl[p.url] = p
        }
        val thumb = o.optString("thumbnail").ifEmpty { null }
            ?: o.optJSONArray("thumbnails")?.let { a -> (a.length() - 1 downTo 0).asSequence().mapNotNull { a.optJSONObject(it)?.optString("url")?.ifEmpty { null } }.firstOrNull() }
        return Result(
            title = o.optString("title").ifEmpty { null },
            thumb = thumb,
            durationSec = o.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
            live = o.optBoolean("is_live", false),
            extractor = o.optString("extractor_key").ifEmpty { o.optString("extractor").ifEmpty { null } },
            webpageUrl = o.optString("webpage_url").ifEmpty { null },
            picks = byUrl.values.sortedByDescending { it.score },
        )
    }

    private fun headersOf(h: JSONObject?): Map<String, String> {
        if (h == null) return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (k in h.keys()) {
            val v = h.optString(k)
            if (v.isNotEmpty() && !k.startsWith("Sec-", true) && !k.equals("Accept-Encoding", true)) m[k] = v
        }
        return m
    }

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }

    private fun pickOf(f: JSONObject, topHeaders: Map<String, String>): Pick? {
        if (f.opt("has_drm") == true) return null
        val url = str(f, "url") ?: return null
        val ext = (str(f, "ext") ?: "").lowercase()
        if (ext == "mhtml" || (str(f, "format_note") ?: "").contains("storyboard", true)) return null
        val protocol = str(f, "protocol") ?: when {
            url.contains(".m3u8") -> "m3u8_native"
            Urls.isHttp(url) -> "https"
            else -> return null
        }
        val vcodec = str(f, "vcodec")
        val acodec = str(f, "acodec")
        val noVideo = vcodec == "none"
        val noAudio = acodec == "none"
        val height = if (f.has("height") && !f.isNull("height")) f.optInt("height").takeIf { it > 0 } else null
        // Format class decides first (master > DASH > muxed file > single variant); height only orders within a class.
        val h = minOf(height ?: 0, 2160) / 10
        val headers = headersOf(f.optJSONObject("http_headers")).ifEmpty { topHeaders }
        val manifest = str(f, "manifest_url")
        val fid = str(f, "format_id") ?: "?"
        return when (protocol) {
            "m3u8", "m3u8_native" -> {
                val master = manifest != null && manifest != url
                val u = manifest ?: url
                var score = (if (master) 1000 else 400) + h
                if (noVideo && !master) score -= 300
                Pick(u, Kind.HLS, Kind.HLS.mime, headers, height, if (master) "HLS (all qualities)" else "HLS $fid", score)
            }
            "http_dash_segments" -> {
                val u = manifest ?: return null
                if (!Urls.isHttp(u)) return null
                Pick(u, Kind.DASH, Kind.DASH.mime, headers, height, "DASH (all qualities)", 900 + h)
            }
            "http", "https" -> {
                if (noVideo && noAudio) return null
                if (noAudio) return null // video-only: no sound on the TV
                val kind = when (ext) {
                    "mp4", "m4v" -> Kind.MP4
                    "webm" -> Kind.WEBM
                    "mkv" -> Kind.MKV
                    "mov" -> Kind.MOV
                    "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav" -> Kind.AUDIO
                    else -> Classify.fromUrl(url) ?: Kind.UNKNOWN
                }
                val audioOnly = noVideo || kind == Kind.AUDIO
                val score = if (audioOnly) 100 + (f.optDouble("abr").takeIf { !it.isNaN() }?.toInt() ?: 0) / 10
                else if (kind == Kind.UNKNOWN) 200 + h
                else if (kind == Kind.OGV) 150 + h // Theora: the TV can't decode it
                else 500 + h
                val mime = if (kind == Kind.UNKNOWN && ext == "flv") "video/x-flv" else Classify.mimeFor(kind, "x.$ext")
                Pick(url, kind, mime, headers, height, if (audioOnly) "audio $ext" else "$ext ${height?.let { "${it}p" } ?: fid}", score)
            }
            else -> null
        }
    }
}
