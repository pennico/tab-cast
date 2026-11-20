// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.json.JSONObject

/** A castable stream found on the page (or by yt-dlp / a site rule). Immutable; the session replaces it on update. */
data class Candidate(
    val url: String,
    val kind: Kind,
    val via: String,
    val frameUrl: String?,
    val headers: Map<String, String>,
    val seq: Int,
    val lastSeen: Long,
    val hits: Int = 1,
    val info: StreamInfo? = null,
    val fromPlaying: Boolean = false,
    val title: String? = null,
    val thumb: String? = null,
    val segmentLike: Boolean = false,
    val ad: Boolean = false,
    val ruleBonus: Int = 0,
    val rangeZero: Boolean = false,
    val mime: String = Classify.mimeFor(kind, url),
    /** A quality variant listed by a master playlist that is also in the list (the master is the better pick). */
    val variantOfMaster: Boolean = false,
) {
    val isMasterish: Boolean get() = kind == Kind.DASH || info?.isMaster == true

    fun score(): Double {
        var s = when (kind) {
            Kind.HLS -> when (info?.isMaster) { true -> 100.0; false -> 80.0; null -> 88.0 }
            Kind.DASH -> 95.0
            Kind.MP4 -> 70.0
            Kind.WEBM -> 66.0
            Kind.MKV -> 64.0
            Kind.MOV -> 62.0
            Kind.OGV -> 15.0 // Theora: neither the Chromecast nor ExoPlayer plays it
            Kind.AUDIO -> 30.0
            Kind.UNKNOWN -> 40.0
        }
        if (fromPlaying) s += 40
        if (rangeZero && kind.isVideoFile) s += 8
        val h = info?.maxHeight
        if (h != null) s += minOf(h, 2160) / 100.0
        val d = info?.durationSec
        if (d != null) { if (d < 40) s -= 25 else if (d > 120) s += 10 }
        if (info?.audioOnly == true) s -= 40
        if (ad) s -= 80
        if (segmentLike) s -= 60
        if (info?.drm == true) s -= 100
        val st = info?.httpStatus
        if (st != null && st >= 400) s -= 120 // the TV would get the same refusal
        if (info?.error != null) s -= 10
        s += ruleBonus
        if (variantOfMaster) s -= 50
        // Players fetch the master playlist before its variants, but play the main video after pre-roll files.
        s += if (kind.streaming) -seq / 1000.0 else seq / 1000.0
        return s
    }

    /** One-line summary for the Cast list: "HLS · 1080p · 42:13 · live · cdn.example.com". */
    fun describe(): String {
        val parts = ArrayList<String>()
        parts += if (kind == Kind.HLS && info?.isMaster == false) "HLS (single quality)" else kind.label
        info?.maxHeight?.let { if (it > 0) parts += "${it}p" }
        if (info?.live == true) parts += "live"
        Manifests.formatDuration(info?.durationSec)?.let { parts += it }
        Manifests.formatSize(info?.sizeBytes)?.let { parts += it }
        if (info?.drm == true) parts += "DRM (won't play)"
        if (info?.audioOnly == true) parts += "audio only"
        if (ad) parts += "ad?"
        if (fromPlaying) parts += "▶ playing"
        if (variantOfMaster) parts += "one quality of the master"
        info?.httpStatus?.let { if (it >= 400) parts += "HTTP $it" }
        parts += Urls.host(url)
        return parts.joinToString(" · ")
    }
}

/** An event from the injected page script (see assets/inject.js). */
data class JsEvent(
    val type: String,
    val url: String? = null,
    val via: String? = null,
    val contentType: String? = null,
    val sniff: String? = null,
    val frameUrl: String? = null,
    val top: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Double? = null,
    val title: String? = null,
    val poster: String? = null,
    val iframes: List<String> = emptyList(),
) {
    companion object {
        fun parse(json: String): JsEvent? = try {
            val o = JSONObject(json)
            val ifr = o.optJSONArray("iframes")
            JsEvent(
                type = o.optString("t"),
                url = o.optString("url").ifEmpty { null },
                via = o.optString("via").ifEmpty { null },
                contentType = o.optString("ct").ifEmpty { null },
                sniff = o.optString("sniff").ifEmpty { null },
                frameUrl = o.optString("frame").ifEmpty { null },
                top = o.optBoolean("top"),
                width = o.optInt("w"),
                height = o.optInt("h"),
                duration = o.optDouble("dur").takeIf { !it.isNaN() && !it.isInfinite() && it > 0 },
                title = o.optString("title").ifEmpty { null },
                poster = o.optString("poster").ifEmpty { null },
                iframes = if (ifr == null) emptyList() else (0 until ifr.length()).map { ifr.optString(it) }.filter { Urls.isHttp(it) },
            )
        } catch (e: Exception) { null }
    }
}

