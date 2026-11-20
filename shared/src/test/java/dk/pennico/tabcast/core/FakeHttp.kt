// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

import java.io.BufferedInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Minimal HTTP/1.1 server for tests (android.jar has no com.sun.net.httpserver). One request per connection. */
class FakeHttp(private val handler: (method: String, path: String, headers: Map<String, String>) -> Resp) : AutoCloseable {
    class Resp(val code: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private val ss = ServerSocket(0)
    val port: Int get() = ss.localPort

    init {
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    s.use { sock ->
                        val inp = BufferedInputStream(sock.getInputStream())
                        fun line(): String {
                            val sb = StringBuilder()
                            while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; sb.append(c.toChar()) }
                            return sb.toString().trimEnd('\r')
                        }
                        val req = line().split(' ')
                        if (req.size < 2) return@use
                        val h = HashMap<String, String>()
                        while (true) { val l = line(); if (l.isEmpty()) break; h[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
                        val r = handler(req[0], req[1], h)
                        val head = StringBuilder("HTTP/1.1 ${r.code} X\r\nContent-Type: ${r.type}\r\nConnection: close\r\n")
                        r.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                        head.append("Content-Length: ${r.body.size}\r\n\r\n")
                        val out = sock.getOutputStream()
                        out.write(head.toString().toByteArray())
                        if (req[0] != "HEAD") out.write(r.body)
                        out.flush()
                    }
                }
            }
        }
    }

    override fun close() = ss.close()
}
