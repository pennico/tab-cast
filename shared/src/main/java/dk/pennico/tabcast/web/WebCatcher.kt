// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.core.CatchSession
import dk.pennico.tabcast.core.JsEvent
import dk.pennico.tabcast.core.Kind
import dk.pennico.tabcast.core.Prober
import dk.pennico.tabcast.core.SiteRule
import dk.pennico.tabcast.core.SiteRules
import dk.pennico.tabcast.core.StreamInfo
import dk.pennico.tabcast.core.YtDlpPicker
import dk.pennico.tabcast.core.adblock.FilterEngine
import dk.pennico.tabcast.core.adblock.FilterEngine.Level
import dk.pennico.tabcast.core.adblock.T
import dk.pennico.tabcast.core.Urls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** What [WebCatcher] needs from the app it runs in (Tab Cast on the tablet, Tab Cast TV). Read on the main thread. */
interface CatcherHost {
    /** Pages may start their own video (no "user gesture required"). */
    val pageAutoplay: Boolean
    /** Ad/tracker blocking level for a page (its site's own setting, else the app's). Main thread. */
    fun adLevel(pageUrl: String): Level
    val desktopMode: Boolean
    fun rules(): List<SiteRule>
    /** The WebView's user agent changed (the tablet reuses it for stream requests). */
    fun userAgentChanged(ua: String) {}
    /** yt-dlp on a page, where the app has it (the tablet); null on the TV. */
    val ytdlp: (suspend (ctx: Context, url: String, rules: List<SiteRule>, referer: String?, onStep: (String) -> Unit) -> YtDlpPicker.Result)?
        get() = null
}

/**
 * Makes a WebView catch media: injects assets/inject.js at document start into every frame, listens to its messages,
 * and watches every request in shouldInterceptRequest. Results go into [session] (one per page).
 */
