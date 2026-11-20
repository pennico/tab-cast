// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.core.SiteRule
import dk.pennico.tabcast.core.SiteRules
import dk.pennico.tabcast.core.adblock.FilterEngine
import dk.pennico.tabcast.link.Pairing
import org.json.JSONArray
import org.json.JSONObject
import dk.pennico.tabcast.web.AdBlock
import dk.pennico.tabcast.web.AdSettings
import dk.pennico.tabcast.web.CatcherHost
import java.net.Inet4Address

class TvApp : Application() {
    companion object {
        /** Who may control this TV (0.3.0): the tablet's link and the web remote both check it. */
        lateinit var pairing: Pairing
            private set
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this, "tabcast-tv", "TabCastTV")
        TvCatcherHost.init(this)
        pairing = Pairing(PrefsPairStore(this))
        AdBlock.init(this)
        AppLog.i("Tab Cast TV ${BuildConfig.VERSION_NAME} start")
    }
}

/**
 * The TV's settings for the shared catcher: pages may start their own video, ads blocked, the built-in site rules
 * (the same file as the tablet's), no yt-dlp (the TV has YouTube's own app; the tablet keeps yt-dlp).
 */
object TvCatcherHost : CatcherHost {
    private var builtIn: List<SiteRule> = emptyList()
    /** The TV's own ad-blocking level and per-site exceptions (set from the tablet's remote). */
    lateinit var ads: AdSettings
        private set
    fun init(ctx: Context) {
        ads = AdSettings(ctx)
        builtIn = try {
            SiteRules.parse(ctx.assets.open("site-rules.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) { AppLog.e("built-in rules", e); emptyList() }
    }
    override val pageAutoplay = true
    override fun adLevel(pageUrl: String): FilterEngine.Level = ads.levelFor(pageUrl)
    override val desktopMode = false
    override fun rules(): List<SiteRule> = builtIn
}

object TvNet {
    /** The TV's IPv4 address on the active network (shown on the waiting screen). */
    fun localIpv4(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }?.address?.hostAddress
    }
}

/** Paired devices in SharedPreferences: id, name, kind, SHA-256 of the token (never the token), dates. */
class PrefsPairStore(ctx: Context) : Pairing.Store {
    private val sp = ctx.applicationContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    override fun load(): List<Pairing.Device> = try {
        val a = JSONArray(sp.getString("devices", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Pairing.Device(it.getString("id"), it.getString("name"), it.optString("kind"), it.getString("hash"), it.optLong("created"), it.optLong("seen"))
        }
    } catch (e: Exception) { AppLog.e("paired devices", e); emptyList() }

    override fun save(devices: List<Pairing.Device>) {
        val a = JSONArray()
        devices.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("hash", it.tokenHash)
            .put("created", it.created).put("seen", it.lastSeen)) }
        sp.edit().putString("devices", a.toString()).apply()
    }
}
