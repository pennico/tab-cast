// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tvlink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.BuildConfig
import dk.pennico.tabcast.link.LinkClient
import dk.pennico.tabcast.link.Peer
import dk.pennico.tabcast.link.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The tablet's connection to Tab Cast TV (the browser app on the Chromecast). One link at a time,
 * kept open with a ping every 2 s while the app runs; the TV sends its STATUS every second and, while the remote shows
 * it, a preview picture of its page.
 *
 * 0.3.3: while a page is open on the TV ([session]) the link is kept up: when it drops it is
 * reconnected (backoff 1 s … 15 s), and playback commands ([command]) are never a silent no-op: they carry an id,
 * the TV answers ACK; one sent on a dead link or not acknowledged within 4 s waits in [pending], the link is
 * reconnected and it is sent again (up to 30 s old). Commands sent this way are idempotent (play, pause, absolute
 * seek and volume), so sending one twice does no harm.
 */
object TvLink {
    data class State(
        val connected: Boolean = false,
        val connecting: Boolean = false,
        val host: String? = null,
        val tvName: String? = null,
        /** The TV's screen size, "1920x1080" (the preview's aspect ratio). */
        val screen: String? = null,
        val status: Map<String, String> = emptyMap(),
        /** Videos on the TV page, best first. */
        val caught: List<String> = emptyList(),
        val error: String? = null,
        /** The link dropped during a session and is being reconnected. */
        val reconnecting: Boolean = false,
        /** Commands waiting for the link to come back. */
        val pending: Int = 0,
    ) {
        val mode get() = status[Wire.Status.MODE] ?: "idle"
        val playing get() = status[Wire.Status.PLAYING] == "true"
        val pos get() = status[Wire.Status.POS]?.toDoubleOrNull()
        val dur get() = status[Wire.Status.DUR]?.toDoubleOrNull()
        val volume get() = status[Wire.Status.VOLUME]?.toDoubleOrNull()
        val title get() = status[Wire.Status.TITLE]
        /** 0.4.0: a film from the tablet plays on the TV. */
        val film get() = status[Wire.Status.KIND] == "file"
        val complete get() = status[Wire.Status.COMPLETE] == "true"
        val waiting get() = status[Wire.Status.WAITING] == "true"
        val heldTo get() = status[Wire.Status.HELD_TO]?.toDoubleOrNull()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview

    /** Tab Cast TV's port (tests point it at a fake TV). */
    @androidx.annotation.VisibleForTesting var port = Wire.PORT

    @Volatile private var peer: Peer? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pinger: Job? = null

    val connected: Boolean get() = peer?.closed == false

    /** [connect]'s answer when the TV wants this tablet paired first (0.3.0): ask for the PIN, then [pair]. */
    const val NEEDS_PAIRING = "needs pairing"
    @Volatile private var pairResult: CompletableDeferred<Map<String, String>>? = null
    @Volatile private var paired = false

    /**
     * Connects to Tab Cast TV at [host] unless already connected there. Null when connected and paired,
     * [NEEDS_PAIRING] when the TV shows a PIN for this tablet, else why not.
     */
    suspend fun connect(host: String, tvName: String): String? = withContext(Dispatchers.IO) {
        val cur = peer
        if (cur != null && !cur.closed && _state.value.host == host) return@withContext if (paired) null else NEEDS_PAIRING
        cur?.close()
        _state.update { it.copy(connecting = true, host = host, tvName = tvName, error = null) }
        try {
            val p = LinkClient.connect(host, port, mapOf("name" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "app" to "Tab Cast ${BuildConfig.VERSION_NAME}", "token" to tokenStore?.invoke()))
            paired = p.hello["paired"] != "false" // a 0.2 TV doesn't say: it needs no pairing
            p.onMessage = { m -> onMessage(m) }
            p.onClosed = { e ->
                if (peer === p) {
                    peer = null
                    _preview.value = null
                    _state.update { it.copy(connected = false, connecting = false, error = e?.message?.let { m -> "connection lost: $m" }) }
                    AppLog.i("TV link closed${e?.message?.let { ": $it" } ?: ""}${if (session) "; session on, reconnecting" else ""}")
                    if (session) reconnectSoon()
                }
            }
            peer = p
            acks = p.hello["acks"] == "1"
            _state.update { it.copy(connected = true, connecting = false, reconnecting = false, tvName = p.hello["name"] ?: tvName, screen = p.hello["screen"]) }
            p.start()
            AppLog.i("TV link: ${p.hello["app"]} on ${p.hello["name"]} ($host)")
            pinger?.cancel()
            pinger = io.launch { while (isActive) { delay(2000); if (peer !== p || !p.ping()) break } }
            if (paired) { sendSites(); flushPending(); null } else { AppLog.i("TV link: Tab Cast TV wants this tablet paired (PIN on the TV)"); NEEDS_PAIRING }
        } catch (e: Exception) {
            _state.update { it.copy(connected = false, connecting = false, error = e.message ?: e.javaClass.simpleName) }
            AppLog.i("TV link: no Tab Cast TV at $host:$port (${e.message})")
            e.message ?: e.javaClass.simpleName
        }
    }

    /** The tablet's start-page sites, sent to the TV on every (paired) connection for the web remote (0.3.2). */
    @Volatile var sitesProvider: (() -> List<Pair<String, String>>)? = null

    private fun sendSites() {
        val list = sitesProvider?.invoke() ?: return
        peer?.send(Wire.SITES, list.joinToString("\n") { (t, u) -> t.replace('\t', ' ').replace('\n', ' ') + "\t" + u }.toByteArray(Charsets.UTF_8))
    }

    /** Where the pairing token is kept (TabCastApp sets it: Prefs.tvToken). */
    @Volatile var tokenStore: (() -> String?)? = null
    @Volatile var tokenSave: ((String) -> Unit)? = null

    sealed class PairOutcome {
        object Ok : PairOutcome()
        class Wrong(val triesLeft: Int?) : PairOutcome()
        class Locked(val seconds: Int) : PairOutcome()
        class Failed(val why: String) : PairOutcome()
    }

    /** Sends the PIN shown on the TV; on success keeps the token and the link carries on paired. */
    suspend fun pair(pin: String): PairOutcome {
        val p = peer ?: return PairOutcome.Failed("not connected to the TV")
        val d = CompletableDeferred<Map<String, String>>()
        pairResult = d
        p.sendKv(Wire.PAIR, "pin" to pin.filter { it.isDigit() }, "name" to "${Build.MANUFACTURER} ${Build.MODEL}")
        val r = withTimeoutOrNull(10_000) { d.await() } ?: return PairOutcome.Failed("the TV didn't answer")
        return when {
            r["ok"] == "true" -> {
                r["token"]?.let { t -> tokenSave?.invoke(t) }
                paired = true
                AppLog.i("TV link: paired with ${_state.value.tvName}")
                sendSites()
                flushPending()
                PairOutcome.Ok
            }
            r["wait"] != null -> PairOutcome.Locked(r["wait"]!!.toIntOrNull() ?: 60)
            r["tries"] != null -> PairOutcome.Wrong(r["tries"]!!.toIntOrNull())
            else -> PairOutcome.Failed(r["error"] ?: "not paired")
        }
    }

    private fun onMessage(m: Wire.Msg) {
        when (m.type) {
            Wire.PAIR_RESULT -> pairResult?.complete(m.kv())
            Wire.ACK -> m.kv()["id"]?.toLongOrNull()?.let { acked(it) }
            Wire.STATUS -> _state.update { it.copy(status = m.kv()) }
            Wire.CAUGHT -> _state.update { it.copy(caught = String(m.payload, Charsets.UTF_8).split('\n').filter { l -> l.isNotBlank() }) }
            Wire.PREVIEW -> BitmapFactory.decodeByteArray(m.payload, 0, m.payload.size)?.let { _preview.value = it }
            Wire.KEYFRAME -> onKeyframeWanted?.invoke()
            Wire.REPORT -> onReport?.invoke(String(m.payload, Charsets.UTF_8))
            Wire.STATS -> AppLog.i("mirror: TV says ${m.kv().entries.joinToString(", ") { "${it.key} ${it.value}" }}")
            else -> {}
        }
    }

    private fun send(type: Int, vararg kv: Pair<String, Any?>): Boolean = peer?.sendKv(type, *kv) ?: false

    /** Opens [url] on the TV; [cookies] = the tablet's cookies for it ("Open on TV with my login"), or null. */
    fun open(url: String, cookies: String?) = send(Wire.OPEN, "url" to url, "cookies" to cookies)
    fun cmd(c: String, v: Any? = null) = send(Wire.CMD, "c" to c, "v" to v)
    /** 0.4.0: Tab Cast TV can play files from the tablet (its HELLO_ACK says files=1). */
    val filesSupported: Boolean get() = peer?.takeIf { !it.closed }?.hello?.get("files") == "1"
    /** Free space on the TV's disk at connect time (bytes), if it says. */
    val tvFree: Long? get() = peer?.hello?.get("free")?.toLongOrNull()
    // ------------------------------------------------------------------------------- 0.5.0: mirror the screen

    /** Tab Cast TV can show the tablet's mirrored screen (HELLO_ACK mirror=1). */
    val mirrorSupported: Boolean get() = peer?.takeIf { !it.closed }?.hello?.get("mirror") == "1"
    /** The TV asks for a key frame (it started, or lost a frame). */
    @Volatile var onKeyframeWanted: (() -> Unit)? = null

    /** Starts the TV's mirror view: the picture's size and the sound's format. */
    fun mirrorStart(w: Int, h: Int, rate: Int, channels: Int): Boolean {
        val p = peer ?: return false
        return p.sendKv(Wire.VIDEO_CONFIG, "codec" to "vp8", "w" to w, "h" to h) &&
            p.sendKv(Wire.AUDIO_CONFIG, "codec" to "pcm_s16le", "rate" to rate, "channels" to channels)
    }

    /**
     * One encoded frame. Frames waiting on a slow Wi-Fi are dropped (newest kept, at most 6 queued): false then, and
     * the sender must restart from a key frame.
     */
    fun mirrorVideo(data: ByteArray, key: Boolean, ptsUs: Long): Boolean =
        peer?.sendLatest(Wire.VIDEO, data, if (key) Wire.FLAG_KEY else 0, ptsUs, keep = 6) ?: false

    /** 20 ms of sound (PCM); on a slow link the oldest waiting ones go (at most ~0.5 s queued). */
    fun mirrorAudio(data: ByteArray, ptsUs: Long): Boolean = peer?.sendLatest(Wire.AUDIO, data, 0, ptsUs, keep = 25) ?: false

    /** Video frames waiting to be sent (the Wi-Fi is behind when this grows). */
    val mirrorBacklog: Int get() = peer?.pending(Wire.VIDEO) ?: 0

    /** 0.5.0: the TV's answer to CMD report (its state and log). */
    @Volatile var onReport: ((String) -> Unit)? = null

    /** Ends the TV's mirror view; [why] (0.5.2, optional: an older TV ignores it) is shown there in plain words. */
    fun mirrorStop(why: String? = null) = send(Wire.STATE, "mirror" to "off", "why" to why)

    /** 0.4.0: play a file the tablet serves (see FileCast). */
    fun file(url: String, name: String, size: Long, id: String, mime: String?, subs: String?, subsMime: String?, rateMbit: Int) =
        send(Wire.FILE, "url" to url, "name" to name, "size" to size, "id" to id, "mime" to mime, "subs" to subs,
            "subsmime" to subsMime, "rate" to rateMbit)
    fun tap(x: Float, y: Float, long: Boolean) = send(Wire.TAP, "x" to x, "y" to y, "long" to long)
    fun scroll(dx: Float, dy: Float) = send(Wire.SCROLL, "dx" to dx, "dy" to dy)
    fun text(t: String, enter: Boolean) = send(Wire.TEXT, "text" to t, "enter" to enter)
    fun previewOn(fps: Int = 3, width: Int = 640) = send(Wire.PREVIEW_ON, "fps" to fps, "w" to width)
    fun previewOff() = send(Wire.PREVIEW_OFF)

    fun disconnect() {
        endSession("disconnect")
        peer?.let { it.send(Wire.BYE); io.launch { delay(300); it.close() } }
        peer = null
        _preview.value = null
        _state.update { State(host = it.host, tvName = it.tvName) }
    }

    // ------------------------------------------------------------------------- session: keep-alive and commands

    /** A page is open on the TV: keep the link up (CastService shows the media controls meanwhile). */
    @Volatile var session = false
        private set
    @Volatile private var acks = false
    private var reconnector: Job? = null
    private val nextId = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() % 1_000_000 * 1000)

