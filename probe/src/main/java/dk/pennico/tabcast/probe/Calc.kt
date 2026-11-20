// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.probe

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Pure helpers (no Android types), unit-tested in CalcTest. */
object Calc {
    /** Latency/time samples in ms → n, mean, p50, p95, max (rounded to 0.1 ms). */
    class Samples {
        private val v = ArrayList<Double>()
        val size get() = v.size
        fun add(ms: Double) { v += ms }
        fun summary(): Map<String, Any> {
            if (v.isEmpty()) return mapOf("n" to 0)
            val s = v.sorted()
            fun r(x: Double) = (x * 10).roundToInt() / 10.0
            return linkedMapOf("n" to s.size, "mean" to r(s.average()), "p50" to r(pct(s, 50.0)), "p95" to r(pct(s, 95.0)),
                "max" to r(s.last()))
        }
        fun text(): String = summary().let { m ->
            if (m["n"] == 0) "no samples" else "mean ${m["mean"]} · p50 ${m["p50"]} · p95 ${m["p95"]} · max ${m["max"]} ms (n=${m["n"]})"
        }
    }

    /** Nearest-rank percentile of a sorted list. */
    fun pct(sorted: List<Double>, p: Double): Double =
        sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]

    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * A page element's CSS rect → pixels of a copy: the view sits at (viewX, viewY) in a srcW x srcH window, the copy
     * is outW x outH. [inset] trims each side (fraction of the size) so borders and rounding don't count.
     */
    fun cssToCopy(x: Double, y: Double, w: Double, h: Double, dpr: Double, viewX: Int, viewY: Int,
                  srcW: Int, srcH: Int, outW: Int, outH: Int, inset: Double = 0.1): Box {
        val sx = outW.toDouble() / srcW
        val sy = outH.toDouble() / srcH
        val l = (viewX + (x + w * inset) * dpr) * sx
        val t = (viewY + (y + h * inset) * dpr) * sy
        val r = (viewX + (x + w * (1 - inset)) * dpr) * sx
        val b = (viewY + (y + h * (1 - inset)) * dpr) * sy
        return Box(l.roundToInt(), t.roundToInt(), (r - l).roundToInt(), (b - t).roundToInt())
    }

    /** Largest even-sized w x h with the source's aspect ratio that fits maxW x maxH. */
    fun fit(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        val k = minOf(maxW.toDouble() / srcW, maxH.toDouble() / srcH)
        fun even(x: Double) = (x + 1e-6).toInt() and 1.inv() // 1e-6: 720 / 2736 * 2736 must stay 720
        return even(srcW * k) to even(srcH * k)
    }

    /** NAL unit types in an Annex-B H.264 stream (start codes 00 00 01 / 00 00 00 01). */
    fun nalTypes(b: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                out += b[i + 3].toInt() and 0x1f
                i += 3
            } else i++
        }
        return out
    }

    /** CPU share of one core: process CPU ms over wall ms, in percent. */
    fun cpuPercent(cpuMs: Long, wallMs: Long): Int = if (wallMs <= 0) 0 else (cpuMs * 100.0 / wallMs).roundToInt()
}
