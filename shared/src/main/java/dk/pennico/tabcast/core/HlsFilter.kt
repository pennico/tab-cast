// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

/**
 * Keeps an HLS master playlist within what the TV can decode. YouTube's master offers up to 2160p60 VP9 at 28 Mbit/s;
 * the Chromecast HD is a 1080p device, and its FCast receiver switched up to a variant it couldn't decode: the
 * picture froze while the sound went on. The receiver picks variants by bandwidth, so the only
 * safe fix is not to offer them.
 *
 * Rules for each `#EXT-X-STREAM-INF` (and `#EXT-X-I-FRAME-STREAM-INF`) variant, using its RESOLUTION, FRAME-RATE and
 * CODECS when present (a variant without the attribute passes that test):
 *  - height ≤ [Limits.maxHeight];
 *  - frame rate ≤ [Limits.maxFps];
 *  - video codec H.264 (avc1/avc3) when [Limits.h264Only].
 * If no video variant would remain, the rules are relaxed in the order codec, frame rate, height (then the smallest
 * variant is kept), so the TV always gets something to play. Audio/subtitle renditions (`#EXT-X-MEDIA`) stay.
 */
object HlsFilter {
    data class Limits(val maxHeight: Int = 1080, val maxFps: Int = 60, val h264Only: Boolean = true) {
        override fun toString() = "≤${maxHeight}p, ≤$maxFps fps${if (h264Only) ", H.264" else ""}"
    }

    data class Variant(val height: Int?, val fps: Double?, val codecs: List<String>, val bandwidth: Long?) {
        val videoCodec: String? get() = codecs.firstOrNull { c -> videoPrefixes.any { c.startsWith(it) } }
        val isH264: Boolean get() = videoCodec.let { it == null || it.startsWith("avc1") || it.startsWith("avc3") }
    }

    class Result(val text: String, val kept: Int, val dropped: Int, val note: String) {
        val changed: Boolean get() = dropped > 0
    }

    private val videoPrefixes = listOf("avc1", "avc3", "hvc1", "hev1", "vp09", "vp8", "av01", "dvh1", "dvhe")
    private val attr = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun parseVariant(tagLine: String): Variant {
        val a = attr.findAll(tagLine.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        val h = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull()
        return Variant(h, a["FRAME-RATE"]?.toDoubleOrNull(), a["CODECS"]?.split(',')?.map { it.trim().lowercase() }.orEmpty(),
            a["BANDWIDTH"]?.toLongOrNull())
    }

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    fun filter(text: String, limits: Limits): Result {
        if (!isMaster(text)) return Result(text, 0, 0, "not a master playlist")
        // Group lines: a STREAM-INF tag owns the next URI line.
        class Entry(val lines: List<String>, val v: Variant?, val iframe: Boolean)
        val entries = ArrayList<Entry>()
        val src = text.split('\n')
        var i = 0
        while (i < src.size) {
            val l = src[i]
            val t = l.trim()
            when {
                t.startsWith("#EXT-X-STREAM-INF") -> {
                    var j = i + 1
                    val own = arrayListOf(l)
                    while (j < src.size && (src[j].isBlank() || (src[j].trim().startsWith("#") && !src[j].trim().startsWith("#EXT-X-")))) { own += src[j]; j++ }
                    if (j < src.size) { own += src[j]; j++ }
                    entries += Entry(own, parseVariant(t), false)
                    i = j
                }
                t.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> { entries += Entry(listOf(l), parseVariant(t), true); i++ }
                else -> { entries += Entry(listOf(l), null, false); i++ }
            }
        }
        val variants = entries.filter { it.v != null && !it.iframe }
        if (variants.isEmpty()) return Result(text, 0, 0, "no variants")

        fun ok(v: Variant, codec: Boolean, fps: Boolean, height: Boolean) =
            (!height || v.height == null || v.height <= limits.maxHeight) &&
                (!fps || v.fps == null || v.fps <= limits.maxFps + 0.5) &&
                (!codec || !limits.h264Only || v.isH264)

        val steps = listOf(Triple(true, true, true) to "", Triple(false, true, true) to " (no H.264 variant: kept other codecs)",
            Triple(false, false, true) to " (nothing at that frame rate: kept higher)")
        var keep: (Variant) -> Boolean = { false }
        var how = ""
        for ((rule, note) in steps) {
            val (c, f, h) = rule
            if (variants.any { ok(it.v!!, c, f, h) }) { keep = { v -> ok(v, c, f, h) }; how = note; break }
        }
        if (how.isEmpty() && variants.none { keep(it.v!!) }) {
            val smallest = variants.minByOrNull { it.v!!.height ?: Int.MAX_VALUE }!!.v!!
            keep = { v -> v.height == smallest.height }
            how = " (all variants too big: kept the smallest)"
        }
        val out = StringBuilder()
        var kept = 0; var dropped = 0
        for (e in entries) {
            val v = e.v
            if (v != null && !keep(v)) { if (!e.iframe) dropped++; continue }
            if (v != null && !e.iframe) kept++
            for (l in e.lines) out.append(l).append('\n')
        }
        if (!text.endsWith("\n") && out.isNotEmpty()) out.setLength(out.length - 1)
        val maxKept = variants.filter { keep(it.v!!) }.mapNotNull { it.v!!.height }.maxOrNull()
        return Result(out.toString(), kept, dropped,
            "kept $kept of ${kept + dropped} variants ($limits)$how${maxKept?.let { ", best ${it}p" } ?: ""}")
    }
}