/**
 * Everything caught for one page. Fed by the WebView (requests + page-script events) on the device, and by recorded
 * browser sessions in the JVM tests. Thread-safe: shouldInterceptRequest runs on WebView IO threads.
 */
class CatchSession(val pageUrl: String, private val rules: List<SiteRule> = emptyList()) {
    private val lock = Any()
    private val cands = LinkedHashMap<String, Candidate>()
    private var seq = 0
    private val segDirs = HashMap<String, Int>()
    private val frames = LinkedHashSet<String>()
    var pageTitle: String? = null
        private set
    var pageThumb: String? = null
        private set
    @Volatile var mseSeen = false
        private set
    @Volatile var playingSeen = false
        private set
    @Volatile var version = 0
        private set
    private var lastPlayFrame: String? = null

    var listener: (() -> Unit)? = null

    private fun bump() { version++; listener?.invoke() }

    private fun ruleFor(url: String): Pair<Boolean, Int> {
        var ignore = false
        var bonus = 0
        for (r in rules) {
            if (r.ignore.any { it.containsMatchIn(url) }) ignore = true
            if (r.prefer.any { it.containsMatchIn(url) }) bonus += 50
        }
        return ignore to bonus
    }

    private fun ruleHeaders(): Map<String, String> = rules.fold(emptyMap()) { acc, r -> acc + r.headers }

    /** A request seen by shouldInterceptRequest. Returns true if it was taken as a candidate. */
    fun onRequest(url: String, headers: Map<String, String>, now: Long = System.currentTimeMillis()): Boolean {
        val u = Urls.stripFragment(url)
        if (!Urls.isHttp(u)) return false
        val kind = Classify.fromUrl(u)
        if (kind == null || ((kind.isVideoFile || kind == Kind.AUDIO) && Classify.isSegmentUrl(u))) {
            if (Classify.isSegmentUrl(u)) synchronized(lock) {
                val dir = u.substringBeforeLast('/')
                segDirs[dir] = (segDirs[dir] ?: 0) + 1
            }
            return false
        }
        val range = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
        add(u, kind, "net", frameFromHeaders(headers), replayHeaders(headers), now, rangeZero = range != null && range.startsWith("bytes=0-"))
        return true
    }

    fun onJs(e: JsEvent, now: Long = System.currentTimeMillis()) {
        when (e.type) {
            "media" -> {
                if (e.via == "mse") { mseSeen = true; return }
                val u = e.url?.let { Urls.stripFragment(it) } ?: return
                if (!Urls.isHttp(u)) return
                val kind = when (e.sniff) { "hls" -> Kind.HLS; "dash" -> Kind.DASH; else -> null }
                    ?: Classify.fromMime(e.contentType) ?: Classify.fromUrl(u)
                    ?: if (e.via == "element" || e.via == "source" || e.via == "meta") Kind.UNKNOWN else return
                if ((kind.isVideoFile || kind == Kind.AUDIO) && Classify.isSegmentUrl(u) && e.via != "element") return
                add(u, kind, e.via ?: "js", e.frameUrl, emptyMap(), now, title = e.title, thumb = e.poster,
                    playInfo = if (e.via == "element") e else null)
            }
            "play" -> {
                playingSeen = true
                synchronized(lock) { lastPlayFrame = e.frameUrl }
                val src = e.url
                if (src != null && Urls.isHttp(src)) {
                    val u = Urls.stripFragment(src)
                    val kind = Classify.fromUrl(u) ?: Kind.UNKNOWN
                    add(u, kind, "element", e.frameUrl, emptyMap(), now, fromPlaying = true, thumb = e.poster,
                        playInfo = e)
                } else {
                    if (src != null && src.startsWith("blob:")) mseSeen = true
                    markPlayingStream(e)
                }
            }
            "page" -> synchronized(lock) {
                if (e.top) {
                    e.title?.let { pageTitle = it }
                    e.poster?.let { pageThumb = it }
                }
                frames.addAll(e.iframes)
                bump()
            }
        }
    }

