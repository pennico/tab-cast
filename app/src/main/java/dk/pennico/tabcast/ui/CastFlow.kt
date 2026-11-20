// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dk.pennico.tabcast.TabCastApp
import dk.pennico.tabcast.cast.CastManager
import dk.pennico.tabcast.cast.CastMedia
import dk.pennico.tabcast.cast.Device
import dk.pennico.tabcast.core.StartPage
import dk.pennico.tabcast.core.Urls
import kotlinx.coroutines.launch

/** Shared "pick the TV, send it, report" steps for the browser and the Share target. */
object CastFlow {
    fun defaultDevice(): Device? {
        val all = TabCastApp.devices.all()
        return all.firstOrNull { it.key == TabCastApp.prefs.defaultDevice } ?: all.singleOrNull()
    }

    /** Run [block] with the default TV; ask if there are several and none is default; set one up if there is none. */
    fun withDevice(act: AppCompatActivity, ask: Boolean = false, block: (Device) -> Unit) {
        val all = TabCastApp.devices.all()
        if (all.isEmpty()) {
            Toast.makeText(act, "First add your TV (one-time setup)", Toast.LENGTH_LONG).show()
            act.startActivity(Intent(act, DevicesActivity::class.java))
            return
        }
        val def = defaultDevice()
        if (def != null && !ask) { block(def); askBatteryOnce(act); return }
        choose(act) { block(it); askBatteryOnce(act) }
    }

    /**
     * 0.3.3: once, at the first cast, offer to let Tab Cast run with the screen off. With battery
     * optimisation on, Android may also refuse the media notification when a cast starts from the background
     * (Share → yt-dlp) and cut the TV connection while the tablet sleeps.
     */
    @android.annotation.SuppressLint("BatteryLife")
    fun askBatteryOnce(act: AppCompatActivity) {
        val prefs = TabCastApp.prefs
        val pm = act.getSystemService(android.os.PowerManager::class.java)
        val exempt = pm.isIgnoringBatteryOptimizations(act.packageName)
        if (exempt || prefs.askedBattery || act.isFinishing || act.isDestroyed) return
        prefs.askedBattery = true
        dk.pennico.tabcast.AppLog.i("battery optimisation is on for Tab Cast; asking once")
        act.dialog("Keep the TV controls working with the screen off?")
            .setMessage("Android may pause Tab Cast in the background, and then Pause on the lock screen doesn't reach the TV. " +
                "Allow Tab Cast to run in the background (no effect on battery while nothing is cast).")
            .setPositiveButton("Allow") { _, _ ->
                try { act.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${act.packageName}"))) }
                catch (_: Exception) { try { act.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {} }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    fun choose(act: AppCompatActivity, picked: (Device) -> Unit) {
        val all = TabCastApp.devices.all()
        val labels = (all.map { "${it.label}\n${it.host}" } + "Set up TVs…").toTypedArray()
        act.dialog("Cast to").setItems(labels) { _, i ->
            if (i == all.size) act.startActivity(Intent(act, DevicesActivity::class.java))
            else { TabCastApp.prefs.defaultDevice = all[i].key; picked(all[i]) }
        }.show()
    }

    /**
     * After the app was killed while casting (no Stop reached the TV), offer to stop what is still playing there.
     * Cheap: no TV connection unless the user says Stop.
     */
    fun offerStopLeftover(act: AppCompatActivity) {
        val key = TabCastApp.prefs.castingOn ?: return
        if (CastManager.state.value.active) return
        val d = TabCastApp.devices.all().firstOrNull { it.key == key } ?: run { TabCastApp.prefs.castingOn = null; return }
        act.dialog("Something may still be playing on ${d.name}").setMessage("Tab Cast was closed while it was casting.")
            .setPositiveButton("Stop it") { _, _ ->
                Toast.makeText(act, "Stopping ${d.name}…", Toast.LENGTH_SHORT).show()
                act.lifecycleScope.launch {
                    val err = CastManager.stopLeftover(d)
                    Toast.makeText(act, err ?: "Stopped", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Leave it") { _, _ -> TabCastApp.prefs.castingOn = null }.show()
    }

    fun askNotifications(act: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** [remember]: list it under "Recently on the TV" on the start page (not for the TV test video). */
    fun cast(act: AppCompatActivity, d: Device, m: CastMedia, openRemote: Boolean, remember: Boolean = true, done: (Boolean) -> Unit = {}) {
        askNotifications(act)
        Toast.makeText(act, "Sending to ${d.name}…", Toast.LENGTH_SHORT).show()
        act.lifecycleScope.launch {
            val err = CastManager.cast(d, m)
            if (err == null) {
                if (remember) TabCastApp.prefs.addRecent(StartPage.Recent(m.title ?: Urls.path(m.url).substringAfterLast('/').ifEmpty { Urls.host(m.url) },
                    m.pageUrl, m.url, m.thumb, System.currentTimeMillis(), d.name))
                if (openRemote) act.startActivity(Intent(act, RemoteActivity::class.java))
                else Toast.makeText(act, "Playing on ${d.name}", Toast.LENGTH_SHORT).show()
                done(true)
            } else {
                if (!act.isFinishing) act.info("Couldn't cast", err)
                done(false)
            }
        }
    }
}
