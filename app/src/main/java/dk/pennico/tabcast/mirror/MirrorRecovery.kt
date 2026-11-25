// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.mirror

/**
 * What "Mirror screen" does when its encoder is lost or can't be had. Pure, so it is unit-tested.
 *
 * Levels go from best to most modest (the service's: hardware VP8 960x720 30 fps, then software VP8 640x480 15 fps).
 *
 * 0.5.4: a lost encoder is made again at the same level, after a short pause that grows with the losses of the last
 * [windowMs] ([retryDelayMs]: 0.3 s, 1 s, then 2 s). A lost hardware encoder comes back in well under a second,
 * while the software one may never produce a frame on a given tablet (one reference tablet's software VP8 fails at
 * its first input), so mirroring no longer steps down because of losses alone. It steps down only to a level that has
 * already produced frames in this session, and only after [giveUpLosses] losses within [windowMs]; with no such
 * level it stops.
 *
 * At the start (before [made]) a level where no encoder can be made is skipped, so a tablet without a hardware VP8
 * encoder uses the software one. After the start, an encoder that can't be made again is retried at the same level
 * up to [retriesWhenUnavailable] times in a row before mirroring gives up in the same way.
 */
class MirrorRecovery(
    private val levels: Int,
    private val giveUpLosses: Int = 10,
    private val windowMs: Long = 60_000,
    private val retriesWhenUnavailable: Int = 3,
) {
    var level = 0; private set
    private val losses = ArrayDeque<Long>()
    private val worked = BooleanArray(levels)
    private var started = false
    private var unavailableInARow = 0
    /** Encoders lost in this mirroring session, for the log. */
    var lostTotal = 0; private set

    /** An encoder was made at the current level and is in use. */
    fun made() { started = true; unavailableInARow = 0 }

    /**
     * The encoder was lost at [nowMs] after [frames] frames: the level to make the next one at, or -1 when mirroring
     * should stop.
     */
    fun lost(nowMs: Long, frames: Long = 0): Int {
        lostTotal++
        if (frames > 0) worked[level] = true
        losses.addLast(nowMs)
        while (losses.isNotEmpty() && losses.first() < nowMs - windowMs) losses.removeFirst()
        if (losses.size >= giveUpLosses) return stepDown()
        return level
    }

    /** How long to wait before making the next encoder, from the losses within the window. */
    fun retryDelayMs(): Long = when (losses.size) {
        0, 1 -> 300L
        2 -> 1_000L
        else -> 2_000L
    }

    /**
     * No encoder could be made at the current level: the level to try next (while mirroring runs, the same one again
     * after [retryDelayMs]), or -1.
     */
    fun unavailable(): Int {
        if (!started) {
            level++
            return if (level < levels) level else -1
        }
        unavailableInARow++
        if (unavailableInARow < retriesWhenUnavailable) return level
        unavailableInARow = 0
        return stepDown()
    }

    /** The next level down that has produced frames in this session, or -1 (stop). */
    private fun stepDown(): Int {
        losses.clear()
        for (l in level + 1 until levels) if (worked[l]) { level = l; return l }
        return -1
    }
}
