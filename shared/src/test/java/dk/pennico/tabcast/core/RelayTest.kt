// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/** Relay + prober against a local fake CDN that insists on Referer and a cookie, like real sites do. */
class RelayTest {
    private lateinit var cdn: FakeHttp
    private lateinit var relay: RelayServer
    private var cdnPort = 0
    private val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val video = ByteArray(300_000) { (it % 251).toByte() }

    @Before fun setUp() {
        cdn = FakeHttp { method, path, h ->
            seen += "$method $path ref=${h["referer"]} cookie=${h["cookie"]} range=${h["range"]}"
            val okRef = h["referer"] == "https://site.example/watch"
            val okCookie = h["cookie"]?.contains("session=42") == true
            when {
                !okRef || !okCookie -> FakeHttp.Resp(403, "text/plain", "forbidden".toByteArray())
                path.startsWith("/redir/") -> FakeHttp.Resp(302, "text/plain", ByteArray(0), mapOf("Location" to "/v/" + path.removePrefix("/redir/")))
                path == "/v/master.m3u8" -> FakeHttp.Resp(200, "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\nhi/index.m3u8?t=1\n".toByteArray())
                path == "/v/hi/index.m3u8?t=1" -> FakeHttp.Resp(200, "application/octet-stream",
                    "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/1\"\n#EXTINF:10,\nseg0.ts\n#EXTINF:10,\n/v/hi/seg1.ts\n#EXT-X-ENDLIST\n".toByteArray())
                path == "/v/stream.mpd" -> FakeHttp.Resp(200, "application/dash+xml",
                    "<MPD><BaseURL>/v/dash/</BaseURL><Period><SegmentTemplate media=\"\$Number\$.m4s\"/></Period></MPD>".toByteArray())
                path == "/v/movie.mp4" -> {
                    val range = h["range"]
                    if (range != null) {
                        val (a, b) = range.removePrefix("bytes=").split('-').let { it[0].toInt() to (it.getOrNull(1)?.toIntOrNull() ?: (video.size - 1)) }
                        FakeHttp.Resp(206, "video/mp4", video.copyOfRange(a, b + 1), mapOf("Content-Range" to "bytes $a-$b/${video.size}", "Accept-Ranges" to "bytes"))
                    } else FakeHttp.Resp(200, "video/mp4", video, mapOf("Accept-Ranges" to "bytes"))
                }
                else -> FakeHttp.Resp(404, "text/plain", "nope".toByteArray())
            }
        }
        cdnPort = cdn.port
        relay = RelayServer()
        relay.start(0)
    }

    @After fun tearDown() { relay.stop(); cdn.close() }

    private val base get() = "http://127.0.0.1:$cdnPort"
    private fun session() = relay.open(mapOf("Referer" to "https://site.example/watch", "User-Agent" to "UA/1")) { url ->
        if (url.startsWith(base)) "session=42" else null
    }

