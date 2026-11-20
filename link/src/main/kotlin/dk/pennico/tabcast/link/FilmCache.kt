// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 0.4.0, Tab Cast TV: the rolling cache of a film the tablet serves (FileServer). The TV plays from here, so the
 * tablet can sleep as soon as the TV holds enough.
 *
 * The file is kept as fixed-size chunks (`dir/c000123`, each written from its start, so a chunk's length on disk is
 * how much of it is there; a restarted app picks them up again). A downloader thread fetches, with HTTP Range requests
 * and at most [rateBitsPerSec], in this order: the chunk the player reads (it waits for it); the first and last chunk
 * (container headers and index, never evicted); then forward from the play position to the end; then, if there is
 * room, what lies before it, so a film that fits ends up entirely on the TV.
 *
 * It stays within [budget] (what it holds + the TV's free space - a safety margin, computed by the caller): to fetch something
 * ahead it first evicts what is furthest behind the read position (keeping [keepBehind] bytes behind it for short
 * rewinds), then what is furthest ahead of the chunk it needs. It never evicts to fill in behind.
 *
 * When the tablet doesn't answer (asleep, out of range) it retries with backoff (1 s … 15 s) forever; the player only
 * waits once it reaches the end of what is here ([Stats.waiting]) and goes on by itself when the bytes come.
 *
 * [read] blocks until the bytes at that position are here (or the thread is interrupted / the cache closed): it is
 * the player's data source.
 */
class FilmCache(
    val dir: File,
    /** Where the tablet serves the film; replaced when the tablet serves it again after a restart (same film, new path). */
    @Volatile var url: String,
    val size: Long,
    /** Bytes the film may use, given what it holds now (the caller adds the disk's free space minus a margin). */
    private val budget: (Long) -> Long,
    @Volatile var rateBitsPerSec: Long = 20_000_000,
    private val log: (String) -> Unit = {},
    val chunkSize: Int = 4 shl 20,
    private val keepBehind: Long = 96L shl 20,
    private val open: (String, Long) -> InputStream = ::httpFrom,
) {
    val chunks: Int = ((size + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
    private val filled = LongArray(chunks)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    @Volatile private var closed = false
    @Volatile private var cached = 0L
    @Volatile private var head = 0L
    @Volatile private var waitingSince = 0L
    @Volatile private var failures = 0
    @Volatile private var lastError: String? = null
    @Volatile private var downloaded = 0L
    @Volatile private var budgetFull = false
    private var thread: Thread? = null
    private var readerFile: Pair<Int, RandomAccessFile>? = null
    private val readerLock = Any()

    init {
        dir.mkdirs()
        for (i in 0 until chunks) {
            val f = file(i)
            if (f.exists()) {
                val len = f.length()
                if (len > chunkLen(i)) f.delete() else filled[i] = len
            }
        }
        cached = filled.sum()
    }

    /** What the TV holds and how the download goes (polled once a second for STATUS). */
    data class Stats(
        val size: Long,
        val cached: Long,
        val complete: Boolean,
        /** Bytes from the read position on that are here without a gap. */
        val aheadBytes: Long,
        val head: Long,
        /** The player waits for bytes and the tablet doesn't answer. */
        val waiting: Boolean,
        /** The player waits for bytes (the download is on its way). */
        val loading: Boolean,
        val failures: Int,
        val lastError: String?,
        val downloaded: Long,
        /** Nothing more can be fetched ahead until the play position moves on (the cache is full). */
        val full: Boolean,
    )

    fun stats(): Stats = lock.withLock {
        val now = System.currentTimeMillis()
        val waitingNow = waitingSince > 0 && now - waitingSince > 1500
        Stats(size, cached, isComplete(), aheadFrom(head), head, waitingNow && failures > 0, waitingNow, failures, lastError,
            downloaded, budgetFull)
    }

    private fun isComplete() = (0 until chunks).all { filled[it] == chunkLen(it) }

    private fun aheadFrom(pos: Long): Long {
        if (pos >= size) return 0
        var i = (pos / chunkSize).toInt()
        var have = filled[i] - (pos - i.toLong() * chunkSize)
        if (have <= 0) return 0
        while (filled[i] == chunkLen(i) && i + 1 < chunks) {
            i++
            have += filled[i]
            if (filled[i] < chunkLen(i)) break
        }
        return have
    }

    fun start() {
        if (thread != null) return
        thread = Thread({ downloadLoop() }, "film-cache").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    fun close() {
        closed = true
        lock.withLock { changed.signalAll() }
        thread?.interrupt()
        synchronized(readerLock) { readerFile?.second?.let { runCatching { it.close() } }; readerFile = null }
    }

    /** Closes and removes everything on disk for this film. */
    fun delete() {
        close()
        thread?.join(2000)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    // ------------------------------------------------------------------------------------------------- reading

    /**
     * Up to [len] bytes at [pos] (fewer when a chunk ends or only part of it is here); -1 at the end of the file.
     * Blocks until something is there.
     */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        if (len == 0) return 0
        val i = (pos / chunkSize).toInt()
        val inChunk = pos - i.toLong() * chunkSize
        val avail: Int
        lock.withLock {
            moveHead(pos)
            while (filled[i] <= inChunk) {
                if (closed) throw IOException("film cache closed")
                if (waitingSince == 0L) { waitingSince = System.currentTimeMillis(); changed.signalAll() }
                try { changed.await(500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("interrupted while waiting for the film")
                }
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("interrupted while waiting for the film")
            }
            waitingSince = 0
            avail = minOf(len.toLong(), filled[i] - inChunk).toInt()
        }
        synchronized(readerLock) {
            val raf = readerFile?.takeIf { it.first == i }?.second ?: run {
                readerFile?.second?.let { runCatching { it.close() } }
                RandomAccessFile(file(i), "r").also { readerFile = i to it }
            }
            raf.seek(inChunk)
            val n = raf.read(buf, off, avail)
            if (n <= 0) throw IOException("chunk $i shorter than expected")
            return n
        }
    }

    private fun moveHead(pos: Long) {
        val before = (head / chunkSize).toInt()
        head = pos
        if ((pos / chunkSize).toInt() != before) changed.signalAll()
    }

    // ---------------------------------------------------------------------------------------------- downloading

    private fun file(i: Int) = File(dir, "c%06d".format(i))
    private fun chunkLen(i: Int): Long = minOf(chunkSize.toLong(), size - i.toLong() * chunkSize)
    private fun headChunk() = (head / chunkSize).toInt().coerceIn(0, chunks - 1)

    /**
     * The chunk to fetch next, after making room for it; -1 when there is nothing to do or no room. Called with the
     * lock held.
     */
    private fun planNext(): Int {
        val h = headChunk()
        val b = budget(cached)
        fun need(c: Int) = chunkLen(c) - filled[c]
        fun fits(c: Int) = cached + need(c) <= b
        budgetFull = false
        // The read position's chunk, then the container's head and tail, then forward from the read position.
        val ahead = sequence {
            yield(h)
            yield(0)
            yield(chunks - 1)
            for (c in h + 1 until chunks) yield(c)
        }.firstOrNull { filled[it] < chunkLen(it) }
        if (ahead != null) {
            if (fits(ahead) || evictFor(ahead, need(ahead), b)) return ahead
            budgetFull = true
            return -1
        }
        // Everything from the read position to the end is here: fill in behind while there is room (no eviction).
        for (c in 0 until h) if (filled[c] < chunkLen(c)) return if (fits(c)) c else { budgetFull = true; -1 }
        return -1
    }

    /** Frees room for [need] bytes of chunk [target]: furthest behind first, then furthest ahead of [target]. */
    private fun evictFor(target: Int, need: Long, budget: Long): Boolean {
        val h = headChunk()
        val keep = (keepBehind / chunkSize).toInt()
        val pinned = setOf(0, chunks - 1, h, target)
        while (cached + need > budget) {
            val behind = (0 until (h - keep).coerceAtLeast(0)).firstOrNull { filled[it] > 0 && it !in pinned }
            val victim = behind ?: (chunks - 1 downTo target + 1).firstOrNull { filled[it] > 0 && it !in pinned } ?: return false
            evict(victim)
        }
        return true
    }

    private fun evict(i: Int) {
        synchronized(readerLock) {
            if (readerFile?.first == i) { readerFile?.second?.let { runCatching { it.close() } }; readerFile = null }
        }
        file(i).delete()
        cached -= filled[i]
        filled[i] = 0
    }

    private fun downloadLoop() {
        var backoff = 1000L
        while (!closed) {
            val target = lock.withLock { planNext() }
            if (target < 0) {
                lock.withLock { if (!closed) try { changed.await(1, TimeUnit.SECONDS) } catch (_: InterruptedException) {} }
                continue
            }
            try {
                fetchFrom(target)
                if (failures > 0) log("film: the tablet answers again after $failures failed tries")
                failures = 0
                lastError = null
                backoff = 1000
            } catch (e: Exception) {
                if (closed) break
                failures++
                lastError = e.message ?: e.javaClass.simpleName
                if (failures == 1 || failures % 20 == 0) log("film: can't fetch from the tablet ($lastError); try $failures, next in ${backoff / 1000} s")
                lock.withLock { if (!closed) try { changed.await(backoff, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {} }
                backoff = (backoff * 2).coerceAtMost(15_000)
            }
        }
    }

    /**
     * One HTTP request from where chunk [first] is missing bytes on: writes it, and goes on into the following chunks
     * while they are still what [planNext] wants next. Returns when the plan changes (the player jumped elsewhere, the
     * cache is full) or the file ends.
     */
    private fun fetchFrom(first: Int) {
        var cur = first
        val start = cur.toLong() * chunkSize + filled[cur]
        val input = open(url, start)
        var raf = RandomAccessFile(file(cur), "rw").apply { seek(filled[cur]) }
        val buf = ByteArray(64 shl 10)
        val t0 = System.nanoTime()
        var sent = 0L
        var sinceCheck = 0L
        try {
            while (!closed) {
                val room = chunkLen(cur) - filled[cur]
                val n = input.read(buf, 0, minOf(buf.size.toLong(), room).toInt())
                if (n < 0) throw IOException("the tablet's stream ended early")
                raf.write(buf, 0, n)
                lock.withLock { filled[cur] += n; cached += n; changed.signalAll() }
                downloaded += n
                sent += n
                sinceCheck += n
                throttle(t0, sent)
                if (filled[cur] == chunkLen(cur)) {
                    raf.close()
                    val next = cur + 1
                    val go = lock.withLock { next < chunks && filled[next] == 0L && planNext() == next }
                    if (!go) return
                    cur = next
                    raf = RandomAccessFile(file(cur), "rw")
                    sinceCheck = 0
                } else if (sinceCheck >= 1 shl 20) {
                    sinceCheck = 0
                    if (lock.withLock { planNext() } != cur) return
                }
            }
        } finally {
            runCatching { raf.close() }
            runCatching { input.close() }
        }
    }

    /** Keeps the average since [t0] at or under [rateBitsPerSec] (a home router hung under full load). */
    private fun throttle(t0: Long, bytes: Long) {
        val rate = rateBitsPerSec
        if (rate <= 0) return
        val due = bytes * 8 * 1_000_000_000L / rate
        val ahead = due - (System.nanoTime() - t0)
        if (ahead > 2_000_000) Thread.sleep(ahead / 1_000_000)
    }

    companion object {
        /** A Range request from [from] on; checks the server really starts there. */
        fun httpFrom(url: String, from: Long): InputStream {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 15_000
            c.setRequestProperty("Range", "bytes=$from-")
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            val ok = code == 206 && c.getHeaderField("Content-Range")?.startsWith("bytes $from-") == true || code == 200 && from == 0L
            if (!ok) { c.disconnect(); throw IOException("HTTP $code for bytes $from-") }
            return c.inputStream
        }

        /** What [FilmCache] may use on a disk with [usable] bytes free while it already holds [cached]: keep [margin] free. */
        fun budgetFor(cached: Long, usable: Long, margin: Long = 1L shl 30): Long = (cached + usable - margin).coerceAtLeast(0)
    }
}
