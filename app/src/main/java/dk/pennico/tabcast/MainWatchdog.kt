// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Logs where the main thread is when it stops answering for 5 s (the app froze and no crash was
 * recorded). One log line per stall with the main thread's top frames, and one when it is free again; the log is what
 * the user shares (Settings → Show log).
 */
object MainWatchdog {
    @Volatile private var pong = SystemClock.uptimeMillis()

    fun start() {
        val main = Handler(Looper.getMainLooper())
        thread(name = "main-watchdog", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            var stalledSince = 0L
            while (true) {
                main.post { pong = SystemClock.uptimeMillis() }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@thread }
                val late = SystemClock.uptimeMillis() - pong
                if (late > 5000 && stalledSince == 0L) {
                    stalledSince = pong
                    val frames = Looper.getMainLooper().thread.stackTrace.take(14).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    AppLog.i("main thread stuck for ${late / 1000} s at: $frames")
                } else if (late < 2500 && stalledSince != 0L) {
                    AppLog.i("main thread free again after ${(SystemClock.uptimeMillis() - stalledSince) / 1000} s")
                    stalledSince = 0L
                }
            }
        }
    }
}
