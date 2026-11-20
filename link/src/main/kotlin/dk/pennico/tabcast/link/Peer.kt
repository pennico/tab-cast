// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One end of an open link (after the handshake): a reader thread hands each message to [onMessage]; a writer thread
 * sends what [send] and [sendLatest] queue. [sendLatest] is for pictures (preview, mirroring frames): a newer one
 * replaces one not yet sent, so a slow Wi-Fi drops pictures instead of building up delay.
 * The other side must send something at least every [READ_TIMEOUT_MS] (PING every 2 s does), else the link closes.
 */
class Peer(private val socket: Socket, val name: String) {
    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 shl 10))
    private val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 shl 10))
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val control = ArrayDeque<Wire.Msg>()
    private val latest = LinkedHashMap<Int, ArrayDeque<Wire.Msg>>()
    private val closedFlag = AtomicBoolean(false)
    val bytesOut = AtomicLong()
    val bytesIn = AtomicLong()
    val dropped = AtomicLong()
    @Volatile var rttMs: Double = -1.0
        private set
    var hello: Map<String, String> = emptyMap()
        internal set
    /** Paired (a valid token in HELLO, or a PIN accepted since): only then may it control the TV. */
    @Volatile var authorized: Boolean = false
        internal set

    var onMessage: (Wire.Msg) -> Unit = {}
    var onClosed: (Throwable?) -> Unit = {}

    val closed: Boolean get() = closedFlag.get()
    val remote: String get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"

    fun start() {
        socket.soTimeout = READ_TIMEOUT_MS
        socket.tcpNoDelay = true
        Thread({ readLoop() }, "link-read-$name").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "link-write-$name").apply { isDaemon = true }.start()
    }

    /** Queues a message that must arrive (commands, status). False when the link is closed. */
    fun send(type: Int, payload: ByteArray = ByteArray(0), flags: Int = 0, pts: Long = 0): Boolean {
        if (closed) return false
        lock.withLock { control.addLast(Wire.Msg(type, flags, pts, payload)); ready.signalAll() }
        return true
    }

    fun sendKv(type: Int, vararg kv: Pair<String, Any?>) = send(type, Wire.kv(*kv))

    /** Queues a picture; keeps at most [keep] unsent of this type (the oldest goes). False when one was dropped. */
    fun sendLatest(type: Int, payload: ByteArray, flags: Int = 0, pts: Long = 0, keep: Int = 1): Boolean {
        if (closed) return false
        var fine = true
        lock.withLock {
            val q = latest.getOrPut(type) { ArrayDeque() }
            while (q.size >= keep) { q.removeFirst(); dropped.incrementAndGet(); fine = false }
            q.addLast(Wire.Msg(type, flags, pts, payload))
            ready.signalAll()
        }
        return fine
    }

    /** Pictures of [type] queued and not yet sent. */
    fun pending(type: Int): Int = lock.withLock { latest[type]?.size ?: 0 }

    fun ping() = send(Wire.PING, Wire.long(System.nanoTime()))

    fun close(reason: Throwable? = null) {
        if (!closedFlag.compareAndSet(false, true)) return
        lock.withLock { ready.signalAll() }
        try { socket.close() } catch (_: Exception) {}
        onClosed(reason)
    }

    private fun next(): Wire.Msg? = lock.withLock {
        while (!closed) {
            control.pollFirst()?.let { return it }
            for (q in latest.values) q.pollFirst()?.let { return it }
            ready.await(1, TimeUnit.SECONDS)
        }
        null
    }

    private fun writeLoop() {
        try {
            while (true) {
                val m = next() ?: break
                Wire.write(out, m.type, m.flags, m.pts, m.payload)
                bytesOut.addAndGet(Wire.HEADER + m.payload.size.toLong())
                // Flush unless more control messages are waiting (pictures are flushed one by one: low delay).
                if (lock.withLock { control.isEmpty() }) out.flush()
            }
        } catch (e: Exception) {
            close(e)
        }
    }

    private fun readLoop() {
        var error: Throwable? = null
        try {
            while (!closed) {
                val m = Wire.read(inp)
                bytesIn.addAndGet(Wire.HEADER + m.payload.size.toLong())
                when (m.type) {
                    Wire.PING -> send(Wire.PONG, m.payload)
                    Wire.PONG -> rttMs = (System.nanoTime() - Wire.long(m.payload)) / 1e6
                    Wire.BYE -> break
                    else -> onMessage(m)
                }
            }
        } catch (e: EOFException) {
        } catch (e: SocketException) {
            if (!closed) error = e
        } catch (e: Exception) {
            error = e
        }
        close(error)
    }

    companion object {
        const val READ_TIMEOUT_MS = 10_000
    }
}

