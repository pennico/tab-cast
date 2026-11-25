// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

/** What Mirror screen does after its encoder is lost (two levels: hardware, then software). */
class MirrorRecoveryTest {
    private fun started() = MirrorRecovery(2).apply { made() }

    @Test fun aLostEncoderIsMadeAgainAtTheSameLevel() {
        val r = started()
        assertEquals(0, r.lost(1_000, 30))
        assertEquals(0, r.lost(20_000, 30))
        assertEquals(2, r.lostTotal)
    }

    /** 0.5.4: the logged sessions that stopped after 13 s and 17 s (losses at 3, 10 and 12 s) now keep going. */
    @Test fun threeQuickLossesStayOnTheHardwareEncoder() {
        val r = started()
        assertEquals(0, r.lost(3_000, 90))
        assertEquals(0, r.lost(10_000, 200))
        assertEquals(0, r.lost(12_000, 40))
        assertEquals(0, r.level)
    }

    /** The other logged stop: losses 8-10 within 6 s, after six earlier ones. */
    @Test fun aBurstOfLossesAfterAnHourStaysOnTheHardwareEncoder() {
        val r = started()
        listOf(20_000L, 90_000L, 160_000L, 230_000L, 300_000L, 340_000L, 363_000L, 366_000L, 369_000L)
            .forEach { assertEquals(0, r.lost(it, 50)) }
    }

    @Test fun thePauseGrowsWithTheLosses() {
        val r = started()
        r.lost(1_000, 10); assertEquals(300L, r.retryDelayMs())
        r.lost(2_000, 10); assertEquals(1_000L, r.retryDelayMs())
        r.lost(3_000, 10); assertEquals(2_000L, r.retryDelayMs())
        r.lost(3_500, 10); assertEquals(2_000L, r.retryDelayMs())
        // A minute later the old losses no longer count.
        r.lost(70_000, 10); assertEquals(300L, r.retryDelayMs())
    }

    @Test fun tenLossesWithinAMinuteStopWhenNoLowerLevelHasWorked() {
        val r = started()
        repeat(9) { i -> assertEquals(0, r.lost(1_000L + i * 1_000, 5)) }
        assertEquals(-1, r.lost(10_000, 5))
    }

    @Test fun lossesFarApartNeverGiveUp() {
        val r = started()
        repeat(50) { i -> assertEquals(0, r.lost(i * 10_000L, 20)) }
    }

    /** A tablet without a hardware VP8 encoder: the software level is used from the start. */
    @Test fun atTheStartALevelWithNoEncoderIsSkipped() {
        val r = MirrorRecovery(2)
        assertEquals(1, r.unavailable())
        assertEquals(-1, r.unavailable())
    }

    /** That software level, once it has produced frames, is kept like the hardware one. */
    @Test fun aSoftwareOnlyTabletKeepsItsLevel() {
        val r = MirrorRecovery(2)
        assertEquals(1, r.unavailable()); r.made()
        assertEquals(1, r.lost(1_000, 15))
        assertEquals(1, r.lost(2_000, 15))
        assertEquals(1, r.lost(3_000, 15))
    }

    /** After the start, an encoder that can't be made again is retried before mirroring gives up. */
    @Test fun anEncoderThatCantBeMadeAgainIsRetried() {
        val r = started()
        assertEquals(0, r.lost(1_000, 30))
        assertEquals(0, r.unavailable())
        assertEquals(0, r.unavailable())
        assertEquals(-1, r.unavailable())
    }

    @Test fun aSuccessfulRetryResetsTheCount() {
        val r = started()
        r.lost(1_000, 30)
        assertEquals(0, r.unavailable()); assertEquals(0, r.unavailable())
        r.made()
        assertEquals(0, r.unavailable()); assertEquals(0, r.unavailable())
    }
}
