// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnexBTest {
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
    private val sc4 = b(0, 0, 0, 1)
    private val sc3 = b(0, 0, 1)

    // HEVC NAL headers: type << 1 in the first byte (VPS 32, SPS 33, PPS 34, IDR 19, trailing 1).
    private val vps = b(0x40, 0x01, 0xAA)
    private val sps = b(0x42, 0x01, 0xBB, 0xBB)
    private val pps = b(0x44, 0x01, 0xCC)
    private val idr = b(0x26, 0x01, 0x11, 0x22, 0x33)
    private val trail = b(0x02, 0x01, 0x44)

    @Test fun hevcKeyFrameGivesItsVpsSpsPps() {
        val au = sc4 + vps + sc4 + sps + sc4 + pps + sc4 + idr
        assertArrayEquals(sc4 + vps + sc4 + sps + sc4 + pps, AnnexB.parameterSets(au, hevc = true))
        assertEquals(listOf(32, 33, 34, 19), AnnexB.nalStarts(au).map { AnnexB.type(au, it, true) })
    }

    @Test fun threeByteStartCodesToo() {
        val au = sc3 + vps + sc3 + sps + sc3 + pps + sc3 + idr
        assertArrayEquals(sc3 + vps + sc3 + sps + sc3 + pps, AnnexB.parameterSets(au, hevc = true))
    }

    @Test fun aFrameWithoutParameterSetsGivesNull() {
        assertNull(AnnexB.parameterSets(sc4 + trail, hevc = true))
        assertNull(AnnexB.parameterSets(b(1, 2, 3), hevc = true))
    }

    @Test fun h264SpsPps() {
        val spsH = b(0x67, 0x64, 0x00, 0x28); val ppsH = b(0x68, 0xEE, 0x3C); val idrH = b(0x65, 0x88, 0x84)
        val au = sc4 + spsH + sc4 + ppsH + sc4 + idrH
        assertArrayEquals(sc4 + spsH + sc4 + ppsH, AnnexB.parameterSets(au, hevc = false))
        assertNull(AnnexB.parameterSets(sc4 + b(0x41, 0x9a, 0x00), hevc = false))
    }
}