/**
 * The TV side: listens on [port], takes a connection only after the magic bytes and a HELLO (within
 * [HANDSHAKE_MS]). Port scans and stray connections never touch the open link. A paired tablet ([isAuthorized] on its
 * HELLO) replaces the previous one; an unpaired one is only handed to [onConnected] to pair ([promote] after a valid
 * PIN) and never pushes the paired tablet out.
 */
class LinkServer(private val port: Int = Wire.PORT, private val name: String = "tv",
                 private val helloAck: (Map<String, String>) -> Map<String, Any?>,
                 private val isAuthorized: (Map<String, String>) -> Boolean = { true },
                 private val onConnected: (Peer) -> Unit) {
    @Volatile private var server: ServerSocket? = null
    @Volatile var active: Peer? = null
        private set
    @Volatile var log: (String) -> Unit = {}

    val localPort: Int get() = server?.localPort ?: port

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        Thread({ acceptLoop(s) }, "link-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        active?.close()
        active = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: Exception) { break }
            Thread({ handshake(c) }, "link-handshake").apply { isDaemon = true }.start()
        }
    }

    private fun handshake(c: Socket) {
        try {
            c.soTimeout = HANDSHAKE_MS
            val inp = c.getInputStream()
            if (!Wire.readMagic(inp)) { c.close(); return }
            val m = Wire.read(DataInputStream(inp))
            if (m.type != Wire.HELLO) { c.close(); return }
            val hello = m.kv()
            val ok = isAuthorized(hello)
            val out = c.getOutputStream()
            Wire.writeMagic(out)
            val dout = DataOutputStream(out)
            Wire.write(dout, Wire.HELLO_ACK, payload = Wire.kv(*(helloAck(hello) + ("paired" to ok)).toList().toTypedArray()))
            dout.flush()
            val p = Peer(c, name)
            p.hello = hello
            p.authorized = ok
            if (ok) makeActive(p)
            onConnected(p)
            p.start()
        } catch (e: Exception) {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun makeActive(p: Peer) {
        active?.takeIf { it !== p }?.let { old -> log("link: ${p.hello["name"] ?: p.remote} replaces ${old.hello["name"] ?: old.remote}"); old.close() }
        active = p
    }

    /** An unpaired peer entered the right PIN: it becomes the active tablet. */
    fun promote(p: Peer) {
        p.authorized = true
        makeActive(p)
    }

    companion object { const val HANDSHAKE_MS = 5000 }
}

/** The tablet side: connects, sends HELLO, waits for HELLO_ACK. Throws on any failure (TV app not open, wrong app). */
object LinkClient {
    fun connect(host: String, port: Int = Wire.PORT, hello: Map<String, Any?>, timeoutMs: Int = 3000, name: String = "tablet"): Peer {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            Wire.writeMagic(out)
            val dout = DataOutputStream(out)
            Wire.write(dout, Wire.HELLO, payload = Wire.kv(*hello.toList().toTypedArray()))
            dout.flush()
            val inp = s.getInputStream()
            if (!Wire.readMagic(inp)) throw java.io.IOException("not Tab Cast TV at $host:$port")
            val ack = Wire.read(DataInputStream(inp))
            if (ack.type != Wire.HELLO_ACK) throw java.io.IOException("unexpected answer ${ack.type}")
            return Peer(s, name).also { it.hello = ack.kv() }
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }
}
