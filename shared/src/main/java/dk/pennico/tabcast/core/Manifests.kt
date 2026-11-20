// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

/** What a probe learned about a stream (all optional; shown in the Cast list and used for ranking). */
data class StreamInfo(
    val kind: Kind,
    val isMaster: Boolean = false,
    val maxHeight: Int? = null,
    val variants: Int = 0,
    val durationSec: Double? = null,
    val live: Boolean = false,
    val drm: Boolean = false,
    val fmp4: Boolean = false,
    val sizeBytes: Long? = null,
    val bestVariantUrl: String? = null,
    val variantUrls: List<String> = emptyList(),
    val audioOnly: Boolean = false,
    val httpStatus: Int? = null,
    val error: String? = null,
)

object Manifests {
    private val attrRe = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun attrs(line: String): Map<String, String> {
        val body = line.substringAfter(':', "")
        return attrRe.findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
    }

    fun parseHls(text: String, baseUrl: String): StreamInfo {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var master = false
        var maxH: Int? = null
        var variants = 0
        var bestBw = -1L
        var bestUrl: String? = null
        var pendingBw = -1L
        var pendingVariant = false
        val variantUrls = ArrayList<String>()
        var allAudio = true
        var dur = 0.0
        var segs = 0
        var endList = false
        var vodType = false
        var drm = false
        var fmp4 = false
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-STREAM-INF") -> {
                    master = true; variants++
                    val a = attrs(l)
                    a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull()?.let { h -> maxH = maxOf(maxH ?: 0, h) }
                    val codecs = a["CODECS"]
                    if (a["RESOLUTION"] != null || codecs == null || codecs.split(',').any { !it.trim().startsWith("mp4a") && !it.trim().startsWith("ac-3") && !it.trim().startsWith("ec-3") && !it.trim().startsWith("opus") }) allAudio = false
                    pendingBw = a["BANDWIDTH"]?.toLongOrNull() ?: 0L
                    pendingVariant = true
                }
                l.startsWith("#EXTINF") -> {
                    segs++
                    dur += l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }
                l.startsWith("#EXT-X-ENDLIST") -> endList = true
                l.startsWith("#EXT-X-PLAYLIST-TYPE") -> vodType = l.substringAfter(':').trim().equals("VOD", true)
                l.startsWith("#EXT-X-MAP") -> fmp4 = true
                l.startsWith("#EXT-X-KEY") || l.startsWith("#EXT-X-SESSION-KEY") -> {
                    val a = attrs(l)
                    val method = a["METHOD"] ?: ""
                    val fmt = (a["KEYFORMAT"] ?: "identity").lowercase()
                    if (method.startsWith("SAMPLE-AES") && fmt != "identity") drm = true
                    if (fmt.contains("streamingkeydelivery") || fmt.contains("edef8ba9") || fmt.contains("playready")) drm = true
                }
                l.startsWith("#EXT-X-MEDIA") || l.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> {
                    attrs(l)["URI"]?.let { variantUrls += Urls.resolve(baseUrl, it) }
                }
                !l.startsWith("#") && pendingVariant -> {
                    pendingVariant = false
                    val v = Urls.resolve(baseUrl, l)
                    variantUrls += v
                    if (pendingBw > bestBw) { bestBw = pendingBw; bestUrl = v }
                }
            }
        }
        return if (master) {
            StreamInfo(Kind.HLS, isMaster = true, maxHeight = maxH, variants = variants, drm = drm,
                bestVariantUrl = bestUrl, variantUrls = variantUrls, audioOnly = variants > 0 && allAudio)
        } else {
            StreamInfo(Kind.HLS, isMaster = false, durationSec = if (segs > 0 && (endList || vodType)) dur else null,
                live = segs > 0 && !endList && !vodType, drm = drm, fmp4 = fmp4)
        }
    }

    fun parseDash(text: String): StreamInfo {
        val mpdTag = Regex("""<MPD\b[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: ""
        val live = Regex("""\btype\s*=\s*"dynamic"""").containsMatchIn(mpdTag)
        val dur = Regex("""mediaPresentationDuration\s*=\s*"([^"]+)"""").find(mpdTag)?.groupValues?.get(1)?.let { isoDuration(it) }
        var maxH: Int? = null
        Regex("""<Representation\b[^>]*?\bheight\s*=\s*"(\d+)"""").findAll(text).forEach {
            val h = it.groupValues[1].toInt(); maxH = maxOf(maxH ?: 0, h)
        }
        Regex("""<AdaptationSet\b[^>]*?\bmaxHeight\s*=\s*"(\d+)"""").findAll(text).forEach {
            val h = it.groupValues[1].toInt(); maxH = maxOf(maxH ?: 0, h)
        }
        val reps = Regex("""<Representation\b""").findAll(text).count()
        val drm = text.contains("<ContentProtection") || text.contains(":ContentProtection")
        val hasVideo = text.contains("video/") || maxH != null
        return StreamInfo(Kind.DASH, isMaster = true, maxHeight = maxH, variants = reps, durationSec = dur, live = live,
            drm = drm, audioOnly = !hasVideo)
    }

    /** ISO-8601 duration as used by DASH (PnYnMnDTnHnMnS, years/months approximated). */
    fun isoDuration(s: String): Double? {
        val m = Regex("""^P(?:(\d+(?:\.\d+)?)Y)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""")
            .matchEntire(s.trim()) ?: return null
        val g = m.groupValues.drop(1).map { it.toDoubleOrNull() ?: 0.0 }
        return g[0] * 365 * 86400 + g[1] * 30 * 86400 + g[2] * 86400 + g[3] * 3600 + g[4] * 60 + g[5]
    }

    fun formatDuration(sec: Double?): String? {
        if (sec == null || sec <= 0) return null
        val s = sec.toLong()
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    fun formatSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        return when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
            bytes >= 1L shl 20 -> "%d MB".format(bytes / 1048576)
            else -> "%d kB".format(bytes / 1024)
        }
    }
}
