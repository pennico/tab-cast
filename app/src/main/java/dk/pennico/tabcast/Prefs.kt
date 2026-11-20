// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast

import android.content.Context
import android.content.SharedPreferences
import dk.pennico.tabcast.core.SiteRule
import dk.pennico.tabcast.core.SiteRules
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)

/** Settings, bookmarks and site rules (SharedPreferences; everything stays on the tablet). */
class Prefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("tabcast", Context.MODE_PRIVATE)

    var relayChromecast: Boolean
        get() = sp.getBoolean("relay_cc", true)
        set(v) = sp.edit().putBoolean("relay_cc", v).apply()
    var relayFcast: Boolean
        get() = sp.getBoolean("relay_fcast", false)
        set(v) = sp.edit().putBoolean("relay_fcast", v).apply()
    var adBlock: Boolean
        get() = sp.getBoolean("adblock", true)
        set(v) = sp.edit().putBoolean("adblock", v).apply()
    /** Pages may start their own video (WebView mediaPlaybackRequiresUserGesture = false). */
    var pageAutoplay: Boolean
        get() = sp.getBoolean("page_autoplay", true)
        set(v) = sp.edit().putBoolean("page_autoplay", v).apply()
    /** Tab Cast TV pairing token (0.3.0): a credential for the TV, never logged. */
    var tvToken: String?
        get() = sp.getString("tv_token", null)
        set(v) = sp.edit().putString("tv_token", v).apply()
    var desktopMode: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) = sp.edit().putBoolean("desktop", v).apply()
    /** 0.3.3: asked once to let Tab Cast run with the screen off (battery optimisation exemption). */
    var askedBattery: Boolean
        get() = sp.getBoolean("asked_battery", false)
        set(v) = sp.edit().putBoolean("asked_battery", v).apply()
    var pauseOnCast: Boolean
        get() = sp.getBoolean("pause_on_cast", true)
        set(v) = sp.edit().putBoolean("pause_on_cast", v).apply()
    var ytdlpNightly: Boolean
        get() = sp.getBoolean("ytdlp_nightly", false)
        set(v) = sp.edit().putBoolean("ytdlp_nightly", v).apply()
    var ytdlpAutoUpdate: Boolean
        get() = sp.getBoolean("ytdlp_auto", true)
        set(v) = sp.edit().putBoolean("ytdlp_auto", v).apply()
    var ytdlpLastUpdate: Long
        get() = sp.getLong("ytdlp_last", 0)
        set(v) = sp.edit().putLong("ytdlp_last", v).apply()
    /** Highest video height offered to the TV in HLS playlists (Chromecast HD: a 1080p device). */
    /** 0.4.0: how fast Tab Cast TV may copy a file from the tablet (Mbit/s; the Wi-Fi router hung under full load). */
    var fileRateMbit: Int
        get() = sp.getInt("fileRateMbit", 20)
        set(v) = sp.edit().putInt("fileRateMbit", v.coerceIn(2, 200)).apply()

    /** 0.4.0: the film last sent to the TV ("uri\nid"), so a restarted app can serve it again while the TV plays it. */
    var lastFile: String?
        get() = sp.getString("lastFile", null)
        set(v) = sp.edit().putString("lastFile", v).apply()

    var maxHeight: Int
        get() = sp.getInt("max_height", 1080)
        set(v) = sp.edit().putInt("max_height", v).apply()
    /** Only H.264 video variants (every TV decodes them in hardware); off = also VP9/HEVC/AV1. */
    var h264Only: Boolean
        get() = sp.getBoolean("h264_only", true)
        set(v) = sp.edit().putBoolean("h264_only", v).apply()
    var max30fps: Boolean
        get() = sp.getBoolean("max_30fps", false)
        set(v) = sp.edit().putBoolean("max_30fps", v).apply()
    fun hlsLimits() = dk.pennico.tabcast.core.HlsFilter.Limits(maxHeight, if (max30fps) 30 else 60, h264Only)

    var defaultDevice: String?
        get() = sp.getString("default_device", null)
        set(v) = sp.edit().putString("default_device", v).apply()
    var lastUrl: String?
        get() = sp.getString("last_url", null)
        set(v) = sp.edit().putString("last_url", v).apply()

    /** Device key while something plays on the TV; cleared by Stop. Survives the app being killed. */
    var castingOn: String?
        get() = sp.getString("casting_on", null)
        set(v) = sp.edit().putString("casting_on", v).apply()

    var lastTitle: String?
        get() = sp.getString("last_title", null)
        set(v) = sp.edit().putString("last_title", v).apply()

    /** The user's sites on the start page (editable). The test pages are separate ([TEST_PAGES]). */
    var bookmarks: List<Bookmark>
        get() {
            val raw = sp.getString("bookmarks", null) ?: return emptyList()
            return try {
                val a = JSONArray(raw)
                (0 until a.length()).map { a.getJSONObject(it) }.map { Bookmark(it.getString("t"), it.getString("u")) }
                    .filter { b -> TEST_PAGES.none { it.url == b.url } } // v0.1.0 kept the test pages here
            } catch (e: Exception) { emptyList() }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
            sp.edit().putString("bookmarks", a.toString()).apply()
        }

    /** What was cast lately, newest first (start page "Recently on the TV"). */
    var recent: List<dk.pennico.tabcast.core.StartPage.Recent>
        get() = try {
            val a = JSONArray(sp.getString("recent", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                dk.pennico.tabcast.core.StartPage.Recent(it.optString("t"), it.optString("p").ifEmpty { null }, it.getString("m"),
                    it.optString("th").ifEmpty { null }, it.optLong("ts"), it.optString("tv").ifEmpty { null })
            }
        } catch (e: Exception) { emptyList() }
        set(v) {
            val a = JSONArray()
            v.take(12).forEach {
                a.put(JSONObject().put("t", it.title).put("p", it.pageUrl ?: "").put("m", it.mediaUrl).put("th", it.thumb ?: "")
                    .put("ts", it.time).put("tv", it.tv ?: ""))
            }
            sp.edit().putString("recent", a.toString()).apply()
        }

    fun addRecent(r: dk.pennico.tabcast.core.StartPage.Recent) {
        val key = r.pageUrl ?: r.mediaUrl
        recent = listOf(r) + recent.filter { (it.pageUrl ?: it.mediaUrl) != key }
    }

    /** The user's rules as JSON text (empty = none). */
    var userRulesJson: String
        get() = sp.getString("user_rules", "") ?: ""
        set(v) = sp.edit().putString("user_rules", v).apply()

    fun builtInRulesJson(): String = app.assets.open("site-rules.json").bufferedReader().use { it.readText() }

    /** User rules first (they win on header conflicts), then built-in ones. Bad user JSON is logged and skipped. */
    fun rules(): List<SiteRule> {
        val builtIn = try { SiteRules.parse(builtInRulesJson()) } catch (e: Exception) { AppLog.e("built-in rules", e); emptyList() }
        val user = userRulesJson.trim()
        if (user.isEmpty()) return builtIn
        return try { SiteRules.parse(user) + builtIn } catch (e: Exception) { AppLog.e("your site rules: ${e.message}"); builtIn }
    }

    companion object {
        /** Known-good public pages, so a test separates "casting works" from "this site is special" (checked 2026-11-14). */
        val TEST_PAGES = listOf(
            Bookmark("Test 1: MP4 file (archive.org)", "https://archive.org/details/BigBuckBunny_124"),
            Bookmark("Test 2: HLS stream (hls.js demo)", "https://hlsjs.video-dev.org/demo/"),
            Bookmark("Test 3: PeerTube video", "https://framatube.org/w/9c9de5e8-0a1e-484a-b099-e80766180a6d"),
            Bookmark("Test 4: Vimeo (needs yt-dlp)", "https://vimeo.com/76979871"),
        )
    }
}
