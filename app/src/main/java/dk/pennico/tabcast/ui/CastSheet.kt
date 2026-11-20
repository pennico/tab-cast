// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.cast.CastMedia
import dk.pennico.tabcast.core.Candidate
import dk.pennico.tabcast.core.Classify
import dk.pennico.tabcast.core.Kind
import dk.pennico.tabcast.core.StreamInfo
import dk.pennico.tabcast.core.Urls
import dk.pennico.tabcast.web.AdBlock
import dk.pennico.tabcast.web.WebCatcher
import dk.pennico.tabcast.ytdlp.YtDlp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Cast list: what the page played (best first), the TV it goes to, and yt-dlp for pages where catching fails. */
class CastSheet(private val act: BrowserActivity, private val catcher: WebCatcher) {
    private val dialog = BottomSheetDialog(act)
    private val list = act.column(0)
    private val status = act.text("", 14f)
    private val deviceLine = act.text("", 15f)
    private val ytBox = act.column(0)
    private val ytLine = act.text("", 14f)
    private var ticker: kotlinx.coroutines.Job? = null

    fun show() {
        catcher.command("scan")
        catcher.probeTop()
        val s = catcher.session
        val root = act.column(16) {
            addView(act.text(s.pageTitle ?: Urls.host(s.pageUrl).ifEmpty { "This page" }, 18f, true))
            addView(act.row {
                addView(deviceLine, weight1())
                addView(act.button("Change", outlined = true) { CastFlow.choose(act) { refresh() } })
            }, matchWrap())
            addView(status)
            addView(list, matchWrap())
            addView(act.header("Not the right video, or nothing found?"))
            addView(ytBox, matchWrap())
            // 0.5.0 (phase 3): what can't be caught can still be shown: the whole screen on the TV.
            addView(act.text("Still nothing? Play the video here full screen and mirror the tablet's screen instead.", 14f), matchWrap())
            addView(act.button("Mirror this instead", outlined = true) {
                dialog.dismiss()
                act.startActivity(android.content.Intent(act, MirrorActivity::class.java))
            })
        }
        dialog.setContentView(act.scroll(root))
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { act.sheet = null; ticker?.cancel() }
        refresh()
        dialog.show()
        // Sites whose rule says yt-dlp (YouTube, Vimeo): don't make the user tap twice when catching found nothing good.
        val good = catcher.session.ranked().firstOrNull()?.let { it.fromPlaying || it.via == "yt-dlp" } == true
        if (!good && catcher.rulesFor(s.pageUrl).any { it.resolver == "ytdlp" }) catcher.runYt(Urls.stripFragment(s.pageUrl))
        // The seconds counter of a running yt-dlp.
        ticker = act.lifecycleScope.launch {
            while (true) { delay(1000); catcher.yt?.takeIf { it.running }?.let { ytLine.text = ytText(it) } }
        }
    }

    fun refresh() {
        val s = catcher.session
        deviceLine.text = CastFlow.defaultDevice()?.let { "To: ${it.label}" } ?: "To: (no TV set up yet)"
        val ranked = s.ranked().filter { it.info?.drm != true || s.ranked().size < 4 }
        list.removeAllViews()
        status.text = when {
            ranked.isEmpty() && s.mseSeen -> "The page plays a stream, but its address wasn't caught yet. Wait until the video plays, then reopen this; or try yt-dlp below."
            ranked.isEmpty() -> "Nothing caught yet. Press play on the video, wait until it plays, then tap Cast again."
            ranked.first().let { !it.fromPlaying && it.via != "yt-dlp" } ->
                "The page hasn't played a video yet. Press play on it first (if it says the video is unavailable, these are something else)."
            else -> "Tap Cast on the first one; the others are alternatives."
        }
        for ((i, c) in ranked.take(10).withIndex()) list.addView(rowFor(c, i == 0), matchWrap())
        buildYt()
    }

    private fun rowFor(c: Candidate, best: Boolean): View {
        val sub = act.attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val col = act.column(0) {
            if (best) addView(if (c.fromPlaying || c.via == "yt-dlp") act.text("Best match", 12f, true, act.attrColor(androidx.appcompat.R.attr.colorPrimary))
                else act.text("Nothing is playing on the page: this may be an ad, a preview or another video", 12f, true, 0xFFE0A84A.toInt()))
            if (c.via == "yt-dlp") addView(act.text("found by yt-dlp" + (c.title?.let { ": $it" } ?: ""), 12f, false, sub))
            addView(act.text(c.describe(), 15f))
            addView(act.text(Urls.path(c.url).takeLast(60), 12f, false, sub))
        }
        val row = act.row {
            setPadding(0, act.dp(10), 0, act.dp(10))
            addView(col, weight1())
            addView(act.button("Cast", outlined = !best) { cast(c) })
        }
        row.setOnLongClickListener { more(c); true }
        return row
    }

