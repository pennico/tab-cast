// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.files.FileCast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 0.4.0 "Cast a file": the tablet's videos, newest first (tap one to play it on the TV), and "Choose another file…"
 * (the system picker, for anything else, e.g. on an SD card or in Downloads).
 */
class FilesActivity : AppCompatActivity() {
    class Video(val uri: Uri, val name: String, val size: Long, val durationMs: Long, val folder: String)

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var allow: View
    private var videos: List<Video> = emptyList()

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            castIt(uri)
        }
    }

    private val askVideos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        AppLog.i("files: access to videos: ${r.entries.joinToString { "${it.key.substringAfterLast('.')} ${if (it.value) "allowed" else "refused"}" }}")
        load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Cast a file"
        empty = text("", 16f).apply { setPadding(dp(4), dp(12), dp(4), dp(12)) }
        allow = button("Show the tablet's videos") { askVideos.launch(videoPermissions()) }
        list = ListView(this).apply {
            dividerHeight = dp(1)
            setOnItemClickListener { _, _, pos, _ -> videos.getOrNull(pos)?.let { castIt(it.uri) } }
        }
        setContentView(column(16) {
            addView(text("Pick a video: it plays on the TV, and the tablet can sleep once the TV has it.", 15f), matchWrap())
            addView(row {
                addView(button("Choose another file…") { pick.launch(arrayOf("video/*")) })
                addView(allow, wrap().apply { marginStart = dp(12) })
            }, matchWrap().apply { topMargin = dp(8); bottomMargin = dp(8) })
            addView(empty, matchWrap())
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        load()
    }

    /** Android 14 may give access to some videos only ("Select videos"): then the list shows those. */
    private fun videoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun load() {
        val granted = videoPermissions().any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        allow.visibility = if (granted) View.GONE else View.VISIBLE
        if (!granted) {
            empty.text = "To list the tablet's videos here, Tab Cast needs access to videos. Or choose a file with the button."
            return
        }
        lifecycleScope.launch {
            val v = withContext(Dispatchers.IO) { query() }
            videos = v
            empty.text = if (v.isEmpty()) "No videos found on the tablet. Choose a file with the button." else ""
            empty.visibility = if (v.isEmpty()) View.VISIBLE else View.GONE
            list.adapter = Adapter(v)
        }
    }

    private fun query(): List<Video> {
        val out = ArrayList<Video>()
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH)
        try {
            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 500) {
                    val id = c.getLong(0)
                    out += Video(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), c.getString(1) ?: "video",
                        c.getLong(2), c.getLong(3), (c.getString(4) ?: "").trimEnd('/'))
                }
            }
        } catch (e: Exception) { AppLog.e("files: can't list videos", e) }
        return out
    }

    private fun castIt(uri: Uri) = FileCast.cast(this, uri) { ok -> if (ok) finish() }

    private inner class Adapter(val items: List<Video>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = items[position]
            val row = (convertView as? LinearLayout) ?: column(12) {
                addView(text("", 17f, true).apply { maxLines = 2 }, matchWrap())
                addView(text("", 13f), matchWrap())
            }
            (row.getChildAt(0) as TextView).text = v.name
            (row.getChildAt(1) as TextView).text = listOfNotNull(v.durationMs.takeIf { it > 0 }?.let { fmtTime(it / 1000.0) },
                sizeText(v.size), v.folder.ifEmpty { null }).joinToString(" · ")
            return row
        }
    }

    companion object {
        fun sizeText(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "${b shr 20} MB"
            else -> "${b shr 10} kB"
        }
    }
}

/**
 * Share target "Cast to TV" for videos (VLC, Files, Gallery: Share, or Open with): casts the file through
 * [FileCast]. Pages and links keep going to [ShareActivity].
 */
class FileShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(column(24) {
            addView(text("Cast to TV", 20f, true))
            addView(text("Sending the video to the TV…", 16f))
        })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        @Suppress("DEPRECATION")
        val uri: Uri? = if (i.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
        } else i.data
        AppLog.i("share: a file (${i.action}, ${i.type}): ${uri?.scheme}://${uri?.authority}")
        if (uri == null) { info("Cast to TV", "Nothing to cast was shared.").setOnDismissListener { finish() }; return }
        FileCast.cast(this, uri) { finish() }
    }
}
