// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * 0.5.1: a subtitle file for a film from the tablet, made ready for the TV's player: read in whatever encoding it has
 * (UTF-8 with or without BOM, UTF-16 with BOM, else Windows-1252, which covers Latin-1 files with æøå/accents) and
 * sent as UTF-8, which is what Media3's SubRip/SSA/WebVTT parsers expect. The format comes from the content (a .txt
 * or a mislabelled file still works), else from the name.
 *
 * 0.5.4: more formats. WebVTT, SSA/ASS and TTML/DFXP go to the TV as they are. SubRip is rewritten in its strict form
 * (cue numbers, "HH:MM:SS,mmm"), because Media3 shows nothing for the common variants with a dot before the
 * milliseconds or without cue numbers. MicroDVD and SubViewer (both often ".sub"), YouTube's SBV, SAMI (".smi") and
 * MPL2 are converted to SubRip on the tablet, so every TV version shows them. Picture subtitles (VobSub .idx/.sub,
 * PGS .sup) can't be sent as files; inside a film the TV reads them itself where its player can.
 */
object Subtitles {
    enum class Format(val ext: String, val mime: String) {
        SRT("srt", "application/x-subrip"), VTT("vtt", "text/vtt"), SSA("ass", "text/x-ssa"),
        TTML("ttml", "application/ttml+xml")
    }

    /** [source]: what the file was (e.g. "MicroDVD, 23.976 fps"), for the log; [format]: what goes to the TV. */
    class Ready(val text: String, val bytes: ByteArray, val format: Format, val charset: String, val cues: Int,
                val source: String = format.name)

    const val MAX_BYTES = 8 shl 20
    /** Names looked for next to a film, best first ("txt" last: often not subtitles). */
    val EXTENSIONS = listOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp", "sub", "sbv", "smi", "sami", "mpl", "txt")
    /** Without a frame rate in a MicroDVD file: the most common one for films. */
    const val MICRODVD_FPS = 23.976

    fun decode(b: ByteArray): Pair<String, String> {
        fun bom(vararg x: Int) = b.size >= x.size && x.indices.all { (b[it].toInt() and 0xff) == x[it] }
        return when {
            bom(0xEF, 0xBB, 0xBF) -> String(b, 3, b.size - 3, Charsets.UTF_8) to "UTF-8"
            bom(0xFF, 0xFE) -> String(b, 2, b.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
            bom(0xFE, 0xFF) -> String(b, 2, b.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
            else -> try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString() to "UTF-8"
            } catch (e: CharacterCodingException) {
                String(b, Charset.forName("windows-1252")) to "Windows-1252"
            }
        }
    }

    /** What the TV gets for this file (SubRip for every format converted on the tablet). */
    fun formatOf(name: String, text: String): Format = when (kindOf(name, text)) {
        Kind.VTT -> Format.VTT
        Kind.SSA -> Format.SSA
        Kind.TTML -> Format.TTML
        else -> Format.SRT
    }

    /** Null when it isn't a subtitle file we can use (too big, a picture format, or no cue in it). */
    fun prepare(name: String, raw: ByteArray): Ready? {
        if (raw.isEmpty() || raw.size > MAX_BYTES) return null
        val (text0, cs) = decode(raw)
        val text = text0.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿")
        return when (val k = kindOf(name, text)) {
            Kind.VTT -> asIs(text, Format.VTT, cs, Regex("""(?m)^\s*(?:\d+:)?\d{1,2}:\d{2}\.\d{3}\s*-->""").findAll(text).count())
            Kind.SSA -> asIs(text, Format.SSA, cs, Regex("""(?m)^Dialogue:""").findAll(text).count())
            Kind.TTML -> asIs(text, Format.TTML, cs, Regex("""<(?:\w+:)?p[\s>]""").findAll(text).count())
            Kind.SRT -> srt(parseSrt(text), cs, "SubRip")
            Kind.SAMI -> srt(parseSami(text), cs, "SAMI")
            Kind.MICRODVD -> { val (cues, fps) = parseMicroDvd(text); srt(cues, cs, "MicroDVD, ${fmtFps(fps)} fps") }
            Kind.MPL2 -> srt(parseMpl2(text), cs, "MPL2")
            Kind.SUBVIEWER -> srt(parseCommaTimes(text), cs, if ("[INFORMATION]" in text || "[br]" in text) "SubViewer" else "SBV")
            Kind.NONE -> null
        }
    }

    // ------------------------------------------------------------------------------------------- detection

    private enum class Kind { VTT, SSA, TTML, SRT, SAMI, MICRODVD, MPL2, SUBVIEWER, NONE }

    private val SRT_TIMING = Regex("""^\s*((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[,.]\d{1,3})?)\s*-->\s*((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[,.]\d{1,3})?)""")
    private val COMMA_TIMING = Regex("""^\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3})\s*,\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3})\s*$""")
    private val MICRODVD_LINE = Regex("""^\{(\d+)\}\{(\d*)\}(.*)$""")
    private val MPL2_LINE = Regex("""^\[(\d+)\]\[(\d*)\](.*)$""")

    private fun kindOf(name: String, text: String): Kind {
        val head = text.trimStart().take(4000)
        val firstLine = head.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val lines = head.lineSequence().take(200).toList()
        return when {
            head.startsWith("WEBVTT") -> Kind.VTT
            head.startsWith("[Script Info]", ignoreCase = true) || "[V4+ Styles]" in head || "[V4 Styles]" in head -> Kind.SSA
            Regex("""<tt[\s>]""").containsMatchIn(head) || Regex("""<\w+:tt[\s>]""").containsMatchIn(head) -> Kind.TTML
            Regex("""<sami[\s>]""", RegexOption.IGNORE_CASE).containsMatchIn(head) -> Kind.SAMI
            MICRODVD_LINE.matches(firstLine) -> Kind.MICRODVD
            MPL2_LINE.matches(firstLine) -> Kind.MPL2
            lines.any { SRT_TIMING.containsMatchIn(it) } -> Kind.SRT
            lines.any { COMMA_TIMING.matches(it) } -> Kind.SUBVIEWER
            else -> when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "vtt" -> Kind.VTT; "ass", "ssa" -> Kind.SSA
                else -> Kind.NONE
            }
        }
    }

