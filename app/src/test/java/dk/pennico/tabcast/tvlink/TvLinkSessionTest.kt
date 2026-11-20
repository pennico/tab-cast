// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tvlink

import dk.pennico.tabcast.link.LinkServer
import dk.pennico.tabcast.link.Peer
import dk.pennico.tabcast.link.Wire
import dk.pennico.tabcast.link.Wire.Cmd
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * during a session, playback commands are never lost silently. A fake Tab Cast TV that acknowledges
 * (or, in one test, swallows) commands; the link is cut under the tablet and must come back with the command.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvLinkSessionTest {
    private var tv: LinkServer? = null
    private val cmds = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile private var tablet: Peer? = null
    @Volatile private var ack = true

    private fun startTv(port: Int = 0): LinkServer {
        val s = LinkServer(port, "fake-tv", helloAck = { mapOf("name" to "Test TV", "acks" to 1) }) { p ->
            tablet = p
            p.onMessage = { m ->
                if (m.type == Wire.CMD) {
                    val kv = m.kv()
                    cmds += kv
                    if (ack) kv["id"]?.let { p.sendKv(Wire.ACK, "id" to it) }
                }
            }
        }
        s.start()
        tv = s
        return s
    }

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private fun connect() {
        TvLink.port = tv!!.localPort
        assertNull(runBlocking { TvLink.connect("127.0.0.1", "Test TV") })
        TvLink.startSession("test")
    }

    @After fun stop() {
        TvLink.disconnect()
        tv?.stop()
    }

    @Test fun commandIsAcknowledged() {
        startTv(); connect()
        TvLink.command(Cmd.PAUSE)
        // 15 s, not 5: on a busy build server this one failed twice in full runs (0.5.0) and never alone.
        assertTrue("PAUSE reached the TV (state ${TvLink.state.value})", waitFor(15_000) { cmds.any { it["c"] == Cmd.PAUSE } })
        assertTrue("acknowledged", waitFor(15_000) { TvLink.state.value.pending == 0 })
    }

    @Test fun linkDropsThenCommandArrivesAfterReconnect() {
        val first = startTv(); connect()
        val port = first.localPort
        // The TV app goes away (or the Wi-Fi drops the socket): the link closes under the tablet.
        first.stop()
        assertTrue(waitFor { !TvLink.connected })
        TvLink.command(Cmd.SEEK, 120.0)
        assertTrue("waits", TvLink.state.value.pending == 1)
        assertTrue("shows reconnecting", waitFor { TvLink.state.value.reconnecting })
        // The TV comes back on the same port: the session reconnects by itself and sends what waited.
        Thread.sleep(300)
        startTv(port)
        assertTrue("reconnected", waitFor(20_000) { TvLink.connected })
        assertTrue("command sent after reconnect", waitFor { cmds.any { it["c"] == Cmd.SEEK && it["v"] == "120.0" } })
        assertTrue(waitFor { TvLink.state.value.pending == 0 })
    }

    @Test fun unacknowledgedCommandReconnectsAndResends() {
        startTv(); connect()
        ack = false
        TvLink.command(Cmd.PLAY)
        assertTrue(waitFor { cmds.size == 1 })
        val firstTablet = tablet
        ack = true
        // No ACK in 4 s: the tablet treats the link as dead, reconnects and sends PLAY again.
        assertTrue("sent again on a new link", waitFor(15_000) { cmds.size >= 2 && tablet !== firstTablet })
        assertEquals(Cmd.PLAY, cmds.last()["c"])
        assertTrue(waitFor { TvLink.state.value.pending == 0 })
    }

    @Test fun newerPlayPauseReplacesWaitingOne() {
        startTv(); connect()
        tv!!.stop()
        assertTrue(waitFor { !TvLink.connected })
        TvLink.command(Cmd.PAUSE)
        TvLink.command(Cmd.PLAY)
        assertEquals(1, TvLink.state.value.pending)
    }
}
