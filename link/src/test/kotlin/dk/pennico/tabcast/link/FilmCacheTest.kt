// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 0.4.0: the tablet's FileServer and the TV's FilmCache over real HTTP on localhost. */
class FilmCacheTest {
    private val server = FileServer()
    private val dirs = ArrayList<File>()
    private val caches = ArrayList<FilmCache>()

    @After fun tearDown() {
        caches.forEach { it.close() }
        server.stop()
        dirs.forEach { it.deleteRecursively() }
    }

    private class Bytes(val data: ByteArray) : FileServer.Source {
        override val size get() = data.size.toLong()
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len.toLong(), data.size - pos).toInt()
            System.arraycopy(data, pos.toInt(), buf, off, n)
            return n
        }
    }

    private fun film(size: Int, seed: Long = 1) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun serve(data: ByteArray, name: String = "Film (2026).mkv"): String {
        val port = server.start()
        val e = server.add(name, "video/x-matroska", Bytes(data))
        return server.url("127.0.0.1:$port", e)
    }

    private fun cache(url: String, size: Long, budget: Long = Long.MAX_VALUE / 4, chunk: Int = 256 shl 10,
                      keepBehind: Long = 512L shl 10, rate: Long = 0,
                      open: (String, Long) -> java.io.InputStream = FilmCache::httpFrom): FilmCache {
        val d = Files.createTempDirectory("film").toFile().also { dirs += it }
        return FilmCache(d, url, size, { _ -> budget }, rate, {}, chunk, keepBehind, open).also { caches += it; it.start() }
    }

    /** Reads [len] bytes at [pos] through the cache (blocking, like the player). */
    private fun FilmCache.readFully(pos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = read(pos + got, out, got, len - got)
            if (n < 0) break
            got += n
        }
        return out.copyOf(got)
    }

    private fun waitFor(ms: Long = 10_000, what: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!what()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out"); Thread.sleep(20) }
    }

    // ------------------------------------------------------------------------------------------------ FileServer

    @Test fun serverRangesAndSecrecy() {
        val data = film(100_000)
        val url = serve(data)
        fun get(u: String, range: String?): Pair<Int, ByteArray> {
            val c = URL(u).openConnection() as HttpURLConnection
            range?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
            if (code == 206) assertTrue(c.getHeaderField("Content-Range")!!.endsWith("/100000"))
            return code to body
        }
        assertEquals(200, get(url, null).first)
        assertArrayEquals(data, get(url, null).second)
        val (c1, b1) = get(url, "bytes=10-19")
        assertEquals(206, c1); assertArrayEquals(data.copyOfRange(10, 20), b1)
        assertArrayEquals(data.copyOfRange(99_000, 100_000), get(url, "bytes=99000-").second)
        assertArrayEquals(data.copyOfRange(99_990, 100_000), get(url, "bytes=-10").second)
        assertEquals(416, get(url, "bytes=200000-").first)
        // Only the random path is served.
        val port = server.port
        assertEquals(404, get("http://127.0.0.1:$port/", null).first)
        assertEquals(404, get("http://127.0.0.1:$port/f/" + "0".repeat(32) + "/x.mkv", null).first)
        assertTrue(url.contains("Film%20%282026%29.mkv"))
    }

    @Test fun rangeParsing() {
        assertEquals(0L to 9L, Http.parseRange("bytes=0-9", 100))
        assertEquals(90L to 99L, Http.parseRange("bytes=90-200", 100))
        assertEquals(50L to 99L, Http.parseRange("bytes=50-", 100))
        assertEquals(0L to 99L, Http.parseRange("bytes=-500", 100))
        assertEquals(Http.BAD_RANGE, Http.parseRange("bytes=100-", 100))
        assertNull(Http.parseRange("items=1-2", 100))
    }

    // ------------------------------------------------------------------------------------------------- FilmCache

    @Test fun wholeFilmEndsUpOnTheTv() {
        val data = film(3_000_000)
        val c = cache(serve(data), data.size.toLong())
        assertArrayEquals(data.copyOfRange(0, 1000), c.readFully(0, 1000))
        waitFor { c.stats().complete }
        val s = c.stats()
        assertEquals(data.size.toLong(), s.cached)
        assertArrayEquals(data, c.readFully(0, data.size))
        // Random reads anywhere.
        val r = Random(7)
        repeat(50) {
            val p = r.nextInt(data.size - 5000)
            assertArrayEquals(data.copyOfRange(p, p + 5000), c.readFully(p.toLong(), 5000))
        }
        assertEquals(-1, c.read(data.size.toLong(), ByteArray(10), 0, 10))
    }

    @Test fun restartedAppKeepsWhatItHad() {
        val data = film(1_500_000)
        val url = serve(data)
        val c = cache(url, data.size.toLong())
        waitFor { c.stats().complete }
        c.close()
        // Same folder, the tablet gone: everything still reads.
        val again = FilmCache(c.dir, url, data.size.toLong(), { _ -> Long.MAX_VALUE / 4 }, 0, {}, 256 shl 10, 512L shl 10,
            { _, _ -> throw IOException("tablet asleep") }).also { caches += it }
        assertTrue(again.stats().complete)
        assertArrayEquals(data, again.readFully(0, data.size))
    }

    @Test fun filmBiggerThanTheCacheRollsThrough() {
        val data = film(6_000_000)
        val budget = 2_000_000L
        val c = cache(serve(data), data.size.toLong(), budget = budget, chunk = 128 shl 10, keepBehind = 256L shl 10)
        val maxSeen = AtomicLong()
        val watch = Thread { while (!Thread.currentThread().isInterrupted) { maxSeen.accumulateAndGet(c.stats().cached, ::maxOf); try { Thread.sleep(5) } catch (_: InterruptedException) { break } } }
        watch.start()
        // The player reads the container's tail first (an index), then plays from the start to the end.
        assertArrayEquals(data.copyOfRange(data.size - 4000, data.size), c.readFully(data.size - 4000L, 4000))
        var pos = 0
        while (pos < data.size) {
            val n = minOf(64 * 1024, data.size - pos)
            assertArrayEquals("at $pos", data.copyOfRange(pos, pos + n), c.readFully(pos.toLong(), n))
            pos += n
        }
        watch.interrupt(); watch.join()
        assertTrue("cache stayed within its budget (max ${maxSeen.get()})", maxSeen.get() <= budget)
        assertFalse(c.stats().complete)
        // Short rewind: still here.
        assertArrayEquals(data.copyOfRange(data.size - 200_000, data.size - 100_000), c.readFully(data.size - 200_000L, 100_000))
    }

    @Test fun tabletAsleepThenBack() {
        val data = film(2_000_000)
        val url = serve(data)
        val down = AtomicBoolean(false)
        val opened = AtomicLong()
        val c = cache(url, data.size.toLong(), chunk = 128 shl 10, open = { u, from ->
            if (down.get()) throw IOException("no route to host")
            opened.incrementAndGet()
            val s = FilmCache.httpFrom(u, from)
            // The tablet falls asleep mid-stream once 700 kB are on the TV.
            object : java.io.InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (down.get()) throw IOException("connection reset")
                    return s.read(b, off, len)
                }
                override fun close() = s.close()
            }
        }, rate = 4_000_000)
        assertArrayEquals(data.copyOfRange(0, 100_000), c.readFully(0, 100_000))
        waitFor { c.stats().cached >= 700_000 }
        down.set(true)
        // The player goes on up to what the TV holds, then waits ("Waiting for the tablet")...
        val held = c.stats().aheadBytes
        assertTrue(held > 0)
        val result = AtomicLong(-1)
        val reader = Thread { result.set(c.readFully(0, data.size).size.toLong()) }
        reader.start()
        waitFor(15_000) { c.stats().waiting }
        assertTrue(c.stats().failures > 0)
        // ...and resumes by itself when the tablet answers again.
        down.set(false)
        reader.join(20_000)
        assertEquals(data.size.toLong(), result.get())
        assertFalse(c.stats().waiting)
        assertArrayEquals(data, c.readFully(0, data.size))
    }

    @Test fun rateCap() {
        val data = film(1_000_000)
        val c = cache(serve(data), data.size.toLong(), rate = 4_000_000) // 1 MB at 4 Mbit/s ≈ 2 s
        val t0 = System.nanoTime()
        waitFor { c.stats().complete }
        val s = (System.nanoTime() - t0) / 1e9
        assertTrue("took $s s", s >= 1.6)
    }

    @Test fun jumpAheadFetchesThereFirst() {
        val data = film(4_000_000)
        val c = cache(serve(data), data.size.toLong(), chunk = 128 shl 10, rate = 8_000_000) // ≈ 4 s for all of it
        assertArrayEquals(data.copyOfRange(0, 1000), c.readFully(0, 1000))
        val t0 = System.nanoTime()
        // A seek to 75 %: there in well under the time the whole film takes.
        assertArrayEquals(data.copyOfRange(3_000_000, 3_010_000), c.readFully(3_000_000, 10_000))
        assertTrue((System.nanoTime() - t0) / 1e9 < 2.0)
        assertFalse(c.stats().complete)
    }

    @Test fun budgetMath() {
        assertEquals(3L shl 30, FilmCache.budgetFor(1L shl 30, 3L shl 30))
        assertEquals(0L, FilmCache.budgetFor(0, 512L shl 20))
    }
}
