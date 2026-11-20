// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.probe

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONObject

/** What the probe page (assets/probe.html) reports every 0.5 s through `probe.state(json)`. */
class PageState(private val report: Report, private val name: String) {
    @Volatile var json: JSONObject? = null
        private set
    @Volatile var updates = 0
        private set

    @JavascriptInterface fun state(s: String) {
        json = runCatching { JSONObject(s) }.getOrNull()
        updates++
    }

    private val video get() = json?.optJSONObject("video")
    val fps get() = json?.optDouble("fps") ?: 0.0
    val dpr get() = json?.optDouble("dpr") ?: 1.0
    val videoTime get() = video?.optDouble("t") ?: 0.0
    val playing get() = video?.let { !it.optBoolean("paused", true) && it.optDouble("t") > 0.5 } ?: false

    /** CSS rect of "video" or "clock" as x, y, w, h. */
    fun rect(what: String): DoubleArray? {
        val o = (if (what == "video") video else json?.optJSONObject(what)) ?: return null
        val r = doubleArrayOf(o.optDouble("x"), o.optDouble("y"), o.optDouble("w"), o.optDouble("h"))
        return r.takeIf { it[2] > 0 && it[3] > 0 }
    }

    fun summary(): Map<String, Any?> {
        val v = video
        return linkedMapOf("pageFps" to fps, "dpr" to dpr, "viewport" to json?.let { "${it.optInt("vw")}x${it.optInt("vh")}" },
            "videoTime" to v?.optDouble("t"), "paused" to v?.optBoolean("paused"), "muted" to v?.optBoolean("muted"),
            "readyState" to v?.optInt("ready"), "videoSize" to v?.let { "${it.optInt("w0")}x${it.optInt("h0")}" },
            "src" to v?.optString("src"), "audioBytes" to v?.optLong("audioBytes"),
            "errors" to json?.optJSONArray("errors")?.toString())
    }

    fun console(m: ConsoleMessage) {
        if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING)
            report.line("page[$name] ${m.messageLevel()}: ${m.message()}")
    }
}

/** A WebView showing the probe page, video allowed to start (and sound) without a tap, reporting into [state]. */
@SuppressLint("SetJavaScriptEnabled")
fun probePage(ctx: Context, state: PageState, label: String): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    addJavascriptInterface(state, "probe")
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage): Boolean { state.console(m); return true }
    }
    loadUrl("file:///android_asset/probe.html?label=" + Uri.encode(label))
}
