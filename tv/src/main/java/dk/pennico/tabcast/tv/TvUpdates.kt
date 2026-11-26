// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.link.Release
import dk.pennico.tabcast.link.RepoIndex
import dk.pennico.tabcast.update.SelfUpdate
import java.io.File

/**
 * 0.5.1: Tab Cast TV updates itself from the F-Droid repo it is published in, so Droid-ify is no
 * longer needed. At start and once a day it reads the repo's signed index (checked against the pinned fingerprint);
 * a newer version shows as "Update to x.y.z" on the start screen, on the tablet's TV remote and on the web remote.
 * The update starts only when someone presses it: download, SHA-256 from the index, Android's installer.
 *
 * [onChange] runs on the main thread whenever [state] changes.
 */
class TvUpdates(context: Context, private val onChange: () -> Unit) {
    private val ctx = context.applicationContext
    private val self = SelfUpdate(ctx)
    private val sp = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    /** The newer version on the repo, or null. */
    @Volatile var available: Release? = null; private set
    /** One line for the screens: what the updater is doing, or the last problem ("" when there's nothing to say). */
    @Volatile var state: String = ""; private set
    @Volatile var busy = false; private set
    private var lastCheck = 0L

    val needsSetup: Boolean get() = !self.mayInstall()
    fun setupIntent() = self.unknownAppsSettings()

    private fun set(s: String, avail: Release? = available) {
        main.post { state = s; available = avail; onChange() }
    }

    /** Checks the repo now when [force] or when the last check is more than a day old. */
    fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (busy || (!force && (!autoCheck || now - lastCheck < DAY))) return
        lastCheck = now
        busy = true
        if (force) set("Looking for an update…")
        Thread({
            try {
                val jar = File(self.dir, "index-v1.jar")
                self.download("${RepoIndex.REPO}/index-v1.jar", jar, 4L shl 20)
                val r = RepoIndex.latest(RepoIndex.verifiedJson(jar), ctx.packageName)
                jar.delete()
                val newer = r?.takeIf { it.code > BuildConfig.VERSION_CODE }
                sp.edit().putLong("checked", now).apply()
                AppLog.i("update: repo has ${r?.name ?: "nothing"} (${r?.code}); this is ${BuildConfig.VERSION_NAME}")
                busy = false
                set(if (newer == null && force) "Tab Cast TV ${BuildConfig.VERSION_NAME} is up to date" else "", newer)
            } catch (e: SecurityException) {
                AppLog.e("update: the repo index doesn't check out", e)
                busy = false
                set("The update site's list doesn't check out: no update", null)
            } catch (e: SelfUpdate.Failed) {
                busy = false; lastCheck = 0 // no network yet (e.g. just after the TV started): the hourly tick tries again
                set(if (force) e.plain else state)
            } catch (e: Exception) {
                AppLog.e("update: check failed", e)
                busy = false; lastCheck = 0
                set(if (force) "Couldn't look for an update, try again" else state)
            }
        }, "tv-update-check").start()
    }

    /**
     * Downloads and installs [available]. [needsSetup] true: Android will ask for "Install unknown apps" first (the
     * activity offers that page before calling this).
     */
    fun install() {
        val r = available ?: run { check(force = true); return }
        if (busy) return
        busy = true
        set("Downloading Tab Cast TV ${r.name}…")
        Thread({
            try {
                self.clean()
                val apk = File(self.dir, "tv-${r.code}.apk")
                self.download("${RepoIndex.REPO}/${r.file}", apk, maxOf(r.size, 1L) + (1L shl 20)) { n, _ ->
                    val total = if (r.size > 0) r.size else -1
                    if (total > 0) set("Downloading Tab Cast TV ${r.name}… ${n * 100 / total}%")
                }
                if (self.sha256(apk) != r.sha256) { apk.delete(); throw SelfUpdate.Failed("The download doesn't match the update list: try again") }
                set("Installing Tab Cast TV ${r.name}… (the TV app restarts: open it again from the home screen)")
                main.post {
                    self.install(apk) { res ->
                        when (res) {
                            SelfUpdate.Result.AskedUser -> { busy = false; set("Press Install on the TV to update to ${r.name}") }
                            SelfUpdate.Result.Done -> { busy = false; set("Updated to ${r.name}", null) }
                            is SelfUpdate.Result.Error -> { busy = false; set(res.plain) }
                        }
                    }
                }
            } catch (e: SelfUpdate.Failed) {
                busy = false; set(e.plain)
            } catch (e: Exception) {
                AppLog.e("update: install failed", e)
                busy = false; set("The update didn't install: the app stays as it was")
            }
        }, "tv-update").start()
    }

    companion object {
        const val DAY = 24 * 3600 * 1000L
        /** Tests switch the automatic daily check off (no network from Robolectric). */
        @androidx.annotation.VisibleForTesting var autoCheck = true
    }
}
