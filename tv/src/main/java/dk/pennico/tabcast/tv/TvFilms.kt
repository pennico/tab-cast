// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.link.FilmCache
import java.io.File
import kotlin.math.roundToLong

/**
 * 0.4.0: films the tablet sends (Wire.FILE). One film at a time is cached on the TV ([FilmCache] in
 * `files/films/<id>/`): starting another one deletes the previous cache; a film that ends deletes its own. The play
 * position is remembered per film id (resume after a reconnect, a Back, an app restart).
 */
class TvFilms(ctx: Context) {
    private val root = File(ctx.filesDir, "films")
    private val subsDir = File(ctx.filesDir, "film-subtitles")
    private val sp = ctx.getSharedPreferences("films", Context.MODE_PRIVATE)

    class Film(val id: String, val ref: String, val name: String, val size: Long, val mime: String?,
               @Volatile var subsUrl: String?, @Volatile var subsMime: String?, val cache: FilmCache) {
        /** 0.5.1: the subtitle file fetched from the tablet once, kept with the film (it plays on while the tablet sleeps). */
        @Volatile var subsFile: File? = null
        /** Seconds per byte, once the player knows the duration (for "on the TV up to …"). */
        @Volatile var duration: Double? = null
        @Volatile var errors = 0
        val uri: String get() = "${FilmDataSource.SCHEME}://$id/" + Uri.encode(name)

        /** The play time up to which the TV holds the film without a gap, from [pos] on (bytes mapped to time linearly). */
        fun heldTo(pos: Double): Double? {
            val d = duration ?: return null
            val s = cache.stats()
            if (s.complete) return d
            val end = s.head + s.aheadBytes
            return maxOf(pos, end.toDouble() / size * d).coerceAtMost(d)
        }
    }

    /**
     * Free bytes on the TV's disk (where the cache lives). Really free space only: getAllocatableBytes would count
     * other apps' caches the system may clear, and Tab Cast TV shouldn't empty those unasked.
     */
    @android.annotation.SuppressLint("UsableSpace")
    fun free(): Long = (root.takeIf { it.exists() } ?: root.parentFile)?.usableSpace ?: 0L

    fun start(kv: Map<String, String>, rateMbit: Double?): Film? {
        val url = kv["url"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val size = kv["size"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val ref = refOf(kv) ?: return null
        val id = safeId(ref)
        val name = kv["name"]?.takeIf { it.isNotBlank() } ?: "Film"
        root.mkdirs()
        // One film on the TV at a time: the next one starts, the previous cache goes.
        root.listFiles()?.filter { it.name != id }?.forEach { old ->
            AppLog.i("film cache: removing the previous film (${old.listFiles()?.sumOf { it.length() }?.let { it shr 20 } ?: 0} MB)")
            old.deleteRecursively()
        }
        val dir = File(root, id)
        val rate = ((rateMbit ?: 20.0) * 1_000_000).roundToLong()
        val cache = FilmCache(dir, url, size, { cached -> FilmCache.budgetFor(cached, free()) }, rate, { AppLog.i(it) })
        val f = Film(id, ref, name, size, kv["mime"], kv["subs"]?.takeIf { it.startsWith("http") }, kv["subsmime"], cache)
        val have = cache.stats().cached
        AppLog.i("film \"$name\": ${size shr 20} MB, ${have shr 20} MB already on the TV, ${free() shr 20} MB free, " +
            "may use ${FilmCache.budgetFor(have, free()) shr 20} MB, download limit ${rate / 1_000_000} Mbit/s")
        cache.start()
        return f
    }

    /** The tablet's id of the film in a FILE message ("name|size"). */
    fun refOf(kv: Map<String, String>): String? {
        val size = kv["size"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return kv["id"]?.takeIf { it.isNotBlank() } ?: "${kv["name"]}|$size"
    }

    private fun subsMimeOf(f: Film): String = f.subsMime ?: when ((f.subsUrl ?: "").substringBefore('?').substringAfterLast('.').lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT; "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML // 0.5.4
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun subsPath(f: Film) = File(subsDir, f.id + "." + when (subsMimeOf(f)) {
        MimeTypes.TEXT_VTT -> "vtt"; MimeTypes.TEXT_SSA -> "ass"; MimeTypes.APPLICATION_TTML -> "ttml"; else -> "srt" })

    /**
     * 0.5.1: fetches the film's subtitle file from the tablet into the TV's own storage (blocking, a few kB; off the
     * main thread). When the tablet doesn't answer, the copy from an earlier start of the same film is used.
     */
    fun fetchSubtitles(f: Film): File? {
        val u = f.subsUrl ?: return null
        val dest = subsPath(f)
        subsDir.mkdirs()
        try {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 6000
            try {
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                val b = c.inputStream.use { it.readBytes() }
                val tmp = File(dest.path + ".part")
                tmp.writeBytes(b)
                if (!tmp.renameTo(dest)) throw java.io.IOException("rename")
                AppLog.i("film \"${f.name}\": subtitles from the tablet, ${b.size} bytes (${subsMimeOf(f)})")
            } finally { c.disconnect() }
        } catch (e: Exception) {
            AppLog.i("film \"${f.name}\": can't fetch the subtitles from the tablet ($e)${if (dest.isFile) "; using the copy from before" else ""}")
        }
        // Old copies of other films go (one film at a time on the TV).
        subsDir.listFiles()?.filter { !it.name.startsWith(f.id) }?.forEach { it.delete() }
        return dest.takeIf { it.isFile && it.length() > 0 }.also { f.subsFile = it }
    }

    @OptIn(UnstableApi::class)
    fun subtitles(f: Film): MediaItem.SubtitleConfiguration? {
        val file = f.subsFile ?: return null
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(subsMimeOf(f))
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setLabel("Subtitles (from the tablet)").build()
    }

    /** Container hint for the extractor, from the name (Media3 also sniffs the bytes). */
    @OptIn(UnstableApi::class)
    fun mime(f: Film): String? = when (f.name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> MimeTypes.VIDEO_MATROSKA; "webm" -> MimeTypes.VIDEO_WEBM; "mp4", "m4v", "mov" -> MimeTypes.VIDEO_MP4
        "ts", "m2ts", "mts" -> MimeTypes.VIDEO_MP2T; "avi" -> "video/x-msvideo"; else -> null
    }

    // ------------------------------------------------------------------------------------------- positions

    /** Where to start: the remembered position, unless it is in the first 30 s or the last minute. */
    fun resumeAt(id: String): Long {
        val v = sp.getString(id, null)?.split(';') ?: return 0
        val pos = v.getOrNull(0)?.toDoubleOrNull() ?: return 0
        val dur = v.getOrNull(1)?.toDoubleOrNull()
        if (pos < 30 || (dur != null && pos > dur - 60)) return 0
        return (pos * 1000).toLong()
    }

    fun savePosition(id: String, pos: Double, dur: Double?) {
        sp.edit().putString(id, "$pos;${dur ?: ""};${System.currentTimeMillis()}").apply()
        val all = sp.all
        if (all.size > 60) {
            val old = all.entries.sortedBy { (it.value as? String)?.split(';')?.getOrNull(2)?.toLongOrNull() ?: 0 }.take(all.size - 50)
            sp.edit().apply { old.forEach { remove(it.key) } }.apply()
        }
    }

    /** The film ended: forget its position, delete its cache. */
    fun finished(f: Film) {
        sp.edit().remove(f.id).apply()
        f.cache.delete()
        AppLog.i("film \"${f.name}\" ended: cache deleted")
    }

    private fun safeId(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return md.take(12).joinToString("") { "%02x".format(it) }
    }
}
