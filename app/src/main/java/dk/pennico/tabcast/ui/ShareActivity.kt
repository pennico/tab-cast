// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.cast.CastMedia
import dk.pennico.tabcast.core.Classify
import dk.pennico.tabcast.core.Urls
import dk.pennico.tabcast.ytdlp.YtDlp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Share target "Cast to TV" (from Brave or any app): a direct video link is cast as is; a page goes to yt-dlp first;
 * if yt-dlp can't do it, the page opens in the Tab Cast browser to be caught there.
 * The "Open in Tab Cast" share entry (activity-alias) skips yt-dlp; "Open on TV" opens the page in Tab Cast TV.
 *
 * singleInstance (manifest): this dialog is always alone in its own task, so a second share while something plays
 * always reaches it ([onNewIntent]) and the Remote it opens lands in the app's main task, not on top of this one.
 * (v0.1.0 kept the Remote in the share task; Android then treated the next share as "bring that task to the front",
 * because it compares intents without their extras, and the new link was dropped.)
 */
class ShareActivity : AppCompatActivity() {
    private lateinit var msg: TextView
    private var job: Job? = null
    private var ticker: Job? = null
    /** Bumped for every share; callbacks of an older share must not finish the dialog or overwrite the message. */
    private var generation = 0

    @VisibleForTesting var url: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        msg = text("", 16f)
        val root = column(24) {
            addView(text("Cast to TV", 20f, true))
            addView(msg, matchWrap())
            addView(ProgressBar(this@ShareActivity).apply { isIndeterminate = true })
            addView(row {
                addView(button("Open the page instead", outlined = true) { openBrowser(null) })
                addView(button("Cancel", outlined = true) { stopWork(); finish() })
            })
        }
        setContentView(root)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun stopWork() {
        job?.cancel(); job = null
        ticker?.cancel(); ticker = null
    }

    private fun handle(i: Intent) {
        stopWork()
        val gen = ++generation
        val u = i.getStringExtra(Intent.EXTRA_TEXT)?.let { Urls.firstUrlIn(it) }
            ?: i.getStringExtra(Intent.EXTRA_SUBJECT)?.let { Urls.firstUrlIn(it) }
            ?: i.dataString?.takeIf { Urls.isHttp(it) }
        url = u
        if (u == null) { msg.text = "No web link in what was shared."; return }
        AppLog.i("share: $u (${i.component?.className})")
        if (i.component?.className?.endsWith(".OpenInBrowser") == true) { openBrowser(null); return }
        if (i.component?.className?.endsWith(".OpenOnTv") == true) {
            msg.text = "Opening on the TV (Tab Cast TV)…"
            TvFlow.open(this, u, withLogin = false) { opened ->
                // Brave keeps playing in the background after a share (its own setting).
                if (opened) android.widget.Toast.makeText(applicationContext,
                    "Opened on the TV. If the video still plays in the other app, pause it there.", android.widget.Toast.LENGTH_LONG).show()
                if (gen == generation) finish()
            }
            return
        }

        val kind = Classify.fromUrl(u)
        if (kind != null && !Classify.isSegmentUrl(u)) {
            msg.text = "Direct ${kind.label} link."
            castNow(gen, CastMedia(u, Classify.mimeFor(kind, u), emptyMap(), Urls.path(u).substringAfterLast('/'), null, null))
            return
        }
        val rules = TabCastApp.prefs.rules().filter { it.matches(u) }
        val resolver = rules.firstOrNull { it.resolver != "auto" }?.resolver ?: "auto"
        if (resolver == "browser") { openBrowser(null); return }

        val head = "Looking for the video on ${Urls.host(u)} with yt-dlp…" +
            if (YtDlp.version(this) == null) "\n(The first time takes up to a minute.)" else ""
        var step = "starting"
        val t0 = SystemClock.elapsedRealtime()
        fun show() { if (gen == generation) msg.text = "$head\n\n$step · ${(SystemClock.elapsedRealtime() - t0) / 1000} s" }
        show()
        ticker = lifecycleScope.launch { while (true) { delay(1000); show() } }
        job = lifecycleScope.launch {
            try {
                // No rule for the site: skip yt-dlp's generic extractor, which spent 35 s on an unknown site before
                // failing. An unknown site then opens in the browser within seconds.
                val r = YtDlp.resolveWithRules(this@ShareActivity, u, rules, noGeneric = resolver == "auto") { s -> runOnUiThread { step = s; show() } }
                ticker?.cancel()
                if (gen != generation) return@launch
                val p = r.best!!
                msg.text = "Found: ${r.title ?: "video"} (${p.note})"
                castNow(gen, CastMedia(p.url, p.mime, p.headers, r.title, r.thumb, u, r.live, rules.firstNotNullOfOrNull { it.relay }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ticker?.cancel()
                if (gen != generation) return@launch
                if (e is YtDlp.Failed && e.unsupported) {
                    AppLog.i("share: yt-dlp doesn't know ${Urls.host(u)}; opening the page")
                    openBrowser("yt-dlp doesn't know this site")
                } else if (resolver == "ytdlp") {
                    msg.text = "yt-dlp couldn't get the video: ${e.message}\n\nYou can open the page and try catching it there."
                } else openBrowser(e.message)
            }
        }
    }

    private fun castNow(gen: Int, m: CastMedia) {
        CastFlow.withDevice(this) { d ->
            CastFlow.cast(this, d, m, openRemote = true) { ok ->
                if (gen != generation) return@cast
                if (ok) finish() else msg.text = "Couldn't cast. See the message."
            }
        }
    }

    private fun openBrowser(why: String?) {
        stopWork()
        val u = url ?: return finish()
        startActivity(Intent(this, BrowserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(BrowserActivity.EXTRA_URL, u)
            .apply { if (why != null) putExtra(BrowserActivity.EXTRA_YTDLP_FAILED, why.take(200)) })
        finish()
    }
}
