// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core.adblock

import java.io.BufferedReader

/** Parses filter lists and compiles them into a [FilterEngine]. Not thread-safe; one builder per compile. */
class FilterBuilder {
    private class Net(val flags: Int, val pattern: String?, val host: String?, val domains: List<Pair<String, Boolean>>?, val raw: String)

    private val blocks = ArrayList<Net>()
    private val allows = ArrayList<Net>()
    private val badfilters = HashSet<String>()

    private val selKey = HashMap<String, Int>()
    private val selText = ArrayList<String>()
    private val selDomains = ArrayList<List<Pair<String, Boolean>>?>()
    private val specific = HashMap<Long, MutableList<Int>>()
    private val exceptions = HashMap<Long, MutableList<Int>>()
    private val generic = LinkedHashSet<Int>()
    private val genericExceptions = HashSet<String>()

    /** What was read and what was skipped (logged, and in the 0.2.2 report). */
    val stats = LinkedHashMap<String, Int>()
    private val info = LinkedHashMap<String, String>()

    private fun count(k: String) { stats[k] = (stats[k] ?: 0) + 1 }

    /** Adds one list; [name] and its "Version"/"Last modified" header go into the engine's info (logged). */
    fun addList(name: String, reader: BufferedReader) {
        var version: String? = null
        var n = 0
        reader.forEachLine { line ->
            n++
            if (version == null && n < 40 && line.startsWith("!")) {
                val l = line.removePrefix("!").trim()
                if (l.startsWith("Version:", true)) version = l.substringAfter(':').trim()
                else if (l.startsWith("Last modified:", true)) version = l.substringAfter(':').trim()
            }
            addLine(line)
        }
        info["list.$name"] = "${version ?: "?"} ($n lines)"
    }

