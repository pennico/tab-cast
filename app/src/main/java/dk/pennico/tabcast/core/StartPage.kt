// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The browser's start page (v0.1.1): the user's sites as large tiles, recent casts with "Cast again",
 * the test pages folded away. Plain HTML + CSS, no scripts, nothing loaded from the network except recent-cast
 * thumbnails. Follows the app's light/dark theme through prefers-color-scheme (the WebView follows the app theme).
 *
 * Actions are `tabcast://<action>?...&k=<token>` links that BrowserActivity handles; the per-render [Model.token]
 * means no other page can trigger them. Built with colour tokens on :root with a dark
 * variant, system font stack, 44 px minimum touch targets.
 */
object StartPage {
    data class Site(val title: String, val url: String)
    data class Recent(val title: String, val pageUrl: String?, val mediaUrl: String, val thumb: String?, val time: Long, val tv: String?)
    data class Model(
        val token: String,
        val sites: List<Site>,
        val tests: List<Site>,
        val recent: List<Recent>,
        val tv: String?,
        val nowPlaying: String?,
        val lastPage: Site?,
        val now: Long = System.currentTimeMillis(),
        /** 0.5.0: show "Mirror screen" among the big actions. */
        val mirror: Boolean = false,
    )

    fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** A stable colour per site, readable with a white letter in both themes. */
    fun hue(host: String): Int = Math.floorMod(host.removePrefix("www.").hashCode(), 360)

    private fun letter(title: String, host: String): String =
        (title.firstOrNull { it.isLetterOrDigit() } ?: host.removePrefix("www.").firstOrNull() ?: '?').uppercaseChar().toString()

