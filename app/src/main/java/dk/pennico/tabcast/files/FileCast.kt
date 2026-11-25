// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.cast.CastService
import dk.pennico.tabcast.cast.Net
import dk.pennico.tabcast.link.FileServer
import dk.pennico.tabcast.link.Subtitles
import dk.pennico.tabcast.tvlink.TvLink
import dk.pennico.tabcast.ui.TvFlow
import dk.pennico.tabcast.ui.TvRemoteActivity
import dk.pennico.tabcast.ui.dialog
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * 0.4.0 "Cast a file": a video on the tablet (picked in Tab Cast, or shared from VLC, Files, Gallery) is served as it
 * is to Tab Cast TV, which copies it into its rolling cache and plays it from there, so the tablet may sleep once the
 * TV holds enough. Nothing is transcoded. The file is served only at a random path for as long as the cast lasts.
 *
 * The file descriptor is opened at once and kept: an app's share permission can end when its share screen closes,
 * an open descriptor keeps working.
 */
object FileCast {
    /** A file opened for casting. */
    class Picked(val uri: Uri, val name: String, val size: Long, val mime: String, val pfd: ParcelFileDescriptor, val path: String?)

    private class Serving(val picked: Picked, val entry: FileServer.Entry, @Volatile var subs: FileServer.Entry?,
                          val hostPort: String, val id: String, val rate: Int)

    private val server = FileServer { AppLog.i(it) }
    @Volatile private var current: Serving? = null

    /** The film being served (its name), if any. */
    val serving: String? get() = current?.picked?.name
    /** Bytes the TV has fetched from the tablet for the current film. */
    val bytesServed: Long get() = current?.entry?.bytesServed?.get() ?: 0

