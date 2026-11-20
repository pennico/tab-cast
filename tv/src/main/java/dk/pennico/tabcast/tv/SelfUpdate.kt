// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dk.pennico.tabcast.AppLog
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/**
 * 0.5.1: how Tab Cast TV installs its own update: download over HTTPS into the app's cache, then
 * Android's PackageInstaller. Android checks that the new APK is signed like the installed app (the release key), so
 * nothing else can be installed this way.
 *
 * Self-updates on Android 12+ need no OK when the app may install unknown apps and asks for no user action
 * (UPDATE_PACKAGES_WITHOUT_USER_ACTION); otherwise Android shows its own dialog once per update. Nothing is left
 * half done: a failed download is deleted, a failed session abandoned, and the installed app stays as it was.
 */
class SelfUpdate(context: Context) {
    private val ctx = context.applicationContext
    val dir: File get() = File(ctx.cacheDir, "update").apply { mkdirs() }

    class Failed(val plain: String, cause: Throwable? = null) : Exception(plain, cause)

    sealed class Result {
        object Done : Result()
        object AskedUser : Result()        // Android's dialog is showing: one OK on the device
        class Error(val plain: String) : Result()
    }

    /** May this app install updates without the user turning on "Install unknown apps" first? */
    fun mayInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Android's "Install unknown apps" page for this app (some TVs have none: starting it then throws). */
    fun unknownAppsSettings(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun online(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun plain(e: Exception): String = when {
        e is Failed -> e.plain
        !online() || e is UnknownHostException || e is ConnectException -> "No internet: the update can't be fetched"
        e is SocketTimeoutException || e is SSLException -> "The update site didn't answer: try again later"
        else -> "Download failed, try again"
    }

    /** GET [url] (https only) into [dest], through a ".part" file; [progress] gets (bytes, total or -1). */
    fun download(url: String, dest: File, maxBytes: Long, progress: (Long, Long) -> Unit = { _, _ -> }) {
        require(url.startsWith("https://")) { "updates come over https only" }
        val part = File(dest.path + ".part")
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.useCaches = false
            c.setRequestProperty("User-Agent", "TabCast-update")
            try {
                if (c.responseCode != 200) throw Failed(if (c.responseCode == 404) "The update isn't on the update site (yet)" else "Download failed (${c.responseCode}), try again")
                val total = c.contentLengthLong
                if (total > maxBytes) throw Failed("The update is unexpectedly big: not downloaded")
                c.inputStream.use { inp -> part.outputStream().use { out -> copy(inp, out, maxBytes) { progress(it, total) } } }
            } finally { c.disconnect() }
            if (!part.renameTo(dest)) throw IOException("can't rename ${part.name}")
        } catch (e: Exception) {
            part.delete(); dest.delete()
            AppLog.i("update: download of ${url.substringAfterLast('/')} failed ($e)")
            throw Failed(plain(e), e)
        }
    }

    private fun copy(inp: InputStream, out: java.io.OutputStream, max: Long, step: (Long) -> Unit) {
        val buf = ByteArray(1 shl 16)
        var n = 0L
        var shown = 0L
        while (true) {
            val r = inp.read(buf); if (r < 0) break
            out.write(buf, 0, r); n += r
            if (n > max) throw Failed("The update is unexpectedly big: not downloaded")
            if (n - shown > 256 * 1024) { shown = n; step(n) }
        }
        step(n)
    }

    fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val r = i.read(b); if (r < 0) break; d.update(b, 0, r) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** Removes downloads of earlier attempts. */
    fun clean() { dir.listFiles()?.forEach { it.delete() } }

    /**
     * Installs [apk] (this app's own package) through a PackageInstaller session. [done] is called on the main
     * thread: [Result.AskedUser] while Android's dialog shows, later [Result.Error] if the user cancels or Android
     * refuses; on success the app is replaced and its process ends (it is opened again from the home screen).
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun install(apk: File, done: (Result) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = try { pi.createSession(params) } catch (e: Exception) {
            AppLog.e("update: no install session", e); done(Result.Error("Android refused to start the update")); return
        }
        val action = "${ctx.packageName}.UPDATE_STATUS"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLog.i("update: install status $status ${msg ?: ""}")
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            else i.getParcelableExtra(Intent.EXTRA_INTENT)
                        if (confirm == null) { finish(); done(Result.Error("Android didn't show its install dialog")); return }
                        try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); done(Result.AskedUser) }
                        catch (e: Exception) { finish(); pi.abandonQuietly(id); done(Result.Error("Android didn't show its install dialog")) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> { finish(); apk.delete(); done(Result.Done) }
                    else -> {
                        finish(); apk.delete()
                        done(Result.Error(when (status) {
                            PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled: the app stays as it was"
                            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                                "Android refused the update (it isn't signed like the installed app)"
                            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space for the update"
                            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the update: allow \"Install unknown apps\" for this app"
                            else -> "The update didn't install: the app stays as it was"
                        }))
                    }
                }
            }
            fun finish() { runCatching { ctx.unregisterReceiver(this) } }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
        try {
            pi.openSession(id).use { s ->
                apk.inputStream().use { inp -> s.openWrite("base.apk", 0, apk.length()).use { out -> inp.copyTo(out, 1 shl 16); s.fsync(out) } }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(ctx, id, Intent(action).setPackage(ctx.packageName), flags)
                AppLog.i("update: installing ${apk.name} (${apk.length()} B)")
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            AppLog.e("update: install session failed", e)
            runCatching { ctx.unregisterReceiver(receiver) }
            pi.abandonQuietly(id)
            apk.delete()
            done(Result.Error("The update didn't install: the app stays as it was"))
        }
    }

    private fun PackageInstaller.abandonQuietly(id: Int) { runCatching { abandonSession(id) } }
}
