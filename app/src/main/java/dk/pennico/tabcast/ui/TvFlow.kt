// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.ui

import android.content.Intent
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.core.Urls
import dk.pennico.tabcast.link.Wire
import dk.pennico.tabcast.tvlink.TvLink
import kotlinx.coroutines.launch

/** Opening pages on Tab Cast TV (the browser on the TV). */
object TvFlow {
    /**
     * Connects to Tab Cast TV on the saved TV (the same IP as its FCast entry), then runs [block]. If it doesn't answer,
     * says how to open it on the TV, with Try again; [cancelled] runs when the user gives up.
     */
    fun withTv(act: AppCompatActivity, cancelled: () -> Unit = {}, block: () -> Unit) {
        CastFlow.withDevice(act) { d ->
            act.lifecycleScope.launch {
                val err = TvLink.connect(d.host, d.name)
                if (err == null) { block(); return@launch }
                if (err == TvLink.NEEDS_PAIRING) { askPin(act, d.name, null, cancelled, block); return@launch }
                if (act.isFinishing || act.isDestroyed) return@launch
                act.dialog("Tab Cast TV isn't answering on ${d.name}")
                    .setMessage("Open Tab Cast TV on the TV (Apps → Tab Cast TV), then tap Try again.\n\n${d.host}:${Wire.PORT}: $err")
                    .setPositiveButton("Try again") { _, _ -> withTv(act, cancelled, block) }
                    .setNegativeButton("Cancel") { _, _ -> cancelled() }
                    .setOnCancelListener { cancelled() }
                    .show()
            }
        }
    }

    /**
     * 0.3.0: Tab Cast TV only obeys paired devices. It shows a 6-digit code; the user types it here once and the
     * tablet keeps the token it gets.
     */
    private fun askPin(act: AppCompatActivity, tv: String, message: String?, cancelled: () -> Unit, block: () -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        val input = android.widget.EditText(act).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "6-digit code"
            textSize = 24f
        }
        act.dialog("Pair with $tv")
            .setMessage((message?.let { "$it\n\n" } ?: "") + "Tab Cast TV shows a 6-digit code. Type it here (once; the tablet remembers the pairing).")
            .setView(act.column(20) { addView(input, matchWrap()) })
            .setPositiveButton("Pair") { _, _ ->
                act.lifecycleScope.launch {
                    when (val r = TvLink.pair(input.text.toString())) {
                        TvLink.PairOutcome.Ok -> block()
                        is TvLink.PairOutcome.Wrong -> askPin(act, tv, "Wrong code${r.triesLeft?.let { ", $it tries left" } ?: ""}.", cancelled, block)
                        is TvLink.PairOutcome.Locked -> act.dialog("Too many wrong codes").setMessage("Wait ${r.seconds} s, then try again (the TV shows a new code).")
                            .setPositiveButton("OK") { _, _ -> cancelled() }.show()
                        is TvLink.PairOutcome.Failed -> askPin(act, tv, "Not paired: ${r.why}.", cancelled, block)
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> cancelled() }
            .setOnCancelListener { cancelled() }
            .show()
    }

    /**
     * Opens [url] on the TV and shows the TV remote. [withLogin]: send the tablet's cookies for that site too.
     * [done] gets true when the page went to the TV, false when the user gave up.
     */
    fun open(act: AppCompatActivity, url: String, withLogin: Boolean, done: (Boolean) -> Unit = {}) {
        if (!Urls.isHttp(url)) { Toast.makeText(act, "Not a web page", Toast.LENGTH_SHORT).show(); done(false); return }
        withTv(act, cancelled = { done(false) }) {
            val cookies = if (withLogin) try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null } else null
            TvLink.open(url, cookies)
            // 0.3.3: a page on the TV is a cast like any other: media notification, lock screen, link kept up.
            TvLink.startSession("open on TV")
            dk.pennico.tabcast.cast.CastService.start(act)
            AppLog.i("open on TV: $url${if (cookies != null) " (with ${cookies.split(';').size} cookies)" else ""}")
            act.startActivity(Intent(act, TvRemoteActivity::class.java))
            done(true)
        }
    }

    /** Shows the TV remote (connecting first). */
    fun remote(act: AppCompatActivity) = withTv(act) { act.startActivity(Intent(act, TvRemoteActivity::class.java)) }
}
