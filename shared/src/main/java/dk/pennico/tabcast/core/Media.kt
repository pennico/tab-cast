// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

/** What a URL carries, as far as casting is concerned. */
enum class Kind(val label: String, val mime: String, val streaming: Boolean = false) {
    HLS("HLS", "application/x-mpegurl", true),
    DASH("DASH", "application/dash+xml", true),
    MP4("MP4", "video/mp4"),
    WEBM("WebM", "video/webm"),
    MKV("MKV", "video/x-matroska"),
    MOV("MOV", "video/quicktime"),
    OGV("Ogg video", "video/ogg"),
    AUDIO("Audio", "audio/mpeg"),
    UNKNOWN("?", "video/mp4");

    val isVideoFile get() = this == MP4 || this == WEBM || this == MKV || this == MOV || this == OGV
}

object Classify {
    private val segmentExts = setOf("ts", "m4s", "m4f", "m4v_seg", "cmfv", "cmfa", "aac_seg", "vtt", "webvtt", "srt", "key")
    private val audioMime = mapOf(
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "oga" to "audio/ogg",
        "ogg" to "audio/ogg", "opus" to "audio/ogg", "flac" to "audio/flac", "wav" to "audio/wav",
    )

    /** Kind from the URL alone (path extension plus a few well-known query hints); null = not media. */
    fun fromUrl(url: String): Kind? {
        if (!Urls.isHttp(url)) return null
        val ext = Urls.ext(url)
        when (ext) {
            "m3u8" -> return Kind.HLS
            "mpd" -> return Kind.DASH
            "mp4", "m4v" -> return Kind.MP4
            "webm" -> return Kind.WEBM
            "mkv" -> return Kind.MKV
            "mov" -> return Kind.MOV
            "ogv" -> return Kind.OGV
            in audioMime.keys -> return Kind.AUDIO
        }
        // A file with some other extension (.json, .js, .html, images...) is not a manifest, whatever its path says.
        if (ext.isNotEmpty() && ext.length <= 5 && ext !in setOf("php", "aspx", "ashx", "do", "cgi", "isml", "ism")) return null
        val lower = url.lowercase()
        // Extension-less manifests that say what they are in the path or query.
        if (lower.contains("format=m3u8") || lower.contains("(format=m3u8") ||
            (Regex("""[/?&=_.-]m3u8([/?&=_.-]|$)""").containsMatchIn(lower) &&
                (lower.contains("manifest") || lower.contains("playlist") || lower.contains("master")))
        ) return Kind.HLS
        if (lower.contains("format=mpd") || lower.contains("/dash/manifest") || lower.contains("(format=mpd")) return Kind.DASH
        return null
    }

    /** Kind from a Content-Type header value; null if it doesn't say. */
    fun fromMime(contentType: String?): Kind? {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            ct.contains("mpegurl") -> Kind.HLS
            ct == "application/dash+xml" -> Kind.DASH
            ct == "video/mp4" || ct == "video/x-m4v" -> Kind.MP4
            ct == "video/webm" -> Kind.WEBM
            ct == "video/x-matroska" -> Kind.MKV
            ct == "video/quicktime" -> Kind.MOV
            ct == "video/ogg" -> Kind.OGV
            ct.startsWith("audio/") && !ct.contains("mpegurl") -> Kind.AUDIO
            else -> null
        }
    }

    /** Kind from the first bytes of a body. */
    fun sniff(head: ByteArray, n: Int = head.size): Kind? {
        if (n <= 0) return null
        val s = String(head, 0, minOf(n, 1024), Charsets.ISO_8859_1).trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\r', '\n', '\t')
        if (s.startsWith("#EXTM3U")) return Kind.HLS
        if ((s.startsWith("<?xml") || s.startsWith("<MPD")) && s.contains("<MPD")) return Kind.DASH
        if (n >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") return Kind.MP4
        if (n >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) return Kind.WEBM
        if (s.startsWith("OggS")) return Kind.AUDIO
        if (s.startsWith("ID3")) return Kind.AUDIO
        return null
    }

    fun mimeFor(kind: Kind, url: String): String {
        if (kind == Kind.AUDIO) return audioMime[Urls.ext(url)] ?: "audio/mpeg"
        return kind.mime
    }

    fun isSegmentUrl(url: String): Boolean {
        val ext = Urls.ext(url)
        if (ext in segmentExts) return true
        val name = Urls.path(url).substringAfterLast('/').lowercase()
        if (ext == "mp4" || ext == "m4v" || ext == "webm" || ext == "m4a" || ext == "aac") {
            // fMP4/CMAF segments and init sections that carry a .mp4 extension
            if (name.startsWith("init") || name.contains("-init") || name.contains("_init")) return true
            if (Regex("""(seg|segment|frag|fragment|chunk|part)[-_]?\d+""").containsMatchIn(name)) return true
            if (Regex("""^(seg|segment|frag|fragment|chunk)[-_.]""").containsMatchIn(name)) return true
            val q = url.substringAfter('?', "").lowercase()
            if (Regex("""(^|&)(st=(video|audio)|range=\d|bytestart=|byterange=)""").containsMatchIn(q)) return true
            if (Regex("""^\d+\.(mp4|m4v|m4a|webm|aac)$""").matches(name)) return true
            val p = Urls.path(url).lowercase()
            if (p.contains("/range/") || p.contains("/segment/") || p.contains("/fragments/") || p.contains("/chunks/")) return true
        }
        return false
    }

    // Small built-in list; the browser's host blocker (assets/blocklist.txt) is the bigger one.
    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "imasdk.googleapis.com",
        "2mdn.net", "fwmrm.net", "freewheel.tv", "spotxchange.com", "spotx.tv", "springserve.com",
        "adsrvr.org", "moatads.com", "innovid.com", "teads.tv", "smartadserver.com", "criteo.com",
        "pubmatic.com", "adnxs.com", "rubiconproject.com", "yieldmo.com", "vidoomy.com", "aniview.com",
        "adform.net", "amazon-adsystem.com", "serving-sys.com", "flashtalking.com", "tremorhub.com",
        "lkqd.net", "vidazoo.com", "connatix.com", "primis.tech", "ex.co", "outbrain.com", "taboola.com",
    )

    fun isAdUrl(url: String): Boolean {
        val h = Urls.host(url)
        if (adHosts.any { Urls.hostMatches(h, it) }) return true
        val p = Urls.path(url).lowercase()
        return p.contains("/ads/") || p.contains("/adserver") || p.contains("/preroll") || p.contains("/vast") ||
            p.contains("/vpaid") || p.contains("/advert") || p.contains("/commercials/")
    }
}
