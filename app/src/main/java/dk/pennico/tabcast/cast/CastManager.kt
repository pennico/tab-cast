// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.cast

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.BuildConfig
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.core.Classify
import dk.pennico.tabcast.core.HlsFilter
import dk.pennico.tabcast.core.Http
import dk.pennico.tabcast.core.Kind
import dk.pennico.tabcast.core.RelayServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.fcast.sender_sdk.ApplicationInfo
import org.fcast.sender_sdk.CastContext
import org.fcast.sender_sdk.CastingDevice
import org.fcast.sender_sdk.DeviceConnectionState
import org.fcast.sender_sdk.DeviceEventHandler
import org.fcast.sender_sdk.DeviceFeature
import org.fcast.sender_sdk.DeviceInfo
import org.fcast.sender_sdk.KeyEvent
import org.fcast.sender_sdk.LoadRequest
import org.fcast.sender_sdk.MediaEvent
import org.fcast.sender_sdk.Metadata
import org.fcast.sender_sdk.PlaybackState
import org.fcast.sender_sdk.ProtocolType
import org.fcast.sender_sdk.Source
import org.fcast.sender_sdk.tryIpAddrFromStr
import org.fcast.sender_sdk.urlFormatIpAddr
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/** What to play on the TV. [headers] are the page's request headers (Referer, Origin, UA...), without cookies. */
data class CastMedia(
    val url: String,
    val mime: String,
    val headers: Map<String, String>,
    val title: String?,
    val thumb: String?,
    val pageUrl: String?,
    val live: Boolean = false,
    val forceRelay: Boolean? = null,
)

enum class Conn { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }
enum class Play { IDLE, BUFFERING, PLAYING, PAUSED }

data class CastState(
    val device: Device? = null,
    val conn: Conn = Conn.IDLE,
    val playback: Play = Play.IDLE,
    val time: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Double = 1.0,
    val speed: Double = 1.0,
    val title: String? = null,
    val thumb: String? = null,
    val media: CastMedia? = null,
    val relayed: Boolean = false,
    val error: String? = null,
    val active: Boolean = false,
    val canVolume: Boolean = true,
    /** What the quality limits did, e.g. "kept 9 of 17 variants (≤1080p, ≤60 fps, H.264), best 1080p". */
    val quality: String? = null,
    /** A passing state for the user, e.g. "reconnecting…" (0.3.3). */
    val note: String? = null,
)

/**
 * One TV session at a time, on top of FUTO's FCast sender SDK (FCast protocol + Google Cast Default Media Receiver).
 *
 * How a stream reaches the TV:
 *  - FCast (default): the TV gets the stream URL plus the page's headers and cookies (FCast PlayMessage.headers);
 *    its ExoPlayer sends them with every request, so Referer/cookie-protected streams work without the tablet.
 *  - Chromecast: the Default Media Receiver can't take headers and needs CORS for HLS/DASH, so by default the stream
 *    goes through the tablet's relay (RelayServer), which adds both.
 * All SDK calls run on one thread.
 */
object CastManager {
    const val RELAY_PORT = 8642

