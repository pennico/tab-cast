// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import dk.pennico.tabcast.link.LinkServer
import dk.pennico.tabcast.link.Peer
import dk.pennico.tabcast.link.Wire
import dk.pennico.tabcast.link.Wire.Cmd
import dk.pennico.tabcast.link.Wire.Status
import dk.pennico.tabcast.tvlink.TvLink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * the TV remote's controls must answer in landscape and portrait on the Tab S2 (1024x768 dp).
 * Taps go through the window's real touch dispatch (so a view lying on top, or a control below the visible area,
 * fails the test), to a fake Tab Cast TV over a socket.
 */
@RunWith(RobolectricTestRunner::class)
class TvRemoteTouchTest {
    private lateinit var tv: LinkServer
    private val cmds = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile private var tablet: Peer? = null

    @Before fun fakeTv() {
        tv = LinkServer(0, "fake-tv", helloAck = { mapOf("name" to "Test TV", "screen" to "1920x1080") }) { p ->
            tablet = p
            p.onMessage = { m -> if (m.type == Wire.CMD) cmds += m.kv() }
        }
        tv.start()
        TvLink.port = tv.localPort
        assertNull(runBlocking { TvLink.connect("127.0.0.1", "Test TV") })
    }

    @After fun stop() {
        TvLink.disconnect()
        tv.stop()
    }

    private fun pump(cond: () -> Boolean = { false }): Boolean {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(15)
        }
        return cond()
    }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    /** Taps the middle of [v] (or [fx] across it) through the activity's window, as a finger would. */
    private fun tap(a: TvRemoteActivity, v: View, fx: Float = 0.5f) {
        val loc = IntArray(2).also { v.getLocationInWindow(it) }
        val x = loc[0] + v.width * fx
        val y = loc[1] + v.height / 2f
        // The device has a status bar (24 dp) and, in landscape, a navigation bar (48 dp) the test window doesn't have.
        val usable = a.window.decorView.height - (72 * a.resources.displayMetrics.density)
        assertTrue("${(v as? TextView)?.text ?: v.contentDescription} is below the visible area ($y > $usable)", y < usable)
        val t = SystemClock.uptimeMillis()
        a.window.decorView.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        a.window.decorView.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        pump()
    }

    private fun check(qualifiers: String) {
        org.robolectric.RuntimeEnvironment.setQualifiers(qualifiers)
        val c = Robolectric.buildActivity(TvRemoteActivity::class.java).setup()
        val a = c.get()
        assertTrue(pump { tablet != null })
        tablet!!.sendKv(Wire.STATUS, Status.MODE to "player", Status.PLAYING to true, Status.POS to 10.0, Status.DUR to 600.0)
        assertTrue(pump { TvLink.state.value.dur == 600.0 })
        pump()
        val views = all(a.window.decorView)
        if (System.getenv("TV_REMOTE_DEBUG") != null) for (v in views) {
            val l = IntArray(2).also { v.getLocationInWindow(it) }
            println("GEOM $qualifiers ${v.javaClass.simpleName} ${(v as? TextView)?.text?.take(20) ?: v.contentDescription ?: ""} at ${l[0]},${l[1]} ${v.width}x${v.height}")
        }
        fun byDesc(d: String) = views.first { it.contentDescription == d }
        fun byText(t: String) = views.first { (it as? TextView)?.text?.toString() == t }
        val expect = listOf(
            byDesc("Play/pause") to Cmd.PAUSE,          // 0.3.3: explicit (idempotent) play/pause, absolute seeks
            byDesc("Back 10 seconds") to Cmd.SEEK,
            byDesc("Forward 30 seconds") to Cmd.SEEK,
            byText("Volume +") to Cmd.VOLUME_BY,
            byText("Volume −") to Cmd.VOLUME_BY,
            byText("Play full screen") to Cmd.PLAY_NATIVE,
            byText("Back") to Cmd.BACK,
        )
        for ((v, cmd) in expect) {
            val n = cmds.size
            tap(a, v)
            assertTrue("$qualifiers: ${(v as? TextView)?.text ?: v.contentDescription} sent nothing", pump { cmds.size > n })
            assertEquals(cmd, cmds.last()["c"])
        }
        // The tablet's volume keys change the TV's volume while the remote is open.
        val k = cmds.size
        a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue("$qualifiers: volume key sent nothing", pump { cmds.size > k })
        assertEquals(Cmd.VOLUME_BY, cmds.last()["c"])
        assertEquals("1", cmds.last()["v"])
        val seek = views.first { it is SeekBar }
        val n = cmds.size
        tap(a, seek, 0.5f)
        assertTrue("$qualifiers: the seek bar sent nothing", pump { cmds.size > n })
        assertEquals(Cmd.SEEK, cmds.last()["c"])
        val to = cmds.last()["v"]!!.toDouble()
        assertTrue("seek to $to", to in 200.0..400.0)
        c.pause().stop().destroy()
    }

    /** 0.4.0: a film from the tablet: the film controls show (page ones hide), plain words, Stop works. */
    private fun film(qualifiers: String) {
        org.robolectric.RuntimeEnvironment.setQualifiers(qualifiers)
        val c = Robolectric.buildActivity(TvRemoteActivity::class.java).setup()
        val a = c.get()
        assertTrue(pump { tablet != null })
        tablet!!.sendKv(Wire.STATUS, Status.MODE to "player", Status.KIND to "file", Status.TITLE to "Big film.mkv",
            Status.PLAYING to true, Status.POS to 600.0, Status.DUR to 7200.0, Status.HELD_TO to 3000.0, Status.COMPLETE to false,
            Status.A_TRACKS to "English · 5.1|Italiano · stereo", Status.A_TRACK to 0, Status.S_TRACKS to "English", Status.S_TRACK to -1)
        assertTrue(pump { TvLink.state.value.film })
        pump()
        fun shown() = all(a.window.decorView).filter { it.isShown }
        fun byText(t: String) = shown().firstOrNull { (it as? TextView)?.text?.toString() == t }
        assertTrue(shown().any { (it as? TextView)?.text?.toString()?.startsWith("On the TV up to 50:00: the tablet can sleep for about 40 min") == true })
        assertNull("page buttons are hidden", byText("Play full screen"))
        assertTrue(byText("Sound…")!!.isEnabled)
        assertTrue(byText("Subtitles…")!!.isEnabled)
        val n = cmds.size
        tap(a, byText("Stop")!!)
        assertTrue(pump { cmds.size > n })
        assertEquals(Cmd.CLOSE, cmds.last()["c"])
        tablet!!.sendKv(Wire.STATUS, Status.MODE to "player", Status.KIND to "file", Status.TITLE to "Big film.mkv", Status.COMPLETE to true)
        assertTrue(pump { shown().any { (it as? TextView)?.text?.toString() == "The whole film is on the TV. The tablet can sleep now." } })
        c.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w1024dp-h768dp-land-xhdpi") fun filmLandscape() = film("w1024dp-h768dp-land-xhdpi")

    @Test @Config(sdk = [34], qualifiers = "w1024dp-h768dp-land-xhdpi") fun landscape() = check("w1024dp-h768dp-land-xhdpi")

    @Test @Config(sdk = [34], qualifiers = "w768dp-h1024dp-port-xhdpi") fun portrait() = check("w768dp-h1024dp-port-xhdpi")
}
