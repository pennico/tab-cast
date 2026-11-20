// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * A small HTTP/1.1 server for Tab Cast TV's web remote (0.3.0): GET/POST, keep-alive (the page polls), bounded header
 * and body sizes, a fixed thread pool. Enough for a few phones and Macs on a home Wi-Fi, nothing more.
 */
class MiniHttp(private val port: Int, private val handler: (Req) -> Resp, private val log: (String) -> Unit = {}) {

    class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>,
              val body: ByteArray, val remote: String) {
        fun header(name: String): String? = headers[name.lowercase()]
        fun cookie(name: String): String? = header("cookie")?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
        fun text(): String = String(body, Charsets.UTF_8)
    }

    class Resp(val status: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
        companion object {
            fun text(status: Int, s: String, type: String = "text/plain; charset=utf-8", headers: Map<String, String> = emptyMap()) =
                Resp(status, type, s.toByteArray(Charsets.UTF_8), headers)
            fun json(status: Int, s: String, headers: Map<String, String> = emptyMap()) = text(status, s, "application/json; charset=utf-8", headers)
        }
    }

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(8) { r -> Thread(r, "web-remote").apply { isDaemon = true } }

    val localPort: Int get() = server?.localPort ?: port

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                try { pool.execute { serve(c) } } catch (e: Exception) { c.close() }
            }
        }, "web-remote-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool.shutdownNow()
    }

    private fun serve(c: Socket) {
        c.use { sock ->
            sock.soTimeout = 20_000
            sock.tcpNoDelay = true
            val inp = BufferedInputStream(sock.getInputStream(), 16 shl 10)
            val out = sock.getOutputStream()
            val remote = (sock.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"
            var served = 0
            while (served < 200) {
                val req = try { read(inp, remote) } catch (e: SocketTimeoutException) { return } catch (e: Exception) { return } ?: return
                val resp = try { handler(req) } catch (e: Exception) { log("web remote ${req.path}: $e"); Resp.text(500, "error") }
                val close = req.header("connection")?.equals("close", true) == true
                write(out, resp, close, req.method == "HEAD")
                served++
                if (close) return
            }
        }
    }

    private fun readLine(inp: InputStream, max: Int): String? {
        val b = ByteArrayOutputStream(128)
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > max) throw IllegalStateException("line too long")
        }
        return b.toString("ISO-8859-1")
    }

    private fun read(inp: InputStream, remote: String): Req? {
        val line = readLine(inp, 8192) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) throw IllegalStateException("bad request line")
        val headers = HashMap<String, String>()
        var total = 0
        while (true) {
            val h = readLine(inp, 8192) ?: break
            if (h.isEmpty()) break
            total += h.length
            if (total > 32 shl 10 || headers.size > 100) throw IllegalStateException("headers too big")
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) throw IllegalStateException("body too big")
        val body = ByteArray(len)
        var n = 0
        while (n < len) { val r = inp.read(body, n, len - n); if (r < 0) break; n += r }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        return Req(parts[0].uppercase(), path, query, headers, body, remote)
    }

    private fun write(out: OutputStream, r: Resp, close: Boolean, head: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        sb.append("Content-Type: ").append(r.type).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append(if (close) "Connection: close\r\n" else "Connection: keep-alive\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head) out.write(r.body)
        out.flush()
    }

    private fun reason(s: Int) = when (s) {
        200 -> "OK"; 204 -> "No Content"; 302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 423 -> "Locked"; 429 -> "Too Many Requests"
        else -> if (s >= 500) "Server Error" else "OK"
    }

    companion object { const val MAX_BODY = 64 shl 10 }
}
