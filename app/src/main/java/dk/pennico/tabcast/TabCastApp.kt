// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast

import android.app.Application
import dk.pennico.tabcast.cast.CastManager
import dk.pennico.tabcast.cast.DeviceStore
import dk.pennico.tabcast.web.AdBlock
import dk.pennico.tabcast.web.AdSettings
import dk.pennico.tabcast.ytdlp.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TabCastApp : Application() {
    companion object {
        lateinit var prefs: Prefs
            private set
        lateinit var devices: DeviceStore
            private set
        /** Ad/tracker blocking level and per-site exceptions (the tablet's own; Tab Cast TV has its own). */
        lateinit var ads: AdSettings
            private set
        /** The browser's user agent (set by WebCatcher), reused for streams so CDNs see the same client. */
        @Volatile var webUserAgent: String? = null
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        prefs = Prefs(this)
        devices = DeviceStore(this)
        CastManager.init(this)
        ads = AdSettings(this)
        // 0.2.1 had an on/off switch: off stays off.
        if (!ads.hasGlobal && !prefs.adBlock) ads.global = dk.pennico.tabcast.core.adblock.FilterEngine.Level.OFF
        AdBlock.init(this)
        dk.pennico.tabcast.tvlink.TvLink.tokenStore = { prefs.tvToken }
        dk.pennico.tabcast.tvlink.TvLink.tokenSave = { prefs.tvToken = it }
        dk.pennico.tabcast.tvlink.TvLink.sitesProvider = { prefs.bookmarks.map { it.title to it.url } }
        AppLog.i("Tab Cast ${BuildConfig.VERSION_NAME} start")
        // 0.4.0: a film still playing on the TV from before a restart of this app gets served again.
        bg.launch { dk.pennico.tabcast.tvlink.TvLink.state.collect { st -> dk.pennico.tabcast.files.FileCast.resumeIfNeeded(this@TabCastApp, st) } }
        MainWatchdog.start()
        dk.pennico.tabcast.update.TabletUpdates.check(this) // 0.5.4.1: at most once a day, in the background
        if (BuildConfig.DEBUG) {
            // Network or slow calls on the main thread go to logcat (adb logcat -s StrictMode).
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
                .detectNetwork().detectCustomSlowCalls().penaltyLog().build())
        }
        bg.launch {
            // Unpack Python + yt-dlp in the background (first start takes a while on the Tab S2), then the weekly update.
            try {
                YtDlp.init(this@TabCastApp)
                val week = 7L * 24 * 3600 * 1000
                if (prefs.ytdlpAutoUpdate && System.currentTimeMillis() - prefs.ytdlpLastUpdate > week) {
                    YtDlp.update(this@TabCastApp, prefs.ytdlpNightly)
                }
            } catch (e: Exception) { AppLog.e("yt-dlp init/update", e) }
        }
    }
}