    /** Name, size and type of [uri], with its file descriptor open (null + a reason when it can't be cast). */
    fun open(ctx: Context, uri: Uri): Pair<Picked?, String?> {
        val cr = ctx.contentResolver
        var name: String? = null
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) { AppLog.i("file: can't read name/size of $uri (${e.message})") }
        val pfd = try { cr.openFileDescriptor(uri, "r") } catch (e: Exception) {
            AppLog.e("file: can't open $uri", e); return null to "Tab Cast can't open this file (${e.message})"
        } ?: return null to "Tab Cast can't open this file"
        if (pfd.statSize > 0) size = pfd.statSize
        if (size <= 0) {
            pfd.close()
            return null to "This app shares the file as a stream, not as a file. Open it with Tab Cast → Cast a file instead."
        }
        val n = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Film"
        val mime = cr.getType(uri)?.takeIf { it.startsWith("video/") || it.startsWith("audio/") } ?: mimeOf(n)
        return Picked(uri, n, size, mime, pfd, pathOf(ctx, uri)) to null
    }

    /**
     * Casts [uri] to Tab Cast TV: connects (pairing if needed), serves the file, tells the TV, shows the remote.
     * [done] runs afterwards (true = it went to the TV).
     */
    fun cast(act: AppCompatActivity, uri: Uri, done: (Boolean) -> Unit = {}) {
        val (p, why) = open(act, uri)
        if (p == null) { act.dialog("Can't cast this file").setMessage(why).setPositiveButton("OK") { _, _ -> done(false) }.show(); return }
        TvFlow.withTv(act, cancelled = { p.pfd.close(); done(false) }) {
            if (!TvLink.filesSupported) {
                p.pfd.close()
                act.dialog("Update Tab Cast TV").setMessage("The TV's Tab Cast TV is too old to play files from the tablet. " +
                    "Update it in Droid-ify on the TV, then try again.").setPositiveButton("OK") { _, _ -> done(false) }.show()
                return@withTv
            }
            val err = start(act, p)
            if (err != null) { Toast.makeText(act, err, Toast.LENGTH_LONG).show(); done(false); return@withTv }
            act.startActivity(Intent(act, TvRemoteActivity::class.java))
            done(true)
        }
    }

    /** Serves [p] and sends it to the TV (the link is up). Null when it went, else why not. */
    private fun start(ctx: Context, p: Picked): String? {
        val ip = Net.localIpv4(ctx)?.first ?: run { p.pfd.close(); return "The tablet has no Wi-Fi address" }
        stop("next film")
        val port = server.start()
        val entry = server.add(p.name, p.mime, PfdSource(p.pfd, p.size))
        val id = "${p.name}|${p.size}"
        val subs = subtitlesFor(ctx, p, id)?.let { serveSubs(p, it) }
        val rate = TabCastApp.prefs.fileRateMbit
        val sv = Serving(p, entry, subs, "$ip:$port", id, rate)
        current = sv
        TabCastApp.prefs.lastFile = "${p.uri}\n$id"
        send(sv)
        AppLog.i("file: casting \"${p.name}\" (${p.size shr 20} MB, ${p.mime}${if (subs != null) ", subtitles ${subs.name}" else ", no subtitle file"}) " +
            "at $ip:$port, TV download limit $rate Mbit/s; TV free space ${TvLink.tvFree?.let { "${it shr 20} MB" } ?: "?"}")
        TvLink.startSession("file")
        CastService.start(ctx)
        return null
    }

    private fun send(sv: Serving) {
        val sub = sv.subs
        TvLink.file(server.url(sv.hostPort, sv.entry), sv.picked.name, sv.picked.size, sv.id, sv.picked.mime,
            sub?.let { server.url(sv.hostPort, it) }, sub?.mime, sv.rate)
    }

    private fun serveSubs(p: Picked, r: Subtitles.Ready): FileServer.Entry =
        server.add(File(p.name).nameWithoutExtension + "." + r.format.ext, r.format.mime, BytesSource(r.bytes))

    /**
     * 0.5.1 (subtitle files weren't played): the subtitles for a film: a same-name file next to it when Tab Cast
     * can read the folder, else the file chosen for this film before ("Subtitles… › Choose a subtitle file").
     */
    private fun subtitlesFor(ctx: Context, p: Picked, id: String): Subtitles.Ready? {
        findSubtitles(p)?.let { f ->
            val r = runCatching { Subtitles.prepare(f.name, f.readBytes()) }.getOrNull()
            if (r != null) { AppLog.i("file: subtitles next to the film: ${f.name} (${r.charset}, ${r.source}, ${r.cues} lines)"); return r }
            AppLog.i("file: ${f.name} next to the film isn't a subtitle file Tab Cast can use")
        }
        val chosen = subsPrefs(ctx).getString(id, null)?.let { Uri.parse(it) } ?: return null
        val r = readSubs(ctx, chosen)
        if (r == null) AppLog.i("file: the subtitle file chosen for this film can't be read any more")
        else AppLog.i("file: subtitles chosen for this film before (${r.charset}, ${r.source}, ${r.cues} lines)")
        return r
    }

    private fun readSubs(ctx: Context, uri: Uri): Subtitles.Ready? = try {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "subtitles.srt"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { i ->
            val b = i.readNBytesCompat(Subtitles.MAX_BYTES + 1); b
        } ?: ByteArray(0)
        Subtitles.prepare(name, bytes)
    } catch (e: Exception) { AppLog.i("file: can't read subtitles ($e)"); null }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (out.size() <= max) { val n = read(buf); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }

    private fun subsPrefs(ctx: Context) = ctx.getSharedPreferences("film-subtitles", Context.MODE_PRIVATE)

    /**
     * "Choose a subtitle file" while a film from this tablet plays: checked, remembered for this film, and sent to the
     * TV, which shows it from where the film is. Null when it went, else why not.
     */
    fun addSubtitles(ctx: Context, uri: Uri): String? {
        val sv = current ?: return "No film from this tablet is playing"
        val r = readSubs(ctx, uri) ?: return "That isn't a subtitle file Tab Cast can read (text subtitles: .srt, .vtt, .ass/.ssa, .sub, .sbv, .smi, .ttml; picture subtitles such as VobSub .idx/.sub can't be sent as a file)"
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val sp = subsPrefs(ctx)
        sp.edit().putString(sv.id, uri.toString()).apply()
        if (sp.all.size > 100) sp.edit().apply { sp.all.keys.take(sp.all.size - 80).forEach { remove(it) } }.apply()
        sv.subs?.let { server.remove(it) }
        sv.subs = serveSubs(sv.picked, r)
        send(sv)
        AppLog.i("file: subtitles chosen for \"${sv.picked.name}\": ${r.charset}, ${r.source}, ${r.cues} lines; sent to the TV")
        return null
    }

    /** Is a film from this tablet being served (the remote offers "Choose a subtitle file")? */
    val canAddSubtitles: Boolean get() = current != null

    /**
     * The TV plays a film from this tablet that this (restarted) app doesn't serve any more: serve the same file again
     * if it is still there and readable, and tell the TV (it carries on from the new address without stopping).
     * Called on every TV status; does something at most once a minute.
     */
    fun resumeIfNeeded(ctx: Context, st: TvLink.State) {
        if (!st.connected || !st.film || st.complete || current != null) return
        val (uri, id) = TabCastApp.prefs.lastFile?.split('\n')?.takeIf { it.size == 2 } ?: return
        if (st.status[dk.pennico.tabcast.link.Wire.Status.FILE_ID] != id) return
        val now = System.currentTimeMillis()
        if (now - lastResumeTry < 60_000) return
        lastResumeTry = now
        val (p, why) = open(ctx, Uri.parse(uri))
        if (p == null) { AppLog.i("file: the TV plays \"${st.title}\" but the tablet can't open it again ($why)"); return }
        AppLog.i("file: the TV still plays \"${p.name}\" and needs more of it; serving it again")
        start(ctx, p)?.let { AppLog.i("file: can't serve it again: $it") }
    }

    @Volatile private var lastResumeTry = 0L

    /** Stops serving (the cast ended): the descriptor closes, the path stops working. */
    fun stop(why: String) {
        val c = current ?: return
        current = null
        server.remove(c.entry)
        c.subs?.let { server.remove(it) }
        AppLog.i("file: stopped serving \"${c.picked.name}\" ($why; the TV fetched ${c.entry.bytesServed.get() shr 20} MB)")
        if (server.count == 0) server.stop()
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private class BytesSource(private val b: ByteArray) : FileServer.Source {
        override val size: Long get() = b.size.toLong()
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= b.size) return -1
            val n = minOf(len.toLong(), b.size - pos).toInt()
            System.arraycopy(b, pos.toInt(), buf, off, n); return n
        }
        override fun close() {}
    }

    private class PfdSource(private val pfd: ParcelFileDescriptor, override val size: Long) : FileServer.Source {
        private val ch = FileInputStream(pfd.fileDescriptor).channel
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = ch.read(ByteBuffer.wrap(buf, off, len), pos)
        override fun close() { runCatching { ch.close() }; runCatching { pfd.close() } }
    }

    /** The file's path when it can be known (MediaStore, the Files app's "primary:" documents), for sidecar subtitles. */
    private fun pathOf(ctx: Context, uri: Uri): String? = try {
        when {
            uri.scheme == ContentResolver.SCHEME_FILE -> uri.path
            uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isDocumentUri(ctx, uri) -> {
                val id = DocumentsContract.getDocumentId(uri)
                if (id.startsWith("primary:")) "/storage/emulated/0/" + id.removePrefix("primary:") else null
            }
            uri.authority == MediaStore.AUTHORITY -> ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            else -> null
        }
    } catch (e: Exception) { null }

    /**
     * A subtitle file next to the film with the same name (`Film.srt`, `Film.en.srt`, `.vtt`, `.ass`). Android only
     * lets Tab Cast see it with "All files access" (optional: `adb shell appops set --uid dk.pennico.tabcast
     * MANAGE_EXTERNAL_STORAGE allow`); without it there are none, and embedded subtitles still work.
     */
    private fun findSubtitles(p: Picked): File? {
        val path = p.path ?: return null
        val film = File(path)
        val base = film.nameWithoutExtension
        val dir = film.parentFile ?: return null
        val exts = Subtitles.EXTENSIONS
        val direct = exts.map { File(dir, "$base.$it") }.firstOrNull { runCatching { it.canRead() && it.length() > 0 }.getOrDefault(false) }
        if (direct != null) return direct
        val list = runCatching { dir.listFiles() }.getOrNull() ?: run {
            AppLog.i("file: can't look for subtitles next to the film (no all-files access)"); return null
        }
        return list.filter { f -> f.name.startsWith("$base.") && f.extension.lowercase() in exts && f.canRead() }
            .minByOrNull { it.name.length }
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"; "ts", "m2ts", "mts" -> "video/mp2t"; "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"; "ass", "ssa" -> "text/x-ssa"; else -> "application/octet-stream"
    }
}
