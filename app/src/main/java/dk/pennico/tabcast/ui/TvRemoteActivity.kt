// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dk.pennico.tabcast.R
import dk.pennico.tabcast.link.Wire.Cmd
import dk.pennico.tabcast.link.Wire.Status
import dk.pennico.tabcast.tvlink.TvLink
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A box with a fixed height/width [ratio] that fits the space it gets (width first, then height). */
class AspectFrame(ctx: Context) : FrameLayout(ctx) {
    var ratio = 9f / 16f
        set(v) { if (field != v) { field = v; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = MeasureSpec.getSize(widthMeasureSpec)
        var h = (w * ratio).roundToInt()
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && h > maxH) {
            h = maxH
            w = (h / ratio).roundToInt()
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}

/**
 * Remote for Tab Cast TV (the browser on the TV): what the TV shows, a live picture of its page (tap = click on the
 * TV, drag = scroll, long press = long tap), player controls, the videos it found, Back / Reload / Type / Close.
 * Landscape: picture left, controls right (0.2.0 stacked them and the controls ended up under the navigation bar).
 * The tablet's volume keys change the TV's volume.
 */
class TvRemoteActivity : AppCompatActivity() {
    private lateinit var head: TextView
    private lateinit var pageTitle: TextView
    private lateinit var url: TextView
    private lateinit var picture: ImageView
    private lateinit var pictureNote: TextView
    private lateinit var frame: AspectFrame
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: android.widget.ImageButton
    private lateinit var videosBtn: com.google.android.material.button.MaterialButton
    private lateinit var blockedRow: View
    private lateinit var blockedText: TextView
    private lateinit var error: TextView
    private lateinit var adsBtn: com.google.android.material.button.MaterialButton
    private lateinit var suggestRow: View
    private lateinit var hint: View
    private lateinit var pageGrid: View
    /** 0.4.0: a film from the tablet: how much the TV holds, sound/subtitle tracks, Stop. */
    private lateinit var filmBox: View
    /** 0.5.1: a subtitle file for the film playing from this tablet (the system picker). */
    private val pickSubs = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = applicationContext
        lifecycleScope.launch {
            val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dk.pennico.tabcast.files.FileCast.addSubtitles(ctx, uri) }
            android.widget.Toast.makeText(this@TvRemoteActivity, err ?: "Subtitles sent to the TV", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    /** 0.5.1: "Update TV app to x.y.z" when the TV says its repo has a newer Tab Cast TV. */
    private lateinit var tvUpdate: com.google.android.material.button.MaterialButton
    private var tvNewer = ""
    private lateinit var filmHeld: TextView
    private lateinit var soundBtn: View
    private lateinit var subsBtn: View
    private var seeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("TV browser")
        head = text("", 14f)
        pageTitle = text("", 18f, true).apply { maxLines = 2 }
        url = text("", 12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        picture = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(0xFF000000.toInt()) }
        pictureNote = text("", 15f, color = 0xFFFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        frame = AspectFrame(this).apply {
            addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(pictureNote, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        pictureTouch()
        pos = text("0:00", 14f)
        dur = text("", 14f)
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) pos.text = fmtTime(p / 10.0) }
                override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar) { seeking = false; TvLink.command(Cmd.SEEK, sb.progress / 10.0) }
            })
        }
        playBtn = iconButton(R.drawable.ic_play, "Play/pause", 56) { TvLink.command(if (TvLink.state.value.playing) Cmd.PAUSE else Cmd.PLAY) }
        videosBtn = button("Videos on the page", outlined = true) { chooseVideo() }
        blockedText = text("", 14f)
        blockedRow = row {
            addView(blockedText, weight1())
            addView(button("Open anyway", outlined = true) { TvLink.cmd(Cmd.OPEN_BLOCKED) })
            visibility = View.GONE
        }
        error = text("", 13f, color = 0xFFE57373.toInt())
        adsBtn = button("Ad blocking on the TV…", outlined = true) { chooseAds() }
        suggestRow = column(0) {
            addView(text("The video doesn't start? Ad blocking may be in the way on this site.", 14f))
            addView(button("Turn ad blocking off for this site") { TvLink.cmd(Cmd.AD_SITE, "off") }, matchWrap())
            visibility = View.GONE
        }