    fun addLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        val sep = cosmeticSeparator(line)
        if (sep != null) { addCosmetic(line, sep.first, sep.second); return }
        if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
            val h = line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase()
            if (validHost(h) && h != "localhost" && h != "0.0.0.0") { blocks += Net(T.DEFAULT, null, h, null, line); count("network.hosts") }
            return
        }
        addNetwork(line)
    }

    // ------------------------------------------------------------------------------------------------- cosmetic

    /** (index, kind): kind 0 = hide "##", 1 = exception "#@#", 2 = needs a script/style (skipped). */
    private fun cosmeticSeparator(line: String): Pair<Int, Int>? {
        var i = line.indexOf('#')
        while (i >= 0 && i + 1 < line.length) {
            val kind = when {
                line.startsWith("##", i) -> 0
                line.startsWith("#@#", i) -> 1
                line.startsWith("#?#", i) || line.startsWith("#$#", i) || line.startsWith("#@?#", i) ||
                    line.startsWith("#@$#", i) || line.startsWith("#%#", i) || line.startsWith("#@%#", i) -> 2
                else -> -1
            }
            if (kind >= 0) {
                val prefix = line.substring(0, i)
                // A domain list can't have / | $ = in it: then "##" is part of a network rule's URL.
                if (prefix.none { it == '/' || it == '|' || it == '$' || it == '=' || it == '^' }) return i to kind
                return null
            }
            i = line.indexOf('#', i + 1)
        }
        return null
    }

    private val procedural = listOf(":-abp-", ":has-text(", ":contains(", ":xpath(", ":upward(", ":remove(", ":style(",
        ":matches-css", ":min-text-length(", ":watch-attr(", ":matches-path(", ":matches-attr(", ":others(", ":if(",
        ":if-not(", ":nth-ancestor(", ":remove-attr(", ":remove-class(", ":matches-media(", ":matches-prop(", ":shadow(")

    private fun addCosmetic(line: String, at: Int, kind: Int) {
        if (kind == 2) { count("skipped.cosmetic-script-or-style"); return }
        val sel = line.substring(at + if (kind == 0) 2 else 3).trim()
        if (sel.isEmpty() || sel.startsWith("+js(") || sel.startsWith("^") || sel.length > 1000) { count("skipped.scriptlet-or-html"); return }
        if (procedural.any { sel.contains(it) }) { count("skipped.cosmetic-procedural"); return }
        val raw = line.substring(0, at)
        val entries = if (raw.isEmpty()) emptyList() else raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val usable = entries.filter { '*' !in it && '/' !in it }
        if (entries.isNotEmpty() && usable.isEmpty()) { count("skipped.cosmetic-entity"); return }
        val includes = usable.filter { !it.startsWith('~') }
        val excludes = usable.filter { it.startsWith('~') }.map { it.substring(1) }
        if (kind == 1) {
            if (includes.isEmpty()) genericExceptions += sel
            else for (d in includes) exceptions.getOrPut(Domains.hash(d)) { ArrayList() } += selectorId(sel, null)
            count("cosmetic.exception")
            return
        }
        val dom = excludes.takeIf { it.isNotEmpty() }?.map { it to false }
        val id = selectorId(sel, dom)
        if (includes.isEmpty()) { generic += id; count("cosmetic.generic") }
        else { for (d in includes) specific.getOrPut(Domains.hash(d)) { ArrayList() } += id; count("cosmetic.specific") }
    }

    private fun selectorId(sel: String, dom: List<Pair<String, Boolean>>?): Int {
        val key = if (dom == null) sel else sel + "\u0000" + dom.joinToString(",") { it.first }
        return selKey.getOrPut(key) { selText += sel; selDomains += dom; selText.size - 1 }
    }

    /** ".class" / "#id" a generic selector starts with, when that alone is enough to find candidates; else null. */
    internal fun genericKey(sel: String): String? {
        if (sel.length < 2 || (sel[0] != '.' && sel[0] != '#') || ',' in sel) return null
        var i = 1
        while (i < sel.length && (sel[i].isLetterOrDigit() || sel[i] == '-' || sel[i] == '_')) i++
        if (i == 1) return null
        if (i < sel.length && sel[i] !in " >+~[:.#") return null
        return sel.substring(0, i)
    }

    // -------------------------------------------------------------------------------------------------- network

    private val skipOptions = setOf("popup", "popunder", "redirect-rule", "csp", "removeparam", "urlskip", "uritransform",
        "urltransform", "replace", "permissions", "header", "method", "to", "denyallow", "rewrite", "empty", "mp4", "cname",
        "inline-script", "inline-font", "webrtc", "specifichide", "shide", "genericblock", "ipaddress", "requestheader",
        "responseheader", "jsonprune", "xmlprune", "cookie", "stealth", "network", "content", "jsinject", "extension", "app")

    private fun optionsLike(o: String) = o.isNotEmpty() && o.split(',').all { Regex("~?[a-z0-9_-]+(=.*)?", RegexOption.IGNORE_CASE).matches(it) }

    private fun addNetwork(line: String) {
        var s = line
        val exception = s.startsWith("@@")
        if (exception) s = s.substring(2)
        var opts: List<String> = emptyList()
        val d = s.lastIndexOf('$')
        if (d >= 0 && optionsLike(s.substring(d + 1))) { opts = s.substring(d + 1).split(','); s = s.substring(0, d) }
        if (s.length >= 2 && s.startsWith("/") && s.endsWith("/")) { count("skipped.regex"); return }

        var flags = 0
        var types = 0
        var negTypes = 0
        var domains: List<Pair<String, Boolean>>? = null
        var badfilter = false
        var pageOnly = false
        for (o in opts) {
            val neg = o.startsWith('~')
            val name = o.removePrefix("~").substringBefore('=').lowercase()
            val value = o.substringAfter('=', "")
            val t = when (name) {
                "script" -> T.SCRIPT; "image" -> T.IMAGE; "stylesheet", "css" -> T.STYLESHEET
                "xmlhttprequest", "xhr" -> T.XHR; "subdocument", "frame" -> T.SUBDOCUMENT; "media" -> T.MEDIA
                "font" -> T.FONT; "object", "object-subrequest" -> T.OBJECT; "ping", "beacon" -> T.PING
                "websocket" -> T.WEBSOCKET; "other" -> T.OTHER; "document", "doc" -> T.DOCUMENT; "all" -> T.ALL
                else -> 0
            }
            if (t != 0) { if (neg) negTypes = negTypes or t else types = types or t; continue }
            when (name) {
                "third-party", "3p", "strict3p" -> flags = flags or if (neg) FilterEngine.F_FIRST else FilterEngine.F_THIRD
                "first-party", "1p", "strict1p" -> flags = flags or if (neg) FilterEngine.F_THIRD else FilterEngine.F_FIRST
                "domain", "from" -> {
                    val list = value.split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    val usable = list.filter { '*' !in it && '/' !in it }
                    if (usable.isEmpty()) { count("skipped.domain-entity"); return }
                    domains = usable.map { if (it.startsWith('~')) it.substring(1) to false else it to true }
                }
                "important" -> flags = flags or FilterEngine.F_IMPORTANT
                "match-case" -> flags = flags or FilterEngine.F_MATCH_CASE
                "badfilter" -> badfilter = true
                "generichide", "ghide" -> { flags = flags or FilterEngine.F_GENERICHIDE; pageOnly = true }
                "elemhide", "ehide" -> { flags = flags or FilterEngine.F_ELEMHIDE; pageOnly = true }
                "redirect", "reason", "_", "" -> {}
                else -> { count(if (name in skipOptions) "skipped.option.$name" else "skipped.unknown-option"); return }
            }
        }
        if (pageOnly && !exception) { count("skipped.unknown-option"); return }
        var typeMask = if (types != 0) types else T.DEFAULT
        typeMask = typeMask and negTypes.inv()
        if (pageOnly && types == 0) typeMask = 0
        if (badfilter) {
            badfilters += (if (exception) "@@" else "") + s + opts.filter { it.lowercase() != "badfilter" }.let { if (it.isEmpty()) "" else "$" + it.joinToString(",") }
            count("badfilter")
            return
        }
        if (s.startsWith("||")) { flags = flags or FilterEngine.F_ANCHOR_HOST; s = s.substring(2) }
        else if (s.startsWith("|")) { flags = flags or FilterEngine.F_ANCHOR_START; s = s.substring(1) }
        if (s.endsWith("|")) { flags = flags or FilterEngine.F_ANCHOR_END; s = s.dropLast(1) }
        if (flags and FilterEngine.F_MATCH_CASE == 0) s = s.lowercase()
        if (s.startsWith("*")) { s = s.trimStart('*'); flags = flags and (FilterEngine.F_ANCHOR_HOST or FilterEngine.F_ANCHOR_START).inv() }
        if (s.endsWith("*")) { s = s.trimEnd('*'); flags = flags and FilterEngine.F_ANCHOR_END.inv() }
        if (s.any { it.code > 126 }) { count("skipped.non-ascii"); return }
        if (s.isEmpty() && domains == null && !pageOnly && typeMask and T.DOCUMENT == 0) { count("skipped.too-broad"); return }
        flags = flags or typeMask

        var host: String? = null
        if (flags and FilterEngine.F_ANCHOR_HOST != 0 && flags and FilterEngine.F_MATCH_CASE == 0) {
            val h = when {
                s.endsWith("^") -> s.dropLast(1)
                flags and FilterEngine.F_ANCHOR_END != 0 -> s
                else -> null
            }
            if (h != null && validHost(h)) host = h
        }
        val r = Net(flags, if (host != null) null else s, host, domains, line)
        if (exception) { allows += r; count("network.exception") } else { blocks += r; count(if (host != null) "network.hosts" else "network.patterns") }
    }

    private fun validHost(h: String) = h.isNotEmpty() && h.length < 254 && '.' in h &&
        h.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' } && !h.startsWith('.') && !h.endsWith('.')

    // ---------------------------------------------------------------------------------------------------- build

    private val pool = java.io.ByteArrayOutputStream(1 shl 20)
    private val domPool = ArrayList<Long>()

    private fun append(s: String): Int { val off = pool.size(); pool.write(s.toByteArray(Charsets.UTF_8)); return off }

    private fun addDomains(d: List<Pair<String, Boolean>>?): Int {
        if (d.isNullOrEmpty()) return -1
        val ref = domPool.size
        domPool += d.size.toLong()
        for ((host, include) in d) domPool += (Domains.hash(host) and 1L.inv()) or if (include) 0L else 1L
        return ref
    }

    /** Safe tokens of a pattern: runs of [a-z0-9%] the URL must contain as a whole run (not cut by a '*' or an open end). */
    private fun tokens(p: String, flags: Int): List<String> {
        val out = ArrayList<String>()
        val l = p.lowercase()
        var i = 0
        while (i < l.length) {
            if (!FilterEngine.tokenChar(l[i])) { i++; continue }
            val a = i
            while (i < l.length && FilterEngine.tokenChar(l[i])) i++
            if (i - a < 2) continue
            val leftOk = if (a == 0) flags and (FilterEngine.F_ANCHOR_START or FilterEngine.F_ANCHOR_HOST) != 0 else l[a - 1] != '*'
            val rightOk = if (i == l.length) flags and FilterEngine.F_ANCHOR_END != 0 else l[i] != '*'
            if (leftOk && rightOk) out += l.substring(a, i)
        }
        return out
    }

    fun build(): FilterEngine {
        val dropped = blocks.count { it.raw in badfilters } + allows.count { it.raw in badfilters }
        if (dropped > 0) stats["badfilter.removed"] = dropped
        val b = blocks.filter { it.raw !in badfilters }
        val a = allows.filter { it.raw !in badfilters }
        val freq = HashMap<String, Int>()
        for (r in b + a) if (r.pattern != null) for (t in tokens(r.pattern, r.flags).toSet()) freq[t] = (freq[t] ?: 0) + 1
        val block = index(b, freq)
        val allow = index(a, freq)

        // element hiding
        val keyed = HashMap<Long, MutableList<Int>>()
        val complex = ArrayList<Int>()
        for (id in generic) {
            if (selText[id] in genericExceptions) continue
            val k = genericKey(selText[id])
            if (k != null) keyed.getOrPut(Domains.hash32(k).toLong() and 0xffffffffL or 0x100000000L) { ArrayList() } += id
            else complex += id
        }
        val selOff = IntArray(selText.size)
        val selLen = IntArray(selText.size)
        val selDom = IntArray(selText.size)
        for (i in selText.indices) {
            val bytes = selText[i].toByteArray(Charsets.UTF_8)
            selOff[i] = pool.size(); selLen[i] = bytes.size; pool.write(bytes)
            selDom[i] = addDomains(selDomains[i])
        }
        val cos = Cosmetic(selOff, selLen, selDom, Multi.build(specific), Multi.build(exceptions), Multi.build(keyed), complex.toIntArray())
        stats["cosmetic.generic.keyed"] = keyed.values.sumOf { it.size }
        stats["cosmetic.generic.complex"] = complex.size
        stats["network.untokenized"] = block.untok.size + allow.untok.size
        info["rules"] = stats.entries.joinToString(", ") { "${it.key} ${it.value}" }
        info["count.network"] = (b.size + a.size).toString()
        info["count.hiding"] = selText.size.toString()
        return FilterEngine(pool.toByteArray(), block, allow, cos, domPool.toLongArray(), LinkedHashMap(info))
    }

    private fun index(rules: List<Net>, freq: Map<String, Int>): NetIndex {
        val n = rules.size
        val flags = IntArray(n); val patOff = IntArray(n); val patLen = IntArray(n); val domRef = IntArray(n); val next = IntArray(n) { -1 }
        val hostHeads = HashMap<Long, Int>()
        val tok = HashMap<Int, MutableList<Int>>()
        val untok = ArrayList<Int>()
        for ((i, r) in rules.withIndex()) {
            flags[i] = r.flags
            domRef[i] = addDomains(r.domains)
            if (r.host != null) {
                patLen[i] = -1
                val h = Domains.hash(r.host)
                next[i] = hostHeads[h] ?: -1
                hostHeads[h] = i
            } else {
                val p = r.pattern!!
                patOff[i] = append(p); patLen[i] = p.toByteArray(Charsets.UTF_8).size
                val best = tokens(p, r.flags).minWithOrNull(compareBy<String> { freq[it] ?: 0 }.thenByDescending { it.length })
                if (best == null) untok += i else tok.getOrPut(Domains.hash32(best)) { ArrayList() } += i
            }
        }
        var hcap = 16
        while (hcap * 3 < hostHeads.size * 4) hcap = hcap shl 1 // load factor <= 0.75
        val hostKeys = LongArray(hcap); val hostHead = IntArray(hcap)
        for ((h, head) in hostHeads) {
            var i = (h xor (h ushr 29)).toInt() and (hcap - 1)
            while (hostKeys[i] != 0L) i = (i + 1) and (hcap - 1)
            hostKeys[i] = h; hostHead[i] = head
        }
        var tcap = 16
        while (tcap < tok.size * 2) tcap = tcap shl 1
        val tokKeys = IntArray(tcap); val tokStart = IntArray(tcap); val tokCount = IntArray(tcap)
        val tokIds = IntArray(tok.values.sumOf { it.size })
        var k = 0
        for ((h, ids) in tok) {
            var i = (h xor (h ushr 15)) and (tcap - 1)
            while (tokKeys[i] != 0) i = (i + 1) and (tcap - 1)
            tokKeys[i] = h; tokStart[i] = k; tokCount[i] = ids.size
            for (id in ids) tokIds[k++] = id
        }
        return NetIndex(flags, patOff, patLen, domRef, next, hostKeys, hostHead, tokKeys, tokStart, tokCount, tokIds, untok.toIntArray())
    }
}
