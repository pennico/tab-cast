// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.os.Looper
import dk.pennico.tabcast.link.LinkClient
import dk.pennico.tabcast.link.Wire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tab Cast TV starts, waits, pairs (0.3.0), and does what the tablet's link and the web remote ask, over real sockets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvSmokeTest {
    @Before fun noUpdateChecks() { TvUpdates.autoCheck = false } // 0.5.1: no network from the tests

    private fun pump(cond: () -> Boolean): Boolean {
        repeat(150) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private class Http(val code: Int, val body: String, val cookie: String?)

    private fun http(port: Int, path: String, body: String? = null, cookie: String? = null, token: String? = null, xrw: Boolean = true): Http {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        cookie?.let { c.setRequestProperty("Cookie", it) }
        token?.let { c.setRequestProperty("X-TabCast-Token", it) }
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (xrw) c.setRequestProperty("X-Requested-With", "tabcast")
            c.outputStream.write(body.toByteArray())
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return Http(code, text, c.getHeaderField("Set-Cookie")?.substringBefore(';'))
    }

    @Test fun pairOpenAndClose() {
        TvActivity.port = 0
        WebRemote.PORT_SETTING = 0
        val c = Robolectric.buildActivity(TvActivity::class.java).setup()
        val a = c.get()
        assertEquals("idle", a.mode)

        // --- the tablet's link: unpaired, then the PIN
        val p = LinkClient.connect("127.0.0.1", a.linkPort!!, mapOf("name" to "test tablet"))
        assertTrue(p.hello["app"]!!.startsWith("Tab Cast TV"))
        assertEquals("false", p.hello["paired"])
        val status = CopyOnWriteArrayList<Map<String, String>>()
        val pairRes = CopyOnWriteArrayList<Map<String, String>>()
        p.onMessage = { m -> if (m.type == Wire.STATUS) status += m.kv(); if (m.type == Wire.PAIR_RESULT) pairRes += m.kv() }
        p.start()
        p.sendKv(Wire.OPEN, "url" to "https://archive.org/details/x")
        pump { false.also { Thread.sleep(5) } }
        assertEquals("idle", a.mode)                                  // ignored: not paired
        val pin = TvApp.pairing.currentPin
        assertNotNull("the TV shows a code for the unpaired tablet", pin)
        p.sendKv(Wire.PAIR, "pin" to pin, "name" to "test tablet")
        assertTrue(pump { pairRes.isNotEmpty() })
        assertEquals("true", pairRes.single()["ok"])
        val tabletToken = pairRes.single()["token"]!!
        assertNotNull(TvApp.pairing.check(tabletToken))

        p.send(Wire.SITES, "DR TV\thttps://www.dr.dk/drtv\nArte\thttps://www.arte.tv/".toByteArray())
        p.sendKv(Wire.OPEN, "url" to "https://archive.org/details/BigBuckBunny_124", "cookies" to "a=1; b=2")
        assertTrue(pump { a.mode == "page" })
        assertEquals("https://archive.org/details/BigBuckBunny_124", shadowOf(a.web).lastLoadedUrl)
        assertTrue(pump { status.any { it[Wire.Status.MODE] == "page" } })
        a.tap(0.5f, 0.5f, false)
        fun vol() = status.last()[Wire.Status.VOLUME]?.toDoubleOrNull() ?: -1.0
        p.sendKv(Wire.CMD, "c" to Wire.Cmd.VOLUME, "v" to 0.6)
        assertTrue("volume set: ${vol()}", pump { vol() in 0.45..0.75 })
        val before = vol()
        repeat(3) { p.sendKv(Wire.CMD, "c" to Wire.Cmd.VOLUME_BY, "v" to -1) }
        assertTrue("volume down: $before -> ${vol()}", pump { vol() in 0.0..(before - 0.05) })
        p.sendKv(Wire.CMD, "c" to Wire.Cmd.AD_LEVEL, "v" to "aggressive")
        assertTrue(pump { status.last()[Wire.Status.ADS_GLOBAL] == "aggressive" })
        p.sendKv(Wire.CMD, "c" to Wire.Cmd.AD_SITE, "v" to "off")
        assertTrue(pump { status.last()[Wire.Status.ADS_SITE] == "off" && status.last()[Wire.Status.ADS] == "off" })
        p.sendKv(Wire.CMD, "c" to Wire.Cmd.AD_SITE, "v" to "default")
        assertTrue(pump { status.last()[Wire.Status.ADS_SITE] == "" && status.last()[Wire.Status.ADS] == "aggressive" })

        // --- the web remote
        val w = a.webPort!!
        assertEquals(200, http(w, "/").code)
        assertEquals(401, http(w, "/api/state").code)
        assertEquals(401, http(w, "/api/open", """{"url":"https://example.com/"}""").code)
        TvApp.pairing.hidePin()
        val shown = http(w, "/api/pair/start", """{"name":"iPhone"}""")
        assertEquals(200, shown.code)
        val webPin = TvApp.pairing.currentPin ?: TvApp.pairing.showPin("test")!!
        assertEquals(false, JSONObject(http(w, "/api/pair", """{"pin":"000000","name":"iPhone"}""").body).optBoolean("ok"))
        val paired = http(w, "/api/pair", """{"pin":"$webPin","name":"iPhone","kind":"web"}""")
        assertTrue(paired.body, JSONObject(paired.body).getBoolean("ok"))
        val cookie = paired.cookie!!
        assertTrue(cookie.startsWith("tc="))
        assertEquals(200, http(w, "/api/state", cookie = cookie).code)
        assertTrue(pump { http(w, "/api/state", cookie = cookie).body.contains("\"mode\":\"page\"") })
        // 0.3.2 start view: the tablet's sites, the page just opened as recent, the test pages; add the TV's page
        val start = JSONObject(http(w, "/api/start", cookie = cookie).body)
        assertEquals(listOf("DR TV", "Arte"), (0 until start.getJSONArray("sites").length()).map { start.getJSONArray("sites").getJSONObject(it).getString("title") })
        assertEquals("https://archive.org/details/BigBuckBunny_124", start.getJSONArray("recent").getJSONObject(0).getString("url"))
        assertEquals(4, start.getJSONArray("tests").length())
        assertEquals(200, http(w, "/api/sites/add", "{}", cookie = cookie).code)
        val sites = JSONObject(http(w, "/api/start", cookie = cookie).body).getJSONArray("sites")
        assertEquals("https://archive.org/details/BigBuckBunny_124", sites.getJSONObject(sites.length() - 1).getString("url"))
        // CSRF: a cookie alone isn't enough for a POST
        assertEquals(401, http(w, "/api/cmd", """{"c":"close"}""", cookie = cookie, xrw = false).code)
        // a token for a Shortcut, used as the header
        val tok = JSONObject(http(w, "/api/token", """{"name":"Shortcut"}""", cookie = cookie).body).getString("token")
        assertEquals(200, http(w, "/api/open", """{"url":"https://www.ted.com/talks/x"}""", token = tok, xrw = false).code)
        assertTrue(pump { shadowOf(a.web).lastLoadedUrl == "https://www.ted.com/talks/x" })
        assertEquals(400, http(w, "/api/open", """{"url":"javascript:alert(1)"}""", token = tok).code)
        // remove the Shortcut's token: it stops working
        val id = TvApp.pairing.devices().first { it.kind == "shortcut" }.id
        assertEquals(200, http(w, "/api/devices/revoke", """{"id":"$id"}""", cookie = cookie).code)
        assertEquals(401, http(w, "/api/state", token = tok).code)

        p.sendKv(Wire.CMD, "c" to Wire.Cmd.CLOSE)
        assertTrue(pump { a.mode == "idle" })
        p.close()
        c.pause().stop().destroy()
    }

    /**
     * 0.6: the Mac's screen: HELLO_ACK lists the decodable codecs (Robolectric has no decoders, so the list may be
     * empty, but the key is there); VIDEO_CONFIG codec=hevc source=mac starts the mirror view with the Mac's note;
     * Annex-B frames go in without stopping it (no decoder here: they are only queued and dropped).
     */
    @Test fun macScreenInHevc() {
        TvActivity.port = 0
        WebRemote.PORT_SETTING = 0
        val c = Robolectric.buildActivity(TvActivity::class.java).setup()
        val a = c.get()
        TvApp.pairing.hidePin()
        val pin = TvApp.pairing.showPin("test")!!
        val p = LinkClient.connect("127.0.0.1", a.linkPort!!, mapOf("name" to "test Mac"))
        assertTrue("HELLO_ACK has codecs", p.hello.containsKey("codecs"))
        val pairRes = CopyOnWriteArrayList<Map<String, String>>()
        p.onMessage = { m -> if (m.type == Wire.PAIR_RESULT) pairRes += m.kv() }
        p.start()
        p.sendKv(Wire.PAIR, "pin" to pin, "name" to "test Mac")
        assertTrue(pump { pairRes.isNotEmpty() })
        p.sendKv(Wire.VIDEO_CONFIG, "codec" to "hevc", "w" to 1720, "h" to 1080, "source" to "mac")
        p.sendKv(Wire.AUDIO_CONFIG, "codec" to "pcm_s16le", "rate" to 48000, "channels" to 2)
        assertTrue(pump { a.mode == "mirror" })
        val texts = a.findViewById<android.view.View>(android.R.id.content).allTexts()
        assertTrue(texts.toString(), texts.any { "The Mac's screen" in it })
        val sc = byteArrayOf(0, 0, 0, 1)
        val key = sc + byteArrayOf(0x40, 0x01, 1) + sc + byteArrayOf(0x42, 0x01, 2) + sc + byteArrayOf(0x44, 0x01, 3) + sc + ByteArray(3000) { 0x26 }
        repeat(20) { i -> p.send(Wire.VIDEO, if (i % 10 == 0) key else sc + ByteArray(800) { 0x02 }, if (i % 10 == 0) Wire.FLAG_KEY else 0, i * 16_667L) }
        pump { false.also { Thread.sleep(5) } }
        assertEquals("mirror", a.mode)
        // An unknown codec falls back to VP8 (an older sender) instead of failing.
        p.sendKv(Wire.VIDEO_CONFIG, "codec" to "av9", "w" to 960, "h" to 720)
        pump { false.also { Thread.sleep(5) } }
        assertEquals("mirror", a.mode)
        p.sendKv(Wire.STATE, "mirror" to "off")
        assertTrue(pump { a.mode == "idle" })
        p.close()
        c.pause().stop().destroy()
    }

    /** 0.5.0: the mirror view starts on VIDEO_CONFIG, takes picture and sound frames, and ends on STATE mirror=off / CLOSE. */
    @Test fun mirrorView() {
        TvActivity.port = 0
        WebRemote.PORT_SETTING = 0
        val c = Robolectric.buildActivity(TvActivity::class.java).setup()
        val a = c.get()
        TvApp.pairing.hidePin()
        val pin = TvApp.pairing.showPin("test")!!
        val p = LinkClient.connect("127.0.0.1", a.linkPort!!, mapOf("name" to "test tablet"))
        assertEquals("1", p.hello["mirror"])
        val status = CopyOnWriteArrayList<Map<String, String>>()
        val pairRes = CopyOnWriteArrayList<Map<String, String>>()
        p.onMessage = { m -> if (m.type == Wire.STATUS) status += m.kv(); if (m.type == Wire.PAIR_RESULT) pairRes += m.kv() }
        p.start()
        p.sendKv(Wire.PAIR, "pin" to pin, "name" to "test tablet")
        assertTrue(pump { pairRes.isNotEmpty() })
        p.sendKv(Wire.VIDEO_CONFIG, "codec" to "vp8", "w" to 960, "h" to 720)
        p.sendKv(Wire.AUDIO_CONFIG, "codec" to "pcm_s16le", "rate" to 48000, "channels" to 2)
        assertTrue(pump { a.mode == "mirror" })
        assertTrue(pump { status.lastOrNull()?.get(Wire.Status.MODE) == "mirror" })
        // 0.5.1: the update keys are always there (empty: nothing checked).
        assertEquals("", status.last()[Wire.Status.UPDATE])
        assertEquals("", status.last()[Wire.Status.UPDATE_STATE])
        repeat(30) { i ->
            p.send(Wire.VIDEO, ByteArray(2000) { it.toByte() }, if (i % 10 == 0) Wire.FLAG_KEY else 0, i * 33_000L)
            p.send(Wire.AUDIO, ByteArray(3840), 0, i * 20_000L)
        }
        p.sendKv(Wire.CMD, "c" to Wire.Cmd.VOLUME, "v" to 0.5)
        pump { false.also { Thread.sleep(5) } }
        assertEquals("mirror", a.mode)
        p.sendKv(Wire.STATE, "mirror" to "off")
        assertTrue(pump { a.mode == "idle" })
        // 0.5.2: the tablet's software fallback restarts the picture at a new size; then it stops and says why.
        p.sendKv(Wire.VIDEO_CONFIG, "codec" to "vp8", "w" to 960, "h" to 720)
        assertTrue(pump { a.mode == "mirror" })
        p.sendKv(Wire.VIDEO_CONFIG, "codec" to "vp8", "w" to 640, "h" to 480)
        pump { false.also { Thread.sleep(5) } }
        assertEquals("mirror", a.mode)
        p.sendKv(Wire.STATE, "mirror" to "off", "why" to "The tablet's video chip is busy with another app")
        assertTrue(pump { a.mode == "idle" })
        val note = a.findViewById<android.view.View>(android.R.id.content).allTexts()
        assertTrue(note.toString(), note.any { "busy with another app" in it })
        // Again, ended from the TV side (Close).
        p.sendKv(Wire.VIDEO_CONFIG, "codec" to "vp8", "w" to 960, "h" to 720)
        assertTrue(pump { a.mode == "mirror" })
        p.sendKv(Wire.CMD, "c" to Wire.Cmd.CLOSE)
        assertTrue(pump { a.mode == "idle" })
        p.close()
        c.pause().stop().destroy()
    }

    /**
     * 0.4.0: a film from the tablet's FileServer: HELLO_ACK says files=1 with the free space, FILE starts the player
     * from the TV's rolling cache, STATUS reports kind=file and how much is on the TV, the cache fills up completely,
     * CLOSE ends it (cache kept), the next film deletes it.
     */
    @Test fun filmFromTheTablet() {
        TvActivity.port = 0
        WebRemote.PORT_SETTING = 0
        val c = Robolectric.buildActivity(TvActivity::class.java).setup()
        val a = c.get()
        TvApp.pairing.hidePin()
        val pin = TvApp.pairing.showPin("test")!!
        val p = LinkClient.connect("127.0.0.1", a.linkPort!!, mapOf("name" to "test tablet"))
        assertEquals("1", p.hello["files"])
        assertTrue((p.hello["free"]?.toLongOrNull() ?: 0) > 0)
        val status = CopyOnWriteArrayList<Map<String, String>>()
        val pairRes = CopyOnWriteArrayList<Map<String, String>>()
        p.onMessage = { m -> if (m.type == Wire.STATUS) status += m.kv(); if (m.type == Wire.PAIR_RESULT) pairRes += m.kv() }
        p.start()
        p.sendKv(Wire.PAIR, "pin" to pin, "name" to "test tablet")
        assertTrue(pump { pairRes.isNotEmpty() })

        val data = ByteArray(3_000_000) { (it * 31).toByte() }
        val server = dk.pennico.tabcast.link.FileServer()
        val port = server.start()
        val e = server.add("Test film.mkv", "video/x-matroska", object : dk.pennico.tabcast.link.FileServer.Source {
            override val size = data.size.toLong()
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len.toLong(), size - pos).toInt(); if (n <= 0) return -1
                System.arraycopy(data, pos.toInt(), buf, off, n); return n
            }
        })
        p.sendKv(Wire.FILE, "url" to server.url("127.0.0.1:$port", e), "name" to "Test film.mkv", "size" to data.size,
            "id" to "Test film.mkv|${data.size}", "mime" to "video/x-matroska", "rate" to 200)
        assertTrue(pump { a.mode == "player" && a.film != null })
        assertTrue(pump { status.lastOrNull()?.get(Wire.Status.KIND) == "file" })
        assertEquals("Test film.mkv", status.last()[Wire.Status.TITLE])
        val film = a.film!!
        assertTrue("the whole (small) film is copied to the TV", pump { film.cache.stats().complete })
        // pump() never moves the clock, so the TV's once-a-second status only comes from events; whether the (fake)
        // film's player error comes before or after the copy ends depends on timing. One second of clock sends it.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100))
        assertTrue(pump { status.last()[Wire.Status.COMPLETE] == "true" })
        assertTrue(e.bytesServed.get() >= data.size)
        // 0.5.1: subtitles chosen on the tablet while the film plays: the same FILE with a subtitle address; the TV keeps
        // its own copy (it plays on while the tablet sleeps) and goes on with them.
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHej Søren\n".toByteArray()
        val se = server.add("Test film.srt", "application/x-subrip", object : dk.pennico.tabcast.link.FileServer.Source {
            override val size = srt.size.toLong()
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len.toLong(), size - pos).toInt(); if (n <= 0) return -1
                System.arraycopy(srt, pos.toInt(), buf, off, n); return n
            }
        })
        p.sendKv(Wire.FILE, "url" to server.url("127.0.0.1:$port", e), "name" to "Test film.mkv", "size" to data.size,
            "id" to "Test film.mkv|${data.size}", "mime" to "video/x-matroska", "rate" to 200,
            "subs" to server.url("127.0.0.1:$port", se), "subsmime" to "application/x-subrip")
        assertTrue("the subtitles are on the TV", pump { film.subsFile?.readText() == String(srt) })
        assertTrue("the same film plays on", a.film === film && a.mode == "player")
        // Not a real video: the player fails and keeps retrying by itself, never giving up.
        p.sendKv(Wire.CMD, "c" to Wire.Cmd.CLOSE)
        assertTrue(pump { a.mode == "idle" && a.film == null })
        assertTrue("the cache stays after Close", film.cache.dir.exists())
        // Another film replaces it: the previous cache goes.
        val e2 = server.add("Other.mp4", "video/mp4", object : dk.pennico.tabcast.link.FileServer.Source {
            override val size = 1000L
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len.toLong(), size - pos).toInt(); if (n <= 0) return -1
                java.util.Arrays.fill(buf, off, off + n, 7); return n
            }
        })
        p.sendKv(Wire.FILE, "url" to server.url("127.0.0.1:$port", e2), "name" to "Other.mp4", "size" to 1000, "id" to "other")
        assertTrue(pump { a.film?.name == "Other.mp4" })
        assertTrue(pump { !film.cache.dir.exists() })
        p.sendKv(Wire.CMD, "c" to Wire.Cmd.CLOSE)
        assertTrue(pump { a.mode == "idle" })
        server.stop()
        p.close()
        c.pause().stop().destroy()
    }

    /** Every visible text under this view (0.5.2: the TV's note). */
    private fun android.view.View.allTexts(): List<String> = when {
        visibility != android.view.View.VISIBLE -> emptyList()
        this is android.widget.TextView -> listOf(text.toString())
        this is android.view.ViewGroup -> (0 until childCount).flatMap { getChildAt(it).allTexts() }
        else -> emptyList()
    }
}
