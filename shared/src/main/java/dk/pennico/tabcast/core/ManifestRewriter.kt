// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

/**
 * Rewrites HLS / DASH manifests so every URL the player follows points back at the relay.
 * [map] turns an absolute upstream URL into a relay URL.
 */
object ManifestRewriter {
    private val uriAttr = Regex("""URI="([^"]*)"""")

    fun rewriteHls(text: String, baseUrl: String, map: (String) -> String): String {
        val sb = StringBuilder(text.length + 1024)
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val t = line.trim()
            when {
                t.isEmpty() -> sb.append(line)
                t.startsWith("#") -> sb.append(uriAttr.replace(line) { m ->
                    val v = m.groupValues[1]
                    val abs = Urls.resolve(baseUrl, v)
                    if (Urls.isHttp(abs)) "URI=\"" + map(abs) + "\"" else m.value
                })
                else -> {
                    val abs = Urls.resolve(baseUrl, t)
                    sb.append(if (Urls.isHttp(abs)) map(abs) else line)
                }
            }
            sb.append('\n')
        }
        sb.setLength(sb.length - 1) // split() yields one more part than there are newlines
        return sb.toString()
    }

    private val baseUrlEl = Regex("""(<(?:\w+:)?BaseURL\b[^>]*>)([^<]*)(</(?:\w+:)?BaseURL>)""")
    private val urlAttrs = Regex("""(\s(?:media|initialization|sourceURL|xlink:href|href)\s*=\s*")([^"]*)(")""")

    /**
     * DASH: relative references keep working because relay URLs preserve the upstream path; only absolute,
     * protocol-relative and root-relative references (which would escape the relay) are mapped.
     * [baseUrl] must be the URL the client used to fetch this MPD (after redirects, see RelayServer).
     */
    fun rewriteDash(text: String, baseUrl: String, map: (String) -> String): String {
        fun fix(v: String): String {
            val unesc = v.replace("&amp;", "&")
            val t = unesc.trim()
            if (!(Urls.isHttp(t) || t.startsWith("/"))) return v
            val abs = Urls.resolve(baseUrl, t)
            if (!Urls.isHttp(abs)) return v
            return map(abs).replace("&", "&amp;")
        }
        var out = baseUrlEl.replace(text) { m -> m.groupValues[1] + fix(m.groupValues[2]) + m.groupValues[3] }
        out = urlAttrs.replace(out) { m -> m.groupValues[1] + fix(m.groupValues[2]) + m.groupValues[3] }
        // Location elements point a live MPD at its next refresh URL.
        out = Regex("""(<Location>)([^<]*)(</Location>)""").replace(out) { m -> m.groupValues[1] + fix(m.groupValues[2]) + m.groupValues[3] }
        return out
    }
}
