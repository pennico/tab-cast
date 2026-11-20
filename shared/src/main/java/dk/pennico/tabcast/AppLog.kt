// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App log: logcat (tag TabCast, Tab Cast TV: TabCastTV), an in-memory ring for Settings > Log, and a file the Mac can
 * pull without root:
 *   adb pull /sdcard/Android/data/dk.pennico.tabcast/files/tabcast.log
 *   adb pull /sdcard/Android/data/dk.pennico.tabcast.tv/files/tabcast-tv.log
 */
object AppLog {
    private var TAG = "TabCast"
    private const val MAX = 600
    private val ring = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context, name: String = "tabcast", tag: String = "TabCast") {
        TAG = tag
        file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "$name.log").also {
            if (it.length() > 2_000_000) it.renameTo(File(it.parentFile, "$name.old.log"))
        }
    }

    @Synchronized
    fun i(msg: String) {
        Log.i(TAG, msg)
        val line = fmt.format(Date()) + "  " + msg
        ring.addLast(line)
        while (ring.size > MAX) ring.removeFirst()
        try { file?.appendText(line + "\n") } catch (_: Exception) {}
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        i("ERROR " + msg + (t?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    @Synchronized
    fun text(): String = ring.joinToString("\n")

    @Synchronized
    fun clear() { ring.clear(); try { file?.writeText("") } catch (_: Exception) {} }

    fun path(): String = file?.absolutePath ?: "-"
}
