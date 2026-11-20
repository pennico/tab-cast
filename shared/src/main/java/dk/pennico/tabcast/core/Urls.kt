// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

/**
 * String-level URL helpers. Deliberately not java.net.URI: real-world media URLs often contain characters
 * (`|`, spaces, `{}`) that URI rejects, and we must pass them through byte-for-byte.
 */
object Urls {
    fun isHttp(u: String): Boolean =
        u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)

    fun scheme(u: String): String = u.substringBefore("://", "").lowercase()

    /** "https://host:port" (no trailing slash), or "" if not absolute. */
    fun origin(u: String): String {
        val i = u.indexOf("://")
        if (i < 0) return ""
        val rest = u.substring(i + 3)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        return u.substring(0, i + 3) + rest.substring(0, end)
    }

    /** Host without port and userinfo, lower-case. */
    fun host(u: String): String {
        val o = origin(u)
        if (o.isEmpty()) return ""
        var h = o.substringAfter("://")
        h = h.substringAfterLast('@')
        if (h.startsWith("[")) return h.substringBefore("]") + "]"
        return h.substringBefore(':').lowercase()
    }

    /** Path without query or fragment ("/" if empty). */
    fun path(u: String): String {
        val o = origin(u)
        if (o.isEmpty()) return u.substringBefore('?').substringBefore('#')
        val rest = u.substring(o.length).substringBefore('#').substringBefore('?')
        return rest.ifEmpty { "/" }
    }

    fun stripFragment(u: String): String = u.substringBefore('#')

    /** Lower-case file extension of the URL path ("" if none). */
    fun ext(u: String): String {
        val last = path(u).substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        return if (dot < 0 || dot == last.length - 1) "" else last.substring(dot + 1).lowercase()
    }

    /** Resolve [ref] against [base] (RFC 3986 style, string-based). */
    fun resolve(base: String, ref: String): String {
        val r = ref.trim()
        if (r.isEmpty()) return stripFragment(base)
        if (isHttp(r)) return r
        if (r.startsWith("//")) return scheme(base).ifEmpty { "https" } + ":" + r
        if (r.contains("://") || r.startsWith("data:") || r.startsWith("blob:") || r.startsWith("skd:")) return r
        val o = origin(base)
        if (r.startsWith("/")) return o + normalizePath(r)
        val baseNoFrag = stripFragment(base)
        if (r.startsWith("?")) return baseNoFrag.substringBefore('?') + r
        if (r.startsWith("#")) return baseNoFrag + r
        val basePath = path(base)
        val dir = basePath.substring(0, basePath.lastIndexOf('/') + 1).ifEmpty { "/" }
        return o + normalizePath(dir + r)
    }

    /** Remove "." and ".." segments from the path part (query untouched). */
    fun normalizePath(pathAndQuery: String): String {
        val q = pathAndQuery.indexOf('?')
        val p = if (q < 0) pathAndQuery else pathAndQuery.substring(0, q)
        val query = if (q < 0) "" else pathAndQuery.substring(q)
        if (!p.contains("/.")) return pathAndQuery
        val out = ArrayList<String>()
        val segs = p.split('/')
        for ((i, s) in segs.withIndex()) {
            when (s) {
                "." -> if (i == segs.lastIndex) out.add("")
                ".." -> {
                    if (out.size > 1) out.removeAt(out.lastIndex)
                    if (i == segs.lastIndex) out.add("")
                }
                else -> out.add(s)
            }
        }
        var joined = out.joinToString("/")
        if (!joined.startsWith("/")) joined = "/$joined"
        return joined + query
    }

    /** True if [host] equals [domain] or is a subdomain of it. */
    fun hostMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /** Extract the first http(s) URL from free text (Share intents often add a title before the link). */
    fun firstUrlIn(text: String): String? {
        val m = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
    }
}
