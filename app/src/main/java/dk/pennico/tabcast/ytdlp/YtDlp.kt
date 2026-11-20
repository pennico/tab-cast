// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ytdlp

import android.content.Context
import android.webkit.CookieManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.core.SiteRule
import dk.pennico.tabcast.core.SiteRules
import dk.pennico.tabcast.core.Urls
import dk.pennico.tabcast.core.YtDlpOutput
import dk.pennico.tabcast.core.YtDlpPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * yt-dlp on the tablet (youtubedl-android: Python 3.12 + yt-dlp + QuickJS for sites that need a JS runtime).
 * Knows ~1,800 sites. We only ask it for the stream URL (`--dump-single-json`); the TV fetches the stream itself.
 */
object YtDlp {
    private val initLock = Mutex()
    @Volatile private var ready = false
    private val ids = AtomicInteger()

    suspend fun init(ctx: Context) = initLock.withLock {
        if (ready) return@withLock
        withContext(Dispatchers.IO) {
            val t = System.currentTimeMillis()
            YoutubeDL.getInstance().init(ctx.applicationContext)
            ready = true
            AppLog.i("yt-dlp ready in ${System.currentTimeMillis() - t} ms, version ${version(ctx)}")
        }
    }

    @Volatile private var cachedVersion: String? = null

    /** yt-dlp's version, or null before it was unpacked. Cached: the Cast list asks every second. */
    fun version(ctx: Context): String? = cachedVersion ?: try {
        YoutubeDL.getInstance().version(ctx.applicationContext)?.also { cachedVersion = it }
    } catch (e: Exception) { null }

    /** Netscape cookies.txt with the WebView's cookies for [url]'s host, so logged-in pages work in yt-dlp too. */
    private fun cookieFile(ctx: Context, url: String): File? {
        val ck = try { CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null } ?: return null
        val host = Urls.host(url).ifEmpty { return null }
        val secure = if (url.startsWith("https")) "TRUE" else "FALSE"
        val exp = System.currentTimeMillis() / 1000 + 86400
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        for (part in ck.split(';')) {
            val p = part.trim()
            val i = p.indexOf('=')
            if (i <= 0) continue
            sb.append(".$host\tTRUE\t/\t$secure\t$exp\t${p.substring(0, i)}\t${p.substring(i + 1)}\n")
        }
        return File(ctx.cacheDir, "ytdlp-cookies.txt").apply { writeText(sb.toString()) }
    }

    class Failed(msg: String, val timedOut: Boolean = false) : Exception(msg) {
        /** yt-dlp has no extractor for the site (only possible with the generic extractor turned off). */
        val unsupported: Boolean get() = message?.let { "No suitable extractor" in it || "Unsupported URL" in it } == true
    }

    /** Leaves out yt-dlp's generic extractor: unknown sites then fail in a second instead of half a minute. */
    val NO_GENERIC = listOf("--ies", "default,-generic")

    /**
     * yt-dlp with the matching site rules: their URL rewrite and options; if a rule has "ytdlpTryFirst" options, a
     * quick run with them comes first and the normal run only if that fails (YouTube: the visionos client gives the HLS
     * master without YouTube's player script, whose JavaScript challenge takes most of a minute in QuickJS on the Tab S2).
     * [onStep] gets yt-dlp's steps as short texts, on a background thread.
     */
    suspend fun resolveWithRules(ctx: Context, url: String, rules: List<SiteRule>, referer: String? = null,
                                 noGeneric: Boolean = false, onStep: ((String) -> Unit)? = null): YtDlpPicker.Result {
        val rs = rules.distinct()
        val target = SiteRules.ytdlpUrl(rs, url)
        val args = rs.flatMap { it.ytdlpArgs } + if (noGeneric) NO_GENERIC else emptyList()
        val first = rs.flatMap { it.ytdlpTryFirst }
        if (first.isNotEmpty()) {
            try {
                return resolve(ctx, target, args + first, referer, onStep, "quick", timeoutMs = 60_000)
            } catch (e: Failed) {
                if (e.unsupported) throw e
                AppLog.i("yt-dlp quick try failed (${e.message}); running the full method")
                onStep?.invoke("quick try didn't work, trying the full method")
            }
        }
        return resolve(ctx, target, args, referer, onStep, if (first.isEmpty()) null else "full", timeoutMs = 150_000)
    }

