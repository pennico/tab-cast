// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class PairingTest {
    private class Mem : Pairing.Store {
        var saved: List<Pairing.Device> = emptyList()
        override fun load() = saved
        override fun save(devices: List<Pairing.Device>) { saved = devices }
    }

    private var now = 1_000_000L
    private val store = Mem()
    private val shown = CopyOnWriteArrayList<String?>()
    private val p = Pairing(store, { now }).also { it.onPin = { pin, _ -> shown += pin } }

    @Test fun pinGivesATokenThatIsChecked() {
        val pin = p.showPin("Pair a device")!!
        assertEquals(6, pin.length)
        assertEquals(pin, shown.last())
        val r = p.pair(pin.substring(0, 3) + " " + pin.substring(3), "iPhone", "web") as Pairing.Result.Ok
        assertNull(shown.last())                       // PIN hidden after success
        assertTrue(r.token.length >= 40)
        assertEquals("iPhone", p.check(r.token)?.name)
        assertNull(p.check(r.token + "x"))
        assertNull(p.check(null))
        assertFalse(store.saved.any { it.tokenHash == r.token })  // only the hash is stored
        assertEquals(Pairing.hash(r.token), store.saved.single().tokenHash)
        // PIN used up
        assertTrue(p.pair(pin, "again", "web") is Pairing.Result.NoPin)
        // revoke
        assertTrue(p.revoke(r.device.id))
        assertNull(p.check(r.token))
    }

    @Test fun wrongPinsLockPairing() {
        val pin = p.showPin("test")!!
        val wrong = if (pin == "123456") "654321" else "123456"
        for (left in 4 downTo 1) assertEquals(left, (p.pair(wrong, "x", "web") as Pairing.Result.Wrong).triesLeft)
        assertTrue(p.pair(wrong, "x", "web") is Pairing.Result.Locked)
        assertTrue(p.pair(pin, "x", "web") is Pairing.Result.Locked)   // the right one is too late now
        assertNull(p.showPin("again"))                                  // no new PIN while locked
        now += Pairing.LOCK_MS + 1
        val pin2 = p.showPin("again")!!
        assertTrue(p.pair(pin2, "x", "web") is Pairing.Result.Ok)
    }

    @Test fun pinExpiresAndDevicesCantFloodTheScreen() {
        val pin = p.showPin("a device at 1.2.3.4 wants to pair", fromDevice = true)!!
        assertEquals(pin, p.showPin("again", fromDevice = true))       // the same PIN while valid
        now += Pairing.PIN_MS + 1
        assertTrue(p.pair(pin, "x", "web") is Pairing.Result.NoPin)
        assertNotNull(p.showPin("knock", fromDevice = true))
        now += Pairing.PIN_MS + 1
        val n = shown.size
        assertNotNull(p.showPin("knock", fromDevice = true))           // > KNOCK_MS later: fine
        assertEquals(n + 1, shown.size)
    }

    @Test fun tokensSurviveARestart() {
        val r = p.pair(p.showPin("x")!!, "Tab S2", "tablet") as Pairing.Result.Ok
        val again = Pairing(store, { now })
        assertEquals("Tab S2", again.check(r.token)?.name)
        assertNotEquals(r.token, Pairing.hash(r.token))
    }
}

/** The link with pairing: an unpaired tablet can't control the TV or push the paired one out; a PIN promotes it. */
class LinkPairingTest {
    private val servers = ArrayList<LinkServer>()
    @After fun stop() = servers.forEach { it.stop() }