    private class Pending(val id: Long, val c: String, val v: Any?, val at: Long, var sentAt: Long = 0)
    private val pending = java.util.LinkedHashMap<Long, Pending>()

    fun startSession(why: String) {
        if (!session) AppLog.i("TV session start ($why)")
        session = true
    }

    fun endSession(why: String) {
        if (session) AppLog.i("TV session end ($why)")
        session = false
        reconnector?.cancel(); reconnector = null
        synchronized(pending) { pending.clear() }
        _state.update { it.copy(reconnecting = false, pending = 0) }
    }

    /** The link is down during a session: reconnect now (if not already trying). */
    private fun reconnectSoon() {
        if (reconnector?.isActive == true) return
        val host = _state.value.host ?: return
        val name = _state.value.tvName ?: "TV"
        _state.update { it.copy(reconnecting = true) }
        reconnector = io.launch {
            var wait = 1000L
            var tries = 0
            while (isActive && session && !connected) {
                tries++
                val r = connect(host, name)
                if (connected && r == null) { AppLog.i("TV link: reconnected after $tries tries"); break }
                if (r == NEEDS_PAIRING) {
                    AppLog.i("TV link: reconnect refused, the TV wants pairing again; session ended")
                    _state.update { it.copy(error = "Pair the tablet again (Open on TV)") }
                    endSession("needs pairing"); break
                }
                if (tries == 1 || tries % 10 == 0) AppLog.i("TV link: reconnect try $tries failed ($r); next in ${wait / 1000} s")
                _state.update { it.copy(reconnecting = true) }
                delay(wait)
                wait = (wait * 2).coerceAtMost(15_000)
            }
            _state.update { it.copy(reconnecting = false) }
        }
    }

