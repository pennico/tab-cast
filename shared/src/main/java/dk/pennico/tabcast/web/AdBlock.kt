// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.core.Urls
import dk.pennico.tabcast.core.adblock.Domains
import dk.pennico.tabcast.core.adblock.FilterEngine
import dk.pennico.tabcast.core.adblock.FilterLists
import dk.pennico.tabcast.core.adblock.FilterEngine.Level
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Ad and tracker blocking for both apps: the filter lists Brave and uBlock Origin use (EasyList,
 * EasyPrivacy, uBlock filters + privacy, Peter Lowe's list), compiled by [FilterEngine].
 *
 * Lists ship in the APK (assets/filters, works offline) and are updated weekly on unmetered networks into
 * files/filters/. The compiled engine is cached (files/filters/engine.bin), so a start costs a file read; a compile
 * happens after an update or an app update, on a background thread. Until the engine is ready nothing is blocked.
 */
object AdBlock {
    @Volatile var engine: FilterEngine? = null
        private set
    /** One line for the settings / log: lists, versions, memory. */
    @Volatile var summary: String = "loading"
        private set

    const val ATTRIBUTION = "Ad and tracker blocking uses EasyList and EasyPrivacy (EasyList authors, GPLv3 / CC BY-SA 3.0), " +
        "uBlock filters and uBlock filters – Privacy (uBlock Origin, GPLv3) and Peter Lowe's ad and tracking server list."

    /** While this says true (Tab Cast TV's player is playing), a list update and its recompile wait: no GC stutter. */
    @Volatile var deferWhile: (() -> Boolean)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private lateinit var app: Context
    private const val WEEK_MS = 7L * 24 * 3600 * 1000

    fun lists(ctx: Context): List<FilterLists.Entry> =
        FilterLists.parseManifest(ctx.assets.open(FilterLists.MANIFEST).bufferedReader().use { it.readText() })

    private fun dir(ctx: Context) = File(ctx.filesDir, "filters").apply { mkdirs() }

    /** Starts loading in the background, and the weekly update check (first after a minute, then daily). */
    fun init(ctx: Context) {
        if (thread != null) return
        app = ctx.applicationContext
        val t = HandlerThread("adblock", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        thread = t
        handler = Handler(t.looper).apply {
            post { load(false) }
            postDelayed(object : Runnable {
                override fun run() { maybeUpdate(force = false); postDelayed(this, 24L * 3600 * 1000) }
            }, 60_000)
        }
    }

    /** Downloads the lists now (Settings "Update now"); reports through [summary] and the log. */
    fun updateNow() { handler?.post { maybeUpdate(force = true) } }

    /** For the Cast list: is this host an ad/tracker host (a plain host rule)? */
    fun blocked(host: String): Boolean = engine?.blockedHost(host) ?: false

    /**
     * A list's text: the weekly download (files/filters/<name>.txt.gz, ours) if there is one, else the shipped asset;
     * null for a list that isn't shipped and isn't downloaded yet.
     */
    private fun source(name: String): Pair<String, () -> java.io.Reader?> {
        val f = File(dir(app), "$name.txt.gz")
        return if (f.length() > 1000) "downloaded ${f.length()} ${f.lastModified()}" to { GZIPInputStream(f.inputStream()).bufferedReader() }
        else "apk" to { try { app.assets.open(FilterLists.assetPath(name)).bufferedReader() } catch (_: java.io.FileNotFoundException) { null } }
    }

    private fun load(forceCompile: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        val lists = lists(app)
        // App updates (new shipped lists, new engine) change lastUpdateTime: then the cache is rebuilt.
        val installed = try { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
        val key = "v1 app=$installed " + lists.joinToString(" ") { "${it.name}:${source(it.name).first}" }
        val bin = File(dir(app), "engine.bin")
        val keyFile = File(dir(app), "engine.key")
        if (!forceCompile && bin.exists() && keyFile.exists() && keyFile.readText() == key) {
            try {
                val e = DataInputStream(BufferedInputStream(bin.inputStream(), 1 shl 16)).use { FilterEngine.read(it) }
                publish(e, "cache", SystemClock.elapsedRealtime() - t0)
                return
            } catch (ex: Exception) { AppLog.e("ad blocking: cache unreadable, compiling", ex) }
        }
        try {
            val (e, b) = FilterLists.build(lists) { name -> source(name).second() }
            val compileMs = SystemClock.elapsedRealtime() - t0
            val tmp = File(dir(app), "engine.bin.tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { e.write(it) }
            tmp.renameTo(bin)
            keyFile.writeText(key)
            publish(e, "compiled", compileMs)
            AppLog.i("ad blocking rules: ${b.stats.entries.joinToString(", ") { "${it.key} ${it.value}" }}")
        } catch (ex: Throwable) {
            summary = "NOT active: couldn't load the filter lists (${ex.message})"
            AppLog.e("ad blocking NOT active: lists not loaded", ex)
        }
    }

    private fun publish(e: FilterEngine, how: String, ms: Long) {
        engine = e
        val lists = e.info.filterKeys { it.startsWith("list.") }.entries.joinToString(", ") { "${it.key.removePrefix("list.")} ${it.value}" }
        summary = "active: ${FilterLists.counts(e)}, ${e.memoryBytes() / 1024} KB, $how in $ms ms; $lists"
        val rt = Runtime.getRuntime()
        AppLog.i("ad blocking active: ${FilterLists.counts(e)} ($how in $ms ms, engine ${e.memoryBytes() / 1024} KB, " +
            "app heap ${(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024} MB used); $lists")
    }

    private fun unmetered(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun maybeUpdate(force: Boolean) {
        if (!force && deferWhile?.invoke() == true) { handler?.postDelayed({ maybeUpdate(false) }, 30 * 60_000L); return }
        val stamp = File(dir(app), "last-update")
        val age = System.currentTimeMillis() - stamp.lastModified()
        if (!force && stamp.exists() && age < WEEK_MS) return
        if (!force && !unmetered()) { AppLog.i("ad blocking: list update waits for Wi-Fi"); return }
        var changed = 0
        for (l in lists(app)) {
            try {
                val c = URL(l.url).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "TabCast-filter-update")
                if (c.responseCode != 200) { AppLog.i("ad blocking: ${l.name} update: HTTP ${c.responseCode}"); continue }
                val text = c.inputStream.bufferedReader().use { it.readText() }
                val lines = text.count { it == '\n' }
                if (lines < 1000 || !(text.startsWith("[") || text.startsWith("!"))) { AppLog.i("ad blocking: ${l.name} update doesn't look like a list ($lines lines); kept the old one"); continue }
                val tmp = File(dir(app), "${l.name}.txt.gz.tmp")
                GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { it.write(text) }
                tmp.renameTo(File(dir(app), "${l.name}.txt.gz"))
                changed++
            } catch (e: Exception) {
                AppLog.i("ad blocking: ${l.name} update failed: ${e.message}")
            }
        }
        stamp.writeText("${System.currentTimeMillis()}")
        stamp.setLastModified(System.currentTimeMillis())
        AppLog.i("ad blocking: $changed lists downloaded; recompiling")
        if (changed > 0) load(forceCompile = true)
    }

    /** CSS hiding [selectors]: `:is()` ignores a selector this WebView doesn't understand instead of dropping the rule. */
    fun css(selectors: List<String>): String {
        if (selectors.isEmpty()) return ""
        val sb = StringBuilder()
        for (chunk in selectors.chunked(300)) sb.append(":is(").append(chunk.joinToString(",")).append("){display:none!important}\n")
        return sb.toString()
    }
}

/** The ad-blocking level of an app (each app its own) and per-site exceptions, remembered by site (example.com). */
class AdSettings(ctx: Context, file: String = "adblock") {
    private val sp = ctx.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    var global: Level
        get() = Level.of(sp.getString("level", null)) ?: Level.STANDARD
        set(v) = sp.edit().putString("level", v.label).apply()

    val hasGlobal: Boolean get() = sp.contains("level")

    fun siteOf(url: String): String = Domains.site(Urls.host(url))

    /** The site's own setting, or null (it follows [global]). */
    fun site(url: String): Level? = Level.of(sp.getString("site." + siteOf(url), null))

    fun setSite(url: String, level: Level?) {
        val k = "site." + siteOf(url)
        if (level == null) sp.edit().remove(k).apply() else sp.edit().putString(k, level.label).apply()
    }

    fun levelFor(url: String): Level = if (Urls.isHttp(url)) site(url) ?: global else Level.OFF

    fun sites(): Map<String, Level> = sp.all.filterKeys { it.startsWith("site.") }
        .mapNotNull { (k, v) -> Level.of(v as? String)?.let { k.removePrefix("site.") to it } }.toMap()

    fun clearSites() { sp.edit().apply { sp.all.keys.filter { it.startsWith("site.") }.forEach { remove(it) } }.apply() }
}
