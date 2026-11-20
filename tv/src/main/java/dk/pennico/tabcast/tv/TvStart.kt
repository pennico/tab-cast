// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.content.Context
import dk.pennico.tabcast.core.Urls
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the web remote's start view shows (0.3.2): the user's sites (sent by the tablet, plus those
 * added from the web remote), the pages recently opened on the TV, and the test pages.
 */
class TvStart(ctx: Context) {
    data class Site(val title: String, val url: String, val time: Long = 0)

    private val sp = ctx.applicationContext.getSharedPreferences("start", Context.MODE_PRIVATE)

    private fun read(key: String): List<Site> = try {
        val a = JSONArray(sp.getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Site(it.optString("t"), it.getString("u"), it.optLong("time")) }
    } catch (e: Exception) { emptyList() }

    private fun write(key: String, list: List<Site>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("time", it.time)) }
        sp.edit().putString(key, a.toString()).apply()
    }

    /** The tablet's start-page sites (replaced whenever it connects). */
    var tabletSites: List<Site>
        get() = read("tablet")
        set(v) = write("tablet", v.filter { Urls.isHttp(it.url) }.take(40))

    private var webSites: List<Site>
        get() = read("web")
        set(v) = write("web", v.take(40))

    /** Tablet's sites first, then the ones added on the TV/web remote; no duplicates. */
    fun sites(): List<Site> = (tabletSites + webSites).distinctBy { Urls.stripFragment(it.url) }

    fun addSite(title: String, url: String) {
        if (!Urls.isHttp(url)) return
        webSites = webSites.filter { it.url != url } + Site(title.ifBlank { Urls.host(url).removePrefix("www.") }, url)
    }

    /** Removes a site added here; a tablet site comes back with the tablet's next connection unless removed there. */
    fun removeSite(url: String) {
        webSites = webSites.filter { it.url != url }
        tabletSites = tabletSites.filter { it.url != url }
    }

    var recent: List<Site>
        get() = read("recent")
        private set(v) = write("recent", v.take(12))

    /** Drops a recent entry (the address that redirected to the one that loaded). */
    fun forget(url: String) { recent = recent.filter { it.url != url } }

    /** A page opened on the TV (the title is filled in when the page has loaded). */
    fun opened(url: String, title: String?) {
        if (!Urls.isHttp(url)) return
        val old = recent.firstOrNull { it.url == url }
        val t = title?.takeIf { it.isNotBlank() && it != url } ?: old?.title ?: Urls.host(url).removePrefix("www.")
        recent = listOf(Site(t, url, System.currentTimeMillis())) + recent.filter { it.url != url }
    }

    fun json(): JSONObject {
        fun arr(l: List<Site>) = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time)) } }
        return JSONObject().put("sites", arr(sites())).put("recent", arr(recent)).put("tests", arr(TESTS))
    }

    companion object {
        /** Public pages to check that the TV plays video. */
        val TESTS = listOf(
            Site("HLS stream (hls.js demo)", "https://hlsjs.video-dev.org/demo/"),
            Site("MP4 file (archive.org)", "https://archive.org/details/BigBuckBunny_124"),
            Site("PeerTube video", "https://framatube.org/w/9c9de5e8-0a1e-484a-b099-e80766180a6d"),
            Site("TED talk", "https://www.ted.com/talks/sir_ken_robinson_do_schools_kill_creativity"),
        )
    }
}
