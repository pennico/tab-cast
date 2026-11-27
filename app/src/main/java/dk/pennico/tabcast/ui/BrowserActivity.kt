// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.Bookmark
import dk.pennico.tabcast.R
import dk.pennico.tabcast.core.StartPage
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.cast.CastManager
import dk.pennico.tabcast.cast.Play
import dk.pennico.tabcast.core.Classify
import dk.pennico.tabcast.core.Urls
import dk.pennico.tabcast.web.WebCatcher
import kotlinx.coroutines.launch

/** The browser: open a page, play the video, tap Cast. */
class BrowserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_YTDLP_FAILED = "ytdlp_failed"
        private const val KEY_RENDERER_GONE = "renderer_gone"
    }

    /** The page's renderer process is gone (see Client.onRenderProcessGone); the WebView must not be used. */
    private var webDead = false
    /** Per-render secret of the start page's tabcast:// links (no other page can trigger them). */
    private var startToken = ""
    /** Every token issued in this activity: a start page reached with Back still works. */
    private val startTokens = HashSet<String>()
    private var resumedOnce = false

    private lateinit var web: WebView
    private lateinit var catcher: WebCatcher
    private lateinit var urlBar: EditText
    private lateinit var progress: LinearProgressIndicator
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: android.widget.ImageButton
    private lateinit var fullscreen: FrameLayout
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    var sheet: CastSheet? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        buildUi()
        catcher = WebCatcher(this, web, lifecycleScope, dk.pennico.tabcast.web.TabletCatcherHost)
        catcher.setup()
        catcher.onChange = { refreshFab(); sheet?.refresh() }
        refreshFab()
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, _, _, mime, _ ->
            val kind = Classify.fromMime(mime) ?: Classify.fromUrl(url)
            if (kind != null) {
                catcher.addExternal(url, kind, "download", mapOf("Referer" to (web.url ?: url)), null, null, null, 40)
                openSheet()
            } else Toast.makeText(this, "Downloads aren't supported here", Toast.LENGTH_SHORT).show()
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> exitFullscreen()
                web.canGoBack() -> web.goBack()
                else -> finish()
            }
        }
        val rendererGone = savedInstanceState?.getBoolean(KEY_RENDERER_GONE) == true
        if (rendererGone) {
            // Android stopped the page's renderer (low memory); this is a fresh WebView. Reopen the last page.
            TabCastApp.prefs.lastUrl?.takeIf { Urls.isHttp(it) }?.let { go(it) } ?: home()
            Toast.makeText(this, "Android closed the page to free memory; it was reopened.", Toast.LENGTH_LONG).show()
        } else if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else if (!handleIntent(intent)) home()
        if (savedInstanceState == null) CastFlow.offerStopLeftover(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastManager.state.collect { s ->
                    mini.visibility = if (s.active) View.VISIBLE else View.GONE
                    miniTitle.text = buildString {
                        append(s.title ?: "Playing on TV")
                        append("  ·  ").append(s.device?.name ?: "")
                        if (s.playback == Play.BUFFERING) append("  ·  loading…")
                        if (s.error != null) append("  ·  ⚠ ").append(s.error)
                    }
                    miniPlay.setImageResource(if (s.playback == Play.PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
                }
            }
        }
        if (TabCastApp.devices.all().isEmpty()) {
            dialog("Set up your TV").setMessage("Tab Cast needs to know your TV once. It will scan the Wi-Fi for it (FCast Receiver or Chromecast).")
                .setPositiveButton("Set up") { _, _ -> startActivity(Intent(this, DevicesActivity::class.java)) }
                .setNegativeButton("Later", null).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?): Boolean {
        val url = i?.getStringExtra(EXTRA_URL) ?: i?.dataString?.takeIf { Urls.isHttp(it) }
            ?: i?.getStringExtra(Intent.EXTRA_TEXT)?.let { Urls.firstUrlIn(it) } ?: return false
        go(url)
        i?.getStringExtra(EXTRA_YTDLP_FAILED)?.let {
            Toast.makeText(this, "yt-dlp: $it\nPress play on the video, then tap Cast.", Toast.LENGTH_LONG).show()
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (webDead) outState.putBoolean(KEY_RENDERER_GONE, true) else web.saveState(outState)
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        if (!webDead) web.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce && !webDead && web.url == "about:blank" && startToken.isNotEmpty()) home() // fresh recents / TV state
        resumedOnce = true
    }

    private fun buildUi() {
        val bar = row {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(iconButton(R.drawable.ic_home, "Home") { home() })
            urlBar = EditText(this@BrowserActivity).apply {
                hint = "Address or search"
                isSingleLine = true
                textSize = 15f
                imeOptions = EditorInfo.IME_ACTION_GO
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                setSelectAllOnFocus(true)
                setOnEditorActionListener { _, a, ev ->
                    if (a == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                        go(toUrl(text.toString())); hideKeyboard(); true
                    } else false
                }
            }
            addView(urlBar, weight1())
            addView(iconButton(R.drawable.ic_refresh, "Reload") { web.reload() })
            lateinit var more: View
            more = iconButton(R.drawable.ic_more, "Menu") { menu(more) }
            addView(more)
        }
        progress = LinearProgressIndicator(this).apply { isIndeterminate = false; max = 100; visibility = View.GONE }
        miniTitle = text("", 14f).apply { maxLines = 2 }
        miniPlay = iconButton(R.drawable.ic_play, "Play/pause") { CastManager.togglePlay() }
        mini = row {
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            addView(iconButton(R.drawable.ic_cast, "Remote") { openRemote() })
            addView(miniTitle, weight1())
            addView(iconButton(R.drawable.ic_replay10, "Back 10 seconds") { CastManager.seekBy(-10.0) })
            addView(miniPlay)
            addView(iconButton(R.drawable.ic_stop, "Stop") { CastManager.stop() })
            setOnClickListener { openRemote() }
            visibility = View.GONE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, matchWrap())
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(mini, matchWrap())
        }
        fab = ExtendedFloatingActionButton(this).apply {
            text = "Cast"
            setIconResource(R.drawable.ic_cast)
            isAllCaps = false
            setOnClickListener { openSheet() }
        }
        fullscreen = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0xFF000000.toInt()) }
        val root = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(fab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(16), dp(16), dp(16), dp(72))
            })
            addView(fullscreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
    }

    private fun refreshFab() {
        val s = catcher.session
        val n = s.count()
        fab.text = if (n > 0) "Cast ($n)" else "Cast"
        val hot = n > 0 && s.ranked().firstOrNull()?.fromPlaying == true
        val yt = catcher.yt?.takeIf { it.running }
        if (yt != null) fab.text = if (n > 0) "Cast ($n) · yt-dlp…" else "Cast · yt-dlp…"
        fab.alpha = if (n > 0 || s.mseSeen || yt != null) 1f else 0.6f
        if (hot || yt != null) fab.extend() else if (n == 0) fab.shrink() else fab.extend()
    }

    fun openSheet() {
        if (sheet != null) return
        sheet = CastSheet(this, catcher).also { it.show() }
    }

    private fun openRemote() = startActivity(Intent(this, RemoteActivity::class.java))

    private fun toUrl(input: String): String {
        val t = input.trim()
        if (Urls.isHttp(t)) return t
        if (!t.contains(' ') && t.contains('.') && !t.endsWith('.')) return "https://$t"
        return "https://duckduckgo.com/?q=" + Uri.encode(t)
    }

    /** Load [url], applying a site rule's user agent first. */
    fun go(url: String) {
        applyRuleUa(url)
        web.loadUrl(url)
    }

    private fun applyRuleUa(url: String): Boolean {
        val ua = catcher.rulesFor(url).firstNotNullOfOrNull { it.userAgent }
        val want = when (ua) {
            null -> if (TabCastApp.prefs.desktopMode) catcher.desktopUa else catcher.mobileUa
            "desktop" -> catcher.desktopUa
            "mobile" -> catcher.mobileUa
            else -> ua
        }
        if (web.settings.userAgentString == want) return false
        web.settings.userAgentString = want
        catcher.userAgent = want
        TabCastApp.webUserAgent = want
        return true
    }

    private fun home() {
        if (webDead) return
        val p = TabCastApp.prefs
        startToken = java.util.UUID.randomUUID().toString()
        startTokens += startToken
        val st = CastManager.state.value
        val tv = CastFlow.defaultDevice()
        val last = p.lastUrl?.takeIf { Urls.isHttp(it) && p.bookmarks.none { b -> b.url == it } }
        val page = StartPage.html(StartPage.Model(
            token = startToken,
            sites = p.bookmarks.map { StartPage.Site(it.title, it.url) },
            tests = dk.pennico.tabcast.Prefs.TEST_PAGES.map { StartPage.Site(it.title.substringAfter(": "), it.url) },
            recent = p.recent.take(8),
            tv = tv?.label,
            nowPlaying = if (st.active) (st.title ?: "a video") else if (dk.pennico.tabcast.tvlink.TvLink.session)
                dk.pennico.tabcast.tvlink.TvLink.state.value.title?.takeIf { it.isNotBlank() } ?: "a page" else null,
            lastPage = last?.let { StartPage.Site(p.lastTitle?.takeIf { t -> t.isNotBlank() } ?: Urls.host(it), it) },
            mirror = true,
        ))
        catcher.newPage("about:blank")
        web.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
        urlBar.setText("")
    }

    /** A tabcast:// link on the start page (only with the current page's token). */
    private fun startAction(url: String): Boolean {
        val k = Regex("[?&]k=([^&]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val (action, q) = StartPage.parseAction(url, k?.takeIf { it in startTokens } ?: return false) ?: return false
        val p = TabCastApp.prefs
        val i = q["i"]?.toIntOrNull()
        when (action) {
            "go" -> q["q"]?.trim()?.takeIf { it.isNotEmpty() }?.let { go(toUrl(it)) }
            "add" -> addSite(null)
            "edit" -> p.bookmarks.getOrNull(i ?: -1)?.let { editSite(i!!, it) }
            "recast" -> p.recent.getOrNull(i ?: -1)?.let { r ->
                startActivity(Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, r.pageUrl ?: r.mediaUrl))
            }
            "clear-recent" -> dialog("Clear the list of recent casts?")
                .setPositiveButton("Clear") { _, _ -> p.recent = emptyList(); home() }.setNegativeButton("Cancel", null).show()
            "tvs" -> startActivity(Intent(this, DevicesActivity::class.java))
            "remote" -> if (dk.pennico.tabcast.tvlink.TvLink.session && !CastManager.state.value.active) TvFlow.remote(this) else openRemote()
            "files" -> startActivity(Intent(this, FilesActivity::class.java))
            "mirror" -> startActivity(Intent(this, MirrorActivity::class.java))
            else -> return false
        }
        return true
    }

    /** Add a site to the start page: [current] = the page shown now, or ask for an address. */
    private fun addSite(current: String?) {
        val name = EditText(this).apply { hint = "Name"; setText(if (current != null) web.title ?: Urls.host(current) else "") }
        val addr = EditText(this).apply {
            hint = "Address, e.g. raiplay.it"; setText(current ?: ""); isSingleLine = true
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        dialog("Add to start page").setView(column(20) { addView(name, matchWrap()); addView(addr, matchWrap()) })
            .setPositiveButton("Add") { _, _ ->
                val url = toUrl(addr.text.toString())
                if (!Urls.isHttp(url) || addr.text.isBlank()) return@setPositiveButton
                val title = name.text.toString().trim().ifEmpty { Urls.host(url).removePrefix("www.") }
                TabCastApp.prefs.bookmarks = TabCastApp.prefs.bookmarks.filter { it.url != url } + Bookmark(title, url)
                Toast.makeText(this, "Added to the start page", Toast.LENGTH_SHORT).show()
                if (web.url == "about:blank") home()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editSite(i: Int, b: Bookmark) {
        dialog(b.title).setItems(arrayOf("Rename", "Move to the front", "Remove")) { _, which ->
            val list = TabCastApp.prefs.bookmarks.toMutableList()
            if (i !in list.indices || list[i] != b) return@setItems
            when (which) {
                0 -> {
                    val input = EditText(this).apply { setText(b.title) }
                    dialog("Rename").setView(column(20) { addView(input, matchWrap()) }).setPositiveButton("Save") { _, _ ->
                        list[i] = b.copy(title = input.text.toString().trim().ifEmpty { b.title }); TabCastApp.prefs.bookmarks = list; home()
                    }.setNegativeButton("Cancel", null).show()
                }
                1 -> { list.removeAt(i); list.add(0, b); TabCastApp.prefs.bookmarks = list; home() }
                2 -> { list.removeAt(i); TabCastApp.prefs.bookmarks = list; home() }
            }
        }.show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private fun menu(anchor: View) {
        val p = PopupMenu(this, anchor)
        val m = p.menu
        m.add(0, 1, 0, "Start page")
        m.add(0, 2, 0, "Add this page to the start page")
        m.add(0, 3, 0, "Find the video with yt-dlp")
        m.add(0, 4, 0, "Desktop site").apply { isCheckable = true; isChecked = TabCastApp.prefs.desktopMode }
        m.add(0, 12, 0, "Ad blocking on this site…")
        m.add(0, 9, 0, "Open this page on the TV")
        m.add(0, 10, 0, "Open on the TV with my login")
        m.add(0, 11, 0, "TV browser remote")
        m.add(0, 5, 0, "TVs")
        m.add(0, 6, 0, "Remote")
        m.add(0, 7, 0, "Share page link")
        m.add(0, 8, 0, "Settings")
        m.add(0, 13, 0, "Check for updates")
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> home()
                2 -> web.url?.takeIf { u -> Urls.isHttp(u) }?.let { u -> addSite(u) }
                3 -> { openSheet() }
                4 -> {
                    TabCastApp.prefs.desktopMode = !TabCastApp.prefs.desktopMode
                    catcher.applyUa(TabCastApp.prefs.desktopMode); web.reload()
                }
                5 -> startActivity(Intent(this, DevicesActivity::class.java))
                6 -> openRemote()
                7 -> web.url?.takeIf { u -> Urls.isHttp(u) }?.let { u ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "Share"))
                }
                8 -> startActivity(Intent(this, SettingsActivity::class.java))
                9, 10 -> web.url?.takeIf { u -> Urls.isHttp(u) }?.let { u -> TvFlow.open(this, u, withLogin = it.itemId == 10) }
                    ?: Toast.makeText(this, "Open a page first", Toast.LENGTH_SHORT).show()
                11 -> TvFlow.remote(this)
                12 -> web.url?.takeIf { u -> Urls.isHttp(u) }?.let { u -> adsForSite(u) }
                13 -> startActivity(Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_UPDATES, true).putExtra(SettingsActivity.EXTRA_CHECK, true))
            }
            true
        }
        p.show()
    }

    /** The site's own ad-blocking level (or back to the one for all sites); the page reloads with it. */
    private fun adsForSite(url: String) {
        val ads = TabCastApp.ads
        val site = ads.siteOf(url)
        val levels = dk.pennico.tabcast.core.adblock.FilterEngine.Level.entries
        val options = listOf("As on all sites (${ads.global.label})") + levels.map { it.label }
        val cur = ads.site(url)?.let { levels.indexOf(it) + 1 } ?: 0
        dialog("Ad blocking on $site").setSingleChoiceItems(options.toTypedArray(), cur) { d, i ->
            ads.setSite(url, if (i == 0) null else levels[i - 1])
            AppLog.i("ad blocking on $site: ${if (i == 0) "default" else levels[i - 1].label} (was ${catcher.adSummary()})")
            catcher.refreshAdLevel()
            web.reload()
            d.dismiss()
        }.show()
    }

    private fun exitFullscreen() {
        customView?.let { fullscreen.removeView(it) }
        customView = null
        fullscreen.visibility = View.GONE
        customCb?.onCustomViewHidden()
        customCb = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = catcher.intercept(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith("tabcast:")) {
                if (request.isForMainFrame && !startAction(url)) AppLog.i("ignored a tabcast: link that isn't from the start page")
                return true
            }
            if (Urls.isHttp(url)) {
                if (request.isForMainFrame && catcher.blocksNavigation(url)) {
                    AppLog.i("blocked an ad page (filter lists): ${url.take(160)}")
                    Toast.makeText(this@BrowserActivity, "Blocked an ad page (${Urls.host(url)})", Toast.LENGTH_SHORT).show()
                    return true
                }
                if (request.isForMainFrame && applyRuleUa(url)) { view.loadUrl(url); return true }
                return false
            }
            if (url.startsWith("intent:")) {
                try {
                    val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    val fb = i.getStringExtra("browser_fallback_url")
                    if (fb != null && Urls.isHttp(fb)) { view.loadUrl(fb); return true }
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    startActivity(i)
                } catch (e: Exception) { AppLog.i("intent link not opened: ${e.message}") }
                return true
            }
            try { startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: Exception) {}
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            catcher.newPage(url)
            if (Urls.isHttp(url)) urlBar.setText(url)
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            progress.visibility = View.GONE
            if (Urls.isHttp(url)) {
                TabCastApp.prefs.lastUrl = url
                TabCastApp.prefs.lastTitle = view.title
                catcher.maybeAutoYt(url)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (Urls.isHttp(url) && !urlBar.hasFocus()) urlBar.setText(url)
            if (Urls.isHttp(url)) catcher.maybeAutoYt(url)
        }

        /**
         * Android killed the page's renderer (on 2 GB, typically low memory while yt-dlp runs) or it crashed.
         * Unhandled, WebView then kills the whole app, which is what happened in v0.1.0.
         */
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            AppLog.i("page renderer gone (${if (detail.didCrash()) "crashed" else "stopped by Android, low memory"}); reopening the browser")
            webDead = true
            (view.parent as? ViewGroup)?.removeView(view)
            try { view.destroy() } catch (_: Throwable) {}
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            progress.setProgressCompat(p, true)
            progress.visibility = if (p < 100) View.VISIBLE else View.GONE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView?.let { exitFullscreen() }
            customView = view
            customCb = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreen.visibility = View.VISIBLE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onHideCustomView() = exitFullscreen()
    }
}
