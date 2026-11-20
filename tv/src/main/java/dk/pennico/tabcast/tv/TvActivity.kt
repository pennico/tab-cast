// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.media3.ui.PlayerView
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.core.Candidate
import dk.pennico.tabcast.core.Kind
import dk.pennico.tabcast.core.Urls
import dk.pennico.tabcast.core.adblock.FilterEngine.Level
import dk.pennico.tabcast.web.AdBlock
import dk.pennico.tabcast.link.LinkServer
import dk.pennico.tabcast.link.Pairing
import dk.pennico.tabcast.link.Peer
import dk.pennico.tabcast.link.Wire
import dk.pennico.tabcast.link.Wire.Cmd
import dk.pennico.tabcast.link.Wire.Status
import dk.pennico.tabcast.web.WebCatcher
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Tab Cast TV: a browser the tablet opens pages in.
 *
 * Modes: **idle** (waiting screen), **page** (the WebView), **player** (the caught video in the TV's own player, full
 * screen), **inpage** (the page's own player stretched over the screen). When the page's video starts playing, the
 * TV switches to its own player by itself; Back returns to the page. The tablet connects over :link (port 46898) and
 * sends OPEN / CMD / TAP / SCROLL / TEXT; the TV answers with STATUS, CAUGHT and, while the tablet shows it, PREVIEW.
 */
class TvActivity : Activity() {
    companion object {
        /** Listening port (tests use 0 = any free port). */
        @VisibleForTesting var port = Wire.PORT
        /** Videos that play this long or less are previews/ads: not switched to full screen by themselves. */
        private const val MIN_AUTO_SECONDS = 45.0
    }

    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("tv-worker").apply { start() }
    private val bg = Handler(worker.looper)

    private lateinit var root: FrameLayout
    lateinit var web: WebView
        private set
    private lateinit var catcher: WebCatcher
    private lateinit var playerView: PlayerView
    private lateinit var player: TvPlayer
    private lateinit var idle: LinearLayout
    private lateinit var idleInfo: TextView
    private lateinit var idleCredits: TextView
    private lateinit var note: TextView
    private lateinit var fullscreen: FrameLayout
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private var server: LinkServer? = null
    private var webRemote: WebRemote? = null
    /** The paired tablet whose link is open (unpaired ones only get to send PAIR). */
    @Volatile private var peer: Peer? = null
    private val pairing get() = TvApp.pairing
    private lateinit var pinBox: LinearLayout
    private lateinit var pinCode: TextView
    private lateinit var pinWhy: TextView
    /** What the HTTP threads read (built on the main thread once a second). */
    @Volatile private var stateCache: Map<String, String> = emptyMap()
    @Volatile private var caughtCache: List<String> = emptyList()
    @Volatile private var latestPreview: ByteArray? = null
    @Volatile private var webPreviewAt = 0L
    private var linkPreview = false
    /** Sites, recent pages and test pages for the web remote's start view (0.3.2). */
    private lateinit var start: TvStart
    /** The address last opened from a remote: its recent entry gets the page's final address and title once loaded. */
    private var openedUrl: String? = null
    @VisibleForTesting val linkPort: Int? get() = server?.localPort
    @VisibleForTesting val webPort: Int? get() = webRemote?.port

    @VisibleForTesting var mode = "idle"
        private set
    private val autoPlayed = HashSet<String>()
    private var pageVideo: Triple<Double, Double?, Boolean>? = null // pos, dur, paused (the page's own player)
    private var loading = false
    private var lastError: String? = null
    private var blocked: String? = null
    /** Suggest a lower ad-blocking level for this site (the video doesn't start and requests were blocked). */
    private var adSuggest = false
    private var pageStartedAt = 0L
    private var previewFps = 0
    private var previewW = 640
    private var previewBusy = false
    private var lastCaught = ""
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    /** Our own playback level 0..1, used when the TV's media volume can't be changed by an app (see [volume]). */
    private var appVolume = 1.0
    /** 0.4.0: films from the tablet (rolling cache + positions) and the one playing, if any. */
    private lateinit var films: TvFilms
    @VisibleForTesting var film: TvFilms.Film? = null
        private set
    private var lastPosSave = 0L
    /** 0.5.0: the tablet's mirrored screen (mode "mirror"). */
    private lateinit var mirrorView: android.view.SurfaceView
    @Volatile private var mirror: TvMirror? = null
    private var lastKeyAsk = 0L
    /** 0.5.1: Tab Cast TV updates itself from its F-Droid repo (no Droid-ify). */
    private lateinit var updates: TvUpdates
    private lateinit var updateButton: android.widget.Button
    private lateinit var updateLine: TextView
    private var lastUpdateState = ""
    private val dailyCheck = object : Runnable {
        override fun run() { updates.check(); main.postDelayed(this, 3600_000L) } // checks the repo once a day
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        start = TvStart(this)
        films = TvFilms(this)
        playerView = PlayerView(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK); useController = true }
        // 0.5.1: subtitles readable from the sofa: larger, white on a dark box, the film's own sizes ignored.
        styleSubtitles(playerView)
        fullscreen = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        mirrorView = android.view.SurfaceView(this).apply {
            visibility = View.GONE
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(h: android.view.SurfaceHolder) { mirror?.setSurface(h.surface) }
                override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: android.view.SurfaceHolder) { mirror?.setSurface(null) }
            })
        }
        idleInfo = text("", 22f, 0xFFB0BEC5.toInt())
        idleCredits = text("", 13f, 0xFF78909C.toInt()).apply { setPadding(dp(80), dp(40), dp(80), 0) }
        idle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF101820.toInt())
            addView(text("Tab Cast TV", 56f, Color.WHITE))
            addView(text("Waiting for the tablet", 30f, 0xFF4FC3F7.toInt()).apply { setPadding(0, dp(16), 0, dp(32)) })
            addView(idleInfo)
            addView(idleCredits)
        }
        note = text("", 22f, Color.WHITE).apply {
            setBackgroundColor(0xCC000000.toInt()); setPadding(dp(20), dp(12), dp(20), dp(12)); visibility = View.GONE
        }
        pinCode = text("", 72f, Color.WHITE).apply { letterSpacing = 0.15f }
        pinWhy = text("", 20f, 0xFFB0BEC5.toInt())
        pinBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xEE0B1218.toInt())
            setPadding(dp(48), dp(32), dp(48), dp(32))
            addView(text("Pairing code", 26f, 0xFF4FC3F7.toInt()))
            addView(pinCode)
            addView(pinWhy)
            addView(text("Enter it on the device within 2 minutes. Back hides it.", 18f, 0xFF90A4AE.toInt()))
            visibility = View.GONE
        }
        updates = TvUpdates(this) { updateChanged() }
        updateButton = tvButton("Update") { askUpdate() }.apply { visibility = View.GONE }
        updateLine = text("", 18f, 0xFF4FC3F7.toInt()).apply { setPadding(0, dp(12), 0, 0); visibility = View.GONE }
        idle.addView(updateLine)
        idle.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
            addView(updateButton)
            addView(tvButton("Pair a device") { pairing.showPin("Pair a device (on the TV)") })
            addView(tvButton("Paired devices") { pairedDevices() })
        })
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(fullscreen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(mirrorView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
            addView(idle, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(note, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
            addView(pinBox, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(root)

        catcher = WebCatcher(this, web, scope, TvCatcherHost)
        catcher.setup()
        // Pop-ups: no new windows at all (window.open is refused, see Chrome.onCreateWindow).
        web.settings.setSupportMultipleWindows(true)
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        catcher.onChange = { onCaught() }
        catcher.onPageTitle = { sendStatus() }
        catcher.onVideoState = { pos, dur, paused -> pageVideo = Triple(pos, dur, paused); if (mode != "player") sendStatus() }
        catcher.onPageVideoProblem = { msg ->
            if (catcher.blockedCount > 0 && catcher.adLevel != Level.OFF && !adSuggest) {
                adSuggest = true
                AppLog.i("ad blocking may be in the way on ${Urls.host(web.url ?: "")}: $msg (${catcher.adSummary()})")
                sendStatus()
            }
        }
        player = TvPlayer(this, playerView, onChange = { main.post { sendStatus() } }, onError = { e -> main.post { playerFailed(e) } })
        player.onEnded = { main.post { filmEnded() } }
        player.onTracks = { main.post { filmTracks() } }
        showIdle()
        intent?.dataString?.takeIf { Urls.isHttp(it) }?.let { open(it) }
        main.post(statusTick)
        main.post(dailyCheck)
        AdBlock.deferWhile = { mode == "player" }
        pairing.onPin = { pin, why -> main.post { showPin(pin, why) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { Urls.isHttp(it) }?.let { open(it) }
    }

    override fun onStart() {
        super.onStart()
        startServer()
        if (webRemote == null) {
            val w = WebRemote(this, pairing, control)
            try { w.start(); webRemote = w } catch (e: Exception) { AppLog.e("web remote: can't listen on ${WebRemote.PORT}", e) }
            updateIdleInfo()
        }
    }

    override fun onStop() {
        server?.stop()
        server = null
        webRemote?.stop()
        webRemote = null
        peer = null
        stopPreview()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        mirror?.release(); mirror = null
        film?.let { saveFilmPosition(); it.cache.close() } // the cache stays on disk: the same film resumes from it
        player.release()
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        scope.cancel()
        worker.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------------------- link

    private fun startServer() {
        if (server != null) return
        lateinit var s: LinkServer
        s = LinkServer(port, "tv", helloAck = { _ ->
            mapOf("name" to tvName(), "app" to "Tab Cast TV ${BuildConfig.VERSION_NAME}",
                "screen" to "${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}", "acks" to 1,
                "files" to 1, "free" to films.free(), "mirror" to 1)
        }, isAuthorized = { pairing.check(it["token"]) != null }) { p ->
            if (!p.authorized) {
                // 0.3.0: an unpaired tablet (also a 0.2 one, once) must enter the PIN shown here; nothing else counts.
                AppLog.i("link: ${p.hello["name"] ?: "?"} (${p.remote}) isn't paired; showing a code")
                pairing.showPin("${p.hello["name"] ?: "A tablet"} at ${p.remote} wants to pair", fromDevice = true)
                p.onMessage = { m -> if (m.type == Wire.PAIR) main.post { linkPair(s, p, m.kv()) } }
                p.onClosed = {}
                return@LinkServer
            }
            p.onMessage = { m -> if (!mirrorMedia(p, m)) main.post { onLink(p, m) } }
            p.onClosed = { e ->
                main.post {
                    if (peer === p) {
                        peer = null
                        stopPreview()
                        stopMirror("the tablet left")
                        AppLog.i("tablet left (${e?.message ?: "closed"})")
                        updateIdleInfo()
                    }
                }
            }
            main.post {
                peer = p
                AppLog.i("tablet connected: ${p.hello["name"] ?: "?"} (${p.remote})")
                if (mode == "idle") updateIdleInfo() else note("Tablet connected")
                lastCaught = ""
                sendStatus(); sendCaught()
            }
        }
        s.log = { AppLog.i(it) }
        try {
            s.start()
            server = s
        } catch (e: Exception) {
            AppLog.e("can't listen on port $port", e)
            idleInfo.text = "Can't listen on port $port: ${e.message}"
        }
        updateIdleInfo()
    }

    /** An unpaired tablet sends the PIN: paired, it becomes the tablet (its link carries on). */
    private fun linkPair(s: LinkServer, p: Peer, kv: Map<String, String>) {
        when (val r = pairing.pair(kv["pin"] ?: "", kv["name"] ?: p.hello["name"] ?: "tablet", "tablet")) {
            is Pairing.Result.Ok -> {
                p.sendKv(Wire.PAIR_RESULT, "ok" to true, "token" to r.token)
                AppLog.i("link: paired \"${r.device.name}\" (${p.remote})")
                p.onMessage = { m -> if (!mirrorMedia(p, m)) main.post { onLink(p, m) } }
                p.onClosed = { e -> main.post { if (peer === p) { peer = null; stopPreview(); stopMirror("the tablet left"); AppLog.i("tablet left (${e?.message ?: "closed"})"); updateIdleInfo() } } }
                s.promote(p)
                peer = p
                note("Paired: ${r.device.name}")
                updateIdleInfo()
                lastCaught = ""
                sendStatus(); sendCaught()
            }
            is Pairing.Result.Wrong -> p.sendKv(Wire.PAIR_RESULT, "ok" to false, "tries" to r.triesLeft, "error" to "wrong code")
            is Pairing.Result.Locked -> p.sendKv(Wire.PAIR_RESULT, "ok" to false, "wait" to r.seconds, "error" to "too many wrong codes")
            Pairing.Result.NoPin -> {
                pairing.showPin("${p.hello["name"] ?: "A tablet"} at ${p.remote} wants to pair", fromDevice = true)
                p.sendKv(Wire.PAIR_RESULT, "ok" to false, "error" to "no code on the TV; a new one is shown if possible")
            }
        }
    }

    private fun tvName(): String = Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() }
        ?: android.os.Build.MODEL

    private fun onLink(p: Peer, m: Wire.Msg) {
        if (peer !== p) return
        val kv = if (m.type == Wire.PREVIEW || m.type == Wire.VIDEO || m.type == Wire.AUDIO) emptyMap() else m.kv()
        when (m.type) {
            Wire.OPEN -> {
                val url = kv["url"]?.takeIf { Urls.isHttp(it) } ?: return
                kv["cookies"]?.takeIf { it.isNotBlank() }?.let { setCookies(url, it) }
                open(url)
            }
            Wire.CMD -> {
                command(kv["c"] ?: return, kv["v"])
                // 0.3.3: the tablet waits for this (a silent dead link is reconnected and the command sent again).
                kv["id"]?.let { p.sendKv(Wire.ACK, "id" to it); sendStatus() }
            }
            Wire.FILE -> playFilm(kv)
            Wire.VIDEO_CONFIG -> startMirror(kv["w"]?.toIntOrNull() ?: 960, kv["h"]?.toIntOrNull() ?: 720)
            Wire.AUDIO_CONFIG -> mirror?.configureAudio(kv["rate"]?.toIntOrNull() ?: 48_000, kv["channels"]?.toIntOrNull() ?: 2)
            Wire.STATE -> if (kv["mirror"] == "off") {
                stopMirror("the tablet stopped" + (kv["why"]?.let { ": $it" } ?: ""))
                // 0.5.2: why the tablet stopped, when it isn't the user's own Stop (e.g. its video chip is busy).
                kv["why"]?.let { note("Mirroring stopped. $it.", 12_000) }
            }
            Wire.TAP -> tap(kv["x"]?.toFloatOrNull() ?: return, kv["y"]?.toFloatOrNull() ?: return, kv["long"] == "true")
            Wire.SCROLL -> scroll(kv["dx"]?.toFloatOrNull() ?: 0f, kv["dy"]?.toFloatOrNull() ?: 0f)
            Wire.TEXT -> type(kv["text"] ?: "", kv["enter"] == "true")
            Wire.SITES -> {
                val sites = String(m.payload, Charsets.UTF_8).split('\n').mapNotNull { l ->
                    l.split('\t').takeIf { it.size >= 2 }?.let { TvStart.Site(it[0], it[1]) } }
                start.tabletSites = sites
                AppLog.i("tablet sent its ${sites.size} start-page sites")
            }
            Wire.PREVIEW_ON -> { linkPreview = true; startPreview(kv["fps"]?.toIntOrNull() ?: 2, kv["w"]?.toIntOrNull() ?: 640) }
            Wire.PREVIEW_OFF -> { linkPreview = false; if (!webWantsPreview()) stopPreview() }
            else -> {}
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            filmTick()
            // Mirroring: the TV's side every 30 s, logged by the tablet ("mirror: TV says …").
            mirror?.let { mr -> if (SystemClock.elapsedRealtime() / 1000 % 30 == 0L) peer?.send(Wire.STATS, Wire.kv(*mr.stats().toList().toTypedArray())) }
            sendStatus()
            main.postDelayed(this, 1000)
        }
    }

    private fun sendStatus() {
        stateCache = statusMap()
        val p = peer ?: return
        p.send(Wire.STATUS, Wire.kv(*stateCache.toList().toTypedArray()))
    }

    // ---------------------------------------------------------------------------------- mirroring (0.5.0)

    /** Picture and sound frames go straight from the link's reader thread to the decoder (not via the main thread). */
    private fun mirrorMedia(p: Peer, m: Wire.Msg): Boolean {
        if (m.type != Wire.VIDEO && m.type != Wire.AUDIO) return false
        if (peer !== p) return true
        val mr = mirror ?: return true
        if (m.type == Wire.VIDEO) mr.video(m.payload, m.key, m.pts) else mr.audio(m.payload, m.pts)
        return true
    }

    private fun startMirror(w: Int, h: Int) {
        closeFilm(save = true)
        leavePlayer(resume = false)
        exitCustomView()
        if (mode == "page" || mode == "inpage") { catcher.command("pause"); web.loadUrl("about:blank"); catcher.newPage("about:blank") }
        mirror?.release()
        val mr = TvMirror(onNeedKey = {
            val now = SystemClock.elapsedRealtime()
            if (now - lastKeyAsk > 400) { lastKeyAsk = now; peer?.send(Wire.KEYFRAME) }
        })
        mr.volume = appVolume.toFloat()
        mirror = mr
        // Letterbox the tablet's 4:3 picture in the TV's 16:9 screen.
        val sw = resources.displayMetrics.widthPixels; val sh = resources.displayMetrics.heightPixels
        val scale = minOf(sw.toDouble() / w, sh.toDouble() / h)
        mirrorView.layoutParams = FrameLayout.LayoutParams((w * scale).roundToInt(), (h * scale).roundToInt(), Gravity.CENTER)
        mirrorView.visibility = View.VISIBLE
        mr.configureVideo(w, h)
        if (mirrorView.holder.surface?.isValid == true) mr.setSurface(mirrorView.holder.surface)
        idle.visibility = View.GONE
        web.visibility = View.INVISIBLE
        mode = "mirror"
        AppLog.i("mirror: the tablet's screen, ${w}x$h")
        note("The tablet's screen")
        sendStatus()
    }

    private fun stopMirror(why: String) {
        val mr = mirror ?: return
        mirror = null
        AppLog.i("mirror: ended ($why): ${mr.stats()}")
        bg.post { mr.release() }
        mirrorView.visibility = View.GONE
        if (mode == "mirror") showIdle()
        sendStatus()
    }

    private fun statusMap(): Map<String, String> {
        val f = film?.takeIf { mode == "player" }
        val (pos, dur, playing) = when {
            mode == "player" -> Triple(player.position, player.duration, player.playing)
            else -> pageVideo?.let { Triple(it.first, it.second, !it.third) } ?: Triple(null, null, false)
        }
        val vol = shownVolume()
        return listOf(
            Status.URL to (if (mode == "idle" || f != null) "" else web.url ?: ""),
            Status.TITLE to (if (mode == "idle") "" else f?.name ?: web.title ?: ""),
            Status.MODE to mode,
            Status.PLAYING to playing,
            Status.POS to pos?.let { (it * 10).roundToInt() / 10.0 },
            Status.DUR to dur?.let { (it * 10).roundToInt() / 10.0 },
            Status.VOLUME to (vol * 100).roundToInt() / 100.0,
            Status.CAUGHT to catcher.session.count(),
            Status.VIDEO to player.label,
            Status.LOADING to loading,
            Status.ERROR to lastError,
            Status.BLOCKED to blocked,
            // The page's configured level (shown at once after a change, before the reload finishes).
            Status.ADS to (web.url?.takeIf { mode != "idle" && Urls.isHttp(it) }?.let { TvCatcherHost.ads.levelFor(it).label } ?: TvCatcherHost.ads.global.label),
            Status.ADS_GLOBAL to TvCatcherHost.ads.global.label,
            Status.ADS_SITE to (web.url?.takeIf { mode != "idle" && Urls.isHttp(it) }?.let { TvCatcherHost.ads.site(it)?.label } ?: ""),
            Status.ADS_BLOCKED to (if (mode == "idle") 0 else catcher.blockedCount),
            Status.ADS_SUGGEST to adSuggest,
            Status.ADS_READY to (AdBlock.engine != null),
            Status.UPDATE to (updates.available?.name ?: ""),
            Status.UPDATE_STATE to updates.state).filter { it.second != null }.associate { it.first to it.second.toString() } +
            (f?.let { filmStatus(it, pos ?: 0.0) } ?: emptyMap())
    }

    // -------------------------------------------------------------------------------------- films (0.4.0)

    /** STATUS of a film: how much of it the TV holds, whether it waits for the tablet, tracks. */
    private fun filmStatus(f: TvFilms.Film, pos: Double): Map<String, String> {
        val st = f.cache.stats()
        val a = player.audioTracks()
        val t = player.textTracks()
        return listOf(
            Status.KIND to "file",
            Status.FILE_ID to f.ref,
            Status.HELD_TO to f.heldTo(pos)?.let { (it * 10).roundToInt() / 10.0 },
            Status.HELD to (st.cached * 1000 / st.size.coerceAtLeast(1)) / 1000.0,
            Status.COMPLETE to st.complete,
            Status.WAITING to (st.waiting && player.buffering),
            Status.FREE to films.free(),
            Status.CACHE to dk.pennico.tabcast.link.FilmCache.budgetFor(st.cached, films.free()),
            Status.A_TRACKS to a.joinToString("|") { it.label.replace('|', '/') },
            Status.A_TRACK to a.indexOfFirst { it.selected },
            Status.S_TRACKS to t.joinToString("|") { it.label.replace('|', '/') },
            Status.S_TRACK to t.indexOfFirst { it.selected },
            Status.NO_SOUND to player.noSound(),
        ).filter { it.second != null }.associate { it.first to it.second.toString() }
    }

    /** Wire.FILE: play a film the tablet serves, through the TV's rolling cache. */
    private fun playFilm(kv: Map<String, String>) {
        val rate = kv["rate"]?.toDoubleOrNull()
        val same = film?.takeIf { it.ref == films.refOf(kv) && mode == "player" }
        if (same != null) {
            // The same film again (the tablet app restarted and serves it at a new address): keep playing, fetch from there.
            kv["url"]?.takeIf { it.startsWith("http") && it != same.cache.url }?.let { u ->
                same.cache.url = u
                AppLog.i("film \"${same.name}\": the tablet serves it again at a new address; playing on")
            }
            rate?.let { same.cache.rateBitsPerSec = (it * 1_000_000).toLong() }
            // 0.5.1: subtitles chosen on the tablet while the film plays: fetch them, go on from the same place with them.
            val subs = kv["subs"]?.takeIf { it.startsWith("http") }
            if (subs != null && subs != same.subsUrl) {
                same.subsUrl = subs; same.subsMime = kv["subsmime"]
                bg.post {
                    val got = films.fetchSubtitles(same)
                    main.post {
                        if (film !== same || mode != "player") return@post
                        if (got == null) { note("The TV couldn't get the subtitles from the tablet"); return@post }
                        val at = player.position * 1000
                        player.playFilm(same.uri, films.mime(same), films.subtitles(same), FilmRouting(same.cache, FilmRouting.http()), at.toLong(), same.name)
                        note("Subtitles on")
                    }
                }
                return
            }
            note(same.name)
            sendStatus()
            return
        }
        stopMirror("a film starts")
        closeFilm(save = true)
        leavePlayer(resume = false)
        exitCustomView()
        if (mode != "idle") { catcher.command("pause"); web.loadUrl("about:blank"); catcher.newPage("about:blank") }
        val f = films.start(kv, rate) ?: run { AppLog.i("film: bad FILE message (${kv.keys})"); note("The tablet sent a file the TV can't open"); return }
        film = f
        val startMs = films.resumeAt(f.id)
        lastError = null
        player.volume = appVolume.toFloat()
        val begin = {
            if (film === f) player.playFilm(f.uri, films.mime(f), films.subtitles(f), FilmRouting(f.cache, FilmRouting.http()), startMs, f.name)
        }
        // 0.5.1: the subtitle file first (a few kB), so the player has it from the start and while the tablet sleeps.
        if (f.subsUrl != null) bg.post { films.fetchSubtitles(f); main.post(begin) } else begin()
        idle.visibility = View.GONE
        web.visibility = View.INVISIBLE
        mode = "player"
        note(if (startMs > 0) "${f.name} · from ${fmt(startMs / 1000.0)}" else f.name)
        sendStatus()
    }

    /** Ends the film on the screen (Back, Close, another page or film); its cache and position stay. */
    private fun closeFilm(save: Boolean) {
        val f = film ?: return
        if (save) saveFilmPosition()
        film = null
        player.release()
        bg.post { f.cache.close() }
        AppLog.i("film \"${f.name}\" closed (position kept)")
    }

    private fun saveFilmPosition() {
        val f = film ?: return
        if (!player.active) return
        films.savePosition(f.id, player.position, player.duration)
    }

    private fun filmEnded() {
        val f = film ?: return
        film = null
        player.release()
        bg.post { films.finished(f) }
        showIdle()
        note("Finished: ${f.name}")
        sendStatus()
    }

    private fun filmTracks() {
        val f = film ?: return
        player.duration?.let { f.duration = it }
        player.noSound()?.let { why ->
            if (lastError != why) { lastError = why; AppLog.i("film: $why"); note(why) }
        }
    }

    private var waitingShown = false

    /** Once a second while a film plays: position saved every 10 s; "Waiting for the tablet" while it waits. */
    private fun filmTick() {
        val f = film ?: return
        if (mode != "player") return
        player.duration?.let { f.duration = it }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPosSave > 10_000 && player.playing) { lastPosSave = now; saveFilmPosition() }
        val waiting = f.cache.stats().waiting && player.buffering
        if (waiting) {
            if (!waitingShown) AppLog.i("film: waiting for the tablet at ${fmt(player.position)} (${f.cache.stats().lastError})")
            waitingShown = true
            note("Waiting for the tablet… (wake it up; the film goes on by itself)")
        } else if (waitingShown) {
            waitingShown = false
            AppLog.i("film: the tablet answers again; playing on")
            note("Playing on")
        }
    }

    private fun fmt(sec: Double): String {
        val s = sec.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun sendCaught() {
        val p = peer
        val list = playableCandidates().map { it.describe().replace('\n', ' ') }
        caughtCache = list
        val lines = list.joinToString("\n")
        if (lines == lastCaught) return
        lastCaught = lines
        p?.send(Wire.CAUGHT, lines.toByteArray(Charsets.UTF_8))
    }

    // --------------------------------------------------------------------------------------------- page & player

    private fun showIdle() {
        mode = "idle"
        idle.visibility = View.VISIBLE
        web.visibility = View.INVISIBLE
        updateIdleInfo()
    }

    private fun updateIdleInfo() {
        val ip = TvNet.localIpv4(this) ?: "no network"
        val who = peer?.let { "Tablet connected: ${it.hello["name"] ?: it.remote}" } ?: "On the tablet: Cast a page or Cast a file, or Share → Cast to TV"
        idleInfo.text = "$who\n\nPhone or computer (on the same Wi-Fi as the TV): http://$ip:${webRemote?.port ?: WebRemote.PORT}\n" +
            "This TV: $ip · Tab Cast TV ${BuildConfig.VERSION_NAME} · ${pairing.devices().size} paired · ad blocking ${TvCatcherHost.ads.global.label}"
        idleCredits.text = AdBlock.ATTRIBUTION
    }

    private fun open(url: String) {
        AppLog.i("open $url")
        stopMirror("a page opens")
        closeFilm(save = true)
        start.opened(url, null)
        openedUrl = url
        leavePlayer(resume = false)
        exitCustomView()
        mode = "page"
        autoPlayed.clear()
        pageVideo = null
        lastError = null
        blocked = null
        idle.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl(url)
        web.requestFocus()
        sendStatus()
    }

    private fun close() {
        stopMirror("Close")
        closeFilm(save = true)
        leavePlayer(resume = false)
        exitCustomView()
        catcher.command("pause")
        web.loadUrl("about:blank")
        catcher.newPage("about:blank")
        showIdle()
        sendStatus()
    }

    private fun playable(c: Candidate) = !c.ad && (c.kind == Kind.HLS || c.kind == Kind.DASH || c.kind.isVideoFile) &&
        c.info?.drm != true && c.info?.audioOnly != true && (c.info?.httpStatus ?: 200) < 400

    private fun playableCandidates(): List<Candidate> = catcher.session.ranked().filter { playable(it) }.take(12)

    /** New catch results: when the page's own video plays (and isn't a short preview), go full screen by itself. */
    private fun onCaught() {
        sendCaught()
        if (mode == "idle") return
        // Probe the best few once each (quality, duration, dead links), as the tablet does when its Cast list opens.
        catcher.probeTop(4)
        if (mode != "page") return
        val c = playableCandidates().firstOrNull { it.fromPlaying } ?: return
        val d = c.info?.durationSec
        if (d != null && d in 0.0..MIN_AUTO_SECONDS && c.info?.live != true) return
        if (!autoPlayed.add(c.url)) return
        playNative(c, auto = true)
    }

    private fun playNative(c: Candidate, auto: Boolean) {
        if (film != null) return // a film plays: page videos don't take over
        exitCustomView()
        catcher.restoreInPage()
        catcher.command("pause")
        lastError = null
        player.volume = appVolume.toFloat()
        player.play(c, catcher.userAgent)
        // The page stays loaded but out of sight: its own video layer (a paused frame, a poster) can't end up on top
        // of ours.
        web.visibility = View.INVISIBLE
        mode = "player"
        note(if (auto) "Playing full screen · Back returns to the page" else "Playing full screen")
        sendStatus()
    }

    private fun playerFailed(e: String) {
        if (mode != "player") return
        film?.let { f ->
            // A film never ends in an error that needs a human: prepare again where it was, with growing pauses.
            // Except when the file itself can't be read or decoded here (not a network matter): say so and stop.
            f.errors++
            if (("PARSING" in e || "DECOD" in e || "UNSUPPORTED" in e) && f.errors >= 2) {
                lastError = "The TV can't play this file ($e)"
                AppLog.i("film: giving up, the TV can't play \"${f.name}\" ($e)")
                note(lastError!!)
                sendStatus()
                return
            }
            val wait = (3000L shl (f.errors - 1).coerceAtMost(3)).coerceAtMost(30_000)
            lastError = "Trying again in ${wait / 1000} s ($e)"
            AppLog.i("film: player error #${f.errors} ($e); preparing again in ${wait / 1000} s")
            main.postDelayed({ if (film === f) { player.retry(); lastError = null; sendStatus() } }, wait)
            sendStatus()
            return
        }
        lastError = "TV player: $e"
        player.release()
        web.visibility = View.VISIBLE
        // Fall back to the page's own player, stretched over the screen.
        mode = "inpage"
        catcher.commandPlaying("play")
        catcher.maximizeInPage()
        note("The TV's player couldn't play it; showing the page's own player")
        sendStatus()
    }

    private fun leavePlayer(resume: Boolean) {
        if (player.active) player.release()
        if (mode != "idle") web.visibility = View.VISIBLE
        if (mode == "player" || mode == "inpage") {
            catcher.restoreInPage()
            mode = "page"
            if (resume) web.requestFocus()
        }
    }

    private fun back() {
        when {
            customView != null -> exitCustomView()
            mirror != null -> { stopMirror("Back on the TV"); note("Mirroring ended") }
            film != null -> { val n = film?.name; close(); note("Stopped $n · the position is kept") }
            mode == "player" || mode == "inpage" -> { leavePlayer(resume = true); note("Back on the page") }
            mode == "page" && web.canGoBack() -> web.goBack()
            mode == "page" -> close()
            else -> {}
        }
        sendStatus()
    }

    private fun command(c: String, v: String?) {
        val x = v?.toDoubleOrNull()
        val inPlayer = mode == "player"
        when (c) {
            Cmd.PLAY -> if (inPlayer) player.play() else catcher.commandPlaying("play")
            Cmd.PAUSE -> if (inPlayer) player.pause() else catcher.command("pause")
            Cmd.TOGGLE -> if (inPlayer) player.toggle() else catcher.commandPlaying("toggle")
            Cmd.SEEK -> x?.let { if (inPlayer) player.seekTo(it) else catcher.commandPlaying("seek:$it") }
            Cmd.SEEK_BY -> x?.let { if (inPlayer) player.seekBy(it) else catcher.commandPlaying("seekby:$it") }
            Cmd.VOLUME -> x?.let { volume(null, it) }
            Cmd.VOLUME_BY -> volume(if ((x ?: 1.0) > 0) 1 else -1, null)
            Cmd.BACK -> back()
            Cmd.RELOAD -> if (film == null) { if (inPlayer) leavePlayer(resume = true); web.reload() }
            Cmd.CLOSE -> close()
            Cmd.PLAY_NATIVE -> playableCandidates().firstOrNull()?.let { playNative(it, auto = false) } ?: note("No video found on this page yet: press play on it first")
            Cmd.CANDIDATE -> x?.toInt()?.let { i -> playableCandidates().getOrNull(i)?.let { playNative(it, auto = false) } }
            Cmd.PLAY_IN_PAGE -> if (film == null) {
                player.release()
                web.visibility = View.VISIBLE
                mode = "inpage"
                catcher.commandPlaying("play")
                catcher.maximizeInPage()
            }
            Cmd.OPEN_BLOCKED -> blocked?.let { u -> blocked = null; allowOnce = u; web.loadUrl(u) }
            Cmd.AD_LEVEL -> Level.of(v)?.let { l ->
                TvCatcherHost.ads.global = l
                AppLog.i("ad blocking on all sites: ${l.label}")
                note("Ad blocking on all sites: ${l.label}")
                reloadForAds()
            }
            Cmd.AD_SITE -> web.url?.takeIf { Urls.isHttp(it) }?.let { u ->
                val l = Level.of(v)
                TvCatcherHost.ads.setSite(u, l)
                val site = TvCatcherHost.ads.siteOf(u)
                AppLog.i("ad blocking on $site: ${l?.label ?: "default (${TvCatcherHost.ads.global.label})"}")
                note("Ad blocking on $site: ${l?.label ?: "as on all sites"}")
                reloadForAds()
            }
            Cmd.REPORT -> {
                // "This didn't work" (0.5.0): what the TV shows, what it caught, and its log, for a bug report.
                AppLog.i("REPORT from the tablet: ${v ?: ""}")
                val text = buildString {
                    append("Tab Cast TV ${BuildConfig.VERSION_NAME} report ${java.util.Date()}\n")
                    append("what failed: ${v ?: ""}\nmode: $mode\npage: ${web.url}\ntitle: ${web.title}\n")
                    append("status: ${statusMap()}\ncaught:\n")
                    catcher.session.ranked().take(20).forEach { append("  ${it.describe().replace('\n', ' ')} | ${it.url.take(300)}\n") }
                    mirror?.let { append("mirror: ${it.stats()}\n") }
                    append("\n--- log ---\n").append(AppLog.text())
                }
                peer?.send(Wire.REPORT, text.toByteArray(Charsets.UTF_8).let { if (it.size > 4_000_000) it.copyOfRange(it.size - 4_000_000, it.size) else it })
                note("Report sent to the tablet")
            }
            Cmd.UPDATE -> if (v == "install") startUpdate(fromTv = false) else updates.check(force = true)
            Cmd.AUDIO -> x?.toInt()?.let { player.selectAudio(it); AppLog.i("film: audio track $it") }
            Cmd.SUBS -> { val i = x?.toInt() ?: -1; player.selectText(i); AppLog.i("film: subtitles ${if (i < 0) "off" else "track $i"}") }
            else -> AppLog.i("unknown command $c")
        }
        sendStatus()
    }

    /**
     * Volume from the tablet (0.2.0's buttons did nothing). The TV's media volume first; on a
     * Chromecast the HDMI output is fixed-volume for apps (the TV's own remote controls the TV over HDMI-CEC), so when
     * the media volume doesn't move, Tab Cast TV turns its own playback down/up instead: the player and every media
     * element of the page. 100 % is the loudest it can make itself; louder is the TV's own volume.
     */
    private fun volume(step: Int?, absolute: Double?) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val fixed = audio.isVolumeFixed
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (!fixed) {
            try {
                if (absolute != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, (absolute.coerceIn(0.0, 1.0) * max).roundToInt(), AudioManager.FLAG_SHOW_UI)
                else audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (step!! > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            } catch (e: SecurityException) { AppLog.e("volume", e) }
        }
        val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val system = after != before
        if (!system) {
            appVolume = ((absolute ?: (appVolume + 0.1 * step!!)) * 10).roundToInt().coerceIn(0, 10) / 10.0
            applyAppVolume()
        }
        AppLog.i("volume ${absolute?.let { "to ${(it * 100).roundToInt()} %" } ?: if (step!! > 0) "up" else "down"}: " +
            "media volume $before→$after of $max${if (fixed) " (fixed for apps)" else ""}; Tab Cast TV's own level ${(appVolume * 100).roundToInt()} %")
        note(if (system) "Volume ${after * 100 / max} %" else "Volume ${(appVolume * 100).roundToInt()} %" +
            if (appVolume >= 1.0 && (step ?: 0) > 0) " (louder: the TV's own volume)" else "")
    }

    private fun applyAppVolume() {
        player.volume = appVolume.toFloat()
        mirror?.volume = appVolume.toFloat()
        catcher.command("volume:$appVolume")
    }

    /** The volume the tablet shows: the media volume where an app can move it, else our own level. */
    private fun shownVolume(): Double {
        if (audio.isVolumeFixed) return appVolume
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max * appVolume
    }

    /** A changed ad-blocking level takes effect on the page: reload it (the player keeps playing if it plays). */
    private fun reloadForAds() {
        adSuggest = false
        catcher.refreshAdLevel()
        if (mode == "page" || mode == "inpage") web.reload()
    }

    // ------------------------------------------------------------------------------------- input from the tablet

    private fun touch(action: Int, down: Long, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(e)
        e.recycle()
    }

    /** A tap (or long press) at x, y (0..1 of the page) on the TV page: for play buttons, consent banners, menus. */
    @VisibleForTesting fun tap(x: Float, y: Float, long: Boolean) {
        if (mode == "player") { note("Back to the page first (Back)"); return }
        if (mode == "idle") return
        val px = x.coerceIn(0f, 1f) * web.width
        val py = y.coerceIn(0f, 1f) * web.height
        val t0 = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, t0, px, py)
        main.postDelayed({ touch(MotionEvent.ACTION_UP, t0, px, py) }, if (long) 700L else 60L)
    }

    /** A slow drag over the page: content moves by dx, dy (fractions of the page; positive = scroll down/right). */
    private fun scroll(dx: Float, dy: Float) {
        if (mode != "page" && mode != "inpage") return
        val w = web.width.toFloat()
        val h = web.height.toFloat()
        val sx = w / 2 + dx.coerceIn(-1f, 1f) * w / 2
        val sy = h / 2 + dy.coerceIn(-1f, 1f) * h / 2
        val ex = w / 2 - dx.coerceIn(-1f, 1f) * w / 2
        val ey = h / 2 - dy.coerceIn(-1f, 1f) * h / 2
        val t0 = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, t0, sx, sy)
        val steps = 10
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            main.postDelayed({ touch(MotionEvent.ACTION_MOVE, t0, sx + (ex - sx) * f, sy + (ey - sy) * f) }, i * 16L)
        }
        // Hold still before lifting, so the page doesn't fling far beyond what was asked.
        main.postDelayed({ touch(MotionEvent.ACTION_UP, t0, ex, ey) }, steps * 16L + 120L)
    }

    /** Text from the tablet into the focused field (keys where the keyboard map has them, else inserted). */
    private fun type(text: String, enter: Boolean) {
        if (text.isNotEmpty()) {
            val evs = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            if (evs != null) evs.forEach { web.dispatchKeyEvent(it) }
            else web.evaluateJavascript("document.execCommand('insertText', false, ${JSONObject.quote(text)})", null)
        }
        if (enter) {
            web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /** "Open on TV with my login": the tablet's cookies for that one site (values never logged). */
    private fun setCookies(url: String, cookies: String) {
        val cm = CookieManager.getInstance()
        val parts = cookies.split(';').map { it.trim() }.filter { it.indexOf('=') > 0 }
        for (c in parts) cm.setCookie(url, "$c; Path=/")
        cm.flush()
        AppLog.i("cookies from the tablet for ${Urls.host(url)}: ${parts.size} (${parts.joinToString(",") { it.substringBefore('=') }.take(200)})")
    }

    // ------------------------------------------------------------------------------------------------ preview

    private fun webWantsPreview() = SystemClock.elapsedRealtime() - webPreviewAt < 3000

    private val previewTick = object : Runnable {
        override fun run() {
            if (previewFps <= 0) return
            if (!linkPreview && !webWantsPreview()) { stopPreview(); return }
            capturePreview()
            main.postDelayed(this, 1000L / previewFps)
        }
    }

    private fun startPreview(fps: Int, w: Int) {
        previewFps = fps.coerceIn(1, 8)
        previewW = w.coerceIn(160, 1280)
        main.removeCallbacks(previewTick)
        main.post(previewTick)
    }

    private fun stopPreview() {
        previewFps = 0
        main.removeCallbacks(previewTick)
    }

    /** A small JPEG of the screen for the tablet and the web remote (the native player's picture is a separate layer: black). */
    private fun capturePreview() {
        val p = peer?.takeIf { linkPreview }
        val v = window.decorView
        if (previewBusy || (p != null && p.pending(Wire.PREVIEW) > 0) || v.width == 0 || v.height == 0) return
        val h = (previewW.toLong() * v.height / v.width).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(previewW, h, Bitmap.Config.ARGB_8888)
        previewBusy = true
        try {
            PixelCopy.request(window, bmp, { r ->
                if (r == PixelCopy.SUCCESS) {
                    val out = ByteArrayOutputStream(48 shl 10)
                    bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    val jpeg = out.toByteArray()
                    latestPreview = jpeg
                    p?.sendLatest(Wire.PREVIEW, jpeg, pts = System.nanoTime() / 1000)
                }
                bmp.recycle()
                previewBusy = false
            }, bg)
        } catch (e: Exception) {
            previewBusy = false
            AppLog.e("preview", e)
            stopPreview()
        }
    }

    // ------------------------------------------------------------------------------------------- remote keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (pinBox.visibility == View.VISIBLE) { pairing.hidePin(); return true }
                if (mode == "idle") return super.onKeyDown(keyCode, event); back(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { command(Cmd.TOGGLE, null); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { command(Cmd.PLAY, null); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { command(Cmd.PAUSE, null); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { command(Cmd.SEEK_BY, "30"); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { command(Cmd.SEEK_BY, "-10"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------------------------------------- pairing

    private val hidePinLater = Runnable { pairing.hidePin() }

    private fun showPin(pin: String?, why: String?) {
        main.removeCallbacks(hidePinLater)
        if (pin == null) { pinBox.visibility = View.GONE; return }
        pinCode.text = pin.substring(0, 3) + " " + pin.substring(3)
        pinWhy.text = why ?: ""
        pinBox.visibility = View.VISIBLE
        main.postDelayed(hidePinLater, Pairing.PIN_MS)
        AppLog.i("pairing code shown: ${why ?: ""}")
    }

    /** "Paired devices" on the TV: pick one to remove it (its token stops working at once). */
    private fun pairedDevices() {
        val list = pairing.devices().sortedByDescending { it.lastSeen }
        if (list.isEmpty()) { note("No paired devices yet"); return }
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
        android.app.AlertDialog.Builder(this).setTitle("Paired devices (select one to remove it)")
            .setItems(list.map { "${it.name} · ${it.kind} · last seen ${fmt.format(java.util.Date(it.lastSeen))}" }.toTypedArray()) { _, i ->
                val d = list[i]
                android.app.AlertDialog.Builder(this).setTitle("Remove ${d.name}?")
                    .setMessage("It can't control the TV any more until it is paired again.")
                    .setPositiveButton("Remove") { _, _ ->
                        pairing.revoke(d.id)
                        AppLog.i("paired device removed on the TV: ${d.name}")
                        if (peer?.hello?.get("token")?.let { pairing.check(it) } == null) peer?.close()
                        note("Removed ${d.name}")
                    }.setNegativeButton("Keep", null).show()
            }.setNegativeButton("Close", null).show()
    }

    // ------------------------------------------------------------------------------------ updates (0.5.1)

    private fun updateChanged() {
        val r = updates.available
        updateButton.text = r?.let { "Update to ${it.name}" } ?: "Update"
        updateButton.visibility = if (r != null && !updates.busy) View.VISIBLE else View.GONE
        updateLine.text = updates.state.ifEmpty { r?.let { "Tab Cast TV ${it.name} is ready" } ?: "" }
        updateLine.visibility = if (updateLine.text.isEmpty()) View.GONE else View.VISIBLE
        // Progress and problems also show over a page or the player (an update started from the tablet).
        if (updates.state.isNotEmpty() && updates.state != lastUpdateState && mode != "idle") note(updates.state)
        lastUpdateState = updates.state
        sendStatus()
    }

    /** "Update to x.y.z" on the TV: what's new, then the update. */
    private fun askUpdate() {
        val r = updates.available ?: return
        android.app.AlertDialog.Builder(this).setTitle("Update Tab Cast TV to ${r.name}?")
            .setMessage((if (r.whatsNew.isNotBlank()) "What's new:\n${r.whatsNew}\n\n" else "") +
                "Now: ${BuildConfig.VERSION_NAME}. Tab Cast TV closes while it updates: open it again from the home screen.")
            .setPositiveButton("Update") { _, _ -> startUpdate(fromTv = true) }
            .setNegativeButton("Later", null).show()
    }

    /**
     * Starts the update. The first time, Android needs "Install unknown apps" for Tab Cast TV: the TV opens that page
     * (or, on a TV without it, says the adb line); from the tablet or web remote the question shows on the TV.
     */
    private fun startUpdate(fromTv: Boolean) {
        if (updates.available == null) { updates.check(force = true); return }
        if (updates.needsSetup) {
            val noPage = "This TV has no \"Install unknown apps\" page. Allow it once by USB:\n" +
                "adb shell appops set $packageName REQUEST_INSTALL_PACKAGES allow\nor choose Update anyway: Android may ask for an OK."
            android.app.AlertDialog.Builder(this).setTitle("One-time setup")
                .setMessage("To update itself, Tab Cast TV needs Android's \"Install unknown apps\" permission. On the next " +
                    "screen, turn it on for Tab Cast TV, press Back, then choose Update again.")
                .setPositiveButton("Open the setting") { _, _ ->
                    try { startActivity(updates.setupIntent()) } catch (e: Exception) {
                        AppLog.i("update: no unknown-apps settings page ($e)")
                        android.app.AlertDialog.Builder(this).setTitle("One-time setup").setMessage(noPage)
                            .setPositiveButton("Update anyway") { _, _ -> beginInstall() }.setNegativeButton("Later", null).show()
                    }
                }
                .setNeutralButton("Update anyway") { _, _ -> beginInstall() }.setNegativeButton("Later", null).show()
            if (!fromTv) note("Update: answer the question on the TV")
            return
        }
        beginInstall()
    }

    private fun beginInstall() {
        // The process ends when Android replaces the app: keep the film's place first.
        if (film != null) saveFilmPosition()
        updates.install()
    }

    private fun tvButton(label: String, onClick: () -> Unit) = android.widget.Button(this).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12) }
    }

    /** The web remote's view of the TV (HTTP threads): cached state, and actions posted to the main thread. */
    private val control = object : WebRemote.Control {
        override fun tvName() = this@TvActivity.tvName()
        override fun state() = stateCache
        override fun caught() = caughtCache
        override fun preview(): ByteArray? {
            val first = !webWantsPreview()
            webPreviewAt = SystemClock.elapsedRealtime()
            if (first) main.post { if (previewFps <= 0) startPreview(3, 640) }
            return latestPreview
        }
        override fun open(url: String, from: String) = main.post { AppLog.i("web remote: open from $from"); this@TvActivity.open(url) }.let {}
        override fun cmd(c: String, v: String?) = main.post { command(c, v) }.let {}
        override fun tap(x: Float, y: Float, long: Boolean) = main.post { this@TvActivity.tap(x, y, long) }.let {}
        override fun scroll(dx: Float, dy: Float) = main.post { this@TvActivity.scroll(dx, dy) }.let {}
        override fun text(t: String, enter: Boolean) = main.post { type(t, enter) }.let {}
        override fun startJson() = start.json().toString()
        override fun addSite(title: String?, url: String?) {
            val u = url?.takeIf { Urls.isHttp(it) } ?: stateCache[Status.URL]?.takeIf { Urls.isHttp(it) } ?: return
            start.addSite(title ?: (if (u == stateCache[Status.URL]) stateCache[Status.TITLE] else null) ?: "", u)
        }
        override fun removeSite(url: String) = start.removeSite(url)
    }

    // -------------------------------------------------------------------------------------------- small helpers

    private val hideNote = Runnable { note.visibility = View.GONE }

    private fun note(s: String, ms: Long = 4000) {
        note.text = s
        note.visibility = View.VISIBLE
        main.removeCallbacks(hideNote)
        main.postDelayed(hideNote, ms)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private fun text(s: String, sp: Float, color: Int) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color); gravity = Gravity.CENTER
    }

    private fun exitCustomView() {
        customView?.let { fullscreen.removeView(it) }
        customView = null
        fullscreen.visibility = View.GONE
        customCb?.onCustomViewHidden()
        customCb = null
    }

    /** 15 s after a page loaded: no video found but requests were blocked → the remote suggests a lower level. */
    private val suggestCheck = Runnable {
        if (mode == "page" && playableCandidates().isEmpty() && catcher.blockedCount > 0 && catcher.adLevel != Level.OFF && !adSuggest) {
            adSuggest = true
            AppLog.i("no video found 15 s after load with ${catcher.adSummary()}: suggesting a lower ad-blocking level")
            sendStatus()
        }
    }

    /** A page jump the user allowed from the tablet ("open anyway"). */
    private var allowOnce: String? = null

    /** Same site = same last two host labels (a lenient guess, so few real navigations are stopped). */
    private fun site(url: String) = Urls.host(url).split('.').takeLast(2).joinToString(".")

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = catcher.intercept(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!Urls.isHttp(url)) { AppLog.i("blocked a non-web link (${request.url.scheme})"); return true }
            if (url == allowOnce) { allowOnce = null; return false }
            if (request.isForMainFrame && catcher.blocksNavigation(url)) {
                blocked = url
                AppLog.i("blocked an ad page (filter lists): ${url.take(160)}")
                note("Blocked an ad page: ${Urls.host(url)}")
                sendStatus()
                return true
            }
            val cur = view.url
            // Pop-unders and ad redirects move the page to another site without a click: stop those (logged; the
            // tablet can still open it).
            if (request.isForMainFrame && !request.hasGesture() && !request.isRedirect && cur != null && Urls.isHttp(cur) &&
                site(cur) != site(url)) {
                blocked = url
                AppLog.i("blocked a jump without a click: ${Urls.host(cur)} → ${url.take(160)}")
                note("Blocked a jump to ${Urls.host(url)}")
                sendStatus()
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            catcher.newPage(url)
            pageStartedAt = SystemClock.elapsedRealtime()
            adSuggest = false
            main.removeCallbacks(suggestCheck)
            loading = true
            if (mode == "inpage") mode = "page"
            pageVideo = null
            sendStatus()
        }

        override fun onPageFinished(view: WebView, url: String) {
            openedUrl?.let { o ->
                if (Urls.isHttp(url)) { if (o != url) start.forget(o); start.opened(url, view.title) }
                openedUrl = null
            }
            if (loading && Urls.isHttp(url)) {
                AppLog.i("page loaded in ${(SystemClock.elapsedRealtime() - pageStartedAt) / 100 / 10.0} s: ${catcher.adSummary()}")
                main.removeCallbacks(suggestCheck)
                main.postDelayed(suggestCheck, 15_000)
            }
            loading = false
            if (appVolume < 1.0) catcher.command("volume:$appVolume")
            sendStatus()
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            AppLog.i("page renderer gone (${if (detail.didCrash()) "crashed" else "stopped by Android, low memory"}); restarting")
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            try { view.destroy() } catch (_: Throwable) {}
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            AppLog.i("pop-up window refused")
            note("Pop-up blocked")
            return false
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            exitCustomView()
            customView = view
            customCb = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            fullscreen.visibility = View.VISIBLE
        }

        override fun onHideCustomView() = exitCustomView()
    }
}
