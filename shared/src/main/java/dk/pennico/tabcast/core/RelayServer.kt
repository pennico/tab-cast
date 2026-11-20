// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A tiny HTTP relay that runs on the tablet. The TV fetches `http://<tablet>:<port>/x/<session>/<scheme>/<host>/<path>`;
 * the relay fetches the upstream URL with the page's headers and fresh cookies, adds CORS headers (the Chromecast's
 * Default Media Receiver needs them for HLS/DASH), and rewrites manifests so segments and keys also go through it.
 *
 * Only paths with a live random session id are served, so it is not an open proxy on the Wi-Fi.
 * Pure JVM (no Android imports) so it is unit-tested on the build server.
 */
class RelayServer(private val log: (String) -> Unit = {}) {

    /**
     * [masterFilter] rewrites HLS master playlists before they are served (quality limits for the TV).
     * [onlyManifest]: URIs inside served playlists point straight at the origin instead of back at the relay, so only
     * the playlist goes through the tablet (FCast, which sends the page's headers itself). [hits] counts requests.
     */
    class Session(
        val id: String,
        val headers: Map<String, String>,
        val cookies: (String) -> String?,
        val masterFilter: ((String) -> String)? = null,
        val onlyManifest: Boolean = false,
    ) {
        val hits = java.util.concurrent.atomic.AtomicInteger()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "relay").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null
    val bytesServed = AtomicLong()
    val requests = AtomicLong()
    @Volatile var lastError: String? = null
        private set

