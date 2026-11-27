// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.BuildConfig
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.core.SiteRules
import dk.pennico.tabcast.core.adblock.FilterEngine
import dk.pennico.tabcast.update.TabletUpdates
import dk.pennico.tabcast.web.AdBlock
import dk.pennico.tabcast.ytdlp.YtDlp
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    companion object {
        /** 0.5.4.1: opened from the update notification: scroll to Updates. */
        const val EXTRA_UPDATES = "updates"
        /** 0.6: with EXTRA_UPDATES, also look for an update now (the main menu's "Check for updates"). */
        const val EXTRA_CHECK = "check"
    }

    private lateinit var ytStatus: TextView
    private lateinit var updStatus: TextView
    private lateinit var updBtn: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = TabCastApp.prefs
        ytStatus = text("yt-dlp " + (YtDlp.version(this) ?: "not unpacked yet"), 14f)
        // 0.4.0 light UI review: the everyday settings first; the technical ones folded under "Advanced".
        val advanced = column(0) { visibility = android.view.View.GONE }
        updStatus = text("", 14f)
        updBtn = button("Check for updates", outlined = true) { updatePressed() }
        val root = column(20) {
            addView(text("Settings", 22f, true))

            // 0.5.4.1: the tablet app updates itself (no cable), from the same signed update site as Tab Cast TV.
            addView(header("Updates"))
            addView(updStatus)
            addView(updBtn)

            addView(header("TV"))
            addView(button("TVs…", outlined = true) { startActivity(Intent(this@SettingsActivity, DevicesActivity::class.java)) })
            addView(button("Let Tab Cast keep running with the screen off", outlined = true) { batteryExemption() })
        }
        advanced.apply {
            addView(header("Files on the TV"))
            addView(text("How fast the TV copies a film from the tablet. Lower it if the Wi-Fi or the router struggles while a film loads.", 14f))
            val rates = listOf(5, 10, 20, 40, 80)
            lateinit var rateBtn: com.google.android.material.button.MaterialButton
            rateBtn = button("Download speed limit: ${p.fileRateMbit} Mbit/s", outlined = true) {
                dialog("Download speed limit").setSingleChoiceItems(rates.map { r -> "$r Mbit/s" + if (r == 20) " (default)" else "" }.toTypedArray(),
                    rates.indexOf(p.fileRateMbit)) { d, i -> p.fileRateMbit = rates[i]; rateBtn.text = "Download speed limit: ${p.fileRateMbit} Mbit/s"; d.dismiss() }.show()
            }
            addView(rateBtn)

            addView(header("TV picture"))
            addView(text("If the picture freezes while the sound goes on, the TV got a stream it can't decode. " +
                "Tab Cast leaves out the qualities above these limits (HLS streams, e.g. YouTube).", 14f))
            val heights = listOf(2160, 1440, 1080, 720, 480)
            lateinit var maxQ: com.google.android.material.button.MaterialButton
            maxQ = button("Max quality: ${p.maxHeight}p", outlined = true) {
                dialog("Max quality").setSingleChoiceItems(heights.map { h -> "${h}p" }.toTypedArray(), heights.indexOf(p.maxHeight)) { d, i ->
                    p.maxHeight = heights[i]; maxQ.text = "Max quality: ${p.maxHeight}p"; d.dismiss()
                }.show()
            }
            addView(maxQ)
            addView(switch("Only H.264 video (safest; off also allows VP9, HEVC and AV1)", p.h264Only) { p.h264Only = it })
            addView(switch("At most 30 frames per second (try this if 60 fps videos stutter or freeze)", p.max30fps) { p.max30fps = it })

            addView(header("Sending the stream"))
            addView(switch("Chromecast: send through the tablet (adds the page's cookies and the CORS headers the Chromecast needs)", p.relayChromecast) { p.relayChromecast = it })
            addView(switch("FCast: send through the tablet (only if a site fails directly; the TV normally gets the cookies itself)", p.relayFcast) { p.relayFcast = it })
            addView(switch("Pause the video on the tablet when it starts on the TV", p.pauseOnCast) { p.pauseOnCast = it })
        }
        root.apply {
            addView(header("Ad and tracker blocking"))
            addView(text("Standard blocks cross-site ads and trackers (like Brave's default). Aggressive also blocks the " +
                "site's own ads and hides more empty ad boxes. If a page or its video breaks, set that site lower from " +
                "the browser's ⋮ menu (\"Ad blocking on this site\").", 14f))
            val ads = TabCastApp.ads
            val levels = FilterEngine.Level.entries
            lateinit var lvl: com.google.android.material.button.MaterialButton
            lvl = button("All sites: ${ads.global.label}", outlined = true) {
                dialog("Ad blocking on all sites").setSingleChoiceItems(levels.map { it.label }.toTypedArray(), levels.indexOf(ads.global)) { d, i ->
                    ads.global = levels[i]; lvl.text = "All sites: ${ads.global.label}"; d.dismiss()
                }.show()
            }
            addView(lvl)
            lateinit var sitesBtn: com.google.android.material.button.MaterialButton
            sitesBtn = button("Sites with their own setting (${ads.sites().size})", outlined = true) {
                val list = ads.sites().entries.sortedBy { it.key }
                if (list.isEmpty()) { info("No exceptions", "Every site follows the setting for all sites."); return@button }
                dialog("Sites with their own setting").setItems(list.map { "${it.key}: ${it.value.label}" }.toTypedArray(), null)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Clear all") { _, _ -> ads.clearSites(); sitesBtn.text = "Sites with their own setting (0)" }.show()
            }
            addView(sitesBtn)
            val listsText = text("Filter lists: ${AdBlock.summary}", 13f)
            addView(listsText)
            addView(button("Update the lists now", outlined = true) {
                AdBlock.updateNow()
                listsText.text = "Updating the filter lists in the background (see the log)…"
            })
        }
        advanced.apply {
            addView(header("Browser"))
            addView(switch("Let pages start their video by themselves (needed by many players; applies from the next start of Tab Cast)", p.pageAutoplay) { p.pageAutoplay = it })
            addView(switch("Desktop site", p.desktopMode) { p.desktopMode = it })
            addView(button("Clear cookies and site data", outlined = true) {
                dialog("Clear?").setMessage("You will be logged out of sites in Tab Cast.")
                    .setPositiveButton("Clear") { _, _ ->
                        CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData()
                        Toast.makeText(this@SettingsActivity, "Cleared", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("Cancel", null).show()
            })

            addView(header("yt-dlp (finds videos on ~1,800 sites)"))
            addView(ytStatus)
            addView(switch("Use nightly builds (newest site fixes)", p.ytdlpNightly) { p.ytdlpNightly = it })
            addView(switch("Update every week", p.ytdlpAutoUpdate) { p.ytdlpAutoUpdate = it })
            addView(button("Update now", outlined = true) { updateYt() })

            addView(header("Site rules"))
            addView(text("Your own recipes for sites where catching or yt-dlp isn't enough.", 14f))
            addView(button("Edit my site rules", outlined = true) { startActivity(Intent(this@SettingsActivity, RulesActivity::class.java)) })
        }
        root.apply {
            lateinit var advBtn: com.google.android.material.button.MaterialButton
            advBtn = button("Advanced settings ▾", outlined = true) {
                val show = advanced.visibility != android.view.View.VISIBLE
                advanced.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                advBtn.text = if (show) "Advanced settings ▴" else "Advanced settings ▾"
            }
            addView(advBtn, matchWrap().apply { topMargin = dp(24) })
            addView(advanced, matchWrap())

            addView(header("Help"))
            addView(button("Show log", outlined = true) { startActivity(Intent(this@SettingsActivity, LogActivity::class.java)) })
            addView(text("Tab Cast ${BuildConfig.VERSION_NAME} · WebView ${WebViewCompat.getCurrentWebViewPackage(this@SettingsActivity)?.versionName ?: "?"}\n" +
                "Casting: FCast sender SDK (FUTO, MIT). yt-dlp (Unlicense) via youtubedl-android (GPL-3.0), Python (PSF).\n" +
                AdBlock.ATTRIBUTION + "\n" +
                "DRM services (Netflix, Prime Video, DRTV, RaiPlay…) can't be cast this way: use their TV apps.", 13f))
        }
        val sc = scroll(root)
        setContentView(sc)
        if (intent?.getBooleanExtra(EXTRA_UPDATES, false) == true) sc.post { sc.scrollTo(0, updStatus.top) }
        lifecycleScope.launch { TabletUpdates.state.collect { showUpdate(it) } }
        if (intent?.getBooleanExtra(EXTRA_CHECK, false) == true && TabletUpdates.state.value.available == null)
            TabletUpdates.check(this, force = true)
    }

    override fun onResume() {
        super.onResume()
        TabletUpdates.check(this) // at most once a day by itself
    }

    private fun showUpdate(s: TabletUpdates.State) {
        val r = s.available
        updStatus.text = s.line.ifEmpty {
            if (r != null) "Tab Cast ${r.name} is available (this is ${BuildConfig.VERSION_NAME})." +
                (if (r.whatsNew.isNotBlank()) "\n" + r.whatsNew else "")
            else "Tab Cast ${BuildConfig.VERSION_NAME}. Looks for updates once a day."
        }
        updBtn.text = if (r != null) "Update to ${r.name}" else "Check for updates"
        updBtn.isEnabled = !s.busy
    }

    private fun updatePressed() {
        if (TabletUpdates.state.value.available == null) { TabletUpdates.check(this, force = true); return }
        if (TabletUpdates.needsSetup(this)) {
            dialog("Allow updates").setMessage("Android asks once: allow \"Install unknown apps\" for Tab Cast, then come " +
                "back and press Update again. Android still checks that each update is signed like this app.")
                .setPositiveButton("Open the setting") { _, _ ->
                    runCatching { startActivity(TabletUpdates.setupIntent(this)) }
                        .onFailure { TabletUpdates.install(this) } // no such page on this tablet: Android asks during the install
                }.setNegativeButton("Cancel", null).show()
            return
        }
        TabletUpdates.install(this)
    }

    private fun updateYt() {
        ytStatus.text = "Updating yt-dlp…"
        lifecycleScope.launch {
            ytStatus.text = try { YtDlp.update(this@SettingsActivity, TabCastApp.prefs.ytdlpNightly) } catch (e: Exception) {
                AppLog.e("yt-dlp update", e); "Update failed: ${e.message?.take(200)}"
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun batteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { info("Already allowed", "Tab Cast may run with the screen off."); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

/** Edit the user's site rules (JSON). Validated on save; the built-in rules are shown for reference. */
class RulesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = TabCastApp.prefs
        val editor = EditText(this).apply {
            setText(p.userRulesJson.ifBlank { EXAMPLE })
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP
            minLines = 14
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
        }
        val root = column(16) {
            addView(text("My site rules", 20f, true))
            addView(text("JSON. Fields: name, match (regex on the page host), prefer / ignore (regexes on stream URLs), " +
                "headers, resolver (auto | ytdlp | browser, for Share), userAgent (desktop | mobile | text), relay (true/false), " +
                "script (JavaScript function body returning a URL or a list of URLs), ytdlpArgs. See README for examples.", 13f))
            addView(editor, matchWrap())
            addView(row {
                addView(button("Save") {
                    val t = editor.text.toString().trim()
                    try {
                        val n = if (t.isEmpty()) 0 else SiteRules.parse(t).size
                        p.userRulesJson = t
                        Toast.makeText(this@RulesActivity, "Saved $n rules. Restart the browser page to apply.", Toast.LENGTH_LONG).show()
                        AppLog.i("site rules saved: $n")
                        finish()
                    } catch (e: Exception) { info("Not saved", e.message ?: "invalid") }
                })
                addView(button("Clear", outlined = true) { editor.setText("") })
                addView(button("Example", outlined = true) { editor.setText(EXAMPLE) })
            })
            addView(header("Built-in rules (read-only)"))
            addView(text(p.builtInRulesJson(), 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
        }
        setContentView(scroll(root))
    }

    companion object {
        val EXAMPLE = """
            {
              "rules": [
                {
                  "name": "Example: prefer the site's own CDN, send a Referer",
                  "match": "(^|\\.)example\\.com${'$'}",
                  "prefer": ["cdn\\.example\\.com/.+\\.m3u8"],
                  "ignore": ["/ads?/"],
                  "headers": { "Referer": "https://www.example.com/" }
                }
              ]
            }
        """.trimIndent()
    }
}

class LogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = text(AppLog.text().ifEmpty { "(empty)" }, 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        val root = column(12) {
            addView(row {
                addView(button("Share") {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.text()), "Share log"))
                })
                addView(button("Clear", outlined = true) { AppLog.clear(); log.text = "(empty)" })
            })
            addView(text("File: ${AppLog.path()}", 12f))
            addView(log, matchWrap())
        }
        setContentView(scroll(root))
    }
}
