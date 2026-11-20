// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import dk.pennico.tabcast.R
import dk.pennico.tabcast.cast.CastManager
import dk.pennico.tabcast.cast.Conn
import dk.pennico.tabcast.cast.Play
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Remote control for what's on the TV. Also reachable from the notification. */
class RemoteActivity : AppCompatActivity() {
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var error: TextView
    private lateinit var retry: MaterialButton
    private lateinit var seek: Slider
    private lateinit var times: TextView
    private lateinit var volume: Slider
    private lateinit var play: android.widget.ImageButton
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = text("", 20f, true)
        status = text("", 14f)
        error = text("", 14f, false, 0xFFE57373.toInt())
        retry = button("Try again through the tablet (relay)", outlined = true) {
            lifecycleScope.launch { CastManager.retryViaRelay()?.let { info("Couldn't cast", it) } }
        }
        seek = Slider(this).apply {
            valueFrom = 0f; valueTo = 1f; value = 0f
            setLabelFormatter { fmtTime(it.toDouble()) }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) { dragging = true }
                override fun onStopTrackingTouch(s: Slider) { dragging = false; CastManager.seekTo(s.value.toDouble()) }
            })
        }
        times = text("0:00 / 0:00", 14f).apply { gravity = Gravity.CENTER }
        volume = Slider(this).apply {
            valueFrom = 0f; valueTo = 100f; stepSize = 1f
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(s: Slider) {}
                override fun onStopTrackingTouch(s: Slider) { CastManager.setVolume(s.value / 100.0) }
            })
        }
        play = iconButton(R.drawable.ic_play, "Play/pause", 72) { CastManager.togglePlay() }
        val root = column(24) {
            addView(title)
            addView(status)
            addView(error)
            addView(retry, wrap())
            addView(seek, matchWrap())
            addView(times, matchWrap())
            addView(row {
                gravity = Gravity.CENTER
                addView(button("−30", outlined = true) { CastManager.seekBy(-30.0) })
                addView(button("−10", outlined = true) { CastManager.seekBy(-10.0) })
                addView(play)
                addView(button("+10", outlined = true) { CastManager.seekBy(10.0) })
                addView(button("+30", outlined = true) { CastManager.seekBy(30.0) })
            }, matchWrap())
            addView(header("TV volume (the tablet's volume keys work too)"))
            addView(volume, matchWrap())
            addView(row {
                gravity = Gravity.CENTER
                addView(button("Stop") { CastManager.stop(); finish() })
                addView(button("Disconnect", outlined = true) { CastManager.disconnect(); finish() })
            }, matchWrap())
        }
        setContentView(scroll(root))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CastManager.state.collect { s ->
                    title.text = s.title ?: "Nothing on the TV"
                    status.text = buildString {
                        append(s.device?.label ?: "No TV")
                        append(" · ")
                        append(when (s.conn) {
                            Conn.CONNECTING -> "connecting…"; Conn.RECONNECTING -> "reconnecting…"; Conn.FAILED -> "can't reach the TV"
                            Conn.IDLE -> "not connected"; Conn.CONNECTED -> when (s.playback) {
                                Play.PLAYING -> "playing"; Play.PAUSED -> "paused"; Play.BUFFERING -> "loading…"; Play.IDLE -> "idle"
                            }
                        })
                        if (s.relayed) append(" · through the tablet (${CastManager.relay.bytesServed.get() / 1_048_576} MB)")
                        s.quality?.let { append("\nQuality: ").append(it) }
                    }
                    error.text = s.error ?: ""
                    error.visibility = if (s.error != null) View.VISIBLE else View.GONE
                    retry.visibility = if (s.error != null && !s.relayed && s.media != null) View.VISIBLE else View.GONE
                    val d = s.duration.takeIf { it > 0 && it < 1e7 }
                    seek.isEnabled = d != null
                    if (d != null) {
                        if (seek.valueTo != d.toFloat()) seek.valueTo = d.toFloat()
                        if (!dragging) seek.value = s.time.toFloat().coerceIn(0f, d.toFloat())
                    }
                    times.text = if (d != null) "${fmtTime(s.time)} / ${fmtTime(d)}" else if (s.active) "live / unknown length · ${fmtTime(s.time)}" else ""
                    // Slider has stepSize 1: a value that isn't a whole step throws when drawn.
                    if (!volume.isPressed) volume.value = (s.volume * 100).roundToInt().coerceIn(0, 100).toFloat()
                    volume.isEnabled = s.canVolume
                    play.setImageResource(if (s.playback == Play.PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
                }
            }
        }
    }
}
