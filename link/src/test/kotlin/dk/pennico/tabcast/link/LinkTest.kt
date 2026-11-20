// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WireTest {
    @Test fun roundTrip() {
        val b = ByteArrayOutputStream()
        val o = DataOutputStream(b)
        Wire.write(o, Wire.OPEN, payload = Wire.kv("url" to "https://a.example/v?x=1&y=2", "title" to "two\nlines", "none" to null))
        Wire.write(o, Wire.PREVIEW, Wire.FLAG_KEY, 1234567890123L, byteArrayOf(1, 2, 3))
        Wire.write(o, Wire.PING)
        val i = DataInputStream(ByteArrayInputStream(b.toByteArray()))
        val m1 = Wire.read(i)
        assertEquals(Wire.OPEN, m1.type)
        assertEquals(mapOf("url" to "https://a.example/v?x=1&y=2", "title" to "two lines"), m1.kv())
        val m2 = Wire.read(i)
        assertTrue(m2.key)
        assertEquals(1234567890123L, m2.pts)
        assertArrayEquals(byteArrayOf(1, 2, 3), m2.payload)
        assertEquals(0, Wire.read(i).payload.size)
        try { Wire.read(i); fail() } catch (e: java.io.EOFException) {}
    }

    @Test fun refusesHugeLengths() {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply { writeByte(3); writeByte(0); writeShort(0); writeInt(Wire.MAX_PAYLOAD + 1); writeLong(0) }
        try { Wire.read(DataInputStream(ByteArrayInputStream(b.toByteArray()))); fail() } catch (e: IOException) {}
    }

    @Test fun longs() {
        for (v in listOf(0L, 1L, -1L, Long.MAX_VALUE, 123456789012345L)) assertEquals(v, Wire.long(Wire.long(v)))
    }
}

class LinkTest {
    private val servers = ArrayList<LinkServer>()
    private val got = CopyOnWriteArrayList<Wire.Msg>()
    private val connected = CopyOnWriteArrayList<Peer>()

    private fun server(): LinkServer {
        val s = LinkServer(0, "tv", helloAck = { h -> mapOf("name" to "Test TV", "you" to h["name"]) }) { p ->
            connected += p
            p.onMessage = { m -> got += m; if (m.type == Wire.OPEN) p.sendKv(Wire.STATUS, Wire.Status.URL to m.kv()["url"], Wire.Status.MODE to "page") }
        }
        s.start()
        servers += s
        return s
    }

    @After fun stop() { servers.forEach { it.stop() } }

    private fun waitFor(ms: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    @Test fun openAndStatus() {
        val s = server()
        val p = LinkClient.connect("127.0.0.1", s.localPort, mapOf("name" to "Tab S2"))
        assertEquals("Test TV", p.hello["name"])
        assertEquals("Tab S2", p.hello["you"])
        val status = CountDownLatch(1)
        var url: String? = null
        p.onMessage = { m -> if (m.type == Wire.STATUS) { url = m.kv()[Wire.Status.URL]; status.countDown() } }
        p.start()
        p.sendKv(Wire.OPEN, "url" to "https://archive.org/details/x")
        assertTrue(status.await(3, TimeUnit.SECONDS))
        assertEquals("https://archive.org/details/x", url)
        assertTrue(waitFor { s.active != null && s.active!!.hello["name"] == "Tab S2" })
        p.ping()
        assertTrue(waitFor { p.rttMs >= 0 })
        p.close()
        assertTrue(waitFor { connected[0].closed })
    }

    /** Tab Cast's port scan (connect, close) and junk must not disturb the open link. */
    @Test fun scansAndJunkDontDisturbTheLink() {
        val s = server()
        val p = LinkClient.connect("127.0.0.1", s.localPort, mapOf("name" to "Tab S2"))
        p.start()
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", s.localPort), 1000) }       // scan
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", s.localPort), 1000); it.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray()) }
        Thread.sleep(200)
        assertEquals(1, connected.size)
        assertFalse(p.closed)
        p.sendKv(Wire.CMD, "c" to Wire.Cmd.PAUSE)
        assertTrue(waitFor { got.any { it.type == Wire.CMD } })
        try { LinkClient.connect("127.0.0.1", s.localPort + 0, mapOf("name" to "x"), timeoutMs = 500).close() } catch (e: Exception) { fail("second client: $e") }
    }

    @Test fun aNewTabletReplacesTheOld() {
        val s = server()
        val a = LinkClient.connect("127.0.0.1", s.localPort, mapOf("name" to "A")).also { it.start() }
        assertTrue(waitFor { s.active?.hello?.get("name") == "A" })
        val b = LinkClient.connect("127.0.0.1", s.localPort, mapOf("name" to "B")).also { it.start() }
        assertTrue(waitFor { s.active?.hello?.get("name") == "B" })
        assertTrue(waitFor { a.closed })
        assertFalse(b.closed)
    }

    @Test fun notTabCastTv() {
        val plain = java.net.ServerSocket(0)
        Thread { try { plain.accept().use { it.getOutputStream().write("HTTP/1.1 400 Bad\r\n\r\n".toByteArray()) } } catch (_: Exception) {} }.start()
        try { LinkClient.connect("127.0.0.1", plain.localPort, mapOf("name" to "x"), timeoutMs = 1000); fail() } catch (e: IOException) {}
        plain.close()
    }

    /** Pictures: a slow reader gets the newest, not a backlog. */
    @Test fun latestOnlyDropsOldPictures() {
        val s = server()
        val p = LinkClient.connect("127.0.0.1", s.localPort, mapOf("name" to "Tab S2"))
        // Not started: nothing is written yet, so queued pictures pile up and the older ones go.
        for (i in 1..5) p.sendLatest(Wire.PREVIEW, byteArrayOf(i.toByte()))
        assertEquals(1, p.pending(Wire.PREVIEW))
        assertEquals(4L, p.dropped.get())
        p.send(Wire.CMD, Wire.kv("c" to "play"))
        p.start()
        assertTrue(waitFor { got.count { it.type == Wire.PREVIEW } == 1 && got.any { it.type == Wire.CMD } })
        assertArrayEquals(byteArrayOf(5), got.first { it.type == Wire.PREVIEW }.payload)
        // control goes before pictures
        assertEquals(Wire.CMD, got.first { it.type == Wire.CMD || it.type == Wire.PREVIEW }.type)
    }

    @Test fun closedPeerRefusesToSend() {
        val s = server()
        val p = LinkClient.connect("127.0.0.1", s.localPort, mapOf("name" to "Tab S2")).also { it.start() }
        var why: Throwable? = Exception("not called")
        p.onClosed = { why = it }
        p.close()
        assertNull(why)
        assertFalse(p.send(Wire.PING))
        assertNotNull(s)
    }
}
