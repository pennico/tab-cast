// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

/**
 * 0.6: H.264 / HEVC access units as the Mac sends them (Wire.VIDEO with codec h264 | hevc): Annex-B, NAL units after
 * 00 00 01 or 00 00 00 01 start codes, the parameter sets (H.264 SPS/PPS, HEVC VPS/SPS/PPS) at the front of every key
 * frame. Android's decoders want those once as codec config before the first frame; [parameterSets] cuts them out.
 * Pure JVM, so it is unit-tested.
 */
object AnnexB {
    /** Start offsets of the NAL units (the first byte after each start code). */
    fun nalStarts(b: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 3 <= b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) { out += i + 3; i += 3 } else i++
        }
        return out
    }

    /** The NAL unit type at [start] (H.264: low 5 bits; HEVC: bits 1-6 of the first header byte). */
    fun type(b: ByteArray, start: Int, hevc: Boolean): Int =
        if (start >= b.size) -1 else if (hevc) (b[start].toInt() shr 1) and 0x3f else b[start].toInt() and 0x1f

    private fun isParameterSet(t: Int, hevc: Boolean) = if (hevc) t in 32..34 else t == 7 || t == 8

    /**
     * The leading parameter-set NAL units of [au] (with their start codes, Annex-B), or null when the access unit
     * doesn't start with them.
     */
    fun parameterSets(au: ByteArray, hevc: Boolean): ByteArray? {
        val starts = nalStarts(au)
        if (starts.isEmpty() || !isParameterSet(type(au, starts[0], hevc), hevc)) return null
        var end = au.size
        for (s in starts) {
            if (!isParameterSet(type(au, s, hevc), hevc)) {
                // Back to the beginning of this NAL's start code (3 or 4 bytes).
                end = if (s >= 4 && au[s - 4].toInt() == 0) s - 4 else s - 3
                break
            }
        }
        val begin = if (starts[0] >= 4 && au[starts[0] - 4].toInt() == 0) starts[0] - 4 else starts[0] - 3
        return au.copyOfRange(begin, end)
    }
}