    // ------------------------------------------------------------------------------------------- parsers

    private class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** h:mm:ss[,.]fff, mm:ss[,.]fff or h:mm:ss: the digits after the separator are a decimal fraction. */
    private fun ms(t: String): Long? {
        val (clock, frac) = t.split(',', '.').let { it[0] to it.getOrNull(1).orEmpty() }
        val p = clock.split(':').map { it.toLongOrNull() ?: return null }
        val s = when (p.size) { 3 -> p[0] * 3600 + p[1] * 60 + p[2]; 2 -> p[0] * 60 + p[1]; else -> return null }
        val f = if (frac.isEmpty()) 0L else frac.take(3).padEnd(3, '0').toLong()
        return s * 1000 + f
    }

    /** SubRip as written by many tools: index lines optional, '.' or ',' before the milliseconds, extra spaces. */
    private fun parseSrt(text: String): List<Cue> {
        val lines = text.split('\n')
        val out = ArrayList<Cue>()
        var start = -1L; var end = -1L
        val body = ArrayList<String>()
        var open = false
        fun flush() { if (start >= 0 && body.isNotEmpty()) out += Cue(start, end, body.joinToString("\n")); body.clear(); start = -1 }
        for ((i, l) in lines.withIndex()) {
            val m = SRT_TIMING.find(l)
            if (m != null) {
                flush()
                val s = ms(m.groupValues[1]); val e = ms(m.groupValues[2])
                if (s != null && e != null) { start = s; end = e; open = true } else open = false
                continue
            }
            if (l.isBlank()) { open = false; continue }
            // A cue number: a line of digits right before a timing line.
            if (l.trim().all { it.isDigit() } && lines.getOrNull(i + 1)?.let { SRT_TIMING.containsMatchIn(it) } == true) continue
            if (open) body += l.trimEnd()
        }
        flush()
        return out
    }

    /** MicroDVD: {startFrame}{endFrame}Text|Second line; an optional first cue {1}{1}25 gives the frame rate. */
    private fun parseMicroDvd(text: String): Pair<List<Cue>, Double> {
        var fps = MICRODVD_FPS
        val out = ArrayList<Cue>()
        for ((n, l) in text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.withIndex()) {
            val m = MICRODVD_LINE.matchEntire(l) ?: continue
            val body = m.groupValues[3]
            if (n == 0 && body.trim().toDoubleOrNull()?.takeIf { it in 5.0..120.0 } != null) { fps = body.trim().toDouble(); continue }
            val s = m.groupValues[1].toLong(); val e = m.groupValues[2].toLongOrNull()
            val italic = Regex("""\{[yY]:[^}]*i[^}]*\}""").containsMatchIn(body)
            val t = body.replace(Regex("""\{[^}]*\}"""), "").split('|').joinToString("\n") { it.trim() }.trim()
            if (t.isEmpty()) continue
            val startMs = (s * 1000 / fps).toLong()
            val endMs = e?.let { (it * 1000 / fps).toLong() } ?: (startMs + 3000)
            out += Cue(startMs, endMs, if (italic) "<i>$t</i>" else t)
        }
        return out to fps
    }

