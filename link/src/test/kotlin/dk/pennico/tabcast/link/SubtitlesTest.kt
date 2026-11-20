// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    private val srt = "1\r\n00:00:01,000 --> 00:00:03,000\r\nHvad så, Søren? Ærlig talt…\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,500\r\nÇa va, garçon?\r\n"

    @Test fun utf8WithAndWithoutBom() {
        val r = Subtitles.prepare("Film.srt", srt.toByteArray(Charsets.UTF_8))!!
        assertEquals("UTF-8", r.charset); assertEquals(Subtitles.Format.SRT, r.format); assertEquals(2, r.cues)
        assertTrue(r.text.contains("Søren") && !r.text.contains('\r'))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.toByteArray(Charsets.UTF_8)
        assertTrue(Subtitles.prepare("Film.srt", bom)!!.text.startsWith("1\n"))
    }

    @Test fun latin1AndWindows1252BecomeUtf8() {
        val r = Subtitles.prepare("Film.da.srt", srt.replace("…", "...").toByteArray(Charsets.ISO_8859_1))!!
        assertEquals("Windows-1252", r.charset)
        assertTrue(r.text.contains("Hvad så, Søren? Ærlig talt...") && r.text.contains("Ça va, garçon?"))
        assertEquals(String(r.bytes, Charsets.UTF_8), r.text)
        val w = Subtitles.prepare("Film.srt", srt.toByteArray(charset("windows-1252")))!!
        assertTrue(w.text.contains("talt…"))
    }

    @Test fun utf16WithBom() {
        val b = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + srt.toByteArray(Charsets.UTF_16LE)
        assertTrue(Subtitles.prepare("x.srt", b)!!.text.contains("Søren"))
    }

    @Test fun formatFromContentThenName() {
        assertEquals(Subtitles.Format.VTT, Subtitles.prepare("a.txt", "WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n".toByteArray())!!.format)
        assertEquals(Subtitles.Format.SRT, Subtitles.prepare("a.vtt", srt.toByteArray())!!.format)
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,Hej\n"
        assertEquals(Subtitles.Format.SSA, Subtitles.prepare("a.ass", ass.toByteArray())!!.format)
    }

    @Test fun notSubtitles() {
        assertNull(Subtitles.prepare("a.srt", ByteArray(0)))
        assertNull(Subtitles.prepare("a.srt", "just some text".toByteArray()))
    }
}