        // 0.6: always there while connected: "Check the TV for updates" (older TVs answer it too), or "Update TV app to
        // x.y.z" once the TV knows one.
        tvUpdate = button("Check the TV for updates", outlined = true) {
            if (tvNewer.isNotEmpty()) TvLink.cmd(Cmd.UPDATE, "install") else TvLink.cmd(Cmd.UPDATE)
        }.apply { visibility = View.GONE }
        val info = column(0) {
            addView(tvUpdate, matchWrap())
            addView(head, matchWrap())
            addView(pageTitle, matchWrap())
            addView(url, matchWrap())
        }
        hint = text("Tap the picture to click on the TV page · drag to scroll · press and hold for a long tap", 12f)
        filmHeld = text("", 16f)
        soundBtn = button("Sound…", outlined = true) { chooseTrack(true) }
        subsBtn = button("Subtitles…", outlined = true) { chooseTrack(false) }
        filmBox = column(0) {
            addView(filmHeld, matchWrap().apply { bottomMargin = dp(8) })
            addView(grid(soundBtn, subsBtn, button("Stop", outlined = true) {
                if (TvLink.state.value.mode == "mirror") dk.pennico.tabcast.mirror.MirrorService.stop(this@TvRemoteActivity)
                TvLink.cmd(Cmd.CLOSE)
            }), matchWrap())
            visibility = View.GONE
        }
        pageGrid = grid(button("Play full screen") { TvLink.cmd(Cmd.PLAY_NATIVE) },
            button("Page's own player", outlined = true) { TvLink.cmd(Cmd.PLAY_IN_PAGE) },
            videosBtn,
            button("Type…", outlined = true) { typeText() },
            button("Back", outlined = true) { TvLink.cmd(Cmd.BACK) },
            button("Reload", outlined = true) { TvLink.cmd(Cmd.RELOAD) },
            button("Close page", outlined = true) { TvLink.cmd(Cmd.CLOSE) },
            button("This didn't work", outlined = true) { report() })
        val controls = column(0) {
            addView(row {
                addView(pos)
                addView(seek, weight1())
                addView(dur)
            }, matchWrap())
            addView(row {
                gravity = Gravity.CENTER
                addView(iconButton(R.drawable.ic_replay10, "Back 10 seconds", 56) { seekBy(-10.0) })
                addView(playBtn)
                addView(iconButton(R.drawable.ic_forward30, "Forward 30 seconds", 56) { seekBy(30.0) })
            }, matchWrap())
            addView(grid(button("Volume −", outlined = true) { TvLink.cmd(Cmd.VOLUME_BY, -1) },
                button("Volume +", outlined = true) { TvLink.cmd(Cmd.VOLUME_BY, 1) }), matchWrap())
            addView(filmBox, matchWrap())
            addView(pageGrid, matchWrap())
            addView(adsBtn, matchWrap())
            addView(suggestRow, matchWrap())
            addView(blockedRow, matchWrap())
            addView(error, matchWrap())
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            // Picture on the left (as large as fits), controls on the right: everything visible without scrolling.
            val left = column(0) {
                addView(info, matchWrap())
                addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = dp(8)
                })
                addView(hint, matchWrap())
            }
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))
                addView(scroll(controls), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f).apply { marginStart = dp(16) })
            })
        } else {
            setContentView(scroll(column(16) {
                addView(info, matchWrap())
                addView(frame, LinearLayoutParams(dp(8)))
                addView(hint, matchWrap())
                addView(controls, matchWrap())
            }))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TvLink.state.collect { show(it) } }
                launch { TvLink.preview.collect { b -> if (b != null) picture.setImageBitmap(b) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TvLink.previewOn(3, 640)
    }

    /** Absolute seek when the position is known (safe to send again after a reconnect), else relative. */
    private fun seekBy(d: Double) {
        val s = TvLink.state.value
        val p = s.pos
        if (p == null) { TvLink.cmd(Cmd.SEEK_BY, d.toInt()); return }
        val max = s.dur?.takeIf { it > 0 }?.let { it - 1 } ?: Double.MAX_VALUE
        TvLink.command(Cmd.SEEK, (p + d).coerceIn(0.0, max))
    }

    override fun onStop() {
        TvLink.previewOff()
        super.onStop()
    }

    @Suppress("FunctionName")
    private fun LinearLayoutParams(top: Int) = matchWrap().apply { topMargin = top; bottomMargin = top }

    /** Buttons two per row, equal widths: fits the narrow right pane in landscape and the full width in portrait. */
    private fun grid(vararg views: View): View = column(0) {
        for (pair in views.toList().chunked(2)) addView(row {
            for ((i, v) in pair.withIndex()) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i == 0 && pair.size == 2) marginEnd = dp(8)
            })
            if (pair.size == 1) addView(View(this@TvRemoteActivity), LinearLayout.LayoutParams(0, 0, 1f))
        }, matchWrap())
    }

    /** The tablet's volume keys: the TV's volume while this screen is open. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { TvLink.cmd(Cmd.VOLUME_BY, 1); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { TvLink.cmd(Cmd.VOLUME_BY, -1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun show(s: TvLink.State) {
        val tv = s.tvName ?: "the TV"
        val mirror = s.mode == "mirror"
        val film = s.film && s.mode == "player" || mirror
        setTitle(if (mirror) "Mirroring" else if (film) "Playing on the TV" else "TV browser")
        filmBox.visibility = if (film) View.VISIBLE else View.GONE
        val newer = s.status[Status.UPDATE].orEmpty()
        val upLine = s.status[Status.UPDATE_STATE].orEmpty()
        tvNewer = newer
        tvUpdate.visibility = if (s.connected) View.VISIBLE else View.GONE
        tvUpdate.text = when {
            newer.isNotEmpty() && upLine.isEmpty() -> "Update TV app to $newer"
            upLine.isNotEmpty() -> upLine
            else -> "Check the TV for updates"
        }
        tvUpdate.isEnabled = newer.isNotEmpty() || !upLine.startsWith("Looking for")
        for (v in listOf(pageGrid, hint)) v.visibility = if (film) View.GONE else View.VISIBLE
        soundBtn.visibility = if (mirror) View.GONE else View.VISIBLE
        subsBtn.visibility = if (mirror) View.GONE else View.VISIBLE
        if (mirror) {
            val m = dk.pennico.tabcast.mirror.MirrorService.state.value
            filmHeld.text = "The tablet's screen is on the TV" + (if (m.running && m.fps > 0) " (${m.fps.toInt()} pictures a second)" else "") +
                (if (m.running && !m.sound) ". No sound: Tab Cast wasn't allowed to capture it." else ".") + " The power button or Stop ends it."
        } else if (film) {
            filmHeld.text = filmText(s)
            soundBtn.isEnabled = (s.status[Status.A_TRACKS] ?: "").split('|').count { it.isNotBlank() } > 1
            subsBtn.isEnabled = (s.status[Status.S_TRACKS] ?: "").isNotBlank() || dk.pennico.tabcast.files.FileCast.canAddSubtitles
        }
        // Something is open on the TV (also from the iPhone's web remote): media controls on the tablet too (0.3.3).
        if (s.connected && s.mode != "idle" && !TvLink.session && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            TvLink.startSession("remote shows an open page")
            dk.pennico.tabcast.cast.CastService.start(this)
        }
        head.text = when {
            s.reconnecting || (!s.connected && TvLink.session) -> "Reconnecting to $tv…"
            s.connecting -> "Connecting to Tab Cast TV on $tv…"
            !s.connected -> "Not connected to $tv${s.error?.let { " ($it)" } ?: ""}"
            else -> "$tv" + (s.status[Status.VOLUME]?.toDoubleOrNull()?.let { " · volume ${(it * 100).roundToInt()}%" } ?: "") + " · " + when (s.mode) {
                "idle" -> "waiting"
                "page" -> if (s.status[Status.LOADING] == "true") "page loading…" else "page"
                "player" -> if (film) "playing" else "playing full screen" + (s.status[Status.VIDEO]?.let { " · $it" } ?: "")
                "inpage" -> "the page's own player, full screen"
                else -> s.mode
            }
        }
        pageTitle.text = s.status[Status.TITLE].orEmpty().ifEmpty { if (s.mode == "idle") "Nothing open on the TV" else "" }
        url.text = s.status[Status.URL].orEmpty()
        s.screen?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 && it[0] > 0 }?.let { frame.ratio = it[1].toFloat() / it[0] }
        pictureNote.text = when {
            !s.connected -> "Not connected"
            mirror -> "The tablet's screen"
            film -> "▶  " + (s.title ?: "")
            s.mode == "player" -> "Playing in the TV's own player\n(its picture isn't sent here)"
            s.mode == "idle" -> "Nothing open"
            else -> ""
        }
        pictureNote.visibility = if (pictureNote.text.isEmpty()) View.GONE else View.VISIBLE
        val d = s.dur
        val p = s.pos
        if (!seeking) {
            seek.isEnabled = d != null && d > 0
            seek.max = ((d ?: 0.0) * 10).toInt().coerceAtLeast(1)
            seek.progress = ((p ?: 0.0) * 10).toInt()
            pos.text = p?.let { fmtTime(it) } ?: ""
        }
        dur.text = d?.let { fmtTime(it) } ?: if (p != null) "live" else ""
        playBtn.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        videosBtn.text = "Videos on the page (${s.caught.size})"
        videosBtn.isEnabled = s.caught.isNotEmpty()
        val blocked = s.status[Status.BLOCKED].orEmpty()
        blockedRow.visibility = if (blocked.isEmpty()) View.GONE else View.VISIBLE
        blockedText.text = "The TV stopped the page from jumping to ${dk.pennico.tabcast.core.Urls.host(blocked)} without a click."
        error.text = s.status[Status.ERROR].orEmpty()
        val ads = s.status[Status.ADS]
        adsBtn.visibility = if (film) View.GONE else View.VISIBLE
        if (film) { suggestRow.visibility = View.GONE; blockedRow.visibility = View.GONE; error.text = s.status[Status.NO_SOUND] ?: s.status[Status.ERROR].orEmpty(); return }
        adsBtn.text = if (ads == null) "Ad blocking on the TV…" else
            "Ads on the TV: $ads" + (if (s.status[Status.ADS_READY] == "false") " (lists not loaded!)" else "") +
                (if (s.status[Status.ADS_SITE].isNullOrEmpty()) "" else " (this site)") +
                (s.status[Status.ADS_BLOCKED]?.takeIf { it != "0" && s.mode != "idle" }?.let { " · $it blocked" } ?: "")
        suggestRow.visibility = if (s.status[Status.ADS_SUGGEST] == "true") View.VISIBLE else View.GONE
    }

    /**
     * 0.5.0 "This didn't work": asks the TV for its state and log and saves them with the tablet's log in
     * `Android/data/dk.pennico.tabcast/files/reports/<time>/` (`adb pull` that folder).
     */
    private fun report() {
        val input = EditText(this).apply { hint = "What went wrong? (optional)" }
        dialog("This didn't work").setMessage("Saves what the TV shows and both logs on the tablet, for a later look.")
            .setView(column(20) { addView(input, matchWrap()) })
            .setPositiveButton("Save") { _, _ ->
                val what = input.text.toString()
                val dir = java.io.File(getExternalFilesDir(null), "reports/" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()))
                dir.mkdirs()
                java.io.File(dir, "tablet.txt").writeText("what failed: $what\nTV state: ${TvLink.state.value}\n\n--- log ---\n${dk.pennico.tabcast.AppLog.text()}")
                TvLink.onReport = { text ->
                    runCatching { java.io.File(dir, "tv.txt").writeText(text) }
                    runOnUiThread { android.widget.Toast.makeText(this, "Saved in ${dir.path.substringAfter("/Android/")}", android.widget.Toast.LENGTH_LONG).show() }
                    TvLink.onReport = null
                }
                TvLink.cmd(Cmd.REPORT, what.take(500))
                dk.pennico.tabcast.AppLog.i("report saved: ${dir.path} (the TV's part follows)")
            }.setNegativeButton("Cancel", null).show()
    }

    /** 0.4.0: how long the tablet is still needed, in plain words. */
    private fun filmText(s: TvLink.State): String {
        val pos = s.pos ?: 0.0
        val held = s.heldTo
        return when {
            s.waiting -> "Waiting for the tablet: keep it awake for a moment; the film goes on by itself."
            s.complete -> "The whole film is on the TV. The tablet can sleep now."
            held != null && held - pos >= 60 -> "On the TV up to ${fmtTime(held)}: the tablet can sleep for about ${((held - pos) / 60).toInt()} min."
            else -> "Copying to the TV… keep the tablet on for now."
        }
    }

    /** Sound or subtitle track (the TV lists them; tracks it can't play are marked). */
    private fun chooseTrack(sound: Boolean) {
        val st = TvLink.state.value.status
        val items = (st[if (sound) Status.A_TRACKS else Status.S_TRACKS] ?: "").split('|').filter { it.isNotBlank() }
        val cur = st[if (sound) Status.A_TRACK else Status.S_TRACK]?.toIntOrNull() ?: -1
        val canPick = !sound && dk.pennico.tabcast.files.FileCast.canAddSubtitles
        val labels = items.mapIndexed { i, l -> if (i == cur) "$l  ✓" else l } +
            (if (sound) emptyList() else listOf("Off" + if (cur < 0) "  ✓" else "")) +
            (if (canPick) listOf("Choose a subtitle file…") else emptyList())
        dialog(if (sound) "Sound" else "Subtitles").setItems(labels.toTypedArray()) { _, i ->
            when {
                // 0.5.4: a track the TV marks as unplayable is explained here instead of sent (the TV refuses it too).
                i < items.size && CANT_PLAY in items[i] -> cantPlay(sound, canPick)
                sound -> TvLink.command(Cmd.AUDIO, i)
                i < items.size -> TvLink.command(Cmd.SUBS, i)
                i == items.size -> TvLink.command(Cmd.SUBS, "off")
                // .srt files often have no proper type on Android: all files, Tab Cast checks the content.
                else -> pickSubs.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream", "*/*"))
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun cantPlay(sound: Boolean, canPick: Boolean) {
        val d = dialog(if (sound) "Sound" else "Subtitles").setMessage(
            if (sound) "The TV can't play this sound track (its format isn't supported there). The film keeps its current sound."
            else "The TV can't show this subtitle track (picture subtitles such as VobSub from a DVD aren't supported there)." +
                if (canPick) " You can choose a subtitle file (.srt) for this film instead." else "")
        if (canPick) d.setPositiveButton("Choose a subtitle file…") { _, _ ->
            pickSubs.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream", "*/*"))
        }.setNegativeButton("Cancel", null) else d.setPositiveButton("OK", null)
        d.show()
    }

    /** Ad blocking on the TV: for the open page's site, or for all sites (the TV keeps its own setting). */
    private fun chooseAds() {
        val st = TvLink.state.value.status
        val url = st[Status.URL].orEmpty()
        val global = st[Status.ADS_GLOBAL] ?: "standard"
        val own = st[Status.ADS_SITE].orEmpty()
        val levels = listOf("off", "standard", "aggressive")
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (dk.pennico.tabcast.core.Urls.isHttp(url) && TvLink.state.value.mode != "idle") {
            val site = dk.pennico.tabcast.core.adblock.Domains.site(dk.pennico.tabcast.core.Urls.host(url))
            items += "$site: as on all sites ($global)${if (own.isEmpty()) "  ✓" else ""}" to { TvLink.cmd(Cmd.AD_SITE, "default") }
            for (l in levels) items += "$site: $l${if (own == l) "  ✓" else ""}" to { TvLink.cmd(Cmd.AD_SITE, l) }
        }
        for (l in levels) items += "All sites on the TV: $l${if (global == l) "  ✓" else ""}" to { TvLink.cmd(Cmd.AD_LEVEL, l) }
        dialog("Ad blocking on the TV").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseVideo() {
        val list = TvLink.state.value.caught
        if (list.isEmpty()) return
        dialog("Play on the TV, full screen").setItems(list.toTypedArray()) { _, i -> TvLink.cmd(Cmd.CANDIDATE, i) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun typeText() {
        val input = EditText(this).apply { hint = "Text for the field selected on the TV"; isSingleLine = true }
        dialog("Type on the TV").setView(column(20) { addView(input, matchWrap()) })
            .setPositiveButton("Send + Enter") { _, _ -> TvLink.text(input.text.toString(), true) }
            .setNeutralButton("Send") { _, _ -> TvLink.text(input.text.toString(), false) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Tap → click on the TV page; drag → scroll it; hold still 0.6 s → long tap. Coordinates are 0..1 of the picture. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pictureTouch() {
        var x0 = 0f
        var y0 = 0f
        var t0 = 0L
        val slop = dp(12)
        frame.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Drags on the picture scroll the TV page, never this screen.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    x0 = e.x; y0 = e.y; t0 = SystemClock.uptimeMillis(); true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - x0
                    val dy = e.y - y0
                    if (abs(dx) < slop && abs(dy) < slop) {
                        TvLink.tap(x0 / v.width, y0 / v.height, SystemClock.uptimeMillis() - t0 > 600)
                    } else {
                        // Finger up = content moves up = the page scrolls down.
                        TvLink.scroll(-dx / v.width, -dy / v.height)
                    }
                    v.performClick()
                    true
                }
                else -> true
            }
        }
    }
}

/** 0.5.4: how the TV marks a track it can't play in its lists (TvPlayer.describe on the TV). */
private const val CANT_PLAY = "can't play here"
