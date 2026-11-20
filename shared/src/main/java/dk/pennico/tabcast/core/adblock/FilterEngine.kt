// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core.adblock

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Ad/tracker filter engine for the standard list syntax (EasyList, EasyPrivacy, uBlock Origin, Peter Lowe's; what
 * Brave's adblock-rust reads), compiled into primitive arrays so 150k rules take a few MB and match in microseconds.
 *
 * Network rules: `||host^`, `|start`, `end|`, `*`, `^`, `@@` exceptions, options for request types, first/third
 * party, `domain=`/`from=`, `important`, `match-case`, `badfilter`, `redirect` (as a block), `generichide`/`elemhide`
 * and `document` exceptions (page-level), `document` blocks (navigation to ad/malware pages). Rules this engine can't
 * apply faithfully (regex patterns, `csp`, `removeparam`, `redirect-rule`, `denyallow`, `popup`, …) are skipped and
 * counted, never half-applied.
 *
 * Element hiding: `domain##selector`, `##selector`, `#@#` exceptions; selectors that need a script (uBO procedural
 * operators, scriptlets, `:style`, HTML filters) are skipped. Generic selectors are indexed by their leading class or
 * id, so a page only gets the ones whose class/id it really has (uBO's approach), plus the few "highly generic" ones.
 *
 * Immutable after [Builder.build]: safe to use from WebView's network threads.
 */
class FilterEngine internal constructor(
    private val pool: ByteArray,
    private val block: NetIndex,
    private val allow: NetIndex,
    private val cos: Cosmetic,
    private val domPool: LongArray,
    val info: Map<String, String>,
) {
    enum class Level { OFF, STANDARD, AGGRESSIVE;
        val label get() = name.lowercase()
        companion object { fun of(s: String?): Level? = entries.firstOrNull { it.label == s?.lowercase() } }
    }

    /** One request: its URL, the type (see [T]) and the page it belongs to. */
    class Request(val url: String, val type: Int, pageUrl: String) {
        val lower: String = url.lowercase()
        internal val hostStart: Int
        internal val hostEnd: Int
        val host: String
        val pageHost: String
        val thirdParty: Boolean
        internal val pageSuffixes: LongArray
        init {
            val r = Domains.hostRange(lower)
            hostStart = r.first; hostEnd = r.last + 1
            host = lower.substring(hostStart, hostEnd)
            val pr = Domains.hostRange(pageUrl)
            pageHost = pageUrl.substring(pr.first, pr.last + 1).lowercase()
            thirdParty = pageHost.isNotEmpty() && Domains.site(host) != Domains.site(pageHost)
            pageSuffixes = Domains.suffixStarts(pageHost).map { Domains.hash(pageHost, it) }.toLongArray()
        }
    }

    enum class Verdict { NONE, BLOCK, ALLOW }

    /** Should this request be blocked at [level]? Standard blocks cross-site requests only, like Brave's default. */
    fun check(r: Request, level: Level): Verdict {
        if (level == Level.OFF) return Verdict.NONE
        if (level == Level.STANDARD && !r.thirdParty) return Verdict.NONE
        val b = find(block, r) { true }
        if (b < 0) return Verdict.NONE
        if (block.flags[b] and F_IMPORTANT != 0) return Verdict.BLOCK
        return if (find(allow, r) { true } >= 0) Verdict.ALLOW else Verdict.BLOCK
    }

    /** Page-level exceptions of a page URL: [PAGE_ALLOWED] (no blocking at all), [PAGE_GENERICHIDE], [PAGE_ELEMHIDE]. */
    fun pageFlags(pageUrl: String): Int {
        val r = Request(pageUrl, T.DOCUMENT, pageUrl)
        var out = 0
        find(allow, r, typeCheck = false) { id ->
            val f = allow.flags[id]
            if (f and T.DOCUMENT != 0) out = out or PAGE_ALLOWED
            if (f and F_GENERICHIDE != 0) out = out or PAGE_GENERICHIDE
            if (f and F_ELEMHIDE != 0) out = out or PAGE_ELEMHIDE
            false // keep looking
        }
        return out
    }

    /** A navigation to a known ad/malware page (`$document` block rules), e.g. a pop-under. */
    fun blocksDocument(url: String, fromPage: String): Boolean {
        val r = Request(url, T.DOCUMENT, fromPage)
        val b = find(block, r) { id -> block.flags[id] and T.DOCUMENT != 0 }
        if (b < 0) return false
        return find(allow, r) { id -> allow.flags[id] and T.DOCUMENT != 0 } < 0
    }

    /** Is the host (or a parent) on a host list as a plain `||host^` rule (the Cast list hides those). */
    fun blockedHost(host: String): Boolean {
        val h = host.lowercase()
        for (s in Domains.suffixStarts(h)) {
            var id = block.lookupHost(Domains.hash(h, s))
            while (id >= 0) {
                val f = block.flags[id]
                if (f and (F_FIRST or F_THIRD) == 0 && block.domRef[id] < 0 && f and T.DEFAULT == T.DEFAULT) return true
                id = block.next[id]
            }
        }
        return false
    }

    // ------------------------------------------------------------------------------------------- element hiding

    /** Site-specific selectors for a frame's host (minus its exceptions), and at Aggressive the highly generic ones. */
    fun specificSelectors(host: String, level: Level, pageFlags: Int = 0): List<String> {
        if (level == Level.OFF || pageFlags and (PAGE_ALLOWED or PAGE_ELEMHIDE) != 0) return emptyList()
        val h = host.lowercase()
        val starts = Domains.suffixStarts(h)
        val suffixes = LongArray(starts.size) { Domains.hash(h, starts[it]) }
        val except = exceptionsFor(suffixes)
        val out = LinkedHashSet<String>()
        for (sx in suffixes) cos.specific.forEach(sx) { id ->
            if (domainsAllow(cos.selDom[id], suffixes)) selector(id).let { if (it !in except) out += it }
        }
        if (level == Level.AGGRESSIVE && pageFlags and PAGE_GENERICHIDE == 0) {
            for (id in cos.complex) if (domainsAllow(cos.selDom[id], suffixes)) selector(id).let { if (it !in except) out += it }
        }
        return out.toList()
    }

    /** Generic selectors (Aggressive) whose leading class/id is among [keys] (".class" / "#id" found on the page). */
    fun genericSelectors(host: String, keys: Collection<String>, level: Level, pageFlags: Int = 0): List<String> {
        if (level != Level.AGGRESSIVE || pageFlags and (PAGE_ALLOWED or PAGE_ELEMHIDE or PAGE_GENERICHIDE) != 0) return emptyList()
        val h = host.lowercase()
        val starts = Domains.suffixStarts(h)
        val suffixes = LongArray(starts.size) { Domains.hash(h, starts[it]) }
        val except = exceptionsFor(suffixes)
        val out = LinkedHashSet<String>()
        for (k in keys) cos.keyed.forEach(Domains.hash32(k)) { id ->
            if (domainsAllow(cos.selDom[id], suffixes)) selector(id).let { if (it !in except) out += it }
        }
        return out.toList()
    }

    private fun exceptionsFor(suffixes: LongArray): Set<String> {
        val out = HashSet<String>()
        for (sx in suffixes) cos.exceptions.forEach(sx) { out += selector(it) }
        return out
    }

    private fun selector(id: Int) = String(pool, cos.selOff[id], cos.selLen[id], Charsets.UTF_8)

    /** Bytes held by the engine's arrays (what the matcher costs in memory, without object headers). */
    fun memoryBytes(): Long = pool.size.toLong() + block.bytes() + allow.bytes() + cos.bytes() + domPool.size * 8L

    // ---------------------------------------------------------------------------------------------- matching

    /** First filter of [idx] matching [r] and [accept], or -1. Important block filters are returned first. */
    private inline fun find(idx: NetIndex, r: Request, typeCheck: Boolean = true, accept: (Int) -> Boolean): Int {
        var found = -1
        // 1. plain host rules, via the request host and its parents
        val host = r.host
        for (s in Domains.suffixStarts(host)) {
            var id = idx.lookupHost(Domains.hash(host, s))
            while (id >= 0) {
                if (matches(idx, id, r, typeCheck) && accept(id)) {
                    if (idx.flags[id] and F_IMPORTANT != 0) return id
                    if (found < 0) found = id
                }
                id = idx.next[id]
            }
        }
        // 2. pattern rules, via the URL's tokens
        val u = r.lower
        var i = 0
        while (i < u.length) {
            if (!tokenChar(u[i])) { i++; continue }
            val a = i
            while (i < u.length && tokenChar(u[i])) i++
            if (i - a < 2) continue
            val slot = idx.tokenSlot(Domains.hash32(u, a, i))
            if (slot < 0) continue
            val end = idx.tokStart[slot] + idx.tokCount[slot]
            for (k in idx.tokStart[slot] until end) {
                val id = idx.tokIds[k]
                if (matches(idx, id, r, typeCheck) && accept(id)) {
                    if (idx.flags[id] and F_IMPORTANT != 0) return id
                    if (found < 0) found = id
                }
            }
        }
        // 3. the few rules without a usable token
        for (id in idx.untok) {
            if (matches(idx, id, r, typeCheck) && accept(id)) {
                if (idx.flags[id] and F_IMPORTANT != 0) return id
                if (found < 0) found = id
            }
        }
        return found
    }

    private fun matches(idx: NetIndex, id: Int, r: Request, typeCheck: Boolean): Boolean {
        val f = idx.flags[id]
        if (typeCheck && f and r.type == 0) return false
        if (f and F_THIRD != 0 && !r.thirdParty) return false
        if (f and F_FIRST != 0 && r.thirdParty) return false
        if (!domainsAllow(idx.domRef[id], r.pageSuffixes)) return false
        val len = idx.patLen[id]
        if (len < 0) return true // plain host rule: the host lookup was the match
        val text = if (f and F_MATCH_CASE != 0) r.url else r.lower
        return Pattern.matches(pool, idx.patOff[id], len, f, text, r.hostStart, r.hostEnd)
    }

    /** `domain=` check: the most specific listed parent of the page decides; with no listed parent, includes must be absent. */
    private fun domainsAllow(ref: Int, pageSuffixes: LongArray): Boolean {
        if (ref < 0) return true
        val n = domPool[ref].toInt()
        var hasInclude = false
        for (k in 1..n) if (domPool[ref + k] and 1L == 0L) { hasInclude = true; break }
        for (h in pageSuffixes) {
            val key = h and 1L.inv()
            for (k in 1..n) {
                val e = domPool[ref + k]
                if (e and 1L.inv() == key) return e and 1L == 0L
            }
        }
        return !hasInclude
    }

    companion object {
        // flags: bits 0-11 request types (T), then:
        const val F_IMPORTANT = 1 shl 13
        const val F_THIRD = 1 shl 14
        const val F_FIRST = 1 shl 15
        const val F_MATCH_CASE = 1 shl 16
        const val F_ANCHOR_HOST = 1 shl 17
        const val F_ANCHOR_START = 1 shl 18
        const val F_ANCHOR_END = 1 shl 19
        const val F_GENERICHIDE = 1 shl 20
        const val F_ELEMHIDE = 1 shl 21

        const val PAGE_ALLOWED = 1
        const val PAGE_GENERICHIDE = 2
        const val PAGE_ELEMHIDE = 4

        internal fun tokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'

        private const val MAGIC = 0x54434142 // "TCAB"
        private const val VERSION = 1

        fun read(inp: DataInputStream): FilterEngine {
            if (inp.readInt() != MAGIC || inp.readInt() != VERSION) throw IOException("not a Tab Cast filter cache (or an old one)")
            val info = (0 until inp.readInt()).associate { inp.readUTF() to inp.readUTF() }
            val pool = inp.readBytes()
            val block = NetIndex.read(inp)
            val allow = NetIndex.read(inp)
            val cos = Cosmetic.read(inp)
            val dom = inp.readLongs()
            return FilterEngine(pool, block, allow, cos, dom, info)
        }
    }

    fun write(out: DataOutputStream) {
        out.writeInt(MAGIC); out.writeInt(VERSION)
        out.writeInt(info.size)
        for ((k, v) in info) { out.writeUTF(k); out.writeUTF(v.take(2000)) }
        out.writeBytes(pool)
        block.write(out)
        allow.write(out)
        cos.write(out)
        out.writeLongs(domPool)
    }
}

/** Request types (bit masks), as the list options name them. */
object T {
    const val SCRIPT = 1; const val IMAGE = 2; const val STYLESHEET = 4; const val XHR = 8; const val SUBDOCUMENT = 16
    const val MEDIA = 32; const val FONT = 64; const val OBJECT = 128; const val PING = 256; const val WEBSOCKET = 512
    const val OTHER = 1024; const val DOCUMENT = 2048
    /** Every type but the page itself: what a rule without type options applies to. */
    const val DEFAULT = 2047
    const val ALL = 4095
    /** A request whose type can't be told from its URL and headers: the usual script/XHR/beacon kinds. */
    const val UNKNOWN = SCRIPT or XHR or OTHER or PING

    /** The type of a WebView request from its URL and headers (WebView doesn't say). */
    fun of(url: String, headers: Map<String, String>?, mainFrame: Boolean): Int {
        if (mainFrame) return DOCUMENT
        if (url.startsWith("ws:") || url.startsWith("wss:")) return WEBSOCKET
        var end = url.length
        for (i in url.indices) if (url[i] == '?' || url[i] == '#') { end = i; break }
        val slash = url.lastIndexOf('/', end - 1)
        val dot = url.lastIndexOf('.', end - 1)
        if (dot > slash && end - dot <= 6) {
            when (url.substring(dot + 1, end).lowercase()) {
                "js", "mjs" -> return SCRIPT
                "css" -> return STYLESHEET
                "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> return IMAGE
                "woff", "woff2", "ttf", "otf", "eot" -> return FONT
                "mp4", "webm", "m3u8", "mpd", "ts", "m4s", "mp3", "aac", "m4a", "ogg", "m4v", "mov" -> return MEDIA
                "html", "htm" -> return SUBDOCUMENT
            }
        }
        var accept: String? = null
        var range = false
        headers?.forEach { (k, v) ->
            if (k.equals("Accept", true)) accept = v.lowercase()
            if (k.equals("Range", true)) range = true
        }
        val a = accept
        return when {
            a == null -> if (range) MEDIA else UNKNOWN
            a.startsWith("text/css") -> STYLESHEET
            a.startsWith("image/") -> IMAGE
            a.startsWith("text/html") -> SUBDOCUMENT
            a.startsWith("video/") || a.startsWith("audio/") || range -> MEDIA
            a.startsWith("application/json") || a.contains("javascript") && !a.startsWith("*/*") -> XHR or SCRIPT
            else -> UNKNOWN
        }
    }
}

/** ABP pattern matching over the pattern pool: `*` any run, `^` a separator or the end, anchors from the flags. */
internal object Pattern {
    private fun sep(c: Char) = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

    fun matches(p: ByteArray, o: Int, n: Int, flags: Int, s: String, hostStart: Int, hostEnd: Int): Boolean {
        val end = o + n
        if (n == 0) return true
        val first = star(p, o, end)
        return when {
            flags and FilterEngine.F_ANCHOR_HOST != 0 -> {
                var pos = hostStart
                while (pos < hostEnd) {
                    if (pos == hostStart || s[pos - 1] == '.') {
                        val e = seg(p, o, first, end, s, pos)
                        if (e >= 0 && rest(p, first, end, flags, s, e)) return true
                    }
                    pos++
                }
                false
            }
            flags and FilterEngine.F_ANCHOR_START != 0 -> {
                val e = seg(p, o, first, end, s, 0)
                e >= 0 && rest(p, first, end, flags, s, e)
            }
            else -> {
                val c0 = p[o].toInt().toChar()
                for (pos in 0..s.length) {
                    if (c0 != '^' && (pos >= s.length || s[pos] != c0)) continue
                    val e = seg(p, o, first, end, s, pos)
                    if (e >= 0 && rest(p, first, end, flags, s, e)) return true
                }
                false
            }
        }
    }

    private fun star(p: ByteArray, from: Int, end: Int): Int {
        for (i in from until end) if (p[i] == '*'.code.toByte()) return i
        return end
    }

    /** Matches the star-free segment p[a, b) at s[pos]: the end index, or -1. */
    private fun seg(p: ByteArray, a: Int, b: Int, end: Int, s: String, pos: Int): Int {
        var i = pos
        for (k in a until b) {
            val c = p[k].toInt().toChar()
            if (c == '^') {
                if (i == s.length) { if (k == end - 1) return i else return -1 }
                if (!sep(s[i])) return -1
                i++
            } else {
                if (i >= s.length || s[i] != c) return -1
                i++
            }
        }
        return i
    }

    /** The rest of the pattern from [at] (a '*' or the end) against s from [pos]. */
    private fun rest(p: ByteArray, at: Int, end: Int, flags: Int, s: String, pos: Int): Boolean {
        if (at >= end) return flags and FilterEngine.F_ANCHOR_END == 0 || pos == s.length
        val a = at + 1
        val b = star(p, a, end)
        if (a == b) return rest(p, b, end, flags, s, pos)
        for (q in pos..s.length) {
            val e = seg(p, a, b, end, s, q)
            if (e >= 0 && rest(p, b, end, flags, s, e)) return true
        }
        return false
    }
}

/** One side (block or allow) of the network rules. */
internal class NetIndex(
    val flags: IntArray, val patOff: IntArray, val patLen: IntArray, val domRef: IntArray, val next: IntArray,
    val hostKeys: LongArray, val hostHead: IntArray,
    val tokKeys: IntArray, val tokStart: IntArray, val tokCount: IntArray, val tokIds: IntArray,
    val untok: IntArray,
) {
    fun lookupHost(h: Long): Int {
        val mask = hostKeys.size - 1
        var i = (h xor (h ushr 29)).toInt() and mask
        while (true) {
            val k = hostKeys[i]
            if (k == 0L) return -1
            if (k == h) return hostHead[i]
            i = (i + 1) and mask
        }
    }

    fun tokenSlot(h: Int): Int {
        val mask = tokKeys.size - 1
        var i = (h xor (h ushr 15)) and mask
        while (true) {
            val k = tokKeys[i]
            if (k == 0) return -1
            if (k == h) return i
            i = (i + 1) and mask
        }
    }

    fun bytes(): Long = 4L * (flags.size + patOff.size + patLen.size + domRef.size + next.size + hostHead.size +
        tokKeys.size + tokStart.size + tokCount.size + tokIds.size + untok.size) + 8L * hostKeys.size

    fun write(o: DataOutputStream) {
        for (a in listOf(flags, patOff, patLen, domRef, next)) o.writeInts(a)
        o.writeLongs(hostKeys); o.writeInts(hostHead)
        for (a in listOf(tokKeys, tokStart, tokCount, tokIds, untok)) o.writeInts(a)
    }

    companion object {
        fun read(i: DataInputStream) = NetIndex(i.readInts(), i.readInts(), i.readInts(), i.readInts(), i.readInts(),
            i.readLongs(), i.readInts(), i.readInts(), i.readInts(), i.readInts(), i.readInts(), i.readInts())
    }
}

/** A long -> [ids] multimap (host hash -> selector ids) or int -> [ids] (key hash -> selector ids), CSR layout. */
internal class Multi(val keys: LongArray, val start: IntArray, val count: IntArray, val ids: IntArray) {
    inline fun forEach(key: Long, f: (Int) -> Unit) {
        if (keys.isEmpty()) return
        val mask = keys.size - 1
        var i = (key xor (key ushr 29)).toInt() and mask
        while (true) {
            val k = keys[i]
            if (k == 0L) return
            if (k == key) { for (j in start[i] until start[i] + count[i]) f(ids[j]); return }
            i = (i + 1) and mask
        }
    }
    inline fun forEach(key: Int, f: (Int) -> Unit) = forEach(key.toLong() and 0xffffffffL or 0x100000000L, f)

    fun bytes() = keys.size * 8L + 4L * (start.size + count.size + ids.size)
    fun write(o: DataOutputStream) { o.writeLongs(keys); o.writeInts(start); o.writeInts(count); o.writeInts(ids) }

    companion object {
        fun read(i: DataInputStream) = Multi(i.readLongs(), i.readInts(), i.readInts(), i.readInts())

        fun build(map: Map<Long, List<Int>>): Multi {
            var cap = 16
            while (cap < map.size * 2) cap = cap shl 1
            val keys = LongArray(cap); val start = IntArray(cap); val count = IntArray(cap)
            val ids = IntArray(map.values.sumOf { it.size })
            var n = 0
            for ((k, v) in map) {
                var i = (k xor (k ushr 29)).toInt() and (cap - 1)
                while (keys[i] != 0L) i = (i + 1) and (cap - 1)
                keys[i] = k; start[i] = n; count[i] = v.size
                for (x in v) ids[n++] = x
            }
            return Multi(keys, start, count, ids)
        }
    }
}

/** Element-hiding rules. */
internal class Cosmetic(
    val selOff: IntArray, val selLen: IntArray, val selDom: IntArray,
    val specific: Multi, val exceptions: Multi, val keyed: Multi, val complex: IntArray,
) {
    fun bytes() = 4L * (selOff.size + selLen.size + selDom.size + complex.size) + specific.bytes() + exceptions.bytes() + keyed.bytes()
    fun write(o: DataOutputStream) {
        o.writeInts(selOff); o.writeInts(selLen); o.writeInts(selDom)
        specific.write(o); exceptions.write(o); keyed.write(o); o.writeInts(complex)
    }
    companion object {
        fun read(i: DataInputStream) = Cosmetic(i.readInts(), i.readInts(), i.readInts(), Multi.read(i), Multi.read(i), Multi.read(i), i.readInts())
    }
}

internal fun DataOutputStream.writeInts(a: IntArray) { writeInt(a.size); val b = java.nio.ByteBuffer.allocate(a.size * 4); b.asIntBuffer().put(a); write(b.array()) }
internal fun DataOutputStream.writeLongs(a: LongArray) { writeInt(a.size); val b = java.nio.ByteBuffer.allocate(a.size * 8); b.asLongBuffer().put(a); write(b.array()) }
internal fun DataOutputStream.writeBytes(a: ByteArray) { writeInt(a.size); write(a) }
internal fun DataInputStream.readInts(): IntArray { val n = readInt(); val b = ByteArray(n * 4); readFully(b); return IntArray(n).also { java.nio.ByteBuffer.wrap(b).asIntBuffer().get(it) } }
internal fun DataInputStream.readLongs(): LongArray { val n = readInt(); val b = ByteArray(n * 8); readFully(b); return LongArray(n).also { java.nio.ByteBuffer.wrap(b).asLongBuffer().get(it) } }
internal fun DataInputStream.readBytes(): ByteArray { val n = readInt(); return ByteArray(n).also { readFully(it) } }