class WebCatcher(private val ctx: Context, private val web: WebView, private val scope: CoroutineScope,
                 private val host: CatcherHost) {
    @Volatile var session: CatchSession = CatchSession("about:blank")
        private set
    var onChange: (() -> Unit)? = null
    var onPageTitle: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var pending = false
    private val probed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val diagSeen: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val probeSem = Semaphore(3)
    private val frames = ConcurrentHashMap<String, JavaScriptReplyProxy>()
    private var rules: List<SiteRule> = emptyList()
    private val ruleHandlers = ArrayList<ScriptHandler>()
    private var injectHandler: ScriptHandler? = null
    private val injectJs: String by lazy { ctx.assets.open("inject.js").bufferedReader().use { it.readText() } }
    private var docStart = false

    val desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    lateinit var mobileUa: String
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // false: pages may start their own video. With true (v0.1.0) every play() not tied to a fresh tap was refused,
        // and many players (the hls.js demo among them) never started on the tablet.
        s.mediaPlaybackRequiresUserGesture = !host.pageAutoplay
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // "; wv" marks an app WebView; some sites then refuse logins or serve worse players. Look like Chrome.
        mobileUa = s.userAgentString.replace("; wv)", ")").replace(Regex("""\sVersion/\d+\.\d+"""), "")
        host.userAgentChanged(mobileUa)
        applyUa(host.desktopMode)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        // Each WebView capability is optional: if one is missing or fails, catching degrades instead of crashing.
        var listener = false
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(web, "tabcast", setOf("*")) { _, message: WebMessageCompat, _: Uri, _, reply ->
                    onMessage(message.data, reply)
                }
                listener = true
            }
        } catch (t: Throwable) { AppLog.e("web message listener", t) }
        if (!listener) {
            web.addJavascriptInterface(object {
                @JavascriptInterface fun postMessage(s: String) { main.post { onMessage(s, null) } }
            }, "TabCastJs")
        }
        try {
            docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (docStart) injectHandler = WebViewCompat.addDocumentStartJavaScript(web, injectJs, setOf("*"))
        } catch (t: Throwable) { docStart = false; AppLog.e("document-start script", t) }
        val wv = try { WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName } catch (t: Throwable) { null }
        AppLog.i("WebView $wv; message listener: $listener; document-start script: $docStart")
        reloadRules()

        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    // Runs on a Chromium network thread, not tied to any WebView: same hook, never touches the WebView.
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = intercept(request, serviceWorker = true)
                })
            }
        } catch (t: Throwable) { AppLog.e("service worker interception", t) }
    }

    fun applyUa(desktop: Boolean) {
        web.settings.userAgentString = if (desktop) desktopUa else mobileUa
        userAgent = web.settings.userAgentString
        host.userAgentChanged(userAgent)
    }

    /**
     * The WebView's user agent, for request hooks. Set on the main thread (here and by BrowserActivity's rule UA);
     * read on network threads, which must never call the WebView (v0.1.0 crashed on every site with a service worker:
     * "All WebView methods must be called on the same thread").
     */
    @Volatile var userAgent: String = ""

    /** (Re)register site-rule scripts: each in its own document-start script, so a broken rule only breaks itself. */
    fun reloadRules() {
        rules = host.rules()
        ruleHandlers.forEach { try { it.remove() } catch (_: Exception) {} }
        ruleHandlers.clear()
        if (!docStart) return
        for (r in rules) {
            val body = r.script ?: continue
            val js = "(window.__tabcastRules=window.__tabcastRules||[]).push({m:${JSONObject.quote(r.match.pattern)}," +
                "n:${JSONObject.quote(r.name)},f:function(){\n$body\n}});"
            try { ruleHandlers += WebViewCompat.addDocumentStartJavaScript(web, js, setOf("*")) } catch (e: Throwable) { AppLog.e("rule ${r.name}", e) }
        }
    }

    fun rulesFor(url: String): List<SiteRule> = SiteRules.forUrl(rules, url).let { r ->
        if (rules.isEmpty()) SiteRules.forUrl(host.rules(), url) else r
    }

    /** Called by the activity when the main frame starts a new document. */
    fun newPage(url: String) {
        if (Urls.stripFragment(url) == Urls.stripFragment(session.pageUrl)) return
        if (adRequests.get() > 0 && adLevel != Level.OFF) AppLog.i("ad blocking: ${adSummary()}")
        adBlocked.set(0); adRequests.set(0)
        frames.clear()
        playingOrigin = null
        probed.clear()
        diagSeen.clear()
        yt?.let { if (Urls.stripFragment(it.pageUrl) != Urls.stripFragment(url)) { it.job?.cancel(); yt = null } }
        session = CatchSession(url, rulesFor(url)).also { it.listener = { changed() } }
        refreshAdLevel()
        changed()
        if (!docStart && Urls.isHttp(url)) web.evaluateJavascript(injectJs, null)
    }

    private fun changed() {
        if (pending) return
        pending = true
        main.postDelayed({ pending = false; onChange?.invoke() }, 250)
    }

    /** The page's own player, reported once a second while it plays (Tab Cast TV's remote): position, duration, paused. */
    var onVideoState: ((pos: Double, dur: Double?, paused: Boolean) -> Unit)? = null

    /** Ad blocking for the current page: its level, page-level exceptions, and what was blocked. */
    @Volatile var adLevel: Level = Level.OFF
        private set
    @Volatile private var adPageFlags = 0
    private val adBlocked = java.util.concurrent.atomic.AtomicInteger()
    private val adRequests = java.util.concurrent.atomic.AtomicInteger()
    val blockedCount: Int get() = adBlocked.get()
    val requestCount: Int get() = adRequests.get()
    /** The page's own video reported an error or a refused play() (Tab Cast TV suggests a lower ad-blocking level). */
    var onPageVideoProblem: ((String) -> Unit)? = null

    /** Re-reads the page's level after the user changed it (then reload the page). */
    fun refreshAdLevel() {
        val url = session.pageUrl
        adLevel = if (Urls.isHttp(url)) host.adLevel(url) else Level.OFF
        adPageFlags = AdBlock.engine?.pageFlags(url) ?: 0
        if (adPageFlags and FilterEngine.PAGE_ALLOWED != 0) adLevel = Level.OFF
    }

    /** A main-frame navigation to a known ad/malware page (pop-unders): the app refuses it. */
    fun blocksNavigation(url: String): Boolean {
        val e = AdBlock.engine ?: return false
        if (adLevel == Level.OFF || !Urls.isHttp(url)) return false
        return e.blocksDocument(url, session.pageUrl)
    }

    /** One line about the current page's blocking, for the log. */
    fun adSummary(): String = "${adBlocked.get()} of ${adRequests.get()} requests blocked on ${Urls.host(session.pageUrl)} (${adLevel.label})"

    /** Origin of the frame whose video played last: where player commands go. */
    @Volatile var playingOrigin: String? = null
        private set

    private fun onMessage(data: String?, reply: JavaScriptReplyProxy?) {
        if (data != null && data.contains("\"t\":\"state\"")) {
            try {
                val o = org.json.JSONObject(data)
                if (o.optString("t") == "state") {
                    onVideoState?.invoke(o.optDouble("pos", 0.0), o.optDouble("dur").takeIf { !it.isNaN() }, o.optBoolean("paused", true))
                    return
                }
            } catch (_: Exception) {}
        }
        if (data != null && reply != null && (data.contains("\"t\":\"css?\"") || data.contains("\"t\":\"keys\""))) { cosmetic(data, reply); return }
        val e = JsEvent.parse(data ?: return) ?: return
        if (reply != null && e.frameUrl != null) frames[Urls.origin(e.frameUrl)] = reply
        if (e.type == "play" && e.frameUrl != null) playingOrigin = Urls.origin(e.frameUrl)
        if (e.type == "diag") {
            val msg = try { org.json.JSONObject(data).optString("msg") } catch (_: Exception) { "" }
            if (msg.startsWith("video error") || msg.startsWith("play() refused: NotSupported")) onPageVideoProblem?.invoke(msg)
            if (msg.isNotEmpty() && diagSeen.size < 30 && diagSeen.add(msg)) AppLog.i("page video (${Urls.host(e.frameUrl ?: "")}): $msg")
            return
        }
        session.onJs(e)
        if (e.type == "page" && e.top) e.title?.let { onPageTitle?.invoke(it) }
    }

    /**
     * Element hiding: a frame asks for its CSS at document start ("css?"), and at Aggressive sends the classes/ids it
     * has ("keys") to get the generic rules that apply to them. Answered through the frame's own reply channel.
     */
    private fun cosmetic(data: String, reply: JavaScriptReplyProxy) {
        val eng = AdBlock.engine ?: return
        try {
            val o = JSONObject(data)
            val frame = o.optString("frame")
            val frameHost = o.optString("host").ifEmpty { Urls.host(frame) }
            val level = if (o.optBoolean("top") && Urls.isHttp(frame)) host.adLevel(frame) else adLevel
            if (level == Level.OFF || adPageFlags and FilterEngine.PAGE_ALLOWED != 0) return
            val sels = if (o.optString("t") == "css?") eng.specificSelectors(frameHost, level, adPageFlags)
            else {
                val a = o.optJSONArray("k") ?: return
                eng.genericSelectors(frameHost, (0 until a.length()).map { a.optString(it) }, level, adPageFlags)
            }
            if (sels.isNotEmpty()) reply.postMessage("css:" + AdBlock.css(sels))
            if (o.optString("t") == "css?" && level == Level.AGGRESSIVE && adPageFlags and FilterEngine.PAGE_GENERICHIDE == 0) reply.postMessage("keys:on")
        } catch (t: Throwable) { AppLog.e("element hiding", t) }
    }

    /** Ask every frame to rescan (before opening the Cast list) or to pause its videos (after casting). */
    fun command(cmd: String) {
        for (p in frames.values) try { p.postMessage(cmd) } catch (_: Exception) {}
        if (frames.isEmpty()) web.evaluateJavascript("window.__tabcastCommand && window.__tabcastCommand(${JSONObject.quote(cmd)})", null)
    }

    /** A player command (play, toggle, seek:<s>, seekby:<s>) for the frame whose video played last, else every frame. */
    fun commandPlaying(cmd: String) {
        val p = playingOrigin?.let { frames[it] }
        if (p == null) command(cmd) else try { p.postMessage(cmd) } catch (_: Exception) { command(cmd) }
    }

    /** The playing video fills the screen inside the page (CSS, no gesture needed); [restoreInPage] undoes it. */
    fun maximizeInPage() {
        commandPlaying("maximize")
        val top = Urls.origin(session.pageUrl)
        val o = playingOrigin
        if (o != null && o != top) frames[top]?.let { try { it.postMessage("maximize:$o") } catch (_: Exception) {} }
            ?: web.evaluateJavascript("window.__tabcastCommand && window.__tabcastCommand(${JSONObject.quote("maximize:$o")})", null)
    }

    fun restoreInPage() = command("restore")

    private val interceptErrors = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Request hook for the page (WebViewClient) and its service workers (ServiceWorkerClient). Called on Chromium's
     * network threads: it must not touch [web]. A failure here is logged and the request passes through untouched.
     */
    fun intercept(request: WebResourceRequest, serviceWorker: Boolean = false): WebResourceResponse? = try {
        interceptImpl(request, serviceWorker)
    } catch (t: Throwable) {
        if (interceptErrors.incrementAndGet() <= 5) AppLog.e("request hook failed (request passed through)", t)
        null
    }

    private fun interceptImpl(request: WebResourceRequest, serviceWorker: Boolean): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        val top = request.isForMainFrame && !serviceWorker
        if (!top) {
            adRequests.incrementAndGet()
            val eng = AdBlock.engine
            val level = adLevel
            if (eng != null && level != Level.OFF && Urls.isHttp(url)) {
                val r = FilterEngine.Request(url, T.of(url, request.requestHeaders, false), session.pageUrl)
                if (eng.check(r, level) == FilterEngine.Verdict.BLOCK) {
                    adBlocked.incrementAndGet()
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
        if (!top) {
            val h = HashMap(request.requestHeaders ?: emptyMap())
            if (h.keys.none { it.equals("User-Agent", true) }) h["User-Agent"] = userAgent
            session.onRequest(url, h)
        }
        return null
    }

    /** A yt-dlp run for the current page or a player embedded in it. Fields are read on the main thread. */
    class YtJob(val url: String, val pageUrl: String, val auto: Boolean, val key: String? = null) {
        private val t0 = android.os.SystemClock.elapsedRealtime()
        @Volatile var step: String = "starting"
        @Volatile var running = true
        @Volatile var error: String? = null
        @Volatile var found: String? = null
        var job: kotlinx.coroutines.Job? = null
        val seconds: Long get() = (android.os.SystemClock.elapsedRealtime() - t0) / 1000
    }

    /** The page's yt-dlp run (one at a time), shown on the Cast button and in the Cast list. Main thread. */
    var yt: YtJob? = null
        private set

    /**
     * Runs yt-dlp on [url] in the background and adds what it finds to the Cast list. User-visible progress and a
     * logged outcome (in v0.1.0 a YouTube run in the browser went on for minutes with no trace).
     */
    fun runYt(url: String, auto: Boolean = false, key: String? = null) {
        if (host.ytdlp == null) return
        val cur = yt
        if (cur != null && cur.url == url && (cur.running || cur.found != null)) return
        cur?.job?.cancel()
        val page = session.pageUrl
        val j = YtJob(url, page, auto, key)
        yt = j
        val rs = (rulesFor(url) + rulesFor(page)).distinct()
        val referer = if (Urls.stripFragment(url) != Urls.stripFragment(page)) page else null
        AppLog.i("browser: yt-dlp ${if (auto) "started by itself" else "asked"} for $url")
        changed()
        j.job = scope.launch {
            try {
                val resolve = host.ytdlp ?: error("no yt-dlp in this app")
                val r = resolve(ctx, url, rs, referer) { st -> j.step = st; changed() }
                if (yt !== j || Urls.stripFragment(session.pageUrl) != Urls.stripFragment(page)) return@launch
                val maxH = r.picks.mapNotNull { it.height }.maxOrNull()
                for ((i, p) in r.picks.take(3).withIndex()) {
                    val master = p.kind == Kind.DASH || p.note.contains("all qualities")
                    val info = StreamInfo(p.kind, isMaster = master, maxHeight = if (master) maxH else p.height,
                        durationSec = r.durationSec, live = r.live)
                    addExternal(p.url, p.kind, "yt-dlp", p.headers, r.title, r.thumb, info, bonus = 80 - i * 10)
                }
                j.found = r.title ?: "a video"
                AppLog.i("browser: yt-dlp found \"${j.found}\" in ${j.seconds} s; it is first in the Cast list")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                j.error = e.message ?: e.javaClass.simpleName
                AppLog.i("browser: yt-dlp gave nothing for $url after ${j.seconds} s: ${j.error}")
            } finally {
                j.running = false
                changed()
            }
        }
    }

    /** Pages of sites whose rule has "ytdlpAuto" (YouTube/Vimeo video pages) start yt-dlp without being asked. */
    fun maybeAutoYt(url: String) {
        if (!Urls.isHttp(url) || host.ytdlp == null) return
        val m = rulesFor(url).firstNotNullOfOrNull { r -> r.ytdlpAuto?.find(url) } ?: return
        // The video, not the URL: YouTube adds and drops parameters on the same video.
        val key = Urls.host(url).removePrefix("www.").removePrefix("m.") + "|" + m.value.trimStart('?', '&')
        val cur = yt
        if (cur != null && cur.key == key && (cur.running || cur.found != null)) return
        // Single-page sites (m.youtube.com) move to the next video without loading a new page.
        if (Urls.stripFragment(url) != Urls.stripFragment(session.pageUrl)) newPage(url)
        runYt(Urls.stripFragment(url), auto = true, key = key)
    }

    /** Probe the best few candidates that haven't been probed (when the Cast list opens). */
    fun probeTop(n: Int = 6) {
        val s = session
        for (c in s.ranked().take(n)) {
            if (c.info?.httpStatus != null || c.info?.error != null) continue
            if (c.ad || !probed.add(c.url)) continue
            scope.launch(Dispatchers.IO) {
                probeSem.withPermit {
                    val hdr = c.headers.toMutableMap()
                    if (hdr.keys.none { it.equals("User-Agent", true) }) hdr["User-Agent"] = mobileUa
                    val info: StreamInfo = Prober.probe(c.url, c.kind, hdr) { u -> CookieManager.getInstance().getCookie(u) }
                    s.updateInfo(c.url, info)
                    AppLog.i("probe ${c.kind}${if (info.isMaster) " master" else ""} ${info.maxHeight ?: ""} status=${info.httpStatus} ${info.error ?: ""} ${c.url.take(160)}")
                }
            }
        }
    }

    fun addExternal(url: String, kind: Kind, via: String, headers: Map<String, String>, title: String?, thumb: String?, info: StreamInfo?, bonus: Int) {
        session.add(url, kind, via, session.pageUrl, headers, System.currentTimeMillis(), title = title, thumb = thumb, info = info)
        session.get(url)?.let { if (bonus != 0) session.boost(url, bonus) }
    }
}
