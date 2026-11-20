// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.R
import dk.pennico.tabcast.tvlink.TvLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 0.5.0 "Mirror screen": whatever the tablet shows (and plays) on the TV, through Tab Cast TV.
 *
 * MediaProjection (whole screen) → VirtualDisplay → ImageReader (RGBA, fitted into 960x720: the tablet is 4:3) →
 * C/NEON conversion → VP8 (MirrorEncoder: Exynos hardware, else c2 software) → the link (Wire.VIDEO, newest kept on a
 * slow Wi-Fi) → Tab Cast TV decodes and shows it. Sound: AudioPlaybackCapture of what other apps play (48 kHz stereo
 * PCM, 20 ms per message) → the TV's AudioTrack with Tab Cast TV's own volume. Both carry capture timestamps; the
 * TV holds the picture back by its sound delay.
 *
 * Light on the CPU: the VirtualDisplay only makes a new image when the screen changes, at most 30 a second; a still
 * screen costs nothing. The screen is kept on while mirroring (a 1-pixel overlay with keep-screen-on, which also turns
 * the backlight down to its minimum: capture is unaffected), restored when it stops; without the overlay permission a
 * dim wake lock keeps it on. The power button (screen off) or Stop ends it cleanly. DRM video shows black: Android
 * leaves protected layers out of every capture.
 */
class MirrorService : Service() {
    data class State(val running: Boolean = false, val tv: String? = null, val fps: Double = 0.0, val kbps: Int = 0,
                     val sound: Boolean = false, val note: String? = null)

    companion object {
        private const val CH = "mirroring"
        private const val ID = 9
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val A_STOP = "dk.pennico.tabcast.MIRROR_STOP"
        /** The picture box: the tablet's 4:3 screen fits 960x720 exactly (probe 1: ImageReader at 39 fps). */
        const val W = 960
        const val H = 720
        const val BITRATE = 3_000_000

        /** 0.5.2: the encoder levels (see [MirrorRecovery]): the video chip first, then software at a lighter size. */
        private class Level(val label: String, val w: Int, val h: Int, val bitrate: Int, val fps: Int, val names: () -> List<String>)
        private val LEVELS = listOf(
            Level("hardware", W, H, BITRATE, 30, { MirrorEncoder.hardware() }),
            Level("software", 640, 480, 1_200_000, 15, { MirrorEncoder.software() }))
        const val BUSY = "The tablet's video chip is busy with another app"
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(Intent(ctx, MirrorService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data))
        }

