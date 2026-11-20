// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.mirror.MirrorService
import dk.pennico.tabcast.tvlink.TvLink

/**
 * 0.5.0 "Mirror screen": connects to Tab Cast TV, asks once for the sound permission (capture of what apps play,
 * never the microphone), then Android's "start casting?" consent, starts [MirrorService] and steps aside so the user
 * can open what they want to show.
 */
class MirrorActivity : AppCompatActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode != RESULT_OK || data == null) { AppLog.i("mirror: capture not allowed"); finish(); return@registerForActivityResult }
        MirrorService.start(this, r.resultCode, data)
        Toast.makeText(this, "Mirroring: open what you want to show. The power button stops it.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
        finish()
    }

    private val sound = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        AppLog.i("mirror: sound capture ${if (ok) "allowed" else "refused (the picture only)"}")
        askConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Mirror screen"
        setContentView(column(24) {
            addView(text("Mirror screen", 20f, true))
            addView(text("Whatever the tablet shows plays on the TV, with its sound. The tablet stays on (dimmed) while it " +
                "mirrors; the power button or Stop ends it. Protected videos (Netflix and the like) show black.", 15f), matchWrap())
        })
        if (MirrorService.state.value.running) {
            dialog("Already mirroring").setPositiveButton("Stop mirroring") { _, _ -> MirrorService.stop(this); finish() }
                .setNegativeButton("Keep going") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        TvFlow.withTv(this, cancelled = { finish() }) {
            if (!TvLink.mirrorSupported) {
                dialog("Update Tab Cast TV").setMessage("The TV's Tab Cast TV is too old to show the tablet's screen. " +
                    "Update it in Droid-ify on the TV, then try again.").setPositiveButton("OK") { _, _ -> finish() }.show()
                return@withTv
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askConsent()
            else sound.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun askConsent() {
        // Once: the overlay permission keeps the screen on with the backlight at its minimum (optional).
        val sp = getSharedPreferences("tabcast", MODE_PRIVATE)
        if (!Settings.canDrawOverlays(this) && !sp.getBoolean("mirrorAskedOverlay", false)) {
            sp.edit().putBoolean("mirrorAskedOverlay", true).apply()
            dialog("Darken the tablet while mirroring?")
                .setMessage("Allow Tab Cast to \"display over other apps\": then the tablet's screen stays on at its lowest " +
                    "brightness while mirroring (the TV picture isn't affected). Without it the screen stays on as it is.")
                .setPositiveButton("Allow") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    Toast.makeText(this, "Then come back and tap Mirror screen again", Toast.LENGTH_LONG).show()
                    finish()
                }
                .setNegativeButton("Not now") { _, _ -> launchConsent() }
                .setOnCancelListener { launchConsent() }.show()
            return
        }
        launchConsent()
    }

    private fun launchConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val ask = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
        consent.launch(ask)
    }
}