    /** [referer]: the page that embeds [url] (embedded players often check it). [label] only goes to the log. */
    suspend fun resolve(ctx: Context, url: String, extraArgs: List<String> = emptyList(), referer: String? = null,
                        onStep: ((String) -> Unit)? = null, label: String? = null, timeoutMs: Long = 120_000): YtDlpPicker.Result {
        val timeline = YtDlpOutput.Timeline()
        timeline.add("start")
        init(ctx)
        timeline.add("python ready")
        val req = withContext(Dispatchers.IO) {
            YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-quiet") // step messages, for progress and the timeline (the JSON stays on its own line)
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--socket-timeout", 20)
                cookieFile(ctx, url)?.let { addOption("--cookies", it.absolutePath) }
                if (referer != null && Urls.isHttp(referer)) addOption("--referer", referer)
                if (extraArgs.isNotEmpty()) addCommands(extraArgs)
            }
        }
        val id = "resolve-${ids.incrementAndGet()}"
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val what = listOfNotNull(Urls.host(url), label).joinToString(", ")
        val resp = try {
            withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(req, id, true) { _, _, line ->
                        if (line.length < 2000) lines += line
                        YtDlpOutput.step(line)?.let { st -> timeline.add(st); onStep?.invoke(st) }
                    }
                }
            } ?: run {
                try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Exception) {}
                AppLog.i("yt-dlp timed out after ${timeoutMs / 1000} s ($what) for $url | steps: ${timeline.summary("timeout")}")
                throw Failed("yt-dlp took longer than ${timeoutMs / 1000} s", timedOut = true)
            }
        } catch (e: Failed) {
            throw e
        } catch (e: CancellationException) {
            try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Exception) {}
            AppLog.i("yt-dlp cancelled ($what): ${timeline.summary("cancelled")}")
            throw e
        } catch (e: Exception) {
            val msg = YtDlpOutput.error(lines) ?: (e.message ?: e.javaClass.simpleName).lineSequence().map { it.trim() }
                .lastOrNull { it.startsWith("ERROR") }?.removePrefix("ERROR: ") ?: (e.message ?: "yt-dlp failed").take(300)
            AppLog.i("yt-dlp failed ($what) for $url: $msg | steps: ${timeline.summary("failed")}")
            throw Failed(msg)
        }
        val json = YtDlpOutput.jsonLine(resp.out)
            ?: throw Failed(YtDlpOutput.error(lines) ?: "yt-dlp gave no answer").also {
                AppLog.i("yt-dlp gave no JSON ($what) for $url | steps: ${timeline.summary("no json")}")
            }
        val r = YtDlpPicker.parse(json)
        AppLog.i("yt-dlp ${r.extractor} ($what) in ${resp.elapsedTime} ms: ${r.picks.size} castable, best ${r.best?.note} ${r.best?.url?.take(120)}")
        AppLog.i("yt-dlp steps: ${timeline.summary()}")
        if (r.picks.isEmpty()) throw Failed("yt-dlp found the video but no format the TV can play (DRM or separate audio/video only)")
        return r
    }

    /** Returns a short status for the Settings screen. */
    suspend fun update(ctx: Context, nightly: Boolean): String {
        init(ctx)
        return withContext(Dispatchers.IO) {
            val ch = if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
            val st = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, ch)
            cachedVersion = null
            TabCastApp.prefs.ytdlpLastUpdate = System.currentTimeMillis()
            val v = version(ctx)
            AppLog.i("yt-dlp update ($ch): $st, now $v")
            when (st) {
                YoutubeDL.UpdateStatus.DONE -> "Updated to $v"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Up to date ($v)"
                else -> "$st ($v)"
            }
        }
    }
}