    /**
     * A playback command that must arrive (media notification, lock screen, remote buttons): sent with an id; if the
     * link is down or the TV doesn't acknowledge it in 4 s, it waits, the link is reconnected, and it is sent again.
     * Use idempotent commands (PLAY/PAUSE, SEEK, VOLUME). Returns at once.
     */
    fun command(c: String, v: Any? = null) {
        val now = System.currentTimeMillis()
        val p = Pending(nextId.incrementAndGet(), c, v, now)
        synchronized(pending) {
            // A newer command of the same kind replaces a waiting one (a second seek, play after pause...).
            val same = if (c == Wire.Cmd.PLAY || c == Wire.Cmd.PAUSE) setOf(Wire.Cmd.PLAY, Wire.Cmd.PAUSE) else setOf(c)
            pending.values.removeAll { it.c in same }
            pending[p.id] = p
        }
        _state.update { it.copy(pending = pendingCount()) }
        if (!sendPending(p)) {
            AppLog.i("TV command $c${v?.let { "=$it" } ?: ""} #${p.id}: link down, waiting for reconnect")
            if (_state.value.host != null) { if (!session) startSession("command"); reconnectSoon() }
        }
    }

    private fun pendingCount() = synchronized(pending) { pending.size }

    private fun sendPending(p: Pending): Boolean {
        val peer = peer ?: return false
        if (peer.closed || !paired) return false
        if (!peer.sendKv(Wire.CMD, "c" to p.c, "v" to p.v, "id" to p.id)) return false
        p.sentAt = System.currentTimeMillis()
        AppLog.i("TV command ${p.c}${p.v?.let { "=$it" } ?: ""} #${p.id} sent${if (p.sentAt - p.at > 500) " (${(p.sentAt - p.at) / 1000.0} s after it was pressed)" else ""}")
        if (!acks) { // a 0.3.2 TV doesn't acknowledge: sent is the best we know
            synchronized(pending) { pending.remove(p.id) }
            _state.update { it.copy(pending = pendingCount()) }
            return true
        }
        io.launch {
            delay(ACK_MS)
            val still = synchronized(pending) { pending[p.id] === p }
            if (still && this@TvLink.peer === peer) {
                AppLog.i("TV command ${p.c} #${p.id}: no answer in ${ACK_MS / 1000} s; the link looks dead, reconnecting")
                peer.close(java.io.IOException("no answer to a command"))
            }
        }
        return true
    }

    private fun acked(id: Long) {
        val p = synchronized(pending) { pending.remove(id) } ?: return
        AppLog.i("TV command ${p.c} #$id done (${System.currentTimeMillis() - p.sentAt} ms)")
        _state.update { it.copy(pending = pendingCount()) }
    }

    /** After (re)connecting: send what waited, unless it is too old to still mean anything. */
    private fun flushPending() {
        val now = System.currentTimeMillis()
        val list = synchronized(pending) {
            val old = pending.values.filter { now - it.at > MAX_AGE_MS }
            old.forEach { pending.remove(it.id); AppLog.i("TV command ${it.c} #${it.id} dropped: ${(now - it.at) / 1000} s old") }
            pending.values.toList()
        }
        _state.update { it.copy(pending = pendingCount()) }
        list.forEach { sendPending(it) }
    }

    private const val ACK_MS = 4000L
    private const val MAX_AGE_MS = 30_000L
}
