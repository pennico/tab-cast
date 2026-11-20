// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.probe

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Probe output: a readable log (probe.log, also logcat tag TabCastProbe) and results.json with one object per step.
 * Both are rewritten as the run goes, so a crash still leaves what was measured.
 */
class Report(val dir: File) {
    private val log = File(dir, "probe.log")
    private val json = JSONObject()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    var onLine: ((String) -> Unit)? = null

    init { dir.mkdirs() }

    fun line(s: String) {
        Log.i(TAG, s)
        synchronized(this) { log.appendText("${time.format(Date())} $s\n") }
        onLine?.invoke(s)
    }

    fun put(step: String, key: String, value: Any?) = synchronized(this) {
        val o = json.optJSONObject(step) ?: JSONObject().also { json.put(step, it) }
        o.put(key, wrap(value))
    }

    fun get(step: String, key: String): Any? = synchronized(this) { json.optJSONObject(step)?.opt(key) }

    fun save() = synchronized(this) { File(dir, "results.json").writeText(json.toString(2)) }

    private fun wrap(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), wrap(x)) } }
        is Collection<*> -> JSONArray().also { a -> v.forEach { a.put(wrap(it)) } }
        is IntArray -> JSONArray().also { a -> v.forEach { a.put(it) } }
        is Double -> if (v.isNaN() || v.isInfinite()) v.toString() else v
        else -> v
    }

    companion object { const val TAG = "TabCastProbe" }
}
