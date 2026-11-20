// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.R
import dk.pennico.tabcast.ui.RemoteActivity
import dk.pennico.tabcast.link.Wire
import dk.pennico.tabcast.tvlink.TvLink
import dk.pennico.tabcast.ui.TvRemoteActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type mediaPlayback) while something is on the TV: keeps the TV connection and the relay alive
 * with the screen off (partial wake lock + Wi-Fi lock), shows the media notification (play/pause, ±seek, stop) and
 * the lock-screen controls through a MediaSession, and routes the tablet's volume keys to the TV (remote playback).
 *
 * 0.3.3: every cast owns it, whatever started it: a cast to FCast / Chromecast ([CastManager]) or a
 * page opened in Tab Cast TV ([TvLink] session, also from the in-app browser's "Open this page on the TV", which had
 * no notification before). Commands go to whichever is on; the service ends itself when neither is.
 */
class CastService : Service() {
    companion object {
        private const val CH = "casting"
        private const val ID = 7
        const val A_TOGGLE = "dk.pennico.tabcast.TOGGLE"
        const val A_BACK = "dk.pennico.tabcast.BACK"
        const val A_FWD = "dk.pennico.tabcast.FWD"
        const val A_STOP = "dk.pennico.tabcast.STOP"

        /** Start it while the app is in front (Android refuses a foreground service started from the background). */
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, CastService::class.java)) }
            catch (e: Exception) {
                AppLog.e("start service: Android refused the media notification (app in the background? " +
                    "Settings → Run with the screen off helps)", e)
            }
        }
        /** CastManager's cast ended: the service goes unless a Tab Cast TV session still needs it. */
        fun stop(ctx: Context) { if (!TvLink.session) ctx.stopService(Intent(ctx, CastService::class.java)) }
    }

    /** What the notification and the MediaSession show, from either source. */
    private data class View(
        val tv: Boolean,
        val active: Boolean,
        val title: String,
        val device: String,
        val playback: Play,
        val time: Double,
        val duration: Double,
        val volume: Double,
        val canVolume: Boolean,
        val note: String?,
        val error: String?,
        val relayed: Boolean,
    )

    private fun view(c: CastState, t: TvLink.State): View =
        if (c.active || !TvLink.session) View(false, c.active, c.title ?: "Tab Cast", c.device?.label ?: "TV", c.playback,
            c.time, c.duration, c.volume, c.canVolume,
            c.note ?: if (c.active && c.conn != Conn.CONNECTED && c.conn != Conn.IDLE) "reconnecting…" else null, c.error, c.relayed)
        else View(true, true, t.title?.takeIf { it.isNotBlank() } ?: "Tab Cast TV", t.tvName ?: "TV",
            when { !t.connected -> Play.PAUSED; t.playing -> Play.PLAYING; t.mode == "player" || t.mode == "inpage" -> Play.PAUSED; else -> Play.IDLE },
            t.pos ?: 0.0, t.dur ?: 0.0, t.volume ?: 1.0, true,
            when {
                t.reconnecting || (!t.connected && TvLink.session) -> "reconnecting…"; t.pending > 0 -> "sending…"
                // 0.4.0, a film from the tablet: how long the tablet is still needed, in plain words.
                t.film && t.waiting -> "waiting for the tablet"
                t.film && t.complete -> "the tablet can sleep now"
                t.film -> t.heldTo?.let { h -> "on the TV up to ${dk.pennico.tabcast.ui.fmtTime(h - h % 60)}" } // minutes: no redraw every second
                else -> null
            },
            t.status[Wire.Status.ERROR]?.takeIf { it.isNotBlank() } ?: t.error.takeIf { !t.connected }, false)

    private val scope = MainScope()
    private lateinit var session: MediaSessionCompat
    private lateinit var volume: VolumeProviderCompat
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var cur: View = View(false, false, "Tab Cast", "TV", Play.IDLE, 0.0, 0.0, 1.0, true, null, null, false)
    private var endJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------------- commands, to either source
    private fun play() { AppLog.i("media control: play (${src()})"); if (cur.tv) TvLink.command(Wire.Cmd.PLAY) else CastManager.play() }
    private fun pause() { AppLog.i("media control: pause (${src()})"); if (cur.tv) TvLink.command(Wire.Cmd.PAUSE) else CastManager.pause() }
    private fun toggle() = if (cur.playback == Play.PLAYING) pause() else play()
    private fun seekTo(t: Double) { AppLog.i("media control: seek to ${t.toInt()} s (${src()})"); if (cur.tv) TvLink.command(Wire.Cmd.SEEK, t.coerceAtLeast(0.0)) else CastManager.seekTo(t) }
    private fun seekBy(d: Double) {
        if (!cur.tv) { AppLog.i("media control: seek ${d.toInt()} s (${src()})"); CastManager.seekBy(d); return }
        val max = if (cur.duration > 0) cur.duration - 1 else Double.MAX_VALUE
        seekTo((cur.time + d).coerceIn(0.0, max)) // absolute: safe to send again after a reconnect
    }
    private fun setVolume(v: Double) { if (cur.tv) TvLink.command(Wire.Cmd.VOLUME, v.coerceIn(0.0, 1.0)) else CastManager.setVolume(v) }
    private fun src() = if (cur.tv) "Tab Cast TV" else "cast"

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a video plays on the TV"; setShowBadge(false)
        })
        cur = view(CastManager.state.value, TvLink.state.value)
        session = MediaSessionCompat(this, "TabCast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stopAll("lock screen / media session")
                override fun onSeekTo(pos: Long) = seekTo(pos / 1000.0)
                override fun onFastForward() = seekBy(30.0)
                override fun onRewind() = seekBy(-10.0)
            })
            isActive = true
        }
        volume = object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, 100, (cur.volume * 100).toInt()) {
            override fun onSetVolumeTo(v: Int) { setVolume(v / 100.0); currentVolume = v }
            override fun onAdjustVolume(direction: Int) {
                if (direction == 0) return
                val v = (currentVolume + direction * 5).coerceIn(0, 100)
                setVolume(v / 100.0); currentVolume = v
            }
        }
        session.setPlaybackToRemote(volume)
        startInForeground(build(cur))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TabCast:cast")
        @Suppress("DEPRECATION")
        wifi = getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TabCast:cast")
        holdLocks(true, "cast started")
        scope.launch {
            var shown = ""
            combine(CastManager.state, TvLink.state) { c, t -> view(c, t) }.collect { v ->
                if (stopping) return@collect
                cur = v
                update(v)
                // 0.4.0: once the whole film is on the TV the tablet isn't needed: let it sleep (locks off).
                val t = TvLink.state.value
                holdLocks(!(v.tv && t.film && t.complete), if (t.film && t.complete) "the whole film is on the TV" else "the TV needs the tablet")
                // The position changes every second; redraw the notification only when what it shows changes.
                val key = "${v.tv}|${v.title}|${v.device}|${v.playback}|${v.relayed}|${v.error}|${v.note}|${v.active}"
                if (key != shown) {
                    if (shown.substringBefore('|') != key.substringBefore('|')) AppLog.i("media session: ${src()} \"${v.title}\" on ${v.device}")
                    shown = key; nm.notify(ID, build(v))
                }
                watchEnd(v)
            }
        }
        AppLog.i("cast service started (${src()}; media session + notification on)")
    }

    /**
     * Nothing on any more (cast stopped, or the TV page closed for 5 s): the service goes. Waits a moment so the
     * gap between "service started" and the cast being marked active doesn't end it.
     */
    private fun watchEnd(v: View) {
        val tvIdle = v.tv && TvLink.state.value.connected && TvLink.state.value.mode == "idle"
        val over = !v.active || tvIdle
        if (!over) { endJob?.cancel(); endJob = null; return }
        if (endJob?.isActive == true) return
        endJob = scope.launch {
            delay(if (tvIdle) 5000 else 2000)
            val again = view(CastManager.state.value, TvLink.state.value)
            val idleNow = again.tv && TvLink.state.value.connected && TvLink.state.value.mode == "idle"
            if (again.active && !idleNow) return@launch
            if (idleNow) { TvLink.endSession("nothing open on the TV"); dk.pennico.tabcast.files.FileCast.stop("nothing on the TV") }
            if (CastManager.state.value.active) return@launch
            AppLog.i("cast service: nothing on the TV any more; ending")
            stopping = true; stopForegroundNow(); stopSelf()
        }
    }

    private var locksHeld = false

    @SuppressLint("WakelockTimeout")
    private fun holdLocks(on: Boolean, why: String) {
        if (on == locksHeld) return
        locksHeld = on
        try {
            if (on) { wake?.acquire(); wifi?.acquire() } else { wake?.takeIf { it.isHeld }?.release(); wifi?.takeIf { it.isHeld }?.release() }
        } catch (e: Exception) { AppLog.e("locks", e) }
        AppLog.i("cast service: wake + Wi-Fi lock ${if (on) "on" else "off"} ($why)")
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(build(cur))
        when (intent?.action) {
            A_TOGGLE -> toggle()
            A_BACK -> seekBy(-10.0)
            A_FWD -> seekBy(30.0)
            A_STOP -> stopAll("notification")
        }
        return START_NOT_STICKY
    }

    /** Stop: ends the app side at once (whatever is on), tells the TV without waiting. */
    private fun stopAll(from: String) {
        // Handled here, not in an activity, and without waiting for anything: the notification goes now.
        AppLog.i("Stop from the $from (${src()})")
        stopping = true
        if (TvLink.session) {
            TvLink.cmd(Wire.Cmd.CLOSE) // best effort: the page on the TV closes
            TvLink.endSession("Stop")
        }
        dk.pennico.tabcast.files.FileCast.stop("Stop")
        if (CastManager.state.value.active) CastManager.stop() else if (!cur.tv) stopLeftoverThenQuit()
        if (!leftoverRunning) { stopForegroundNow(); stopSelf() }
    }

    private var leftoverRunning = false
    /** Set once Stop is pressed: the state collector must not post the notification again. */
    @Volatile private var stopping = false

    /** The app process was restarted (nothing connected): connect to the TV that was casting and stop it. */
    private fun stopLeftoverThenQuit() {
        val key = dk.pennico.tabcast.TabCastApp.prefs.castingOn
        val d = dk.pennico.tabcast.TabCastApp.devices.all().firstOrNull { it.key == key }
        if (d == null) { CastManager.stop(); return }
        leftoverRunning = true
        scope.launch {
            try { CastManager.stopLeftover(d) } finally { leftoverRunning = false; stopForegroundNow(); stopSelf() }
        }
    }

    private fun stopForegroundNow() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun update(v: View) {
        val st = when (v.playback) {
            Play.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            Play.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            Play.BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            // A page open on the TV without a video playing yet: still show the controls (paused), not "stopped".
            Play.IDLE -> if (v.active) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(st, (v.time * 1000).toLong(), if (v.playback == Play.PLAYING) 1f else 0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND)
            .build())
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, v.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, v.device + (v.note?.let { " · $it" } ?: ""))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (v.duration * 1000).toLong())
            .build())
        val vol = (v.volume * 100).toInt()
        if (vol != volume.currentVolume) volume.currentVolume = vol
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, CastService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(v: View): Notification {
        val target = if (v.tv) TvRemoteActivity::class.java else RemoteActivity::class.java
        val open = PendingIntent.getActivity(this, if (v.tv) 1 else 0, Intent(this, target), PendingIntent.FLAG_IMMUTABLE)
        val playing = v.playback == Play.PLAYING
        val text = buildString {
            append(v.device)
            when (v.playback) { Play.BUFFERING -> append(" · loading"); Play.PAUSED -> append(" · paused"); else -> {} }
            if (v.relayed) append(" · via tablet")
            v.note?.let { append(" · ").append(it) }
            v.error?.let { append(" · ").append(it) }
        }
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(v.title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(v.active)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_replay10, "Back 10 s", pi(A_BACK, 1))
            .addAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pause" else "Play", pi(A_TOGGLE, 2))
            .addAction(R.drawable.ic_forward30, "Forward 30 s", pi(A_FWD, 3))
            .addAction(R.drawable.ic_stop, "Stop", pi(A_STOP, 4))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        try { getSystemService(NotificationManager::class.java).cancel(ID) } catch (_: Exception) {}
        try { session.isActive = false; session.release() } catch (_: Exception) {}
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        AppLog.i("cast service stopped (media session released)")
        super.onDestroy()
    }
}