    val port: Int get() = server?.localPort ?: -1
    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(preferredPort: Int = 0): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val ss = try { ServerSocket(preferredPort) } catch (e: IOException) { ServerSocket(0) }
        server = ss
        pool.execute { acceptLoop(ss) }
        log("relay listening on ${ss.localPort}")
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        sessions.clear()
    }

    fun open(headers: Map<String, String>, masterFilter: ((String) -> String)? = null, onlyManifest: Boolean = false,
             cookies: (String) -> String? = { null }): Session {
        val s = Session(randomId(), headers, cookies, masterFilter, onlyManifest)
        sessions[s.id] = s
        return s
    }

    fun close(id: String) { sessions.remove(id) }

    /** Relay URL for [upstream] as seen from the TV, given the tablet address [hostPort] ("192.0.2.10:8642"). */
    fun url(hostPort: String, session: Session, upstream: String): String = relayUrl(hostPort, session.id, upstream)

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (e: IOException) { break }
            pool.execute { handle(s) }
        }
    }

    private class Request(val method: String, val target: String, val headers: Map<String, String>)

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream(), 8192)
            val out = BufferedOutputStream(sock.getOutputStream(), 65536)
            while (true) {
                val req = readRequest(input) ?: break
                requests.incrementAndGet()
                val keep = try { serve(req, out) } catch (e: IOException) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    false
                }
                out.flush()
                if (!keep || req.headers["connection"].equals("close", true)) break
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } finally {
            try { sock.close() } catch (_: IOException) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) throw IOException("header line too long")
        }
    }

    private fun readRequest(input: InputStream): Request? {
        var line = readLine(input) ?: return null
        while (line.isEmpty()) line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) throw IOException("bad request line")
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        return Request(parts[0].uppercase(), parts[1], headers)
    }

    private fun writeHead(out: OutputStream, code: Int, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun simple(out: OutputStream, code: Int, msg: String, keep: Boolean = true): Boolean {
        val body = msg.toByteArray()
        val h = mutableListOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to body.size.toString())
        if (!keep) h += "Connection" to "close"
        writeHead(out, code, h)
        out.write(body)
        return keep
    }

    private fun fetch(upstream: String, s: Session, method: String, range: String?): Http.Fetched =
        Http.open(upstream, s.headers, s.cookies, method, range)

    private fun serve(req: Request, out: OutputStream): Boolean {
        if (req.method == "OPTIONS") {
            writeHead(out, 204, listOf(
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to (req.headers["access-control-request-headers"] ?: "*"),
                "Access-Control-Max-Age" to "86400",
                "Content-Length" to "0",
            ))
            return true
        }
        if (req.method != "GET" && req.method != "HEAD") return simple(out, 405, "method not allowed")
        val parsed = parseTarget(req.target) ?: return simple(out, 404, "not found")
        val (sid, upstream) = parsed
        val s = sessions[sid] ?: return simple(out, 404, "unknown or closed session")
        s.hits.incrementAndGet()
        val hostPort = req.headers["host"] ?: "127.0.0.1:$port"

        val f = try { fetch(upstream, s, req.method, req.headers["range"]) } catch (e: IOException) {
            lastError = "upstream ${Urls.host(upstream)}: ${e.message}"
            log("relay: $lastError")
            return simple(out, 502, "upstream error: ${e.message}", keep = false)
        }
        val conn = f.conn
        try {
            val ct = conn.contentType
            val raw: InputStream? = try { if (f.code >= 400) conn.errorStream else conn.inputStream } catch (e: IOException) { conn.errorStream }
            val body = BufferedInputStream(raw ?: java.io.ByteArrayInputStream(ByteArray(0)), 65536)

            var manifest: Kind? = null
            if (req.method == "GET" && f.code in 200..299) {
                val hinted = (Classify.fromMime(ct) ?: Classify.fromUrl(f.finalUrl))
                val generic = ct == null || ct.contains("text/") || ct.contains("octet-stream") || ct.contains("xml") ||
                    ct.contains("mpegurl") || ct.contains("dash")
                if (hinted == Kind.HLS || hinted == Kind.DASH || generic) {
                    body.mark(4096)
                    val peek = ByteArray(1024)
                    val n = readUpTo(body, peek)
                    body.reset()
                    val sniffed = Classify.sniff(peek, n)
                    if (sniffed == Kind.HLS || sniffed == Kind.DASH) manifest = sniffed
                }
            }

            if (manifest != null) {
                if (manifest == Kind.DASH && f.finalUrl != upstream) {
                    // Let the client re-request from the final location so relative references resolve there.
                    writeHead(out, 302, listOf("Location" to relayUrl(hostPort, sid, f.finalUrl), "Content-Length" to "0"))
                    return true
                }
                var text = String(readAll(body, 16 shl 20), Charsets.UTF_8)
                val filter = s.masterFilter
                if (manifest == Kind.HLS && filter != null && HlsFilter.isMaster(text)) text = filter(text)
                val map = if (s.onlyManifest) { abs: String -> abs } else { abs: String -> relayUrl(hostPort, sid, abs) }
                val rewritten = if (manifest == Kind.HLS) ManifestRewriter.rewriteHls(text, f.finalUrl, map)
                else ManifestRewriter.rewriteDash(text, f.finalUrl, map)
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                writeHead(out, 200, listOf(
                    "Content-Type" to (ct ?: manifest.mime),
                    "Content-Length" to bytes.size.toString(),
                    "Cache-Control" to "no-cache",
                ))
                out.write(bytes)
                bytesServed.addAndGet(bytes.size.toLong())
                return true
            }

            val len = conn.getHeaderField("Content-Length")?.toLongOrNull()
            val h = mutableListOf<Pair<String, String>>()
            for (name in passHeaders) conn.getHeaderField(name)?.let { h += name to it }
            if (req.method == "HEAD") {
                writeHead(out, f.code, h)
                return true
            }
            if (len == null) h += "Connection" to "close"
            writeHead(out, f.code, h)
            val buf = ByteArray(65536)
            var left = len ?: Long.MAX_VALUE
            while (left > 0) {
                val r = body.read(buf, 0, if (left < buf.size) left.toInt() else buf.size)
                if (r < 0) break
                out.write(buf, 0, r)
                left -= r
                bytesServed.addAndGet(r.toLong())
            }
            return len != null && left == 0L
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val passHeaders = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")

        fun relayUrl(hostPort: String, sid: String, upstream: String): String {
            val i = upstream.indexOf("://")
            require(i > 0) { "not absolute: $upstream" }
            return "http://$hostPort/x/$sid/${upstream.substring(0, i).lowercase()}/${upstream.substring(i + 3)}"
        }

        /** "/x/<sid>/<scheme>/<rest>" -> (sid, "scheme://rest"), or null. */
        fun parseTarget(target: String): Pair<String, String>? {
            if (!target.startsWith("/x/")) return null
            val rest = target.substring(3)
            val a = rest.indexOf('/')
            if (a <= 0) return null
            val sid = rest.substring(0, a)
            val b = rest.indexOf('/', a + 1)
            if (b < 0) return null
            val scheme = rest.substring(a + 1, b)
            if (scheme != "http" && scheme != "https") return null
            val tail = rest.substring(b + 1)
            if (tail.isEmpty()) return null
            return sid to "$scheme://$tail"
        }

        private val rng = SecureRandom()
        private fun randomId(): String {
            val a = "abcdefghijkmnopqrstuvwxyz23456789"
            return (1..20).map { a[rng.nextInt(a.length)] }.joinToString("")
        }

        private fun readUpTo(input: InputStream, buf: ByteArray): Int {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }

        private fun readAll(input: InputStream, limit: Int): ByteArray {
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                bo.write(buf, 0, r)
                if (bo.size() > limit) throw IOException("manifest too large")
            }
            return bo.toByteArray()
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 302 -> "Found"; 304 -> "Not Modified"
            400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"; 502 -> "Bad Gateway"; else -> "Status"
        }
    }
}