        fun stop(ctx: Context) {
            if (_state.value.running) ctx.startService(Intent(ctx, MirrorService::class.java).setAction(A_STOP))
        }
    }

    /**
     * The pipeline's thread. 0.5.2: an exception escaping it (0.5.0: the codec's, from the once-a-second tick) used
     * to kill the whole app; now it ends mirroring with a log line instead.
     */
    private val thread = HandlerThread("mirror").apply {
        setUncaughtExceptionHandler { _, ex ->
            AppLog.e("mirror: the pipeline failed", ex)
            Handler(mainLooper).post { finish("an error in the pipeline (${ex.javaClass.simpleName})") }
        }
        start()
    }
    private val h = Handler(thread.looper)
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var enc: MirrorEncoder? = null
    private var audio: Thread? = null
    @Volatile private var stopping = false
    private var overlay: View? = null
    private var wake: PowerManager.WakeLock? = null
    private var startedAt = 0L
    private var lastQueuedNs = 0L
    private var lastImageAt = 0L
    @Volatile private var needKey = true
    /** The frames since the last key frame: sent again when the TV needs a key frame while the screen is still. */
    private val gop = ArrayList<Pair<ByteArray, Long>>()
    private var drops = 0
    private var sent = 0L
    private var sentBytes = 0L
    private var notTvMirror = 0
    /** The current picture box (the encoder's size) and the screen's place in it. */
    private var boxW = W
    private var boxH = H
    private var fitted = intArrayOf(W, H, 0, 0)
    private var frameGapNs = 30_000_000L
    private var bitrate = BITRATE
    private val recovery = MirrorRecovery(LEVELS.size)
    private var encoderSince = 0L
    private var framesBefore = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // On the pipeline's own thread, so a frame being encoded isn't cut off mid-way.
        if (intent?.action == A_STOP) { h.post { finish("Stop") }; return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Mirroring", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the tablet's screen is on the TV"; setShowBadge(false)
        })
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(ID, n)
            val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
            val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data ?: error("no consent"))
                ?: error("no screen capture")
            mp = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { h.post { finish("Android ended the screen capture") } }
            }, h)
            h.post { begin(p) }
        } catch (e: Exception) {
            AppLog.e("mirror: can't start", e)
            _state.value = State(note = "Can't mirror: ${e.message}")
            finish("start failed")
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------------------------------------ the pipeline

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    }

    /** The screen's shape inside the box (landscape fills it; portrait gets bars), even sizes for 4:2:0: w, h, x, y. */
    private fun fit(sw: Int, sh: Int): IntArray {
        val scale = minOf(boxW.toDouble() / sw, boxH.toDouble() / sh)
        val aw = (sw * scale).toInt() and 1.inv()
        val ah = (sh * scale).toInt() and 1.inv()
        return intArrayOf(aw, ah, ((boxW - aw) / 2) and 1.inv(), ((boxH - ah) / 2) and 1.inv())
    }

    private var shownSize = 0 to 0

    /**
     * 0.5.1 (probe 2 run 2): the tablet turned. Android 14 allows one VirtualDisplay per projection, so the same one
     * is resized and gets a new ImageReader of the new shape; the old reader closes after the switch.
     */
    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != android.view.Display.DEFAULT_DISPLAY || stopping) return
            val size = screenSize()
            if (size == shownSize) return
            if (recapture(size)) AppLog.i("mirror: the screen turned: ${size.first}x${size.second} → ${fitted[0]}x${fitted[1]} in ${boxW}x$boxH")
        }
    }

    /** The same VirtualDisplay, resized for [size] in the current box, onto a new ImageReader (Android 14: never a second VD). */
    private fun recapture(size: Pair<Int, Int>): Boolean {
        val v = vd ?: return false
        return try {
            val f = fit(size.first, size.second)
            val old = reader
            val r = newReader(f)
            v.resize(f[0], f[1], resources.displayMetrics.densityDpi)
            v.surface = r.surface
            runCatching { old?.close() }
            shownSize = size
            needKey = true; enc?.requestKeyFrame()
            true
        } catch (e: Exception) { AppLog.e("mirror: resizing the capture", e); false }
    }

    private fun newReader(f: IntArray): ImageReader {
        val (aw, ah, x0, y0) = f
        fitted = f
        val r = ImageReader.newInstance(aw, ah, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val now = System.nanoTime()
                lastImageAt = SystemClock.elapsedRealtime()
                // At most the encoder's frame rate (the screen may refresh at 60). No encoder (being made again): skipped.
                val e = enc
                if (e != null && now - lastQueuedNs >= frameGapNs) {
                    val pl = img.planes[0]
                    if (e.offer(pl.buffer, pl.rowStride, aw, ah, x0, y0, now / 1000)) lastQueuedNs = now
                }
            } catch (ex: Exception) {
                AppLog.e("mirror: frame", ex)
            } finally { runCatching { img.close() } }
        }, h)
        return r
    }

    /** An encoder at [level], or null when none can be made there (logged). */
    private fun makeEncoder(level: Int): MirrorEncoder? {
        val l = LEVELS[level]
        val names = runCatching { l.names() }.getOrDefault(emptyList())
        if (names.isEmpty()) { AppLog.i("mirror: no ${l.label} VP8 encoder on this tablet"); return null }
        return try { MirrorEncoder(l.w, l.h, l.bitrate, l.fps, names, h, ::send, ::encoderLost) }
        catch (ex: Exception) { AppLog.i("mirror: no ${l.label} encoder now (${ex.message})"); null }
    }

    /** The first encoder that works from [recovery]'s level down; null when none (the levels are used up). */
    private fun nextEncoder(): MirrorEncoder? {
        var level = recovery.level
        while (level >= 0) {
            makeEncoder(level)?.let { return it }
            level = recovery.unavailable()
        }
        return null
    }

    /** Takes [e] into the pipeline: a new box size goes to the TV and the capture; the TV gets a key frame first. */
    private fun use(e: MirrorEncoder) {
        enc = e
        encoderSince = SystemClock.elapsedRealtime()
        frameGapNs = 1_000_000_000L / e.fps - 3_000_000L
        bitrate = LEVELS[recovery.level].bitrate
        gop.clear(); needKey = true
        if (e.w != boxW || e.h != boxH) {
            boxW = e.w; boxH = e.h
            TvLink.mirrorStart(boxW, boxH, RATE, 2) // the TV starts its picture again at the new size
            recapture(screenSize())
        }
        e.requestKeyFrame()
    }

    /**
     * 0.5.2: the encoder ended under us (Android took it back for another app, or it failed). Mirroring goes
     * on: a new encoder at the same level, or the next one down after repeated losses ([MirrorRecovery]); the
     * projection, the VirtualDisplay and the sound carry on. None left: stop with plain words on the tablet and the TV.
     */
    private fun encoderLost(e: MirrorEncoder, why: String) {
        if (stopping || enc !== e) return
        enc = null
        framesBefore += e.frames
        val level = recovery.lost(SystemClock.elapsedRealtime())
        AppLog.i("mirror: encoder lost: ${e.name} after ${(SystemClock.elapsedRealtime() - encoderSince) / 1000} s and ${e.frames} frames " +
            "(${(SystemClock.elapsedRealtime() - startedAt) / 1000} s into mirroring, loss ${recovery.lostTotal}): $why")
        if (level < 0) { finish("the encoder kept stopping ($why)", tell = keptStopping(why)); return }
        // A short pause: the app that wanted the chip gets it, and a dying component is gone.
        h.postDelayed({
            if (stopping) return@postDelayed
            val n = nextEncoder() ?: run { finish("no encoder left after a loss", tell = BUSY); return@postDelayed }
            use(n)
            AppLog.i("mirror: encoder back: ${n.name} (${LEVELS[recovery.level].label}, ${n.w}x${n.h} ${n.fps} fps)")
        }, 300)
    }

    private fun keptStopping(why: String) =
        if ("reclaimed" in why || "no room" in why) BUSY else "The tablet's video encoder keeps stopping"

    private fun begin(p: MediaProjection) {
        val (sw, sh) = screenSize()
        val e = nextEncoder() ?: run {
            AppLog.i("mirror: no VP8 encoder works")
            _state.value = State(note = BUSY); finish("no encoder", tell = BUSY); return
        }
        boxW = e.w; boxH = e.h
        use(e)
        val f = fit(sw, sh)
        val (aw, ah) = f
        val r = newReader(f)
        shownSize = sw to sh
        TvLink.onKeyframeWanted = { h.post { keyframeWanted() } }
        TvLink.mirrorStart(boxW, boxH, RATE, 2)
        vd = p.createVirtualDisplay("tabcast-mirror", aw, ah, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displays, h)
        val sound = startAudio(p)
        keepAwake()
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        startedAt = SystemClock.elapsedRealtime()
        encoderSince = startedAt
        _state.value = State(running = true, tv = TvLink.state.value.tvName, sound = sound)
        AppLog.i("mirror: started, screen ${sw}x$sh → ${aw}x$ah in ${boxW}x$boxH, sound ${if (sound) "on" else "off (no permission)"}")
        h.postDelayed(tick, 1000)
    }

    private fun send(data: ByteArray, key: Boolean, ptsUs: Long) {
        if (key) { gop.clear(); needKey = false }
        if (needKey) return // after a dropped frame the next ones can't be decoded: wait for the key frame
        gop += data to ptsUs
        if (gop.size > 90) gop.clear()
        if (!TvLink.mirrorVideo(data, key, ptsUs)) {
            drops++
            needKey = true
            enc?.requestKeyFrame()
        }
        sent++; sentBytes += data.size
    }

    /** The TV needs a key frame: the encoder makes one with the next picture; on a still screen, send the last ones again. */
    private fun keyframeWanted() {
        if (stopping) return
        val e = enc ?: return // a new encoder starts with a key frame anyway
        if (SystemClock.elapsedRealtime() - lastImageAt < 300 || gop.isEmpty()) { e.requestKeyFrame(); needKey = true; return }
        for ((i, f) in gop.withIndex()) TvLink.mirrorVideo(f.first, i == 0, f.second)
    }

    private var lastSent = 0L
    private var lastBytes = 0L

    /** Once a second: watch the TV and the link, adapt the bitrate, log. (0.5.2: the encoder delivers by itself.) */
    private val tick = object : Runnable {
        override fun run() {
            if (stopping) return
            try { check() } catch (ex: Exception) { AppLog.e("mirror: tick", ex) }
            if (!stopping) h.postDelayed(this, 1000)
        }

        private fun check() {
            val t = TvLink.state.value
            if (!TvLink.connected) { finish("the TV link dropped"); return }
            // The TV left the mirror view (Back / Stop on the TV or the web remote).
            notTvMirror = if (SystemClock.elapsedRealtime() - startedAt > 4000 && t.mode != "mirror") notTvMirror + 1 else 0
            if (notTvMirror >= 3) { finish("the TV stopped showing it"); return }
            val fps = (sent - lastSent).toDouble()
            val kbps = ((sentBytes - lastBytes) * 8 / 1000).toInt()
            lastSent = sent; lastBytes = sentBytes
            _state.value = _state.value.copy(fps = fps, kbps = kbps)
            val secs = (SystemClock.elapsedRealtime() - startedAt) / 1000
            if (secs % 30 == 0L) AppLog.i("mirror: ${secs}s, $fps fps, $kbps kbit/s, ${framesBefore + (enc?.frames ?: 0)} frames" +
                " (${enc?.name ?: "no encoder"}), $drops dropped on the Wi-Fi, backlog ${TvLink.mirrorBacklog}")
            // A Wi-Fi that can't keep up: lower the bitrate once.
            if (drops > 10 && enc != null) { enc?.setBitrate(bitrate / 2); AppLog.i("mirror: Wi-Fi behind ($drops frames dropped); bitrate halved"); drops = -1_000_000 }
        }
    }

    // ------------------------------------------------------------------------------------------------------ sound

    private fun startAudio(p: MediaProjection): Boolean {
        // Capturing what other apps play needs Android 10 (the tablet runs 14).
        if (Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val rec = try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min * 2, CHUNK * 8)).setAudioPlaybackCaptureConfig(cfg).build()
        } catch (e: Exception) { AppLog.e("mirror: no sound capture", e); return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        audio = Thread({
            try {
                rec.startRecording()
                val buf = ByteArray(CHUNK)
                while (!stopping) {
                    var got = 0
                    while (got < CHUNK && !stopping) { val n = rec.read(buf, got, CHUNK - got); if (n < 0) throw IllegalStateException("read $n"); got += n }
                    // pts = when the chunk's first sample was captured.
                    TvLink.mirrorAudio(buf.copyOf(got), System.nanoTime() / 1000 - CHUNK_US)
                }
            } catch (e: Exception) { if (!stopping) AppLog.e("mirror: sound capture", e) }
            finally { runCatching { rec.stop() }; rec.release() }
        }, "mirror-audio").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    // ------------------------------------------------------------------------------------------- screen on, dim

    /**
     * Keeps the screen on and the backlight at its minimum: a 1-pixel overlay window (keep-screen-on + brightness
     * override), needs "Display over other apps" (Settings, or `adb shell appops set dk.pennico.tabcast
     * SYSTEM_ALERT_WINDOW allow`). Without it, a dim wake lock only keeps the screen on.
     */
    @SuppressLint("WakelockTimeout")
    private fun keepAwake() {
        val dim = getSharedPreferences("tabcast", MODE_PRIVATE).getBoolean("mirrorDim", true)
        if (Settings.canDrawOverlays(this)) {
            val ui = Handler(mainLooper)
            ui.post {
                if (stopping) return@post
                try {
                    val v = View(this)
                    val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT).apply {
                        gravity = Gravity.TOP or Gravity.START
                        if (dim) screenBrightness = 0.01f
                    }
                    getSystemService(WindowManager::class.java).addView(v, lp)
                    overlay = v
                    AppLog.i("mirror: screen kept on${if (dim) ", backlight at its minimum" else ""} (overlay)")
                } catch (e: Exception) { AppLog.e("mirror: overlay", e); wakeLock() }
            }
        } else wakeLock()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun wakeLock() {
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "TabCast:mirror").apply { acquire() }
        AppLog.i("mirror: screen kept on by a dim wake lock (no overlay permission: the backlight stays as it is)")
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { h.post { finish("the screen was turned off") } }
    }

    // --------------------------------------------------------------------------------------------------- the end

    /** Ends mirroring. [tell]: plain words for the user, shown on the tablet (toast + notification) and the TV. */
    private fun finish(why: String, tell: String? = null) {
        if (stopping) return
        stopping = true
        val secs = if (startedAt == 0L) 0 else (SystemClock.elapsedRealtime() - startedAt) / 1000
        AppLog.i("mirror: stopped ($why) after $secs s, ${framesBefore + (enc?.frames ?: 0)} frames, ${recovery.lostTotal} encoder(s) lost")
        TvLink.onKeyframeWanted = null
        TvLink.mirrorStop(tell)
        runCatching { unregisterReceiver(screenOff) }
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displays) }
        h.removeCallbacksAndMessages(null)
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        enc?.release(); enc = null
        audio?.join(500)
        runCatching { mp?.stop() }
        // On the main thread, after keepAwake's own post (which checks [stopping]).
        Handler(mainLooper).post { overlay?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }; overlay = null }
        wake?.takeIf { it.isHeld }?.release()
        _state.value = State(note = tell ?: if (why == "Stop" || why.startsWith("the screen")) null else "Mirroring stopped: $why")
        stopForeground(STOP_FOREGROUND_REMOVE)
        tell?.let { tellUser(it) }
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) finish("service ended")
        thread.quitSafely()
        super.onDestroy()
    }

    /** 0.5.2: why mirroring ended, where the user looks: a toast now and a notification that stays. */
    private fun tellUser(text: String) {
        Handler(mainLooper).post { runCatching { Toast.makeText(applicationContext, "Mirroring stopped. $text.", Toast.LENGTH_LONG).show() } }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(ID + 1, NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_cast)
                .setContentTitle("Mirroring stopped")
                .setContentText("$text.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$text. " + if (text == BUSY)
                    "Close the app that plays video, then try Mirror screen again." else "Try Mirror screen again; Tab Cast's log has the details."))
                .setAutoCancel(true)
                .build())
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 9, Intent(this, MirrorService::class.java).setAction(A_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Mirroring to ${TvLink.state.value.tvName ?: "the TV"}")
            .setContentText("The power button or Stop ends it")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .build()
    }
}

private const val RATE = 48_000
/** 20 ms of 48 kHz stereo 16-bit sound. */
private const val CHUNK = RATE / 50 * 4
private const val CHUNK_US = 20_000L