    private fun media(c: Candidate, forceRelay: Boolean? = null): CastMedia {
        val s = catcher.session
        val rules = catcher.rulesFor(s.pageUrl)
        return CastMedia(
            url = c.url, mime = c.mime, headers = c.headers,
            title = c.title ?: s.pageTitle ?: Urls.host(s.pageUrl), thumb = c.thumb ?: s.pageThumb,
            pageUrl = s.pageUrl, live = c.info?.live == true,
            forceRelay = forceRelay ?: rules.firstNotNullOfOrNull { it.relay },
        )
    }

    private fun cast(c: Candidate, forceRelay: Boolean? = null, ask: Boolean = false) {
        CastFlow.withDevice(act, ask) { d ->
            CastFlow.cast(act, d, media(c, forceRelay), openRemote = false) { ok ->
                if (ok) {
                    dialog.dismiss()
                    if (TabCastApp.prefs.pauseOnCast) catcher.command("pause")
                }
            }
        }
    }

    private fun more(c: Candidate) {
        val items = arrayOf("Cast through the tablet (relay)", "Cast directly (no relay)", "Cast to another TV…", "Copy stream address", "Open with another app")
        act.dialog(c.describe()).setItems(items) { _, i ->
            when (i) {
                0 -> cast(c, forceRelay = true)
                1 -> cast(c, forceRelay = false)
                2 -> cast(c, ask = true)
                3 -> {
                    act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("stream", c.url))
                    Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show()
                }
                4 -> try {
                    act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(c.url), c.mime))
                } catch (e: Exception) { Toast.makeText(act, "No app for this", Toast.LENGTH_SHORT).show() }
            }
        }.show()
    }

    private val playerHint = Regex("""embed|player|video|watch|/v/|media|vod|live|stream|clip""", RegexOption.IGNORE_CASE)
    private val junk = Regex("""consent|recaptcha|accounts\.google|/plugins/|disqus|sharethis|addthis|about:blank|googletagmanager|cookie""", RegexOption.IGNORE_CASE)

    private fun buildYt() {
        ytBox.removeAllViews()
        val s = catcher.session
        if (!Urls.isHttp(s.pageUrl)) return
        val j = catcher.yt
        when {
            j != null && j.running -> {
                ytLine.text = ytText(j)
                ytBox.addView(ytLine, matchWrap())
                ytBox.addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }, matchWrap())
                return
            }
            j != null && j.found != null -> ytBox.addView(act.text("yt-dlp found \"${j.found}\": it is first in the list above.", 14f))
            j != null && j.error != null -> ytBox.addView(act.text("yt-dlp couldn't get it: ${j.error}" +
                "\nIf the site is new to yt-dlp, an update may help (Settings > yt-dlp > Update now).", 14f))
            else -> ytBox.addView(act.text("yt-dlp knows about 1,800 sites and also finds videos inside embedded players.", 13f))
        }
        ytBox.addView(act.button("Find this page's video with yt-dlp", outlined = true) { runYt(s.pageUrl) }, matchWrap())
        val embeds = s.iframes().filter { Urls.isHttp(it) && playerHint.containsMatchIn(it) && !junk.containsMatchIn(it) &&
            !Classify.isAdUrl(it) && !AdBlock.blocked(Urls.host(it)) && Urls.stripFragment(it) != Urls.stripFragment(s.pageUrl) }
            .distinctBy { Urls.stripFragment(it) }.take(4)
        for (e in embeds) {
            ytBox.addView(act.button("yt-dlp on embedded player: ${Urls.host(e)}${Urls.path(e).take(30)}", outlined = true) { runYt(e) }, matchWrap())
        }
    }

    private fun ytText(j: WebCatcher.YtJob): String {
        val first = YtDlp.version(act) == null
        return "yt-dlp is looking at ${Urls.host(j.url)}… ${j.step} · ${j.seconds} s" +
            if (first) "\n(the first run unpacks Python and takes up to a minute)" else ""
    }

    private fun runYt(url: String) {
        catcher.runYt(url)
        refresh()
    }
}