    @Test fun unpairedThenPaired() {
        val store = object : Pairing.Store { var s = emptyList<Pairing.Device>(); override fun load() = s; override fun save(devices: List<Pairing.Device>) { s = devices } }
        val pairing = Pairing(store)
        val cmds = CopyOnWriteArrayList<String>()
        lateinit var server: LinkServer
        server = LinkServer(0, "tv", helloAck = { mapOf("name" to "TV") }, isAuthorized = { pairing.check(it["token"]) != null }) { p ->
            if (!p.authorized) pairing.showPin("link", fromDevice = true)
            p.onMessage = { m ->
                when {
                    m.type == Wire.PAIR && !p.authorized -> when (val r = pairing.pair(m.kv()["pin"] ?: "", m.kv()["name"] ?: "", "tablet")) {
                        is Pairing.Result.Ok -> { server.promote(p); p.sendKv(Wire.PAIR_RESULT, "ok" to true, "token" to r.token) }
                        else -> p.sendKv(Wire.PAIR_RESULT, "ok" to false)
                    }
                    p.authorized && m.type == Wire.CMD -> cmds += m.kv()["c"] ?: ""
                }
            }
        }
        server.start(); servers += server
        val paired = Pairing.Result.Ok::class
        // A paired tablet is active
        val okToken = (pairing.pair(pairing.showPin("setup")!!, "old tablet", "tablet") as Pairing.Result.Ok).token
        val a = LinkClient.connect("127.0.0.1", server.localPort, mapOf("name" to "A", "token" to okToken))
        assertEquals("true", a.hello["paired"])
        a.start()
        // An unpaired one connects: told so, can't command, doesn't replace A
        val b = LinkClient.connect("127.0.0.1", server.localPort, mapOf("name" to "B"))
        assertEquals("false", b.hello["paired"])
        val results = CopyOnWriteArrayList<Map<String, String>>()
        b.onMessage = { m -> if (m.type == Wire.PAIR_RESULT) results += m.kv() }
        b.start()
        b.sendKv(Wire.CMD, "c" to "close")
        Thread.sleep(200)
        assertTrue(cmds.isEmpty())
        assertEquals("A", server.active?.hello?.get("name"))
        assertFalse(a.closed)
        // B's knock came < 10 s after the last PIN, so the TV showed none (rate limit); the user uses "Pair a device".
        assertNull(pairing.currentPin)
        pairing.showPin("Pair a device")
        b.sendKv(Wire.PAIR, "pin" to pairing.currentPin, "name" to "B")
        val end = System.currentTimeMillis() + 3000
        while (results.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals("true", results.single()["ok"])
        assertNotNull(pairing.check(results.single()["token"]))
        assertEquals("B", server.active?.hello?.get("name"))
        b.sendKv(Wire.CMD, "c" to "pause")
        while (cmds.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(listOf("pause"), cmds)
        assertNotNull(paired)
    }
}

class MiniHttpTest {
    private var http: MiniHttp? = null
    @After fun stop() { http?.stop() }

    @Test fun getPostKeepAliveAndLimits() {
        val h = MiniHttp(0, { r ->
            when (r.path) {
                "/hello" -> MiniHttp.Resp.text(200, "hi ${r.query["name"]} ${r.cookie("tc")}")
                "/echo" -> if (r.method == "POST") MiniHttp.Resp.json(200, r.text()) else MiniHttp.Resp.text(405, "no")
                else -> MiniHttp.Resp.text(404, "not found")
            }
        })
        h.start(); http = h
        val base = "http://127.0.0.1:${h.localPort}"
        val c1 = URL("$base/hello?name=a%20b").openConnection() as HttpURLConnection
        c1.setRequestProperty("Cookie", "x=1; tc=tok")
        assertEquals("hi a b tok", c1.inputStream.bufferedReader().readText())
        val c2 = URL("$base/echo").openConnection() as HttpURLConnection
        c2.requestMethod = "POST"; c2.doOutput = true
        c2.outputStream.write("""{"url":"https://x"}""".toByteArray())
        assertEquals(200, c2.responseCode)
        assertEquals("""{"url":"https://x"}""", c2.inputStream.bufferedReader().readText())
        assertEquals(404, (URL("$base/nope").openConnection() as HttpURLConnection).responseCode)
        // two requests on one connection
        Socket("127.0.0.1", h.localPort).use { s ->
            val o = s.getOutputStream()
            o.write("GET /hello?name=1 HTTP/1.1\r\nHost: x\r\n\r\nGET /hello?name=2 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            val all = DataInputStream(s.getInputStream()).readBytes().toString(Charsets.UTF_8)
            assertTrue(all, all.contains("hi 1 null") && all.contains("hi 2 null"))
        }
        // too big a body: the connection is dropped, the server keeps serving
        Socket("127.0.0.1", h.localPort).use { s ->
            s.getOutputStream().write("POST /echo HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n".toByteArray())
            assertEquals(-1, s.getInputStream().read())
        }
        assertEquals(200, (URL("$base/hello").openConnection() as HttpURLConnection).responseCode)
    }
}
