// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.web

import android.content.Context
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.core.SiteRule
import dk.pennico.tabcast.core.YtDlpPicker
import dk.pennico.tabcast.ytdlp.YtDlp

/** The tablet app's settings and yt-dlp for the shared [WebCatcher]. */
object TabletCatcherHost : CatcherHost {
    override val pageAutoplay get() = TabCastApp.prefs.pageAutoplay
    override fun adLevel(pageUrl: String) = TabCastApp.ads.levelFor(pageUrl)
    override val desktopMode get() = TabCastApp.prefs.desktopMode
    override fun rules(): List<SiteRule> = TabCastApp.prefs.rules()
    override fun userAgentChanged(ua: String) { TabCastApp.webUserAgent = ua }
    override val ytdlp: (suspend (Context, String, List<SiteRule>, String?, (String) -> Unit) -> YtDlpPicker.Result) =
        { ctx, url, rules, referer, onStep -> YtDlp.resolveWithRules(ctx, url, rules, referer, onStep = onStep) }
}
