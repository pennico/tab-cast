// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.cast.CastMedia
import dk.pennico.tabcast.cast.Device
import dk.pennico.tabcast.cast.Discovery
import dk.pennico.tabcast.cast.Net
import dk.pennico.tabcast.cast.Protocol
import dk.pennico.tabcast.core.TestVideos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Set up TVs: scan the Wi-Fi by port (works where mDNS doesn't), add by IP, pick the default, send a test video. */
class DevicesActivity : AppCompatActivity() {
    private lateinit var saved: android.widget.LinearLayout
    private lateinit var found: android.widget.LinearLayout
    private lateinit var busy: ProgressBar
    private lateinit var status: android.widget.TextView
    private val foundSet = LinkedHashMap<String, Device>()
    private var discovery: Discovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saved = column(0)
        found = column(0)
        busy = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        status = text("", 14f)
        val root = column(20) {
            addView(text("TVs", 22f, true))
            addView(text("FCast Receiver (on the TV) is the best choice: it plays the stream with the page's cookies. " +
                "Chromecast built-in works too; then the stream goes through the tablet.", 14f))
            addView(header("Saved"))
            addView(saved, matchWrap())
            addView(header("Find TVs"))
            addView(row {
                addView(button("Scan the Wi-Fi") { scan() })
                addView(button("Add by IP address", outlined = true) { addByIp() })
            })
            addView(busy)
            addView(status)
            addView(found, matchWrap())
        }
        setContentView(scroll(root))
        refresh()
        if (TabCastApp.devices.all().isEmpty()) scan()
    }

    override fun onStart() {
        super.onStart()
        discovery = Discovery(this) { d -> runOnUiThread { addFound(d) } }.also { it.start() }
    }

    override fun onStop() {
        discovery?.stop(); discovery = null
        super.onStop()
    }

    private fun refresh() {
        saved.removeAllViews()
        val all = TabCastApp.devices.all()
        if (all.isEmpty()) saved.addView(text("None yet. Scan the Wi-Fi, or add the TV's IP address (Chromecast: Settings > System > About > Status).", 14f))
        val def = CastFlow.defaultDevice()
        for (d in all) {
            val isDef = d.key == def?.key
            saved.addView(row {
                setPadding(0, dp(8), 0, dp(8))
                addView(column(0) {
                    addView(text((if (isDef) "★ " else "") + d.label, 16f, isDef))
                    addView(text("${d.host}:${d.port}", 13f))
                }, weight1())
                addView(button("Test", outlined = true) { test(d) })
                addView(button("⋮", outlined = true) { manage(d) })
            }, matchWrap())
        }
        found.removeAllViews()
        val keys = all.map { it.key }.toSet()
        for (d in foundSet.values.filter { it.key !in keys }) {
            found.addView(row {
                setPadding(0, dp(6), 0, dp(6))
                addView(column(0) {
                    addView(text(d.label, 16f))
                    addView(text("${d.host}:${d.port}" + if (d.protocol == Protocol.FCAST) "  (recommended)" else "", 13f))
                }, weight1())
                addView(button("Add") { save(d) })
            }, matchWrap())
        }
    }

    private fun save(d: Device) {
        TabCastApp.devices.add(d)
        val def = CastFlow.defaultDevice()
        if (def == null || (d.protocol == Protocol.FCAST && def.protocol != Protocol.FCAST)) TabCastApp.prefs.defaultDevice = d.key
        AppLog.i("saved TV ${d.label} ${d.host}:${d.port}")
        refresh()
    }

    private fun addFound(d: Device) {
        foundSet.putIfAbsent(d.key, d)
        refresh()
    }

    private fun scan() {
        busy.visibility = View.VISIBLE
        status.text = "Scanning ${Net.localIpv4(this)?.first?.substringBeforeLast('.') ?: "the Wi-Fi"}.x for FCast (46899) and Chromecast (8009)…"
        lifecycleScope.launch {
            val t = System.currentTimeMillis()
            val list = try { Net.scan(this@DevicesActivity) } catch (e: Exception) { AppLog.e("scan", e); emptyList() }
            AppLog.i("scan found ${list.size} in ${System.currentTimeMillis() - t} ms: ${list.joinToString { it.key }}")
            list.forEach { foundSet.putIfAbsent(it.key, it) }
            busy.visibility = View.GONE
            status.text = if (list.isEmpty()) "No TV answered. Is the TV on, and is FCast Receiver open on it? Both must be on the same Wi-Fi." else "Found ${list.size}:"
            // First run: one TV with FCast → save it right away.
            if (TabCastApp.devices.all().isEmpty() && list.isNotEmpty()) {
                list.filter { it.protocol == Protocol.FCAST }.ifEmpty { list }.forEach { save(it) }
                list.forEach { if (TabCastApp.devices.all().none { s -> s.key == it.key }) foundSet.putIfAbsent(it.key, it) }
                Toast.makeText(this@DevicesActivity, "TV saved. Tap Test to check it.", Toast.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    private fun addByIp() {
        val input = EditText(this).apply { hint = "The TV's IP address"; inputType = InputType.TYPE_CLASS_PHONE }
        dialog("TV IP address").setView(column(20) { addView(input, matchWrap()) })
            .setPositiveButton("Check") { _, _ ->
                val host = input.text.toString().trim()
                if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)) { Toast.makeText(this, "Not an IP address", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                busy.visibility = View.VISIBLE
                status.text = "Checking $host…"
                lifecycleScope.launch {
                    val list = Net.probeHost(host)
                    busy.visibility = View.GONE
                    if (list.isEmpty()) {
                        status.text = "Nothing answers at $host on 46899 (FCast) or 8009 (Chromecast)."
                        dialog("Not found").setMessage("Save it anyway as FCast Receiver?")
                            .setPositiveButton("Save as FCast") { _, _ -> save(Device("TV $host", Protocol.FCAST, host)) }
                            .setNegativeButton("Cancel", null).show()
                    } else {
                        status.text = "Found at $host:"
                        list.forEach { foundSet[it.key] = it }
                        refresh()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun manage(d: Device) {
        dialog(d.label).setItems(arrayOf("Make default", "Rename", "Remove")) { _, i ->
            when (i) {
                0 -> { TabCastApp.prefs.defaultDevice = d.key; refresh() }
                1 -> {
                    val input = EditText(this).apply { setText(d.name) }
                    dialog("Name").setView(column(20) { addView(input, matchWrap()) })
                        .setPositiveButton("Save") { _, _ ->
                            val wasDef = TabCastApp.prefs.defaultDevice == d.key
                            TabCastApp.devices.rename(d, input.text.toString().ifBlank { d.name })
                            if (wasDef) TabCastApp.prefs.defaultDevice = d.copy(name = input.text.toString()).key
                            refresh()
                        }.setNegativeButton("Cancel", null).show()
                }
                2 -> { TabCastApp.devices.remove(d); refresh() }
            }
        }.show()
    }

    /** A public sample video: if this plays, the TV path works and any failure is about the site. */
    private fun test(d: Device) {
        Toast.makeText(this, "Checking the test videos…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val pick = withContext(Dispatchers.IO) { TestVideos.firstWorking { TestVideos.httpCheck(it) } }
            if (pick.failures.isNotEmpty()) AppLog.i("test videos not answering: ${pick.failures.joinToString()}")
            val v = pick.video
            if (v == null) {
                info("No test video answers", "None of the sample videos answered from the tablet:\n" +
                    pick.failures.joinToString("\n") + "\n\nIs the tablet online?")
                return@launch
            }
            val m = CastMedia(url = v.url, mime = v.mime, headers = emptyMap(), title = v.title, thumb = v.thumb, pageUrl = null)
            CastFlow.cast(this@DevicesActivity, d, m, openRemote = true, remember = false)
        }
    }
}