    /** The playing <video> uses MSE (blob: src): credit the manifests the same frame loaded (ranking picks the master). */
    private fun markPlayingStream(e: JsEvent) = synchronized(lock) {
        val manifests = cands.values.filter { (it.kind == Kind.HLS || it.kind == Kind.DASH) && !it.ad }
        val pool = manifests.filter { sameDoc(it.frameUrl, e.frameUrl) }.ifEmpty { manifests }
        if (pool.isEmpty()) return@synchronized
        val dur = e.duration
        for (c in pool) {
            val info = c.info ?: StreamInfo(c.kind)
            cands[c.url] = c.copy(fromPlaying = true, thumb = c.thumb ?: e.poster,
                info = info.copy(
                    durationSec = info.durationSec ?: dur?.takeIf { it < 1e7 },
                    live = info.live || (dur != null && dur >= 1e7),
                    maxHeight = info.maxHeight ?: e.height.takeIf { it > 0 }))
        }
        bump()
    }

    private fun sameDoc(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return Urls.stripFragment(a) == Urls.stripFragment(b) || Urls.origin(a) == Urls.origin(b)
    }

    fun add(
        url: String, kind: Kind, via: String, frameUrl: String?, headers: Map<String, String>, now: Long,
        title: String? = null, thumb: String? = null, fromPlaying: Boolean = false, rangeZero: Boolean = false,
        playInfo: JsEvent? = null, info: StreamInfo? = null,
    ) {
        val (ignored, bonus) = ruleFor(url)
        if (ignored) return
        synchronized(lock) {
            val old = cands[url]
            val extra = ruleHeaders()
            if (old == null) {
                var inf = info
                if (playInfo != null && (playInfo.duration != null || playInfo.height > 0)) {
                    inf = (inf ?: StreamInfo(kind)).copy(
                        durationSec = playInfo.duration?.takeIf { it < 1e7 },
                        live = playInfo.duration != null && playInfo.duration >= 1e7,
                        maxHeight = playInfo.height.takeIf { it > 0 })
                }
                cands[url] = Candidate(
                    url = url, kind = kind, via = via, frameUrl = frameUrl,
                    headers = withDefaults(headers, frameUrl) + extra, seq = ++seq, lastSeen = now,
                    info = inf, fromPlaying = fromPlaying, title = title, thumb = thumb,
                    segmentLike = (kind.isVideoFile && Classify.isSegmentUrl(url)),
                    ad = Classify.isAdUrl(url) || (frameUrl != null && Classify.isAdUrl(frameUrl)),
                    ruleBonus = bonus, rangeZero = rangeZero,
                )
            } else {
                val betterKind = if (old.kind == Kind.UNKNOWN && kind != Kind.UNKNOWN) kind else old.kind
                val hdrs = if (headers.isNotEmpty() && (old.via != "net" || old.headers.size < headers.size)) withDefaults(headers, frameUrl ?: old.frameUrl) + extra else old.headers
                var inf = old.info ?: info
                if (playInfo != null && inf?.durationSec == null && playInfo.duration != null) {
                    inf = (inf ?: StreamInfo(betterKind)).copy(durationSec = playInfo.duration.takeIf { it < 1e7 },
                        maxHeight = inf?.maxHeight ?: playInfo.height.takeIf { it > 0 })
                }
                cands[url] = old.copy(
                    kind = betterKind, mime = Classify.mimeFor(betterKind, url), headers = hdrs,
                    frameUrl = old.frameUrl ?: frameUrl,
                    hits = old.hits + 1, lastSeen = now, fromPlaying = old.fromPlaying || fromPlaying,
                    title = old.title ?: title, thumb = old.thumb ?: thumb, rangeZero = old.rangeZero || rangeZero, info = inf,
                )
            }
            bump()
        }
    }

