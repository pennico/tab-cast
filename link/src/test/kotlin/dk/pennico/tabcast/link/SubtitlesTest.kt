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

    // ---------------------------------------------------------------- 0.5.4: more formats, strict SubRip out

    private fun ok(name: String, text: String): Subtitles.Ready = Subtitles.prepare(name, text.toByteArray())!!

    @Test fun srtVariantsAreRewrittenStrictly() {
        val dotted = ok("a.srt", "1\n00:00:01.000 --> 00:00:03.000\nDot\n\n2\n00:00:04.5 --> 00:00:05.25\nTwo\n")
        assertEquals("1\n00:00:01,000 --> 00:00:03,000\nDot\n\n2\n00:00:04,500 --> 00:00:05,250\nTwo\n", dotted.text)
        val noIndex = ok("a.srt", "00:00:01,000 --> 00:00:03,000\nNo index\n\n00:00:04,000 --> 00:00:05,000\nTwo\nlines\n")
        assertEquals("1\n00:00:01,000 --> 00:00:03,000\nNo index\n\n2\n00:00:04,000 --> 00:00:05,000\nTwo\nlines\n", noIndex.text)
        assertEquals(2, noIndex.cues)
        // Numbers that are text stay; position info after the times is dropped.
        val num = ok("a.srt", "7\n00:00:01,000 --> 00:00:02,000 X1:10 X2:20\n42\n")
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\n42\n", num.text)
    }

    @Test fun microDvdWithAndWithoutFrameRate() {
        val r = ok("Film.sub", "{1}{1}25\n{25}{75}Hello|world\n{100}{150}{y:i}Leaning\n")
        assertEquals(Subtitles.Format.SRT, r.format); assertEquals("MicroDVD, 25 fps", r.source)
        assertEquals("1\n00:00:01,000 --> 00:00:03,000\nHello\nworld\n\n2\n00:00:04,000 --> 00:00:06,000\n<i>Leaning</i>\n", r.text)
        val d = ok("Film.sub", "{0}{24}One\n")
        assertEquals("MicroDVD, 23.976 fps", d.source)
        assertTrue(d.text.contains("00:00:00,000 --> 00:00:01,001"))
    }

    @Test fun subViewerAndSbv() {
        val sv = ok("a.sub", "[INFORMATION]\n[TITLE]x\n[END INFORMATION]\n[SUBTITLE]\n00:00:01.00,00:00:03.50\nOne[br]Two\n\n00:00:04.00,00:00:05.00\nThree\n")
        assertEquals("SubViewer", sv.source)
        assertEquals("1\n00:00:01,000 --> 00:00:03,500\nOne\nTwo\n\n2\n00:00:04,000 --> 00:00:05,000\nThree\n", sv.text)
        val sbv = ok("a.sbv", "0:00:01.000,0:00:02.500\nHi there\n\n0:00:03.000,0:00:04.000\nBye\n")
        assertEquals("SBV", sbv.source); assertEquals(2, sbv.cues)
        assertTrue(sbv.text.startsWith("1\n00:00:01,000 --> 00:00:02,500\nHi there\n"))
    }

    @Test fun samiKeepsTheFirstLanguage() {
        val smi = """<SAMI><HEAD><STYLE>.ENCC {Name: English;} .DACC {Name: Dansk;}</STYLE></HEAD><BODY>
<SYNC Start=1000><P Class=ENCC>Hello &amp; welcome<br>to the film
<P Class=DACC>Hej og velkommen
<SYNC Start=3000><P Class=ENCC>&nbsp;
<SYNC Start=4000><P Class=ENCC><i>Søren</i> says hi
</BODY></SAMI>"""
        val r = ok("a.smi", smi)
        assertEquals("SAMI", r.source)
        assertEquals("1\n00:00:01,000 --> 00:00:03,000\nHello & welcome\nto the film\n\n2\n00:00:04,000 --> 00:00:08,000\n<i>Søren</i> says hi\n", r.text)
    }

    @Test fun mpl2() {
        val r = ok("a.txt", "[10][30]One|/Two\n[40][]Three\n")
        assertEquals("MPL2", r.source)
        assertEquals("1\n00:00:01,000 --> 00:00:03,000\nOne\n<i>Two</i>\n\n2\n00:00:04,000 --> 00:00:07,000\nThree\n", r.text)
    }

    @Test fun ttmlGoesAsItIs() {
        val ttml = "<?xml version=\"1.0\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>" +
            "<p begin=\"00:00:01.000\" end=\"00:00:02.000\">One</p><p begin=\"00:00:03.000\" end=\"00:00:04.000\">Two</p></div></body></tt>\n"
        val r = ok("a.dfxp", ttml)
        assertEquals(Subtitles.Format.TTML, r.format); assertEquals("application/ttml+xml", r.format.mime); assertEquals(2, r.cues)
        assertEquals(ttml, r.text)
    }

    @Test fun pictureSubtitlesAreNotTextFiles() {
        assertNull(Subtitles.prepare("a.idx", "# VobSub index file, v7 (do not modify this line!)\nsize: 720x576\n".toByteArray()))
        assertNull(Subtitles.prepare("a.sub", ByteArray(2048) { (0xBA + it).toByte() }))
    }
}
