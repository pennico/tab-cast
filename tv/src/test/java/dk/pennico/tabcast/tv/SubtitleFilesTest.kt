// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.ttml.TtmlParser
import dk.pennico.tabcast.link.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.5.4: every subtitle file format the tablet accepts comes out as something the TV's own player (Media3) reads, with
 * the same number of cues the tablet reported. Before, SubRip with '.' before the milliseconds or without cue numbers
 * was accepted but showed nothing.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleFilesTest {
    private fun media3Cues(r: Subtitles.Ready): Int {
        val parser = when (r.format) {
            Subtitles.Format.SRT -> SubripParser()
            Subtitles.Format.TTML -> TtmlParser()
            else -> error("not checked here: ${r.format}")
        }
        var n = 0
        parser.parse(r.bytes, SubtitleParser.OutputOptions.allCues()) { c -> if (c.cues.isNotEmpty()) n++ }
        return n
    }

    private fun check(name: String, text: String, cues: Int) {
        val r = Subtitles.prepare(name, text.toByteArray())!!
        assertEquals("$name: cues the tablet counts", cues, r.cues)
        assertEquals("$name (${r.source}): cues Media3 reads", cues, media3Cues(r))
    }

    @Test fun strictSubRip() = check("a.srt", "1\n00:00:01,000 --> 00:00:03,000\nComma\n\n2\n00:00:04,000 --> 00:00:05,000\nTwo\n", 2)
    @Test fun subRipWithDots() = check("a.srt", "1\n00:00:01.000 --> 00:00:03.000\nDot\n\n2\n00:00:04.000 --> 00:00:05.000\nTwo\n", 2)
    @Test fun subRipWithoutNumbers() = check("a.srt", "00:00:01,000 --> 00:00:03,000\nNo index\n\n00:00:04,000 --> 00:00:05,000\nTwo\n", 2)
    @Test fun microDvd() = check("a.sub", "{1}{1}25\n{25}{75}Hello|world\n{100}{150}{y:i}Leaning\n", 2)
    @Test fun subViewer() = check("a.sub", "[INFORMATION]\n[END INFORMATION]\n00:00:01.00,00:00:03.50\nOne[br]Two\n\n00:00:04.00,00:00:05.00\nThree\n", 2)
    @Test fun sbv() = check("a.sbv", "0:00:01.000,0:00:02.500\nHi there\n\n0:00:03.000,0:00:04.000\nBye\n", 2)
    @Test fun mpl2() = check("a.txt", "[10][30]One|/Two\n[40][]Three\n", 2)
    @Test fun sami() = check("a.smi", "<SAMI><BODY>\n<SYNC Start=1000><P Class=ENCC>Hello<br>there\n<SYNC Start=3000><P Class=ENCC>&nbsp;\n" +
        "<SYNC Start=4000><P Class=ENCC>Bye\n</BODY></SAMI>", 2)
    @Test fun ttml() = check("a.dfxp", "<?xml version=\"1.0\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>" +
        "<p begin=\"00:00:01.000\" end=\"00:00:02.000\">One</p><p begin=\"00:00:03.000\" end=\"00:00:04.000\">Two</p></div></body></tt>\n", 2)
}
