// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection helpers shared by the relay and the prober (manual redirects, so https->http works too). */
object Http {
    class Fetched(val conn: HttpURLConnection, val finalUrl: String, val code: Int)

    private val skip = setOf(
        "host", "content-length", "connection", "accept-encoding", "range", "te", "upgrade", "transfer-encoding",
        "keep-alive", "expect", "cookie",
    )

    fun open(
        url0: String,
        headers: Map<String, String>,
        cookies: (String) -> String? = { null },
        method: String = "GET",
        range: String? = null,
        connectTimeout: Int = 15_000,
        readTimeout: Int = 30_000,
    ): Fetched {
        var url = url0
        val staticCookie = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
        for (hop in 0 until 8) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            conn.requestMethod = method
            conn.useCaches = false
            for ((k, v) in headers) {
                val lk = k.lowercase()
                if (lk in skip || lk.startsWith("sec-fetch-") || lk.startsWith("proxy-") || lk.startsWith("if-") || lk.startsWith(":")) continue
                conn.setRequestProperty(k, v)
            }
            val cookie = cookies(url)?.takeIf { it.isNotBlank() } ?: staticCookie
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            if (range != null) conn.setRequestProperty("Range", range)
            conn.setRequestProperty("Accept-Encoding", "identity")
            // The JDK's default Accept ("text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2") reads as a bot.
            if (headers.keys.none { it.equals("Accept", true) }) conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399 && code != 304) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw IOException("redirect without Location")
                url = Urls.resolve(url, loc)
                continue
            }
            return Fetched(conn, url, code)
        }
        throw IOException("too many redirects")
    }

    fun body(f: Fetched): InputStream? = try {
        if (f.code >= 400) f.conn.errorStream else f.conn.inputStream
    } catch (e: IOException) { f.conn.errorStream }

    fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val bo = ByteArrayOutputStream()
        val buf = ByteArray(32768)
        while (bo.size() < limit) {
            val r = input.read(buf, 0, minOf(buf.size, limit - bo.size()))
            if (r < 0) break
            bo.write(buf, 0, r)
        }
        return bo.toByteArray()
    }
}

/** Fetches a little of a candidate to learn what it is (master playlist? 1080p? 42 min? DRM? dead link?). */
object Prober {
    private const val TEXT_LIMIT = 2 shl 20

    fun probe(url: String, kind: Kind, headers: Map<String, String>, cookies: (String) -> String? = { null }): StreamInfo {
        return try {
            when {
                kind.isVideoFile || kind == Kind.AUDIO -> probeFile(url, kind, headers, cookies)
                kind == Kind.UNKNOWN -> {
                    val i = probeFile(url, kind, headers, cookies)
                    if (i.kind == Kind.HLS || i.kind == Kind.DASH) probeText(url, i.kind, headers, cookies) else i
                }
                else -> probeText(url, kind, headers, cookies)
            }
        } catch (e: Exception) {
            StreamInfo(kind, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probeFile(url: String, kind: Kind, headers: Map<String, String>, cookies: (String) -> String?): StreamInfo {
        val f = Http.open(url, headers, cookies, range = "bytes=0-1023", connectTimeout = 8000, readTimeout = 8000)
        try {
            val total = f.conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: f.conn.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { f.code == 200 }
            val ct = f.conn.contentType
            if (f.code >= 400) return StreamInfo(kind, httpStatus = f.code)
            val head = Http.body(f)?.use { Http.readUpTo(it, 1024) } ?: ByteArray(0)
            val sniffed = Classify.sniff(head)
            if (ct != null && ct.startsWith("text/html")) return StreamInfo(kind, httpStatus = f.code, error = "got a web page, not a video")
            val k = sniffed ?: Classify.fromMime(ct) ?: kind
            return StreamInfo(k, sizeBytes = total, httpStatus = f.code, audioOnly = kind == Kind.AUDIO)
        } finally { f.conn.disconnect() }
    }

    private fun probeText(url: String, kind: Kind, headers: Map<String, String>, cookies: (String) -> String?): StreamInfo {
        val f = Http.open(url, headers, cookies, connectTimeout = 8000, readTimeout = 10000)
        val bytes: ByteArray
        val ct: String?
        try {
            ct = f.conn.contentType
            if (f.code >= 400) return StreamInfo(kind, httpStatus = f.code)
            bytes = Http.body(f)?.use { Http.readUpTo(it, TEXT_LIMIT) } ?: ByteArray(0)
        } finally { f.conn.disconnect() }
        val sniffed = Classify.sniff(bytes) ?: Classify.fromMime(ct)
        return when (sniffed) {
            Kind.HLS -> {
                var info = Manifests.parseHls(String(bytes, Charsets.UTF_8), f.finalUrl)
                val v = info.bestVariantUrl
                if (info.isMaster && v != null) {
                    try {
                        val g = Http.open(v, headers, cookies, connectTimeout = 6000, readTimeout = 8000)
                        try {
                            if (g.code < 400) {
                                val vb = Http.body(g)?.use { Http.readUpTo(it, TEXT_LIMIT) } ?: ByteArray(0)
                                val media = Manifests.parseHls(String(vb, Charsets.UTF_8), g.finalUrl)
                                info = info.copy(durationSec = media.durationSec, live = media.live, fmp4 = media.fmp4, drm = info.drm || media.drm)
                            }
                        } finally { g.conn.disconnect() }
                    } catch (_: Exception) {}
                }
                info.copy(httpStatus = f.code)
            }
            Kind.DASH -> Manifests.parseDash(String(bytes, Charsets.UTF_8)).copy(httpStatus = f.code)
            null -> StreamInfo(kind, httpStatus = f.code, error = if (ct?.startsWith("text/html") == true) "got a web page, not a stream" else null)
            else -> StreamInfo(sniffed, httpStatus = f.code)
        }
    }
}
