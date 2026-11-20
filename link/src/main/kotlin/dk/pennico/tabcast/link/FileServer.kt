// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.4.0 "Cast a file": the tablet serves a local file to Tab Cast TV as it is (no transcoding), over plain HTTP with
 * Range requests, so the TV can copy it into its rolling cache from any position and resume after a gap.
 *
 * Only paths with a live random id are served (`/f/<32 hex>/<name>`); nothing else on the tablet is reachable, so it
 * is not an open file server on the Wi-Fi. GET and HEAD; `Range: bytes=a-b`, `a-`, `-n`. Pure JVM (tested on the
 * server together with [FilmCache]).
 */
class FileServer(private val log: (String) -> Unit = {}) {
    /** What one cast serves: positional reads, safe from several threads at once (FileChannel.read(buf, pos)). */
    interface Source {
        val size: Long
        /** Reads up to [len] bytes at [pos] into [buf]; returns the number read, -1 at the end. */
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
        fun close() {}
    }

    class Entry(val id: String, val name: String, val mime: String, val source: Source) {
        val path: String get() = "/f/$id/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val bytesServed = AtomicLong()
        @Volatile var lastRequestAt = 0L
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "file-server").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null
    val bytesServed = AtomicLong()
    val port: Int get() = server?.localPort ?: -1
    val running: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(preferredPort: Int = 0): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val ss = try { ServerSocket(preferredPort) } catch (e: IOException) { ServerSocket(0) }
        server = ss
        pool.execute { acceptLoop(ss) }
        log("file server listening on ${ss.localPort}")
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        entries.values.forEach { runCatching { it.source.close() } }
        entries.clear()
    }

    fun add(name: String, mime: String, source: Source): Entry {
        val e = Entry(randomId(), name.replace('/', '_'), mime, source)
        entries[e.id] = e
        return e
    }

    fun remove(e: Entry) {
        entries.remove(e.id)?.let { runCatching { it.source.close() } }
    }

    val count: Int get() = entries.size

    /** `http://<hostPort><path>`, [hostPort] = the tablet's Wi-Fi address and [port]. */
    fun url(hostPort: String, e: Entry) = "http://$hostPort${e.path}"

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (e: IOException) { break }
            pool.execute { handle(s) }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 60_000
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream(), 8192)
            val out = BufferedOutputStream(sock.getOutputStream(), 64 shl 10)
            while (true) {
                val req = Http.readRequest(input) ?: break
                val keep = serve(req, out)
                out.flush()
                if (!keep || req.headers["connection"].equals("close", true)) break
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } finally {
            try { sock.close() } catch (_: IOException) {}
        }
    }

    private fun serve(req: Http.Request, out: OutputStream): Boolean {
        val path = req.target.substringBefore('?')
        val parts = path.split('/')
        val e = if (parts.size >= 3 && parts[1] == "f") entries[parts[2]] else null
        if (e == null) return Http.simple(out, 404, "not found")
        if (req.method != "GET" && req.method != "HEAD") return Http.simple(out, 405, "method not allowed")
        e.lastRequestAt = System.currentTimeMillis()
        val size = e.source.size
        val range = req.headers["range"]?.let { Http.parseRange(it, size) }
        if (range == Http.BAD_RANGE) {
            Http.head(out, 416, listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
            return true
        }
        val (from, to) = range ?: (0L to size - 1)
        val len = (to - from + 1).coerceAtLeast(0)
        val h = arrayListOf("Content-Type" to e.mime, "Content-Length" to len.toString(), "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store")
        if (range != null) h += "Content-Range" to "bytes $from-$to/$size"
        Http.head(out, if (range != null) 206 else 200, h)
        if (req.method == "HEAD" || len == 0L) return true
        val buf = ByteArray(64 shl 10)
        var pos = from
        val end = to + 1
        while (pos < end) {
            val n = e.source.read(pos, buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
            if (n <= 0) throw IOException("file shorter than its size ($pos of $size)")
            out.write(buf, 0, n)
            pos += n
            e.bytesServed.addAndGet(n.toLong())
            bytesServed.addAndGet(n.toLong())
        }
        return true
    }

    companion object {
        private val rnd = SecureRandom()
        fun randomId(): String = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

/** The few HTTP/1.1 pieces [FileServer] needs (requests in, heads out, byte ranges). */
internal object Http {
    class Request(val method: String, val target: String, val headers: Map<String, String>)

    val BAD_RANGE = -1L to -1L

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

    fun readRequest(input: InputStream): Request? {
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

    /**
     * `bytes=a-b`, `bytes=a-`, `bytes=-n` against [size]: the inclusive range, null for no/unsupported range
     * (serve the whole file), [BAD_RANGE] when it can't be satisfied.
     */
    fun parseRange(h: String, size: Long): Pair<Long, Long>? {
        val v = h.trim()
        if (!v.startsWith("bytes=", true)) return null
        val spec = v.substring(6).split(',')[0].trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return try {
            if (a.isEmpty()) {
                val n = b.toLong()
                if (n <= 0 || size == 0L) BAD_RANGE else (size - minOf(n, size)) to size - 1
            } else {
                val from = a.toLong()
                val to = if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1)
                if (from >= size || from > to) BAD_RANGE else from to to
            }
        } catch (e: NumberFormatException) { null }
    }

    fun head(out: OutputStream, code: Int, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun simple(out: OutputStream, code: Int, msg: String): Boolean {
        val body = msg.toByteArray()
        head(out, code, listOf("Content-Type" to "text/plain", "Content-Length" to body.size.toString()))
        out.write(body)
        return true
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 206 -> "Partial Content"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"; else -> "Status"
    }
}