    private fun get(url: String, range: String? = null): Triple<Int, Map<String, String>, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        val h = c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.readBytes() ?: ByteArray(0)
        return Triple(code, h, body)
    }

    @Test fun hlsChainThroughRelay() {
        val s = session()
        val hp = "127.0.0.1:${relay.port}"
        val (code, h, body) = get(relay.url(hp, s, "$base/v/master.m3u8"))
        assertEquals(200, code)
        assertEquals("*", h["Access-Control-Allow-Origin"])
        val master = String(body)
        val variant = master.lines().first { it.startsWith("http") }
        assertEquals("http://$hp/x/${s.id}/http/127.0.0.1:$cdnPort/v/hi/index.m3u8?t=1", variant)
        val (c2, _, b2) = get(variant)
        assertEquals(200, c2)
        val media = String(b2)
        assertTrue(media, media.contains("URI=\"http://$hp/x/${s.id}/http/127.0.0.1:$cdnPort/keys/1\""))
        assertTrue(media.contains("http://$hp/x/${s.id}/http/127.0.0.1:$cdnPort/v/hi/seg0.ts"))
        assertTrue(media.contains("http://$hp/x/${s.id}/http/127.0.0.1:$cdnPort/v/hi/seg1.ts"))
    }

    /** v0.1.2: FCast with quality limits: only the (filtered) master goes through the tablet. */
    @Test fun filteredMasterWithDirectVariants() {
        val s = relay.open(mapOf("Referer" to "https://site.example/watch"),
            masterFilter = { t -> t.replace("hi/index.m3u8?t=1", "hi/index.m3u8?t=filtered") }, onlyManifest = true) { url ->
            if (url.startsWith(base)) "session=42" else null
        }
        val hp = "127.0.0.1:${relay.port}"
        val (code, _, body) = get(relay.url(hp, s, "$base/v/master.m3u8"))
        assertEquals(200, code)
        assertEquals("$base/v/hi/index.m3u8?t=filtered", String(body).lines().first { it.startsWith("http") })
        assertEquals(1, s.hits.get())
    }

    @Test fun rangeAndKeepAlive() {
        val s = session()
        val u = relay.url("127.0.0.1:${relay.port}", s, "$base/v/movie.mp4")
        val (code, h, body) = get(u, "bytes=1000-1999")
        assertEquals(206, code)
        assertEquals("bytes 1000-1999/${video.size}", h["Content-Range"])
        assertEquals(1000, body.size)
        assertEquals(video[1000], body[0])
        // Two requests on one socket (keep-alive) both answered.
        Socket("127.0.0.1", relay.port).use { sock ->
            val path = u.substringAfter(":${relay.port}")
            val req = "GET $path HTTP/1.1\r\nHost: 127.0.0.1:${relay.port}\r\nRange: bytes=0-9\r\n\r\n"
            val last = "GET $path HTTP/1.1\r\nHost: 127.0.0.1:${relay.port}\r\nRange: bytes=0-9\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().write((req + last).toByteArray())
            sock.soTimeout = 5000
            val text = String(sock.getInputStream().readBytes(), Charsets.ISO_8859_1)
            assertEquals(2, Regex("HTTP/1.1 206").findAll(text).count())
        }
    }

    @Test fun fullFileAndHead() {
        val s = session()
        val u = relay.url("127.0.0.1:${relay.port}", s, "$base/v/movie.mp4")
        val (code, _, body) = get(u)
        assertEquals(200, code)
        assertTrue(body.contentEquals(video))
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        assertEquals(200, c.responseCode)
        assertEquals(video.size.toString(), c.getHeaderField("Content-Length"))
    }

    @Test fun dashRedirectThenRootRelativeBase() {
        val s = session()
        val hp = "127.0.0.1:${relay.port}"
        val (code, _, body) = get(relay.url(hp, s, "$base/redir/stream.mpd"))
        assertEquals(200, code)
        val mpd = String(body)
        assertTrue(mpd, mpd.contains("<BaseURL>http://$hp/x/${s.id}/http/127.0.0.1:$cdnPort/v/dash/</BaseURL>"))
    }

    @Test fun rejectsUnknownSessionAndCors() {
        val (code, _, _) = get("http://127.0.0.1:${relay.port}/x/nosuchsession/http/127.0.0.1:$cdnPort/v/movie.mp4")
        assertEquals(404, code)
        val c = URL("http://127.0.0.1:${relay.port}/x/a/http/h/p").openConnection() as HttpURLConnection
        c.requestMethod = "OPTIONS"
        assertEquals(204, c.responseCode)
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
    }

    @Test fun withoutContextTheCdnRefuses() {
        val s = relay.open(emptyMap())
        val (code, _, _) = get(relay.url("127.0.0.1:${relay.port}", s, "$base/v/movie.mp4"))
        assertEquals(403, code)
    }

    @Test fun prober() {
        val hdr = mapOf("Referer" to "https://site.example/watch", "Cookie" to "session=42")
        val m = Prober.probe("$base/v/master.m3u8", Kind.HLS, hdr)
        assertTrue(m.isMaster); assertEquals(720, m.maxHeight); assertEquals(20.0, m.durationSec!!, 0.0); assertEquals(false, m.live)
        val f = Prober.probe("$base/v/movie.mp4", Kind.MP4, hdr)
        assertEquals(video.size.toLong(), f.sizeBytes)
        val u = Prober.probe("$base/redir/movie.mp4", Kind.UNKNOWN, hdr)
        assertEquals(Kind.MP4, u.kind) // no ftyp box in the test bytes; the Content-Type decides
        val denied = Prober.probe("$base/v/movie.mp4", Kind.MP4, emptyMap())
        assertEquals(403, denied.httpStatus)
    }
}
