// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.BuildConfig
import dk.pennico.tabcast.link.Release
import dk.pennico.tabcast.link.RepoIndex
import dk.pennico.tabcast.ui.SettingsActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 0.5.4.1: the tablet app updates itself without a cable, like Tab Cast TV. It reads the same signed update list (the
 * F-Droid repo on GitHub Pages, its index checked against the pinned fingerprint), at start and once a day. A newer
 * version shows in Settings › Updates and as a notification; it installs only when the user presses Update: download,
 * SHA-256 from the list, Android's installer (which also checks that the APK is signed like the installed app).
 */
object TabletUpdates {
    class State(val available: Release? = null, val line: String = "", val busy: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    private var lastCheck = 0L
    private const val DAY = 24 * 3600 * 1000L
    private const val CH = "updates"
    private const val NOTE_ID = 7301
    /**
     * The automatic daily check; off under Robolectric (no network from the tests), because it starts in
     * TabCastApp.onCreate, before a test could switch it off.
     */
    @androidx.annotation.VisibleForTesting var autoCheck = android.os.Build.FINGERPRINT != "robolectric"

    private fun set(line: String, avail: Release? = _state.value.available) {
        _state.value = State(avail, line, busy)
    }

    /** May the app install its update without first turning on "Install unknown apps" for it? */
    fun needsSetup(ctx: Context) = !SelfUpdate(ctx).mayInstall()
    fun setupIntent(ctx: Context) = SelfUpdate(ctx).unknownAppsSettings()

    /** Looks for an update now when [force], else when the last look is more than a day old. */
    fun check(context: Context, force: Boolean = false) {
        val ctx = context.applicationContext
        val sp = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (lastCheck == 0L) lastCheck = sp.getLong("checked", 0L)
        if (busy || (!force && (!autoCheck || now - lastCheck < DAY))) return
        lastCheck = now
        busy = true
        if (force) set("Looking for an update…")
        val self = SelfUpdate(ctx)
        Thread({
            try {
                val jar = File(self.dir, "index-v1.jar")
                self.download("${RepoIndex.REPO}/index-v1.jar", jar, 4L shl 20)
                val r = RepoIndex.latest(RepoIndex.verifiedJson(jar), ctx.packageName)
                jar.delete()
                val newer = r?.takeIf { it.code > BuildConfig.VERSION_CODE }
                sp.edit().putLong("checked", now).apply()
                AppLog.i("update: the update site has ${r?.name ?: "nothing"} (${r?.code}); this is ${BuildConfig.VERSION_NAME}")
                busy = false
                set(if (newer == null && force) "Tab Cast ${BuildConfig.VERSION_NAME} is up to date" else "", newer)
                if (newer != null && !force) main.post { notify(ctx, newer) }
            } catch (e: SecurityException) {
                AppLog.e("update: the update list doesn't check out", e)
                busy = false
                set("The update site's list doesn't check out: no update", null)
            } catch (e: SelfUpdate.Failed) {
                busy = false; lastCheck = 0 // no network yet: the next start or a press tries again
                set(if (force) e.plain else _state.value.line)
            } catch (e: Exception) {
                AppLog.e("update: check failed", e)
                busy = false; lastCheck = 0
                set(if (force) "Couldn't look for an update, try again" else _state.value.line)
            }
        }, "tablet-update-check").start()
    }

    /**
     * Downloads and installs the available update. Android may first ask to allow "Install unknown apps" for Tab Cast
     * (the screen offers that page before calling this), then shows its own Install dialog.
     */
    fun install(context: Context) {
        val ctx = context.applicationContext
        val r = _state.value.available ?: run { check(ctx, force = true); return }
        if (busy) return
        busy = true
        set("Downloading Tab Cast ${r.name}…")
        val self = SelfUpdate(ctx)
        Thread({
            try {
                self.clean()
                val apk = File(self.dir, "tablet-${r.code}.apk")
                self.download("${RepoIndex.REPO}/${r.file}", apk, maxOf(r.size, 1L) + (1L shl 20)) { n, _ ->
                    if (r.size > 0) set("Downloading Tab Cast ${r.name}… ${n * 100 / r.size}%")
                }
                if (self.sha256(apk) != r.sha256) { apk.delete(); throw SelfUpdate.Failed("The download doesn't match the update list: try again") }
                set("Installing Tab Cast ${r.name}… (Tab Cast closes and updates; open it again afterwards)")
                main.post {
                    self.install(apk) { res ->
                        busy = false
                        when (res) {
                            SelfUpdate.Result.AskedUser -> set("Press Install to update to ${r.name}")
                            SelfUpdate.Result.Done -> set("Updated to ${r.name}", null)
                            is SelfUpdate.Result.Error -> set(res.plain)
                        }
                    }
                }
            } catch (e: SelfUpdate.Failed) {
                busy = false; set(e.plain)
            } catch (e: Exception) {
                AppLog.e("update: install failed", e)
                busy = false; set("The update didn't install: the app stays as it was")
            }
        }, "tablet-update").start()
    }

    /** A quiet notification that opens Settings, where the update is one press. */
    private fun notify(ctx: Context, r: Release) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CH, "Updates", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(ctx, NOTE_ID, Intent(ctx, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_UPDATES, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Tab Cast ${r.name} is available")
            .setContentText(r.whatsNew.ifBlank { "Tap to update" }.take(200))
            .setStyle(Notification.BigTextStyle().bigText(r.whatsNew.ifBlank { "Tap to update" }))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(NOTE_ID, n) }
    }
}
