// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.web

import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

/**
 * v0.1.0 crashed on every site with a service worker, because the request hook (called on a Chromium
 * network thread) read WebView.getSettings(). This WebView throws like WebView 155 does when used off the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebCatcherThreadTest {
    private class StrictWebView(ctx: android.content.Context) : WebView(ctx) {
        override fun getSettings(): WebSettings {
            check(Looper.myLooper() == Looper.getMainLooper()) { "A WebView method was called on thread '${Thread.currentThread().name}'" }
            return super.getSettings()
        }
    }

    private fun req(url: String, headers: Map<String, String>) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = headers
    }

    @Test fun interceptOffTheMainThreadNeverTouchesTheWebView() {
        val web = StrictWebView(ApplicationProvider.getApplicationContext())
        val host = object : CatcherHost {
            override val pageAutoplay = true
            override fun adLevel(pageUrl: String) = dk.pennico.tabcast.core.adblock.FilterEngine.Level.STANDARD
            override val desktopMode = false
            override fun rules() = emptyList<dk.pennico.tabcast.core.SiteRule>()
        }
        val c = WebCatcher(ApplicationProvider.getApplicationContext(), web, MainScope(), host)
        c.setup()
        c.newPage("https://site.example/live")
        var error: Throwable? = null
        thread(name = "ThreadPoolForeg") {
            try {
                // A service-worker request without a User-Agent header (what reached getSettings() in v0.1.0).
                assertNull(c.intercept(req("https://cdn.example/live/master.m3u8", emptyMap()), serviceWorker = true))
                assertNull(c.intercept(req("https://cdn.example/live/other.m3u8", mapOf("Referer" to "https://site.example/live"))))
            } catch (t: Throwable) { error = t }
        }.join()
        assertNull(error)
        // The strict WebView really is strict (so the test above can't pass by accident).
        var strict: Throwable? = null
        thread { try { web.settings } catch (t: Throwable) { strict = t } }.join()
        assertEquals(IllegalStateException::class.java, strict?.javaClass)
        val got = c.session.ranked().map { it.url }
        assertEquals(setOf("https://cdn.example/live/master.m3u8", "https://cdn.example/live/other.m3u8"), got.toSet())
        assertEquals(c.userAgent, c.session.get("https://cdn.example/live/master.m3u8")?.headers?.get("User-Agent"))
    }
}
