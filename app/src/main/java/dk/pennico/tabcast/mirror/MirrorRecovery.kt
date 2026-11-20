// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.mirror

/**
 * 0.5.2: what "Mirror screen" does when its encoder is lost or can't be had. Pure, so it is unit-tested.
 *
 * Levels go from best to most modest (the service's: hardware VP8 960x720 30 fps, then software VP8 640x480 15 fps).
 * A lost encoder is made again at the same level, unless that level lost [perLevel] encoders within [windowMs]: then
 * the next level. A level where no encoder can be made at all is skipped. Past the last level: stop.
 */
class MirrorRecovery(private val levels: Int, private val perLevel: Int = 3, private val windowMs: Long = 60_000) {
    var level = 0; private set
    private val losses = ArrayDeque<Long>()
    /** Encoders lost in this mirroring session, for the log. */
    var lostTotal = 0; private set

    /** The encoder was lost at [nowMs]: the level to make the next one at, or -1 when none is left. */
    fun lost(nowMs: Long): Int {
        lostTotal++
        losses.addLast(nowMs)
        while (losses.isNotEmpty() && losses.first() < nowMs - windowMs) losses.removeFirst()
        if (losses.size >= perLevel) return next()
        return level
    }

    /** No encoder could be made at the current level: the next level, or -1. */
    fun unavailable(): Int = next()

    private fun next(): Int {
        losses.clear()
        level++
        return if (level < levels) level else -1
    }
}