    fun ago(then: Long, now: Long): String {
        val m = (now - then) / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            m < 7 * 24 * 60 -> "${m / (24 * 60)} d ago"
            else -> SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(then))
        }
    }

    private const val CAST_SVG = """<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M21 3H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7zm0-4v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11z"/></svg>"""
    private const val MORE_SVG = """<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>"""
    private const val PLAY_SMALL = """<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>"""
    private const val MENU_SVG = """<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" style="vertical-align:-3px" aria-label="menu"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>"""
    private const val PAGE_SVG = """<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm6.9 6h-2.9a15.7 15.7 0 0 0-1.4-3.6A8 8 0 0 1 18.9 8zM12 4c.8 1.2 1.5 2.5 1.9 4h-3.8c.4-1.5 1.1-2.8 1.9-4zM4.3 14a8 8 0 0 1 0-4h3.4a16.5 16.5 0 0 0 0 4H4.3zm.8 2h2.9c.3 1.3.8 2.5 1.4 3.6A8 8 0 0 1 5.1 16zM8 8H5.1a8 8 0 0 1 4.3-3.6C8.8 5.5 8.3 6.7 8 8zm4 12c-.8-1.2-1.5-2.5-1.9-4h3.8c-.4 1.5-1.1 2.8-1.9 4zm2.3-6H9.7a14.7 14.7 0 0 1 0-4h4.6a14.7 14.7 0 0 1 0 4zm.3 5.6c.6-1.1 1.1-2.3 1.4-3.6h2.9a8 8 0 0 1-4.3 3.6zm1.7-5.6a16.5 16.5 0 0 0 0-4h3.4a8 8 0 0 1 0 4h-3.4z"/></svg>"""
    private const val FILE_SVG = """<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M4 4h10l6 6v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm9 1.5V11h5.5L13 5.5zM8 13v6l5-3-5-3z"/></svg>"""
    private const val MIRROR_SVG = """<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M4 3h9a2 2 0 0 1 2 2v3h-2V5H4v12h5v2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm8 7h8a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2zm0 2v7h8v-7h-8z"/></svg>"""
    private const val PLAY_SVG = """<svg viewBox="0 0 24 24" width="40" height="40" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>"""

    private const val CSS = """
:root{--bg:#f4f5f7;--panel:#fff;--sunk:#e9ecf0;--fg:#1b2027;--muted:#5d6876;--line:#dde2e8;--accent:#2f6fd0;--accent-soft:#e2ecfb;--on-accent:#fff;--ok:#2f8a5f;
--shadow:0 1px 2px rgb(20 30 50/.07),0 3px 10px rgb(20 30 50/.05);--sans:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
@media (prefers-color-scheme:dark){:root{--bg:#111316;--panel:#1b1e23;--sunk:#252930;--fg:#e9ebef;--muted:#9ba5b1;--line:#2e333b;--accent:#8ab4ff;--accent-soft:#1e2a3f;--on-accent:#0c1322;--ok:#5cc28f;--shadow:0 1px 2px rgb(0 0 0/.45)}}
*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.4 var(--sans);-webkit-tap-highlight-color:transparent}
a{color:inherit;text-decoration:none}
.wrap{max-width:1120px;margin:0 auto;padding:28px 32px 48px}
header{display:flex;align-items:center;gap:16px;flex-wrap:wrap}
.logo{width:52px;height:52px;border-radius:15px;background:var(--accent);color:var(--on-accent);display:grid;place-items:center;flex:none}
.logo svg{width:30px;height:30px}
.brand h1{margin:0;font-size:26px;font-weight:650;letter-spacing:-.01em}
.brand p{margin:2px 0 0;color:var(--muted);font-size:15px}
.pills{margin-left:auto;display:flex;gap:10px;flex-wrap:wrap}
.pill{display:flex;align-items:center;gap:10px;min-height:48px;max-width:360px;padding:0 18px;border-radius:24px;background:var(--panel);border:1px solid var(--line);font-size:15px;box-shadow:var(--shadow);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.pill svg{flex:none;width:18px;height:18px}
.pill.cta{background:var(--accent);color:var(--on-accent);border-color:transparent;font-weight:600}
.dot{width:10px;height:10px;border-radius:50%;background:var(--ok);flex:none}
form{margin:24px 0 0;display:flex}
input[type=search]{flex:1;height:56px;border-radius:28px;border:1px solid var(--line);background:var(--panel);color:var(--fg);padding:0 24px;font:inherit;font-size:17px;box-shadow:var(--shadow);outline:none;-webkit-appearance:none}
input[type=search]:focus{border-color:var(--accent)}
input::placeholder{color:var(--muted)}
.cont{display:flex;align-items:center;gap:12px;margin-top:14px;min-height:48px;padding:0 18px;border-radius:14px;background:var(--accent-soft);color:var(--fg);font-size:15px}
.cont b{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cont span{color:var(--muted);flex:none}
section{margin-top:30px}
.sh{display:flex;align-items:center;justify-content:space-between;min-height:44px;margin-bottom:10px}
.sh h2{margin:0;font-size:13px;font-weight:650;letter-spacing:.07em;text-transform:uppercase;color:var(--muted)}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:44px;padding:0 18px;border-radius:22px;background:var(--accent-soft);color:var(--accent);font-weight:600;font-size:15px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:14px}
.tw{position:relative}
.tile{display:flex;align-items:center;gap:14px;min-height:100px;height:100%;padding:16px 50px 16px 16px;border-radius:18px;background:var(--panel);border:1px solid var(--line);box-shadow:var(--shadow)}
.tile:active,.card a:active{opacity:.75}
.av{width:56px;height:56px;border-radius:16px;display:grid;place-items:center;font-size:26px;font-weight:700;color:#fff;flex:none}
.tt{min-width:0}
.tt b{font-size:17px;font-weight:600;overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}
.tt span{display:block;color:var(--muted);font-size:13px;margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.more{position:absolute;top:4px;right:4px;width:44px;height:44px;border-radius:22px;display:grid;place-items:center;color:var(--muted)}
.empty{border:2px dashed var(--line);border-radius:18px;padding:26px 28px;color:var(--muted);font-size:16px;line-height:1.5}
.empty b{color:var(--fg)}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:14px}
.card{border-radius:18px;background:var(--panel);border:1px solid var(--line);overflow:hidden;box-shadow:var(--shadow);display:flex;flex-direction:column}
.thumb{aspect-ratio:16/9;background:var(--sunk);position:relative;display:grid;place-items:center;color:var(--muted)}
.thumb img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.cb{padding:12px 14px 4px}
.cb b{font-size:16px;font-weight:600;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.meta{color:var(--muted);font-size:13px;margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ca{padding:8px 14px 14px;margin-top:auto}
details{margin-top:34px;border-top:1px solid var(--line);padding-top:10px}
summary{list-style:none;color:var(--muted);font-size:15px;min-height:48px;display:flex;align-items:center;gap:8px}
summary::-webkit-details-marker{display:none}
summary:before{content:"";width:0;height:0;border:6px solid transparent;border-left:8px solid var(--muted);margin-right:-2px}
details[open] summary:before{border:6px solid transparent;border-top:8px solid var(--muted);margin-top:6px}
.chips{display:flex;flex-wrap:wrap;gap:10px;margin:4px 0 6px}
.chip{min-height:44px;display:inline-flex;align-items:center;padding:0 16px;border-radius:22px;border:1px solid var(--line);background:var(--panel);font-size:15px}
.tip{margin-top:26px;color:var(--muted);font-size:14px}
.acts{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:14px;margin-top:22px}
.act{display:flex;align-items:center;gap:16px;min-height:96px;padding:16px 20px;border-radius:18px;background:var(--panel);border:1px solid var(--line);box-shadow:var(--shadow)}
.act:active{opacity:.75}
.act svg{width:40px;height:40px;flex:none;color:var(--accent)}
.act b{display:block;font-size:19px;font-weight:650}
.act span{display:block;color:var(--muted);font-size:14px;margin-top:2px}
@media (max-width:640px){.wrap{padding:20px 16px 40px}.pills{margin-left:0}.grid{grid-template-columns:1fr 1fr}}
"""

    fun html(m: Model): String {
        val k = enc(m.token)
        fun act(a: String, i: Int? = null) = "tabcast://$a?" + (if (i != null) "i=$i&" else "") + "k=$k"
        val sb = StringBuilder(8192)
        sb.append("""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""")
        sb.append("""<meta name="color-scheme" content="light dark"><title>Tab Cast</title><style>""").append(CSS).append("</style></head><body><div class=wrap>")

        // Header: brand, TV status, what's playing.
        sb.append("<header><div class=logo>").append(CAST_SVG).append("</div><div class=brand><h1>Tab Cast</h1>")
        sb.append("<p>Play videos on the TV.</p></div><div class=pills>")
        if (m.nowPlaying != null) {
            sb.append("<a class=pill href=\"").append(act("remote")).append("\">").append(PLAY_SMALL).append("On the TV: ").append(esc(m.nowPlaying)).append("</a>")
        }
        if (m.tv != null) sb.append("<a class=pill href=\"").append(act("tvs")).append("\"><span class=dot></span>").append(esc(m.tv)).append("</a>")
        else sb.append("<a class=\"pill cta\" href=\"").append(act("tvs")).append("\">Set up your TV</a>")
        sb.append("</div></header>")

        sb.append("<form action=\"tabcast://go\" method=get><input type=hidden name=k value=\"").append(esc(m.token))
        sb.append("\"><input type=search id=q name=q placeholder=\"Search or type a web address\" autocomplete=off enterkeyhint=go></form>")

        // 0.4.0 (light UI review): the big actions first.
        sb.append("<div class=acts><label class=act for=q>").append(PAGE_SVG)
            .append("<div><b>Cast a page</b><span>Open a site, start its video, tap Cast</span></div></label>")
        sb.append("<a class=act href=\"").append(act("files")).append("\">").append(FILE_SVG)
            .append("<div><b>Cast a file</b><span>A video on the tablet</span></div></a>")
        if (m.mirror) sb.append("<a class=act href=\"").append(act("mirror")).append("\">").append(MIRROR_SVG)
            .append("<div><b>Mirror screen</b><span>Whatever the tablet shows, on the TV</span></div></a>")
        sb.append("</div>")

        m.lastPage?.let { p ->
            sb.append("<a class=cont href=\"").append(esc(p.url)).append("\"><span>Continue:</span><b>").append(esc(p.title))
                .append("</b><span>").append(esc(Urls.host(p.url))).append("</span></a>")
        }

        // The user's sites.
        sb.append("<section><div class=sh><h2>Your sites</h2><a class=btn href=\"").append(act("add")).append("\">+ Add</a></div>")
        if (m.sites.isEmpty()) {
            sb.append("<div class=empty><b>Add the sites you watch videos on.</b><br>Open a site and choose <b>").append(MENU_SVG).append(" → Add to start page</b>, ")
            sb.append("or tap <b>+ Add</b> and type its address.</div>")
        } else {
            sb.append("<div class=grid>")
            for ((i, s) in m.sites.withIndex()) {
                val host = Urls.host(s.url)
                sb.append("<div class=tw><a class=tile href=\"").append(esc(s.url)).append("\"><div class=av style=\"background:hsl(")
                    .append(hue(host)).append(" 48% 42%)\">").append(esc(letter(s.title, host))).append("</div><div class=tt><b>")
                    .append(esc(s.title)).append("</b><span>").append(esc(host.removePrefix("www."))).append("</span></div></a>")
                    .append("<a class=more href=\"").append(act("edit", i)).append("\" aria-label=\"Edit ").append(esc(s.title)).append("\">")
                    .append(MORE_SVG).append("</a></div>")
            }
            sb.append("</div>")
        }
        sb.append("</section>")

        // Recent casts.
        if (m.recent.isNotEmpty()) {
            sb.append("<section><div class=sh><h2>Recently on the TV</h2><a class=btn href=\"").append(act("clear-recent")).append("\">Clear</a></div><div class=cards>")
            for ((i, r) in m.recent.withIndex()) {
                val open = r.pageUrl ?: r.mediaUrl
                val host = Urls.host(open).removePrefix("www.")
                sb.append("<div class=card><a href=\"").append(esc(open)).append("\"><div class=thumb>").append(PLAY_SVG)
                if (r.thumb != null && Urls.isHttp(r.thumb)) {
                    sb.append("<img src=\"").append(esc(r.thumb)).append("\" alt=\"\" loading=lazy referrerpolicy=no-referrer onerror=\"this.remove()\">")
                }
                sb.append("</div><div class=cb><b>").append(esc(r.title)).append("</b><div class=meta>").append(esc(host)).append(" · ")
                    .append(ago(r.time, m.now))
                if (r.tv != null) sb.append(" · ").append(esc(r.tv))
                sb.append("</div></div></a><div class=ca><a class=btn href=\"").append(act("recast", i)).append("\">Cast again</a></div></div>")
            }
            sb.append("</div></section>")
        }

        // Test pages, out of the way.
        sb.append("<details><summary>Test pages: check that casting works</summary><div class=chips>")
        for (t in m.tests) sb.append("<a class=chip href=\"").append(esc(t.url)).append("\">").append(esc(t.title)).append("</a>")
        sb.append("<a class=chip href=\"").append(act("tvs")).append("\">TV test video</a></div></details>")

        sb.append("<p class=tip>From Brave, VLC or any app: <b>Share → Cast to TV</b> sends a page or a video straight to the TV.</p>")
        sb.append("</div></body></html>")
        return sb.toString()
    }

    /** Parses a `tabcast://action?i=..&k=..` link; null unless the token matches. */
    fun parseAction(url: String, token: String): Pair<String, Map<String, String>>? {
        if (!url.startsWith("tabcast://")) return null
        val rest = url.removePrefix("tabcast://")
        val action = rest.substringBefore('?').trimEnd('/')
        val q = rest.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        if (q["k"] != token) return null
        return action to q
    }
}