    /** MPL2: [start][end]Text|Second line, times in tenths of a second; '/' at a line's start is italic. */
    private fun parseMpl2(text: String): List<Cue> = text.lineSequence().mapNotNull { raw ->
        val m = MPL2_LINE.matchEntire(raw.trim()) ?: return@mapNotNull null
        val s = m.groupValues[1].toLong() * 100
        val e = m.groupValues[2].toLongOrNull()?.times(100) ?: (s + 3000)
        val t = m.groupValues[3].split('|').joinToString("\n") { line ->
            val x = line.trim(); if (x.startsWith("/")) "<i>${x.removePrefix("/").trim()}</i>" else x
        }.trim()
        if (t.isEmpty()) null else Cue(s, e, t)
    }.toList()

    /** SubViewer 2 and YouTube SBV: a "start,end" line, then text lines until a blank line ([br] = new line). */
    private fun parseCommaTimes(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val m = COMMA_TIMING.matchEntire(lines[i])
            i++
            if (m == null) continue
            val s = ms(m.groupValues[1]) ?: continue
            val e = ms(m.groupValues[2]) ?: continue
            val body = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() && !COMMA_TIMING.matches(lines[i])) { body += lines[i].trim(); i++ }
            val t = body.joinToString("\n").replace(Regex("""\[br]""", RegexOption.IGNORE_CASE), "\n").trim()
            if (t.isNotEmpty()) out += Cue(s, e, t)
        }
        return out
    }

    /**
     * SAMI (.smi): <SYNC Start=ms> blocks; a block ends where the next begins, an empty block (&nbsp;) clears the
     * screen. A file with several languages (P classes) keeps the first one used.
     */
    private fun parseSami(text: String): List<Cue> {
        val body = Regex("""<body[^>]*>(.*)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.groupValues?.get(1) ?: text
        val syncs = Regex("""<sync\s+start\s*=\s*["']?(\d+)["']?[^>]*>""", RegexOption.IGNORE_CASE).findAll(body).toList()
        val pClass = Regex("""<p\b[^>]*class\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
        val firstClass = pClass.find(body)?.groupValues?.get(1)
        val blocks = syncs.mapIndexed { i, m ->
            val from = m.range.last + 1
            val to = syncs.getOrNull(i + 1)?.range?.first ?: body.length
            m.groupValues[1].toLong() to body.substring(from, to)
        }
        val out = ArrayList<Cue>()
        for ((i, b) in blocks.withIndex()) {
            val (start, html) = b
            // Several languages: only the part in the first language's <P>.
            val part = if (firstClass != null && pClass.findAll(html).count() > 1) {
                Regex("""<p\b[^>]*class\s*=\s*["']?$firstClass["']?[^>]*>(.*?)(?=<p\b|$)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.get(1) ?: html
            } else html
            val t = htmlText(part)
            if (t.isEmpty()) continue
            val end = blocks.getOrNull(i + 1)?.first ?: (start + 4000)
            if (end > start) out += Cue(start, end, t)
        }
        return out
    }

    private fun htmlText(html: String): String {
        val withBreaks = html.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</?(?!i\b|b\b|u\b)[a-z!][^>]*>""", RegexOption.IGNORE_CASE), "")
        val decoded = withBreaks.replace("&nbsp;", " ", ignoreCase = true).replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
            .replace("&amp;", "&")
        return decoded.split('\n').map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    // ------------------------------------------------------------------------------------------- output

    private fun asIs(text: String, f: Format, cs: String, cues: Int): Ready? =
        if (cues == 0) null else Ready(text, text.toByteArray(Charsets.UTF_8), f, cs, cues)

    /** Strict SubRip, which every TV version's player reads: numbered, sorted, "HH:MM:SS,mmm", no blank lines in a cue. */
    private fun srt(cues: List<Cue>, cs: String, source: String): Ready? {
        val ok = cues.filter { it.endMs > it.startMs && it.text.isNotBlank() }.sortedBy { it.startMs }
        if (ok.isEmpty()) return null
        val sb = StringBuilder()
        for ((i, c) in ok.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(i + 1).append('\n').append(clock(c.startMs)).append(" --> ").append(clock(c.endMs)).append('\n')
            c.text.split('\n').filter { it.isNotBlank() }.forEach { sb.append(it).append('\n') }
        }
        val text = sb.toString()
        return Ready(text, text.toByteArray(Charsets.UTF_8), Format.SRT, cs, ok.size, source)
    }

    private fun clock(ms: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun fmtFps(f: Double) = if (f == Math.floor(f)) f.toLong().toString() else String.format(Locale.ROOT, "%.3f", f)
}
