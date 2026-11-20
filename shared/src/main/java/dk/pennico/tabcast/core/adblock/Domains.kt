// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core.adblock

/** Host helpers for the filter engine: 64-bit hashes (no strings kept for 100k hosts), sites, parent domains. */
object Domains {
    /** FNV-1a 64 of s[from, to), lower-cased ASCII. Never 0 (0 marks an empty slot in the tables). */
    fun hash(s: CharSequence, from: Int = 0, to: Int = s.length): Long {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037
        for (i in from until to) {
            var c = s[i].code
            if (c in 65..90) c += 32
            h = (h xor c.toLong()) * 0x100000001b3L
        }
        return if (h == 0L) 1L else h
    }

    /** 32-bit FNV-1a of s[from, to) as is (tokens and cosmetic keys). Never 0. */
    fun hash32(s: CharSequence, from: Int = 0, to: Int = s.length): Int {
        var h = -0x7ee3623b // 2166136261
        for (i in from until to) h = (h xor s[i].code) * 16777619
        return if (h == 0) 1 else h
    }

    /** Country-code second levels where the site is three labels deep (bbc.co.uk, abc.net.au, x.com.br). */
    private val SECOND_LEVELS = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "nic", "mil", "ltd", "plc", "sch", "nhs")

    /**
     * The registrable domain ("site") of a host, by a small rule instead of the 200 KB public suffix list:
     * the last two labels, or three when the second-to-last is a common second level under a two-letter country code.
     * Used for first/third-party decisions, where an occasional wrong guess only changes Standard vs Aggressive.
     */
    fun site(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h[0].isDigit() && h.all { it.isDigit() || it == '.' } || ':' in h) return h // IPs
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val tld = parts[parts.size - 1]
        val sld = parts[parts.size - 2]
        val n = if (tld.length == 2 && sld in SECOND_LEVELS) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    /** Start indexes of the host and its parent domains, most specific first ("a.b.c" -> 0, 2, 4). */
    fun suffixStarts(host: String): IntArray {
        var n = 1
        for (c in host) if (c == '.') n++
        val out = IntArray(n)
        var k = 1
        for (i in host.indices) if (host[i] == '.') out[k++] = i + 1
        return out
    }

    /** host[from, to) of an http(s) URL: after "://" (and any user@), up to / ? # or :port. */
    fun hostRange(url: String): IntRange {
        val p = url.indexOf("://")
        var a = if (p < 0) 0 else p + 3
        var b = a
        while (b < url.length) {
            val c = url[b]
            if (c == '/' || c == '?' || c == '#') break
            if (c == '@') a = b + 1
            b++
        }
        val colon = url.lastIndexOf(':', b - 1).takeIf { it >= a && url.indexOf(']', a).let { r -> r < 0 || r < it } } ?: -1
        return a until (if (colon >= 0) colon else b)
    }
}