    fun updateInfo(url: String, info: StreamInfo) = synchronized(lock) {
        val c = cands[url] ?: return@synchronized
        // The probe knows best whether something is a manifest; for files keep what the page/URL said (OGV vs audio).
        val k = when {
            info.kind == Kind.UNKNOWN -> c.kind
            c.kind == Kind.UNKNOWN || info.kind.streaming -> info.kind
            else -> c.kind
        }
        // Keep what the playing element told us when the probe can't know it.
        val merged = info.copy(
            durationSec = info.durationSec ?: c.info?.durationSec,
            maxHeight = info.maxHeight ?: c.info?.maxHeight,
            live = info.live || (c.info?.live == true),
        )
        cands[url] = c.copy(kind = k, mime = Classify.mimeFor(k, url), info = merged)
        bump()
    }

    /** Ranked, best first. Hides obvious segments when there is anything better. */
    fun ranked(): List<Candidate> = synchronized(lock) {
        val variants = HashSet<String>()
        for (c in cands.values) c.info?.variantUrls?.forEach { variants += it; variants += it.substringBefore('?') }
        // Several files from one folder are pieces of one stream (Vimeo, some CDNs), not videos of their own.
        val dirCount = HashMap<String, Int>()
        for (c in cands.values) if (c.kind.isVideoFile && !c.fromPlaying) {
            val dir = c.url.substringBefore('?').substringBeforeLast('/')
            dirCount[dir] = (dirCount[dir] ?: 0) + 1
        }
        val all = cands.values.map { c ->
            val v = c.kind == Kind.HLS && c.info?.isMaster != true && (c.url in variants || c.url.substringBefore('?') in variants)
            val piece = c.kind.isVideoFile && !c.fromPlaying && (c.info?.sizeBytes ?: 0L) < 20_000_000L &&
                (dirCount[c.url.substringBefore('?').substringBeforeLast('/')] ?: 0) >= 3
            if (v != c.variantOfMaster || (piece && !c.segmentLike)) c.copy(variantOfMaster = v, segmentLike = c.segmentLike || piece) else c
        }.sortedByDescending { it.score() }
        val good = all.filter { !it.segmentLike }
        if (good.isEmpty()) all else good
    }

    fun get(url: String): Candidate? = synchronized(lock) { cands[url] }

    /** Raise (or lower) a candidate's rank, e.g. for what yt-dlp found when asked explicitly. */
    fun boost(url: String, bonus: Int) = synchronized(lock) {
        val c = cands[url] ?: return@synchronized
        cands[url] = c.copy(ruleBonus = c.ruleBonus + bonus)
        bump()
    }
    fun count(): Int = synchronized(lock) { cands.values.count { !it.segmentLike && !it.ad && it.info?.drm != true } }
    fun iframes(): List<String> = synchronized(lock) { frames.toList() }
    fun segmentsWithoutManifest(): Boolean = synchronized(lock) { segDirs.values.sum() > 3 && cands.values.none { it.kind.streaming } }
    fun playingFrame(): String? = synchronized(lock) { lastPlayFrame }

    companion object {
        private val dropReplay = setOf(
            "range", "accept-encoding", "cookie", "connection", "host", "upgrade-insecure-requests", "accept",
            "content-length", "content-type", "dnt", "x-requested-with", "priority",
        )

        /**
         * Headers worth replaying on the TV / relay: Referer, Origin, User-Agent, Accept-Language, client hints
         * (sec-ch-ua*), auth and custom X- headers. Cloudflare-fronted CDNs (Dailymotion, measured 2026-11-14) refuse a
         * manifest request that has Referer + User-Agent but neither Accept-Language nor sec-ch-ua ("error code 1010").
         */
        fun replayHeaders(h: Map<String, String>): Map<String, String> = h.filter { (k, _) ->
            val lk = k.lowercase()
            lk !in dropReplay && !lk.startsWith("sec-fetch-") && !lk.startsWith("if-") && !lk.startsWith("proxy-") &&
                !lk.startsWith(":")
        }

        fun frameFromHeaders(h: Map<String, String>): String? =
            h.entries.firstOrNull { it.key.equals("Referer", true) }?.value

        private fun withDefaults(h: Map<String, String>, frameUrl: String?): Map<String, String> {
            if (frameUrl == null || h.keys.any { it.equals("Referer", true) }) return h
            return h + ("Referer" to frameUrl)
        }
    }
}
