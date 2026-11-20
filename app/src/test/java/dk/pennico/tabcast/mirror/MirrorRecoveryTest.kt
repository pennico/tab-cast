// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.5.2: what Mirror screen does after its encoder is lost (two levels: hardware, then software). */
class MirrorRecoveryTest {
    @Test fun aLostEncoderIsMadeAgainAtTheSameLevel() {
        val r = MirrorRecovery(2)
        assertEquals(0, r.lost(1_000))
        assertEquals(0, r.lost(20_000))
        assertEquals(2, r.lostTotal)
    }

    @Test fun threeLossesWithinAMinuteGoOneLevelDown() {
        val r = MirrorRecovery(2)
        assertEquals(0, r.lost(1_000))
        assertEquals(0, r.lost(4_000))
        assertEquals(1, r.lost(8_000))
        // The software level gets its own three.
        assertEquals(1, r.lost(9_000))
        assertEquals(1, r.lost(10_000))
        assertEquals(-1, r.lost(11_000))
    }

    @Test fun lossesFarApartNeverGiveUp() {
        val r = MirrorRecovery(2)
        repeat(20) { i -> assertEquals(0, r.lost(i * 40_000L)) }
    }

    @Test fun aLevelWithNoEncoderIsSkipped() {
        val r = MirrorRecovery(2)
        assertEquals(1, r.unavailable())
        assertEquals(-1, r.unavailable())
    }

    @Test fun lossesBeforeAStepDownDontCountBelow() {
        val r = MirrorRecovery(2)
        r.lost(1_000); r.lost(2_000)
        assertEquals(1, r.unavailable())
        assertEquals(1, r.lost(3_000))
        assertEquals(1, r.lost(4_000))
    }
}