    private lateinit var app: Context
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "cast").apply { isDaemon = true } }
    private val dispatcher = exec.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state

    val relay = RelayServer { AppLog.i(it) }
    @Volatile private var relaySession: RelayServer.Session? = null
    /** Bumped by every cast and by Stop: a cast still on its way checks it before telling the TV to play. */
    private val castGen = java.util.concurrent.atomic.AtomicInteger()
    /** Stop and disconnect run here, never on the cast thread, which may be stuck in a slow TV call. */
    private val stopExec = Executors.newCachedThreadPool { r -> Thread(r, "tv-stop").apply { isDaemon = true } }

    private var castCtx: CastContext? = null
    @Volatile private var device: CastingDevice? = null
    @Volatile private var deviceKey: String? = null
    private var waiter: CompletableDeferred<String>? = null
    @Volatile private var localIp: String? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun cookieFor(url: String): String? = try { CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }

    private fun withBrowserHeaders(h: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap(h)
        // Cloudflare-fronted CDNs refuse requests that don't look like a browser (see CatchSession.replayHeaders).
        if (out.keys.none { it.equals("Accept-Language", true) }) {
            val l = Locale.getDefault()
            out["Accept-Language"] = "${l.toLanguageTag()},${l.language};q=0.9,en;q=0.8"
        }
        if (out.keys.none { it.equals("User-Agent", true) }) TabCastApp.webUserAgent?.let { out["User-Agent"] = it }
        return out
    }

    /** Cast [m] to [d]. Returns null on success or a message for the user. */
    suspend fun cast(d: Device, m: CastMedia): String? {
        val gen = castGen.incrementAndGet()
        return withContext(dispatcher) { castImpl(d, m, gen) }
    }

    private suspend fun castImpl(d: Device, m: CastMedia, gen: Int): String? {
        return try {
            CastService.start(app)
            _state.update {
                it.copy(device = d, media = m, title = m.title, thumb = m.thumb, error = null, active = true,
                    playback = Play.BUFFERING, time = 0.0, duration = 0.0, quality = null)
            }
            var target = d
            val ip = try { connect(d) } catch (e: IOException) {
                AppLog.i("connect failed (${e.message}); scanning for the TV on port ${d.port}")
                val found = Net.scan(app, listOf(d.protocol)).filter { it.port == d.port }
                val moved = found.singleOrNull() ?: found.firstOrNull { it.name == d.name } ?: throw e
                if (moved.host == d.host) throw e
                target = d.copy(host = moved.host)
                TabCastApp.devices.replace(d, target)
                if (TabCastApp.prefs.defaultDevice == d.key) TabCastApp.prefs.defaultDevice = target.key
                AppLog.i("TV moved to ${target.host}; retrying")
                connect(target)
            }
            val prefs = TabCastApp.prefs
            val useRelay = m.forceRelay ?: if (target.protocol == Protocol.CHROMECAST) prefs.relayChromecast else prefs.relayFcast
            relaySession?.let { relay.close(it.id) }
            relaySession = null
            val hdr = withBrowserHeaders(m.headers)
            var url = m.url
            var tvHeaders: Map<String, String>? = null
            // HLS: leave out variants the TV can't decode (see HlsFilter). Checked here on the tablet first, so the
            // TV only depends on the tablet when something actually has to be left out.
            val limits = prefs.hlsLimits()
            val hls = Classify.fromMime(m.mime) == Kind.HLS || Classify.fromUrl(m.url) == Kind.HLS
            val check = if (hls) checkMaster(m.url, hdr, limits) else null
            check?.let { AppLog.i("HLS master: ${it.note}") }
            _state.update { it.copy(quality = check?.takeIf { c -> c.kept + c.dropped > 0 }?.note) }
            val filter: ((String) -> String)? = if (hls) { text -> HlsFilter.filter(text, limits).text } else null
            var masterViaTablet = false
            if (useRelay) {
                val port = relay.start(RELAY_PORT)
                val s = relay.open(hdr, filter) { u -> cookieFor(u) }
                relaySession = s
                url = relay.url("$ip:$port", s, m.url)
            } else if (target.protocol == Protocol.FCAST) {
                val ck = cookieFor(m.url)
                tvHeaders = if (ck.isNullOrBlank()) hdr else hdr + ("Cookie" to ck)
                if (check?.changed == true) {
                    // Only the master playlist goes through the tablet; the TV fetches everything else itself.
                    val port = relay.start(RELAY_PORT)
                    val s = relay.open(hdr, filter, onlyManifest = true) { u -> cookieFor(u) }
                    relaySession = s
                    url = relay.url("$ip:$port", s, m.url)
                    masterViaTablet = true
                }
            }
            AppLog.i("cast to ${target.label} (${target.host}): ${m.mime}${if (useRelay) " via relay" else if (masterViaTablet) " (playlist via tablet)" else ""} ${m.url.take(300)}")
            val dev = device ?: throw IOException("not connected")
            if (gen != castGen.get()) { AppLog.i("cast of ${m.title} dropped: stopped or replaced meanwhile"); return "Stopped" }
            val meta = Metadata(m.title ?: "Tab Cast", m.thumb)
            dev.load(LoadRequest.Url(m.mime, url, 0.0, null, null, meta, tvHeaders))
            TabCastApp.prefs.castingOn = target.key
            _state.update { it.copy(device = target, relayed = useRelay, canVolume = dev.supportsFeature(DeviceFeature.SET_VOLUME)) }
            if (masterViaTablet) watchMasterFetch(relaySession!!, m, meta, tvHeaders)
            keepAlive(gen)
            null
        } catch (e: Exception) {
            AppLog.e("cast failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            _state.update { it.copy(error = msg, playback = Play.IDLE) }
            msg
        }
    }

    /** Fetches [url] on the tablet; if it is an HLS master, what [limits] would leave out. Null if it can't tell. */
    private suspend fun checkMaster(url: String, hdr: Map<String, String>, limits: HlsFilter.Limits): HlsFilter.Result? =
        withContext(Dispatchers.IO) {
            try {
                val f = Http.open(url, hdr, { u -> cookieFor(u) }, connectTimeout = 6000, readTimeout = 8000)
                try {
                    if (f.code !in 200..299) return@withContext null
                    val text = f.conn.inputStream.use { String(Http.readUpTo(it, 4 shl 20), Charsets.UTF_8) }
                    if (!HlsFilter.isMaster(text)) null else HlsFilter.filter(text, limits)
                } finally { f.conn.disconnect() }
            } catch (e: Exception) {
                AppLog.i("HLS master check failed (${e.message}); sending it unchanged")
                null
            }
        }

    /**
     * The filtered master is served by the tablet. If the TV hasn't asked for it after 12 s (it can't reach the
     * tablet), send the original playlist instead: full quality range, but it plays.
     */
    private fun watchMasterFetch(s: RelayServer.Session, m: CastMedia, meta: Metadata, tvHeaders: Map<String, String>?) {
        scope.launch {
            delay(12_000)
            if (relaySession !== s || _state.value.media !== m || s.hits.get() > 0) return@launch
            AppLog.i("the TV didn't fetch the playlist from the tablet in 12 s; sending the original playlist")
            try {
                device?.load(LoadRequest.Url(m.mime, m.url, 0.0, null, null, meta, tvHeaders))
                _state.update { it.copy(quality = "the TV can't reach the tablet: all qualities sent") }
            } catch (e: Exception) { AppLog.e("fallback load failed", e) }
        }
    }

    private suspend fun connect(d: Device): String {
        val ip0 = localIp
        if (device != null && deviceKey == d.key && _state.value.conn == Conn.CONNECTED && ip0 != null) return ip0
        dropDevice()
        val ctx = castCtx ?: CastContext().also { castCtx = it }
        val proto = if (d.protocol == Protocol.FCAST) ProtocolType.F_CAST else ProtocolType.CHROMECAST
        val info = DeviceInfo(d.name, proto, listOf(tryIpAddrFromStr(d.host)), d.port.toUShort())
        val dev = ctx.createDeviceFromInfo(info)
        val w = CompletableDeferred<String>()
        waiter = w
        device = dev
        deviceKey = d.key
        _state.update { it.copy(conn = Conn.CONNECTING, device = d) }
        val g = ++connGen
        dev.connect(ApplicationInfo("Tab Cast", BuildConfig.VERSION_NAME, Build.MODEL), Handler(d.key, g), 1000uL)
        val ip = withTimeoutOrNull(9000) { w.await() }
        if (ip == null) {
            dropDevice()
            _state.update { it.copy(conn = Conn.FAILED) }
            throw IOException("Can't reach ${d.protocol.label} at ${d.host}:${d.port}. Is the TV on and the receiver app installed?")
        }
        return ip
    }

    private fun dropDevice() {
        val d = device ?: return
        device = null
        deviceKey = null
        localIp = null
        try { d.disconnect() } catch (_: Exception) {}
        try { d.destroy() } catch (_: Exception) {}
    }

    /**
     * 0.3.3: a command is never a silent no-op. If the connection to the TV is gone (or still
     * reconnecting after a few seconds) while a cast is on, it reconnects first, then sends; every command is logged
     * with how long the TV took to take it.
     */
    private fun cmd(name: String, block: (CastingDevice) -> Unit) = scope.launch {
        val t0 = System.currentTimeMillis()
        val d = ensureConnected(name) ?: return@launch
        try {
            block(d)
            AppLog.i("TV command $name sent (${System.currentTimeMillis() - t0} ms)")
        } catch (e: Exception) {
            AppLog.e("TV command $name failed; reconnecting and trying once more", e)
            val d2 = ensureConnected(name, force = true) ?: return@launch
            try { block(d2); AppLog.i("TV command $name sent after reconnecting") } catch (e2: Exception) {
                AppLog.e("TV command $name failed again", e2)
                _state.update { it.copy(error = "The TV didn't take $name: ${e2.message ?: e2.javaClass.simpleName}") }
            }
        }
    }

    /** On the cast thread: the device, connected (reconnecting if needed); null (and the reason shown) if it can't. */
    private suspend fun ensureConnected(why: String, force: Boolean = false): CastingDevice? {
        val s = _state.value
        val dev = device
        if (!force && dev != null && s.conn == Conn.CONNECTED) return dev
        val target = s.device
        if (!s.active || target == null) {
            AppLog.i("TV command $why: nothing is cast (${s.conn}); ignored")
            return dev
        }
        if (!force && dev != null && (s.conn == Conn.RECONNECTING || s.conn == Conn.CONNECTING)) {
            // The SDK is already reconnecting: give it a moment before starting over.
            val ok = withTimeoutOrNull(5000) { while (_state.value.conn != Conn.CONNECTED) delay(200); true }
            if (ok == true) return device
        }
        AppLog.i("TV command $why: connection is ${s.conn}${if (force) " (forced)" else ""}; reconnecting to ${target.label}")
        _state.update { it.copy(conn = Conn.RECONNECTING, note = "reconnecting…") }
        return try {
            if (force) dropDevice()
            connect(target)
            _state.update { it.copy(note = null) }
            AppLog.i("reconnected to ${target.label}")
            device
        } catch (e: Exception) {
            AppLog.e("reconnect for $why failed", e)
            _state.update { it.copy(note = null, error = "Can't reach the TV: ${e.message ?: e.javaClass.simpleName}") }
            null
        }
    }

    /**
     * While cast [gen] is on: every 10 s, if the connection has been lost (not just reconnecting by itself), reconnect
     * now, so the next press of Pause on the lock screen doesn't have to.
     */
    private fun keepAlive(gen: Int) = scope.launch {
        var bad = 0
        while (gen == castGen.get() && _state.value.active) {
            delay(10_000)
            if (gen != castGen.get() || !_state.value.active) break
            val c = _state.value.conn
            bad = if (c == Conn.CONNECTED) 0 else bad + 1
            if (bad >= 2) {
                AppLog.i("keep-alive: connection $c for ${bad * 10} s during a cast; reconnecting")
                ensureConnected("keep-alive", force = c != Conn.RECONNECTING && c != Conn.CONNECTING || bad >= 4)
                bad = 0
            }
        }
    }

    fun togglePlay() = if (_state.value.playback == Play.PLAYING) pause() else play()
    fun play() = cmd("play") { it.resumePlayback() }
    fun pause() = cmd("pause") { it.pausePlayback() }
    fun seekTo(t: Double) = cmd("seek") { it.seek(t.coerceAtLeast(0.0)) }
    fun seekBy(delta: Double) {
        val s = _state.value
        val max = if (s.duration > 0) s.duration - 1 else Double.MAX_VALUE
        seekTo((s.time + delta).coerceIn(0.0, max))
    }
    fun setVolume(v: Double) {
        _state.update { it.copy(volume = v.coerceIn(0.0, 1.0)) }
        cmd("volume") { it.changeVolume(v.coerceIn(0.0, 1.0)) }
    }

    /**
     * Stop, from any thread, always: the app side ends at once (state, relay, the foreground service
     * and its notification); the TV gets Stop on its own thread, and if it hasn't taken it within 4 s the connection
     * is dropped so the next cast starts clean. Nothing here waits for the cast thread.
     */
    fun stop() {
        castGen.incrementAndGet()
        TabCastApp.prefs.castingOn = null
        relaySession?.let { relay.close(it.id) }
        relaySession = null
        _state.update { it.copy(playback = Play.IDLE, active = false, time = 0.0) }
        CastService.stop(app)
        val d = device ?: return
        AppLog.i("stop: telling the TV")
        withDeadline("Stop", d) { it.stopPlayback() }
    }

    fun disconnect() {
        castGen.incrementAndGet()
        TabCastApp.prefs.castingOn = null
        relaySession = null
        _state.update { CastState() }
        CastService.stop(app)
        val d = device ?: return
        device = null; deviceKey = null; localIp = null
        withDeadline("disconnect", d) { it.disconnect(); it.destroy() }
        stopExec.execute { relay.stop() }
    }

    /** Runs [block] on a stop thread; if it takes more than 4 s, forgets [d] (the next cast reconnects). */
    private fun withDeadline(what: String, d: CastingDevice, block: (CastingDevice) -> Unit) {
        val f = stopExec.submit { block(d) }
        stopExec.execute {
            try { f.get(4, java.util.concurrent.TimeUnit.SECONDS) }
            catch (e: java.util.concurrent.TimeoutException) {
                AppLog.i("the TV didn't take $what within 4 s; dropping the connection (the next cast reconnects)")
                if (device === d) { device = null; deviceKey = null; localIp = null; _state.update { it.copy(conn = Conn.IDLE) } }
                stopExec.execute { try { d.disconnect() } catch (_: Throwable) {} }
            } catch (e: Exception) { AppLog.e("$what failed", e) }
        }
    }

    /** Stop what a killed Tab Cast left playing on [d]: connect, Stop. Null on success, else a message. */
    suspend fun stopLeftover(d: Device): String? {
        val r = withTimeoutOrNull(15_000) {
            withContext(dispatcher) {
                try { connect(d); device?.stopPlayback(); "ok" } catch (e: Exception) { e.message ?: "failed" }
            }
        } ?: "The TV didn't answer within 15 s"
        AppLog.i("stop leftover on ${d.label}: $r")
        if (r != "ok") return r
        TabCastApp.prefs.castingOn = null
        return null
    }

    /** Retry the current media through the relay (offered when the TV reports a playback error). */
    suspend fun retryViaRelay(): String? {
        val s = _state.value
        val d = s.device ?: return "no TV"
        val m = s.media ?: return "nothing to retry"
        return cast(d, m.copy(forceRelay = true))
    }

    /** Bumped by every connect: events from a replaced connection (same TV, older device object) are ignored. */
    @Volatile private var connGen = 0

    private class Handler(val key: String, val gen: Int) : DeviceEventHandler {
        private fun mine() = key == deviceKey && gen == connGen
        override fun connectionStateChanged(state: DeviceConnectionState) {
            if (!mine()) return
            when (state) {
                is DeviceConnectionState.Connected -> {
                    val ip = urlFormatIpAddr(state.localAddr).trim('[', ']')
                    localIp = ip
                    _state.update { it.copy(conn = Conn.CONNECTED) }
                    AppLog.i("connected; tablet address $ip")
                    waiter?.complete(ip)
                }
                is DeviceConnectionState.Reconnecting -> { _state.update { it.copy(conn = Conn.RECONNECTING) }; AppLog.i("connection lost; the SDK is reconnecting") }
                is DeviceConnectionState.Connecting -> _state.update { it.copy(conn = Conn.CONNECTING) }
                else -> { _state.update { it.copy(conn = Conn.IDLE) }; AppLog.i("disconnected${if (_state.value.active) " during a cast" else ""}") }
            }
        }
        override fun volumeChanged(volume: Double) { if (mine()) _state.update { it.copy(volume = volume) } }
        override fun timeChanged(time: Double) { if (mine()) _state.update { it.copy(time = time) } }
        override fun durationChanged(duration: Double) { if (mine()) _state.update { it.copy(duration = duration) } }
        override fun speedChanged(speed: Double) { if (mine()) _state.update { it.copy(speed = speed) } }
        override fun playbackStateChanged(state: PlaybackState) {
            if (!mine()) return
            val p = when (state) {
                PlaybackState.PLAYING -> Play.PLAYING
                PlaybackState.PAUSED -> Play.PAUSED
                PlaybackState.BUFFERING -> Play.BUFFERING
                else -> Play.IDLE
            }
            _state.update { it.copy(playback = p, error = if (p == Play.PLAYING) null else it.error) }
        }
        override fun sourceChanged(source: Source) {}
        override fun keyEvent(event: KeyEvent) {}
        override fun mediaEvent(event: MediaEvent) {}
        override fun playbackError(message: String) {
            if (!mine()) return
            AppLog.e("TV playback error: $message")
            _state.update { it.copy(error = "The TV couldn't play it: $message") }
        }
    }
}
