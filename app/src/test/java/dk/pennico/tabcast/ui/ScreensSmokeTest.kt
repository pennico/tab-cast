// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.cast.Device
import dk.pennico.tabcast.cast.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Opens every screen on the JVM (Robolectric, Android 14 framework) to catch crashes on first launch: theme/attribute
 * problems, null views, bad intents. No emulator is available on the build server, and the tablet is a round trip away.
 * WebView and the native casting/yt-dlp libraries are not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TabCastApp::class)
class ScreensSmokeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<TabCastApp>()

    @Test fun browserStartsWithoutTv() {
        val a = Robolectric.buildActivity(BrowserActivity::class.java).setup().get()
        assertFalse(a.isFinishing)
    }

    @Test fun browserOpensUrlFromIntent() {
        val i = Intent(ctx, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, "https://example.com/")
        val a = Robolectric.buildActivity(BrowserActivity::class.java, i).setup().get()
        a.openSheet()
    }

    @Test fun devicesSettingsRulesLogRemote() {
        TabCastApp.devices.add(Device("TV", Protocol.FCAST, "192.0.2.10"))
        TabCastApp.prefs.defaultDevice = TabCastApp.devices.all().first().key
        assertEquals("192.0.2.10", CastFlow.defaultDevice()?.host)
        for (c in listOf(DevicesActivity::class.java, SettingsActivity::class.java, RulesActivity::class.java,
            LogActivity::class.java, RemoteActivity::class.java, TvRemoteActivity::class.java, FilesActivity::class.java, MirrorActivity::class.java)) {
            Robolectric.buildActivity(c).setup().get()
        }
    }

    @Test fun shareWithoutLinkAndOpenAlias() {
        val none = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "no link")
            .setClass(ctx, ShareActivity::class.java)
        Robolectric.buildActivity(ShareActivity::class.java, none).setup().get()
        val open = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "see https://example.com/x")
            .setClassName(ctx, "dk.pennico.tabcast.OpenInBrowser")
        val a = Robolectric.buildActivity(ShareActivity::class.java, open).setup().get()
        assert(a.isFinishing)
        // "Open on TV" with no TV saved yet: sends the user to TV setup, doesn't crash.
        val tv = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/x")
            .setClassName(ctx, "dk.pennico.tabcast.OpenOnTv")
        val t = Robolectric.buildActivity(ShareActivity::class.java, tv).setup().get()
        assertEquals(DevicesActivity::class.java.name, shadowOf(t).nextStartedActivity?.component?.className)
    }

    /** v0.1.1: a second share while the first is still on screen (or something plays) must be handled, not dropped. */
    @Test fun secondShareReachesTheOpenDialog() {
        val info = ctx.packageManager.getActivityInfo(android.content.ComponentName(ctx, ShareActivity::class.java), 0)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE, info.launchMode)
        fun share(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .setClass(ctx, ShareActivity::class.java)
        val c = Robolectric.buildActivity(ShareActivity::class.java, share("https://example.com/a.mp4")).setup()
        val a = c.get()
        assertEquals("https://example.com/a.mp4", a.url)
        // No TV saved yet: the direct link leads to the TV setup screen.
        assertEquals(DevicesActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        c.newIntent(share("look: https://example.com/b.mp4"))
        assertEquals("https://example.com/b.mp4", a.url)
        assertEquals(DevicesActivity::class.java.name, shadowOf(a).nextStartedActivity?.component?.className)
        val open = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/page")
            .setClassName(ctx, "dk.pennico.tabcast.OpenInBrowser")
        c.newIntent(open)
        val started = shadowOf(a).nextStartedActivity
        assertEquals(BrowserActivity::class.java.name, started?.component?.className)
        assertEquals("https://example.com/page", started?.getStringExtra(BrowserActivity.EXTRA_URL))
        assert(a.isFinishing)
    }

    @Test fun rulesAndBookmarksDefaults() {
        val p = TabCastApp.prefs
        assert(p.rules().isNotEmpty())
        assertEquals(0, p.bookmarks.size) // the user's sites; the test pages are separate since v0.1.1
        assertEquals(4, dk.pennico.tabcast.Prefs.TEST_PAGES.size)
        p.bookmarks = dk.pennico.tabcast.Prefs.TEST_PAGES.take(1) + dk.pennico.tabcast.Bookmark("Mine", "https://mine.example/")
        assertEquals(listOf("https://mine.example/"), p.bookmarks.map { it.url }) // old saved test pages are dropped
        p.addRecent(dk.pennico.tabcast.core.StartPage.Recent("A", "https://p.example/1", "https://m.example/1.m3u8", null, 1, "TV"))
        p.addRecent(dk.pennico.tabcast.core.StartPage.Recent("A again", "https://p.example/1", "https://m.example/2.m3u8", null, 2, "TV"))
        assertEquals(listOf("A again"), p.recent.map { it.title })
        // The start page renders in the browser.
        val b = Robolectric.buildActivity(BrowserActivity::class.java).setup().get()
        assertFalse(b.isFinishing)
    }
}
