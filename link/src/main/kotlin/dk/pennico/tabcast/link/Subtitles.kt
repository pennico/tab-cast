// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 0.5.1: a subtitle file for a film from the tablet, made ready for the TV's player: read in whatever encoding it has
 * (UTF-8 with or without BOM, UTF-16 with BOM, else Windows-1252, which covers Latin-1 files with æøå/accents) and
 * sent as UTF-8, which is what Media3's SubRip/SSA/WebVTT parsers expect. The format comes from the content (a .txt
 * or a mislabelled file still works), else from the name.
 */
object Subtitles {
    enum class Format(val ext: String, val mime: String) {
        SRT("srt", "application/x-subrip"), VTT("vtt", "text/vtt"), SSA("ass", "text/x-ssa")
    }

    class Ready(val text: String, val bytes: ByteArray, val format: Format, val charset: String, val cues: Int)

    const val MAX_BYTES = 8 shl 20
    val EXTENSIONS = listOf("srt", "vtt", "ass", "ssa")

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

    fun formatOf(name: String, text: String): Format {
        val head = text.trimStart().take(200)
        return when {
            head.startsWith("WEBVTT") -> Format.VTT
            head.startsWith("[Script Info]", ignoreCase = true) || head.contains("[V4+ Styles]") || head.contains("[V4 Styles]") -> Format.SSA
            Regex("""^\d+\s*\r?\n\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""").containsMatchIn(head) -> Format.SRT
            else -> when (name.substringAfterLast('.', "").lowercase()) {
                "vtt" -> Format.VTT; "ass", "ssa" -> Format.SSA; else -> Format.SRT
            }
        }
    }

    /** Null when it isn't a subtitle file we can use (too big, or no cue in it). */
    fun prepare(name: String, raw: ByteArray): Ready? {
        if (raw.isEmpty() || raw.size > MAX_BYTES) return null
        val (text0, cs) = decode(raw)
        val text = text0.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿")
        val f = formatOf(name, text)
        val cues = when (f) {
            Format.SSA -> Regex("""(?m)^Dialogue:""").findAll(text).count()
            else -> Regex("""\d{1,2}:\d{2}[:.,]\d{2}[,.]?\d*\s*-->|\d{2}:\d{2}\.\d{3}\s*-->""").findAll(text).count()
        }
        if (cues == 0) return null
        return Ready(text, text.toByteArray(Charsets.UTF_8), f, cs, cues)
    }
}
