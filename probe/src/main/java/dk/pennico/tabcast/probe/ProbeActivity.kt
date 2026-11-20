// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.probe

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Presentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.Surface
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Runs the measurements of step 1 one after the other on the probe page (assets/probe.html):
 *
 *  info        device, display, GPU, WebView, AVC encoders and what they claim
 *  page        the page's video starts (and how fast the page renders on its own)
 *  copy        PixelCopy of the window: frames per second at several sizes, and whether the video is in the copy
 *  convert     RGBA -> NV12 in C (NEON and plain C), and whether both agree
 *  encode      each H.264 encoder with ByteBuffer input: throughput, delay, decoded-back picture check
 *  pipeline    copy -> convert -> encode at 30 fps for 10 s, letterboxed into 1280x720 (the PoC sender's hot path)
 *  vd          a private VirtualDisplay (no permission) -> ImageReader, page shown on it with a Presentation
 *  vdenc       the same VirtualDisplay straight into the encoders' input Surface (screenrecord's path)
 *  projection  MediaProjection consent, AudioPlaybackCapture of our own sound, whole-screen mirror -> ImageReader
 *  probe 2 (0.4/0.5):
 *  vp8         each VP8 encoder (Exynos OMX, c2) at 960x720 and 1280x720: throughput, delay, bitrate, decode-back check
 *  audiothers  AudioPlaybackCapture of other apps' sound (VLC, Brave, Tab Cast's browser)
 *  mirrorenc   MediaProjection -> ImageReader -> C convert -> VP8 at both sizes for 20 s each
 *  long        the same for minutes, another app's video in front, screen dimmed + kept on (`--es long <seconds>`)
 *
 * Start: `am start -n dk.pennico.tabcast.probe/.ProbeActivity [--es tests copy,encode]`. Results: probe.log,
 * results.json, PNGs and encoded samples in /sdcard/Android/data/dk.pennico.tabcast.probe/files/run-<time>/.
 * The log line "PROBE consent-wanted" asks for the screen-capture tap; "PROBE DONE" ends the run.
 */
class ProbeActivity : Activity() {
    private val scope = MainScope()
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var report: Report
    private lateinit var page: PageState
    private val worker = HandlerThread("probe-worker").apply { start() }
    private val handler = Handler(worker.looper)
    private var consent: CompletableDeferred<Pair<Int, Intent?>>? = null
    private var permission: CompletableDeferred<Boolean>? = null
    /** Real 1280x720 copies of the page, used as encoder input. */
    private val frames720 = ArrayList<Bitmap>()
    /** Encoders that worked in "encode": name -> (hardware, paced fps). */
    private val working = LinkedHashMap<String, Pair<Boolean, Double>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val files = getExternalFilesDir(null) ?: filesDir
        val dir = File(files, "run-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()))
        report = Report(dir)
        File(files, "LAST").writeText(dir.name + "\n")
        page = PageState(report, "main")
        web = probePage(this, page, "Tab Cast probe")
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB0000000.toInt())
            textSize = 13f
            setPadding(16, 8, 16, 8)
        }
        setContentView(FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        })
        hideBars()
        report.onLine = { s -> runOnUiThread { status.text = s.take(200) } }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { report.line("CRASH in ${t.name}: ${Log.getStackTraceString(e)}"); report.save() }
            previous?.uncaughtException(t, e)
        }
        val wanted = (intent.getStringExtra("tests") ?: "all").split(',').map { it.trim() }.toSet()
        scope.launch { runAll(wanted) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        report.line("probe already running; start it again after PROBE DONE (or force-stop it first)")
    }

    override fun onResume() {
        super.onResume()
        hideBars()
    }

    override fun onDestroy() {
        scope.cancel()
        web.destroy()
        worker.quitSafely()
        super.onDestroy()
    }

    @Deprecated("Activity result API of android.app.Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONSENT) consent?.complete(resultCode to data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permission?.complete(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- sequence

    private suspend fun runAll(wanted: Set<String>) {
        fun on(n: String) = "all" in wanted || n in wanted
        report.line("PROBE START ${packageManager.getPackageInfo(packageName, 0).versionName}, steps: ${wanted.joinToString(",")}")
        step("info", 30) { info() }
        step("page", 45) { waitPage() }
        if (on("copy")) step("copy", 90) { copyTests() }
        if (on("convert")) step("convert", 60) { convertTests() }
        if (on("encode")) step("encode", 150) { encodeTests() }
        if (on("pipeline")) step("pipeline", 60) { pipeline() }
        if (on("vd")) step("vd", 60) { virtualDisplayReader() }
        if (on("vdenc")) step("vdenc", 90) { virtualDisplayEncoders() }
        // Probe 2 (phase 0): VP8 instead of H.264, other apps' sound, the mirror pipeline.
        if (on("vp8")) step("vp8", 180) { vp8Tests() }
        if (on("projection")) step("projection", 180) { projection() }
        if (on("audiothers")) step("audiothers", 150) { audioOthers(projectionOnce()) }
        if (on("mirrorenc")) step("mirrorenc", 120) { mirrorEncode(projectionOnce()) }
        if (on("long")) step("long", longSeconds() + 90) { longMirror(projectionOnce()) }
        projectionHeld?.let { mp -> runCatching { mp.stop() }; stopService(Intent(this, ProjectionService::class.java)) }
        projectionHeld = null
        report.save()
        File(report.dir, "DONE").writeText("ok\n")
        report.line("PROBE DONE: ${report.dir}")
    }

    private suspend fun step(name: String, limitS: Int, block: suspend () -> Unit) {
        report.line("== $name")
        val t0 = SystemClock.elapsedRealtime()
        try {
            withTimeout(limitS * 1000L) { block() }
        } catch (e: TimeoutCancellationException) {
            report.put(name, "error", "timed out after $limitS s")
            report.line("$name: timed out after $limitS s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report.put(name, "error", e.toString())
            report.line("$name failed: ${Log.getStackTraceString(e)}")
        }
        report.put(name, "seconds", (SystemClock.elapsedRealtime() - t0) / 1000.0)
        report.save()
    }

    // -------------------------------------------------------------------------------------------------------- info

    private suspend fun info() {
        val k = "info"
        report.put(k, "app", packageManager.getPackageInfo(packageName, 0).versionName)
        report.put(k, "device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        report.put(k, "build", Build.DISPLAY)
        report.put(k, "abis", Build.SUPPORTED_ABIS.toList())
        report.put(k, "neon", Native.hasNeon())
        val d = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        d?.mode?.let { report.put(k, "display", "${it.physicalWidth}x${it.physicalHeight} @ ${it.refreshRate} Hz, ${resources.displayMetrics.densityDpi} dpi") }
        report.put(k, "webView", WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" })
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        report.put(k, "memory", "total ${mi.totalMem shr 20} MB, available ${mi.availMem shr 20} MB, app heap ${am.largeMemoryClass} MB")
        report.put(k, "gpu", withContext(Dispatchers.Default) { glInfo() })
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        report.put(k, "videoEncoders", codecs.filter { c -> c.isEncoder && c.supportedTypes.any { it.startsWith("video/") } }
            .map { c -> c.name + " " + c.supportedTypes.joinToString("/") })
        for (c in codecs.filter { c -> c.isEncoder && AVC in c.supportedTypes }) {
            val caps = c.getCapabilitiesForType(AVC)
            val v = caps.videoCapabilities
            val e = caps.encoderCapabilities
            report.put("encoders", c.name, linkedMapOf(
                "hardware" to c.isHardwareAccelerated,
                "colorFormats" to caps.colorFormats.map { Encoder.colorName(it) },
                "cbr" to e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
                "vbr" to e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
                "720p" to v.isSizeSupported(1280, 720), "1080p" to v.isSizeSupported(1920, 1080),
                "achievable720p" to runCatching { v.getAchievableFrameRatesFor(1280, 720)?.toString() }.getOrNull(),
                "maxInstances" to caps.maxSupportedInstances,
                "profiles" to caps.profileLevels.map { "${it.profile}/${it.level}" }.distinct().take(12)))
        }
        report.line("info: ${report.get(k, "device")}; ${report.get(k, "display")}; ${report.get(k, "gpu")}; ${report.get(k, "webView")}")
    }

    private fun glInfo(): String = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val cfg = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
        val ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        val s = "${GLES20.glGetString(GLES20.GL_VENDOR)} ${GLES20.glGetString(GLES20.GL_RENDERER)}, ${GLES20.glGetString(GLES20.GL_VERSION)}"
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, surf)
        EGL14.eglDestroyContext(dpy, ctx)
        s
    }.getOrElse { "unknown ($it)" }

    private suspend fun waitPage() {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < 30_000 && !page.playing) delay(250)
        report.put("page", "videoPlayingAfterS", if (page.playing) (SystemClock.elapsedRealtime() - t0) / 1000.0 else null)
        page.summary().forEach { (key, v) -> report.put("page", key, v) }
        val decor = window.decorView
        val loc = IntArray(2).also { web.getLocationInWindow(it) }
        report.put("page", "window", "${decor.width}x${decor.height}, WebView at ${loc[0]},${loc[1]} ${web.width}x${web.height}")
        delay(3000)
        report.put("page", "idlePageFps", page.fps)
        report.line("page: video ${if (page.playing) "playing" else "NOT playing"} (${page.summary()["src"]}), page ${page.fps} fps, window ${decor.width}x${decor.height}")
    }

    // -------------------------------------------------------------------------------------------------------- copy

    /** One PixelCopy of the whole window into [bmp]: result code, time of the callback, time spent inside request(). */
    private suspend fun copyOnce(bmp: Bitmap): Triple<Int, Long, Long> {
        var callNs = 0L
        val (code, at) = suspendCancellableCoroutine { c ->
            val t = System.nanoTime()
            try {
                PixelCopy.request(window, bmp, { r -> c.resume(r to System.nanoTime()) }, handler)
            } catch (e: Exception) {
                report.line("PixelCopy.request threw $e")
                c.resume(-100 to System.nanoTime())
            }
            callNs = System.nanoTime() - t
        }
        return Triple(code, at, callNs)
    }

    private suspend fun copyTests() {
        val decor = window.decorView
        val (aw, ah) = Calc.fit(decor.width, decor.height, 1280, 720)
        val runs = listOf("full" to (decor.width to decor.height), "fit${aw}x$ah" to (aw to ah), "1280x720" to (1280 to 720),
            "640x360" to (640 to 360))
        for ((label, size) in runs) copyRun(label, Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888), 4.0, 0)
        copyRun("fit${aw}x$ah-paced30", Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888), 4.0, 33)
    }

    private suspend fun copyRun(label: String, bmp: Bitmap, seconds: Double, paceMs: Long) {
        val decor = window.decorView
        val loc = IntArray(2).also { web.getLocationInWindow(it) }
        val lat = Calc.Samples()
        val call = Calc.Samples()
        val codes = TreeMap<Int, Int>()
        val vs = SigTrack()
        val cs = SigTrack()
        val fps = Calc.Samples()
        var ok = 0
        val t0 = System.nanoTime()
        var next = t0
        while (System.nanoTime() - t0 < seconds * 1e9) {
            if (paceMs > 0) {
                val wait = (next - System.nanoTime()) / 1_000_000
                if (wait > 0) delay(wait)
                next += paceMs * 1_000_000
            }
            val ts = System.nanoTime()
            val (code, at, callNs) = withTimeoutOrNull(2000) { copyOnce(bmp) } ?: Triple(-99, System.nanoTime(), 0L)
            codes.merge(code, 1, Int::plus)
            if (code == -99) { report.line("copy $label: no answer from PixelCopy in 2 s"); break }
            if (code != PixelCopy.SUCCESS) continue
            ok++
            lat.add((at - ts) / 1e6)
            call.add(callNs / 1e6)
            page.rect("video")?.let { r ->
                val b = Calc.cssToCopy(r[0], r[1], r[2], r[3], page.dpr, loc[0], loc[1], decor.width, decor.height, bmp.width, bmp.height)
                vs.add(Native.bitmapSig(bmp, b.x, b.y, b.w, b.h, 4))
            }
            page.rect("clock")?.let { r ->
                val b = Calc.cssToCopy(r[0], r[1], r[2], r[3], page.dpr, loc[0], loc[1], decor.width, decor.height, bmp.width, bmp.height, 0.02)
                cs.add(Native.bitmapSig(bmp, b.x, b.y, b.w, b.h, 2))
            }
            fps.add(page.fps)
            if (bmp.width == 1280 && bmp.height == 720 && frames720.size < 6 && ok % 8 == 1) frames720 += bmp.copy(Bitmap.Config.ARGB_8888, false)
        }
        val wall = (System.nanoTime() - t0) / 1e9
        val res = linkedMapOf<String, Any?>("size" to "${bmp.width}x${bmp.height}", "fps" to r1(ok / wall),
            "latency" to lat.summary(), "insideRequest" to call.summary(), "resultCodes" to codes.map { "${it.key}x${it.value}" },
            "video" to vs.summary(), "clock" to cs.summary(), "pageFpsMean" to fps.summary()["mean"])
        report.put("copy", label, res)
        report.line("copy $label: ${r1(ok / wall)} fps, latency ${lat.text()}, inside request() ${call.summary()["mean"]} ms; " +
            "video ${vs.verdict()}; clock changed ${cs.changedText()}; page ${fps.summary()["mean"]} fps")
        if (ok > 0 && (label == "full" || label.startsWith("fit") && paceMs == 0L)) savePng(bmp.copy(Bitmap.Config.ARGB_8888, false), "copy-$label")
    }

    /** Signatures of one region over successive frames: changed share, brightness, dark share. */
    private class SigTrack {
        var n = 0; var changed = 0; var compared = 0; var mean = 0L; var dark = 0.0
        private var last: Long? = null
        fun add(s: LongArray?) {
            if (s == null || s[2] == 0L) return
            n++
            last?.let { compared++; if (it != s[3]) changed++ }
            last = s[3]
            mean += s[0]
            dark += s[1].toDouble() / s[2]
        }
        fun changedText() = if (compared == 0) "n/a" else "$changed/$compared"
        fun summary(): Map<String, Any?> = if (n == 0) mapOf("frames" to 0) else linkedMapOf("changed" to changedText(),
            "brightness" to mean / n, "darkShare" to (dark / n * 100).roundToInt())
        /** Is the page's video in the frames? Changing and not black = yes. */
        fun verdict(): String = when {
            n == 0 -> "not measured"
            compared > 0 && changed * 2 > compared && dark / n < 0.9 -> "IN the copy (changed ${changedText()}, brightness ${mean / n})"
            dark / n >= 0.9 -> "BLACK in the copy (dark ${(dark / n * 100).roundToInt()}%, changed ${changedText()})"
            else -> "NOT changing in the copy (changed ${changedText()}, brightness ${mean / n})"
        }
    }

    // ----------------------------------------------------------------------------------------------------- convert

    private suspend fun convertTests() = withContext(Dispatchers.Default) {
        val src = frames720.firstOrNull() ?: synthetic(1280, 720, 0)
        val y = ByteBuffer.allocateDirect(1280 * 720)
        val uv = ByteBuffer.allocateDirect(1280 * 360)
        val u = uv.at(0)
        val v = uv.at(1)
        fun run(plainC: Boolean, n: Int) = Calc.Samples().also { s ->
            repeat(3) { Native.convert(src, y, 1280, u, v, 1280, 2, 0, 0, plainC) }
            repeat(n) { s.add(Native.convert(src, y, 1280, u, v, 1280, 2, 0, 0, plainC) / 1e6) }
        }
        val neon = run(false, 60)
        val yA = y.bytes()
        val uvA = uv.bytes()
        val plain = run(true, 30)
        val (yDiff, uvDiff) = diff(yA, y.bytes()) to diff(uvA, uv.bytes())
        // I420 layout and the letterbox case (960x720 into 1280x720 with black bars), as the pipeline does it
        val up = ByteBuffer.allocateDirect(640 * 360)
        val vp = ByteBuffer.allocateDirect(640 * 360)
        Native.convert(src, y, 1280, up, vp, 640, 1, 0, 0, false)
        val i420 = y.bytes() to up.bytes()
        Native.convert(src, y, 1280, up, vp, 640, 1, 0, 0, true)
        val i420Diff = diff(i420.first, y.bytes()) + diff(i420.second, up.bytes())
        val small = Bitmap.createScaledBitmap(src, 960, 720, true)
        val box = Calc.Samples()
        repeat(30) {
            val t = System.nanoTime()
            Native.fillBlack(y, 1280, u, v, 1280, 2, 0, 0, 1280, 720)
            Native.convert(small, y, 1280, u, v, 1280, 2, 160, 0, false)
            box.add((System.nanoTime() - t) / 1e6)
        }
        report.put("convert", "neon", Native.hasNeon())
        report.put("convert", "best1280x720", neon.summary())
        report.put("convert", "plainC1280x720", plain.summary())
        report.put("convert", "letterbox960into1280", box.summary())
        report.put("convert", "neonVsC", "Y ${yDiff} bytes differ, UV ${uvDiff}, I420 ${i420Diff}")
        report.line("convert 1280x720: NEON ${neon.summary()["p50"]} ms, plain C ${plain.summary()["p50"]} ms, letterbox ${box.summary()["p50"]} ms; " +
            "NEON vs C differing bytes: Y $yDiff, UV $uvDiff, I420 $i420Diff (must be 0)")
    }

    private fun ByteBuffer.bytes(): ByteArray = ByteArray(capacity()).also { duplicate().apply { clear() }.get(it) }
    private fun diff(a: ByteArray, b: ByteArray): Int = a.indices.count { a[it] != b[it] }

    /** Test picture when no real copy exists: gradient, shapes, moving with [k]. */
    private fun synthetic(w: Int, h: Int, k: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(20, 40, 120), Color.rgb(230, 120, 30), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.WHITE
        p.textSize = h / 8f
        c.drawText("Tab Cast probe $k", w / 10f, h / 3f, p)
        p.color = Color.rgb(40, 200, 90)
        c.drawCircle(w * (0.2f + 0.1f * (k % 7)), h * 0.7f, h / 8f, p)
    }

    // ------------------------------------------------------------------------------------------------------ encode

    private fun avcEncoders(): List<String> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && AVC in it.supportedTypes }.map { it.name }
        val first = listOf("OMX.Exynos.AVC.Encoder", "c2.android.avc.encoder", "OMX.google.h264.encoder")
        return first.filter { it in all } + all.filter { it !in first }
    }

    private suspend fun encodeTests() {
        val frames = frames720.ifEmpty { List(6) { synthetic(1280, 720, it) } }
        report.put("encode", "input", if (frames720.isEmpty()) "synthetic pictures" else "${frames.size} copies of the page")
        for (name in avcEncoders()) withContext(Dispatchers.Default) { encodeOne(name, frames) }
    }

    private fun encodeOne(name: String, frames: List<Bitmap>, mime: String = AVC, w: Int = 1280, h: Int = 720,
                          into: MutableMap<String, Pair<Boolean, Double>>? = working) {
        val key = if (mime == AVC) "encode:$name" else "vp8:$name:${w}x$h"
        val ext = if (mime == AVC) "mp4" else "webm"
        report.line("encode $name ${w}x$h …")
        val enc = try { Encoder(name, report, key, w, h, if (mime == AVC) 4_000_000 else 3_000_000, 30, mime) } catch (e: Exception) {
            report.put(key, "error", "create: $e"); report.line("encode $name: can't create ($e)"); return
        }
        val base = File(report.dir, "enc-" + name.replace(Regex("[^A-Za-z0-9.]"), "_") + if (mime == AVC) "" else "-${w}x$h")
        var ok = false
        try {
            enc.start(surfaceInput = false)
            val burst = enc.burst(frames, 3.0, image = true)
            report.put(key, "burstImage", burst)
            report.put(key, "firstOutputMs", enc.firstOutputMs)
            enc.requestKeyFrame()
            enc.sink = Sink(File(base.path + "-image"), report, mime == VP8)
            val pacedImage = enc.paced(frames, 3.0, image = true)
            report.put(key, "pacedImage", pacedImage)
            report.put(key, "keyframes/nals", "${enc.sink.keyframes} key of ${enc.sink.frames}" + if (mime == AVC) "; NAL types ${enc.sink.nals.take(16)}" else "")
            enc.sink.close()
            enc.requestKeyFrame()
            enc.sink = Sink(File(base.path + "-buffer"), report, mime == VP8)
            val pacedBuffer = enc.paced(frames, 3.0, image = false)
            report.put(key, "pacedBuffer", pacedBuffer)
            enc.sink.close()
            val fps = (pacedImage["encodedFps"] as Double)
            ok = fps > 0
            if (ok) into?.put(name, enc.hardware to fps)
            report.line("encode $name ${w}x$h: burst ${burst["encodedFps"]} fps (convert ${(burst["convertMs"] as Map<*, *>)["p50"]} ms), " +
                "paced 30 → ${pacedImage["encodedFps"]} fps, delay ${(pacedImage["latency"] as Map<*, *>)["p50"]} ms p50, " +
                "${pacedImage["kbps"]} kbit/s; buffer mode ${pacedBuffer["encodedFps"]} fps")
        } catch (e: Exception) {
            val diag = (e as? android.media.MediaCodec.CodecException)?.diagnosticInfo
            report.put(key, "error", e.toString() + (diag?.let { " ($it)" } ?: ""))
            report.line("encode $name failed: $e ${diag ?: ""}")
        } finally {
            enc.release()
        }
        if (ok) for (mode in listOf("image", "buffer")) {
            val check = decodeCheck(File(base.path + "-$mode.$ext"), frames, report)
            report.put(key, "decoded-$mode", check)
            report.line("encode $name ($mode input) decoded back: ${check["verdict"] ?: check["error"]} (diff ${check["meanDiff"]})")
        }
        report.save()
    }

    /** Hardware first (the PoC wants the CPU for the conversion), then the fastest. */
    private fun bestEncoder(): String? = working.entries.sortedWith(compareByDescending<Map.Entry<String, Pair<Boolean, Double>>> {
        it.value.first && it.value.second >= 20 }.thenByDescending { it.value.second }).firstOrNull()?.key

    // ---------------------------------------------------------------------------------------------------- pipeline

    private suspend fun pipeline() {
        val name = bestEncoder() ?: avcEncoders().firstOrNull() ?: error("no H.264 encoder")
        val decor = window.decorView
        val (aw, ah) = Calc.fit(decor.width, decor.height, 1280, 720)
        val x0 = ((1280 - aw) / 2) and 1.inv()
        val y0 = ((720 - ah) / 2) and 1.inv()
        val key = "pipeline"
        report.put(key, "encoder", name)
        report.put(key, "picture", "${aw}x$ah at $x0,$y0 in 1280x720")
        val bmp = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
        val enc = withContext(Dispatchers.Default) { Encoder(name, report, key).also { it.start(false); it.sink = Sink(File(report.dir, "pipeline"), report) } }
        val copyLat = Calc.Samples()
        val pageFps = Calc.Samples()
        var captured = 0
        var queued = 0
        var noInput = 0
        var copyFail = 0
        var encoded = 0
        try {
            val period = 1_000_000_000L / 30
            val t0 = System.nanoTime()
            val end = t0 + 10_000_000_000L
            var next = t0
            val cpu0 = Process.getElapsedCpuTime()
            while (System.nanoTime() < end) {
                val wait = (next - System.nanoTime()) / 1_000_000
                if (wait > 0) delay(wait)
                next += period
                if (System.nanoTime() - next > period) next = System.nanoTime()
                val ts = System.nanoTime()
                val (code, at, _) = withTimeoutOrNull(1000) { copyOnce(bmp) } ?: Triple(-99, System.nanoTime(), 0L)
                if (code != PixelCopy.SUCCESS) { copyFail++; continue }
                captured++
                copyLat.add((at - ts) / 1e6)
                pageFps.add(page.fps)
                withContext(Dispatchers.Default) {
                    val idx = enc.codec.dequeueInputBuffer(0)
                    if (idx < 0) noInput++ else { enc.queue(idx, bmp, x0, y0, image = true, pts = (ts - t0) / 1000, capturedAt = ts); queued++ }
                    encoded += enc.drain(0)
                }
            }
            val wall = (System.nanoTime() - t0) / 1e6
            val cpu = Process.getElapsedCpuTime() - cpu0
            encoded += withContext(Dispatchers.Default) { enc.drain(300_000) }
            val res = linkedMapOf("capturedFps" to r1(captured * 1000 / wall), "encodedFps" to r1(encoded * 1000 / wall),
                "copyFailures" to copyFail, "noInputBuffer" to noInput, "copyLatency" to copyLat.summary(),
                "convertMs" to enc.convert.summary(), "captureToEncoded" to enc.latency.summary(),
                "kbps" to (enc.sink.bytes * 8 / wall).roundToInt(), "appCpu%" to Calc.cpuPercent(cpu, wall.toLong()),
                "pageFpsMean" to pageFps.summary()["mean"])
            report.put(key, "result", res)
            report.line("pipeline ($name, ${aw}x$ah in 1280x720): captured ${res["capturedFps"]} fps, encoded ${res["encodedFps"]} fps, " +
                "capture→encoded ${enc.latency.text()}, app CPU ${res["appCpu%"]}% of one core, page ${res["pageFpsMean"]} fps")
        } finally {
            withContext(Dispatchers.Default) { enc.release() }
        }
    }

    // ------------------------------------------------------------------------------------------- virtual displays

    /** The page on a private VirtualDisplay (no permission needed) through a Presentation. */
    private class VdPage(val vd: VirtualDisplay, val pres: Presentation, val web: WebView, val state: PageState) {
        fun close() { runCatching { pres.dismiss() }; runCatching { web.destroy() }; runCatching { vd.release() } }
    }

    private fun showOnVirtualDisplay(name: String, surface: Surface, w: Int, h: Int, label: String): VdPage {
        val dm = getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(name, w, h, 160, surface, 0) ?: error("createVirtualDisplay returned null")
        val pres = Presentation(this, vd.display)
        val state = PageState(report, name)
        val v = probePage(pres.context, state, label)
        pres.setContentView(v)
        pres.show()
        return VdPage(vd, pres, v, state)
    }

    /** Counts ImageReader frames and tracks the video region; keeps one picture. */
    private inner class ReaderWatch(val reader: ImageReader, val state: () -> PageState?, val srcW: Int, val srcH: Int,
                                    val viewX: Int = 0, val viewY: Int = 0) {
        val frames = AtomicInteger()
        val sig = SigTrack()
        @Volatile var measuring = false
        @Volatile var keep: Bitmap? = null
        @Volatile var wantKeep = false
        val errors = AtomicInteger()
        val lastAt = AtomicLong()

        init {
            reader.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (e: Exception) { errors.incrementAndGet(); null } ?: return@setOnImageAvailableListener
                try {
                    frames.incrementAndGet()
                    lastAt.set(System.nanoTime())
                    val p = img.planes[0]
                    val st = state()
                    if (measuring && st != null) st.rect("video")?.let { v ->
                        val b = Calc.cssToCopy(v[0], v[1], v[2], v[3], st.dpr, viewX, viewY, srcW, srcH, img.width, img.height)
                        synchronized(sig) { sig.add(Native.bufferSig(p.buffer, img.width, img.height, p.rowStride, b.x, b.y, b.w, b.h, 4)) }
                    }
                    if (wantKeep) { keep = toBitmap(img); wantKeep = false }
                } finally {
                    img.close()
                }
            }, handler)
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val rowPx = p.rowStride / p.pixelStride
        val full = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
        val need = p.rowStride * img.height
        val src = p.buffer.duplicate().apply { clear() }
        val buf = if (src.capacity() >= need) src else ByteBuffer.allocate(need).also { it.put(src); it.rewind() }
        full.copyPixelsFromBuffer(buf)
        return Bitmap.createBitmap(full, 0, 0, img.width, img.height)
    }

    private suspend fun virtualDisplayReader() {
        val key = "vd"
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3)
        var vdp: VdPage? = null
        try {
            vdp = showOnVirtualDisplay("tabcast-probe", reader.surface, 1280, 720, "Tab Cast probe · virtual display")
            val st = vdp.state
            val watch = ReaderWatch(reader, { st }, 1280, 720)
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 20_000 && !st.playing) delay(250)
            report.put(key, "framesWhileLoading", watch.frames.get())
            report.put(key, "videoPlayingAfterS", if (st.playing) (SystemClock.elapsedRealtime() - t0) / 1000.0 else null)
            st.summary().forEach { (k, v) -> report.put(key, "page.$k", v) }
            val f0 = watch.frames.get()
            watch.measuring = true
            delay(5000)
            watch.measuring = false
            val fps = (watch.frames.get() - f0) / 5.0
            watch.wantKeep = true
            delay(500)
            watch.keep?.let { savePng(it, "vd-reader") }
            report.put(key, "readerFps", fps)
            report.put(key, "acquireErrors", watch.errors.get())
            report.put(key, "video", synchronized(watch.sig) { watch.sig.summary() })
            report.put(key, "pageFps", st.fps)
            report.line("vd → ImageReader: $fps fps, video ${synchronized(watch.sig) { watch.sig.verdict() }}, page ${st.fps} fps, " +
                "video ${if (st.playing) "playing" else "not playing"}, acquire errors ${watch.errors.get()}")
        } finally {
            vdp?.close()
            reader.close()
        }
    }

    private suspend fun virtualDisplayEncoders() {
        val names = (listOfNotNull(bestEncoder()?.takeIf { working[it]?.first == true }, avcEncoders().firstOrNull { it.startsWith("OMX.Exynos") }) +
            listOf("c2.android.avc.encoder")).distinct().filter { it in avcEncoders() }
        for (name in names) {
            val key = "vdenc:$name"
            val enc = try { withContext(Dispatchers.Default) { Encoder(name, report, key) } } catch (e: Exception) {
                report.put(key, "error", "create: $e"); continue
            }
            var vdp: VdPage? = null
            try {
                val surface = withContext(Dispatchers.Default) { enc.start(surfaceInput = true) } ?: error("no input surface")
                enc.sink = Sink(File(report.dir, "vdenc-" + name.replace(Regex("[^A-Za-z0-9.]"), "_")), report)
                vdp = showOnVirtualDisplay("tabcast-probe-enc", surface, 1280, 720, "Tab Cast probe · VD → $name")
                var out = 0
                val t0 = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - t0 < 8000) out += withContext(Dispatchers.Default) { enc.drain(20_000) }
                report.put(key, "framesIn8s", out)
                report.put(key, "kbps", (enc.sink.bytes * 8 / 8000.0).roundToInt())
                report.put(key, "pagePlaying", vdp.state.playing)
                report.line("vd → $name input Surface: $out frames in 8 s (${enc.sink.bytes / 1024} KB)")
            } catch (e: Exception) {
                report.put(key, "error", e.toString())
                report.line("vd → $name failed: $e")
            } finally {
                vdp?.close()
                withContext(Dispatchers.Default) { enc.release() }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------- projection

    /** Probe 2: the MediaProjection kept for the steps after "projection" (one consent for the whole run). */
    private var projectionHeld: MediaProjection? = null

    private suspend fun projectionOnce(): MediaProjection = projectionHeld ?: run {
        consentAndProjection() ?: error("no screen-capture consent")
    }

    private suspend fun projection() {
        val key = "projection"
        val mp = consentAndProjection() ?: return
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audio) audioCapture(mp) else report.put("audio", "skipped", "no RECORD_AUDIO permission")
        report.save()
        mirrorDisplay(mp)
        report.put(key, "keptForNextSteps", true)
    }

    /**
     * Consent (the dialog, or none when `appops set … PROJECT_MEDIA allow` was given: probe 2 records which) and the
     * foreground service that turns it into a MediaProjection. Null when refused.
     */
    private suspend fun consentAndProjection(): MediaProjection? {
        projectionHeld?.let { return it }
        val key = "projection"
        var audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!audio) {
            permission = CompletableDeferred()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            audio = withTimeoutOrNull(30_000) { permission?.await() } ?: false
        }
        report.put(key, "recordAudioPermission", audio)
        report.put(key, "appopProjectMedia", appop("android:project_media"))
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val ask = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
        consent = CompletableDeferred()
        val t0 = SystemClock.elapsedRealtime()
        report.line("PROBE consent-wanted: tap \"Start\" / \"Start now\" in the screen-capture dialog on the tablet")
        @Suppress("DEPRECATION") startActivityForResult(ask, REQ_CONSENT)
        val answer = withTimeoutOrNull(90_000) { consent?.await() }
        if (answer == null) {
            report.put(key, "consent", "no answer within 90 s"); report.line("projection: no consent answer in 90 s"); return null
        }
        val consentS = (SystemClock.elapsedRealtime() - t0) / 1000.0
        report.put(key, "consentSeconds", consentS)
        // Under ~1 s nobody tapped anything: the appop let it through without the dialog (the runner says whether it tapped).
        report.line("projection: consent answered after $consentS s (appop project_media ${report.get(key, "appopProjectMedia")})")
        val (code, data) = answer
        if (code != RESULT_OK || data == null) {
            report.put(key, "consent", "refused (result $code)"); report.line("projection: consent refused"); return null
        }
        report.put(key, "consent", "given")
        ProjectionService.ready = CompletableDeferred()
        startForegroundService(Intent(this, ProjectionService::class.java)
            .putExtra(ProjectionService.EXTRA_CODE, code).putExtra(ProjectionService.EXTRA_DATA, data))
        val mp = withTimeoutOrNull(10_000) { ProjectionService.ready?.await() } ?: error("the capture service didn't start in 10 s")
        report.line("projection: MediaProjection granted")
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { report.line("projection: stopped") }
        }, handler)
        projectionHeld = mp
        return mp
    }

    private suspend fun audioCapture(mp: MediaProjection) {
        val key = "audio"
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val rate = 48_000
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min * 4, rate * 4 / 5))
            .setAudioPlaybackCaptureConfig(cfg).build()
        report.put(key, "recordState", if (rec.state == AudioRecord.STATE_INITIALIZED) "initialized" else "NOT initialized (${rec.state})")
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        val wav = ByteArrayOutputStream()
        try {
            rec.startRecording()
            for ((phase, ms) in listOf("video" to 4000L, "tone" to 3000L)) {
                withContext(Dispatchers.Main) {
                    if (phase == "video") web.evaluateJavascript("probeMute(false)", null)
                    else { web.evaluateJavascript("probeMute(true)", null); web.evaluateJavascript("probeTone(true)", null) }
                }
                withContext(Dispatchers.IO) { readAudio(rec, 400, null) }
                val r = withContext(Dispatchers.IO) { readAudio(rec, ms, wav) }
                report.put(key, phase, r + ("page" to page.summary().filterKeys { it in setOf("muted", "paused", "audioBytes", "errors") }))
                report.line("audio capture ($phase): level ${r["rmsDb"]} dBFS, peak ${r["peak"]}, ${r["silentShare%"]}% silent samples")
            }
        } finally {
            withContext(Dispatchers.Main) { web.evaluateJavascript("probeTone(false); probeMute(true)", null) }
            runCatching { rec.stop() }
            rec.release()
        }
        writeWav(File(report.dir, "audio-capture.wav"), wav.toByteArray(), rate, 2)
    }

    /** Reads [ms] of 16-bit audio; level stats; appends the bytes to [out]. */
    private fun readAudio(rec: AudioRecord, ms: Long, out: ByteArrayOutputStream?): Map<String, Any?> {
        val buf = ShortArray(4096)
        val end = SystemClock.elapsedRealtime() + ms
        var n = 0L
        var sq = 0.0
        var peak = 0
        var silent = 0L
        var errors = 0
        val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (SystemClock.elapsedRealtime() < end) {
            val r = rec.read(buf, 0, buf.size)
            if (r < 0) { errors++; if (errors > 20) break; continue }
            bytes.clear()
            for (i in 0 until r) {
                val s = buf[i].toInt()
                sq += s.toDouble() * s
                if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s)
                if (s == 0) silent++
                bytes.putShort(buf[i])
            }
            n += r
            out?.write(bytes.array(), 0, r * 2)
        }
        val rms = if (n > 0) sqrt(sq / n) else 0.0
        return linkedMapOf("samples" to n, "rmsDb" to if (rms > 0) r1(20 * log10(rms / 32768)) else "silence", "peak" to peak,
            "silentShare%" to if (n > 0) (silent * 100 / n).toInt() else 100, "readErrors" to errors)
    }

    private fun writeWav(f: File, pcm: ByteArray, rate: Int, channels: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort())
            .putShort(16).put("data".toByteArray()).putInt(pcm.size)
        f.outputStream().use { it.write(h.array()); it.write(pcm) }
    }

    private suspend fun mirrorDisplay(mp: MediaProjection) {
        val key = "mirror"
        val decor = window.decorView
        val (w, h) = Calc.fit(decor.width, decor.height, 1280, 720)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        var vd: VirtualDisplay? = null
        try {
            val loc = IntArray(2).also { web.getLocationInWindow(it) }
            val watch = ReaderWatch(reader, { page }, decor.width, decor.height, loc[0], loc[1])
            vd = mp.createVirtualDisplay("tabcast-probe-mirror", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
                ?: error("createVirtualDisplay returned null")
            delay(1500)
            report.put(key, "framesFirst1.5s", watch.frames.get())
            val f0 = watch.frames.get()
            watch.measuring = true
            delay(5000)
            watch.measuring = false
            val fps = (watch.frames.get() - f0) / 5.0
            watch.wantKeep = true
            delay(500)
            watch.keep?.let { savePng(it, "mirror-reader") }
            report.put(key, "size", "${w}x$h")
            report.put(key, "readerFps", fps)
            report.put(key, "acquireErrors", watch.errors.get())
            report.put(key, "video", synchronized(watch.sig) { watch.sig.summary() })
            report.line("mirror (MediaProjection → ImageReader ${w}x$h): $fps fps, video ${synchronized(watch.sig) { watch.sig.verdict() }}")
        } finally {
            runCatching { vd?.release() }
            reader.close()
        }
    }

    // ---------------------------------------------------------------------------------------------- probe 2 (0.4/0.5)

    private fun appop(op: String): String = runCatching {
        val ao = getSystemService(android.app.AppOpsManager::class.java)
        when (ao.unsafeCheckOpNoThrow(op, Process.myUid(), packageName)) {
            android.app.AppOpsManager.MODE_ALLOWED -> "allow"; android.app.AppOpsManager.MODE_IGNORED -> "ignore"
            android.app.AppOpsManager.MODE_ERRORED -> "deny"; android.app.AppOpsManager.MODE_DEFAULT -> "default"; else -> "other"
        }
    }.getOrElse { "unknown ($it)" }

    private fun vp8Encoders(): List<String> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && VP8 in it.supportedTypes }.map { it.name }
        val first = listOf("OMX.Exynos.VP8.Encoder", "c2.android.vp8.encoder", "OMX.google.vp8.encoder")
        return first.filter { it in all } + all.filter { it !in first }
    }

    /** VP8 encoders that worked in "vp8" at 960x720: name -> (hardware, paced fps). */
    private val workingVp8 = LinkedHashMap<String, Pair<Boolean, Double>>()

    /**
     * Each VP8 encoder with ByteBuffer input at 960x720 and 1280x720 (the mirroring sizes): burst throughput, paced
     * 30 fps delay and bitrate, and a decode-back check of the .webm against the source pictures.
     */
    private suspend fun vp8Tests() {
        val names = vp8Encoders()
        report.put("vp8", "encoders", names)
        for (n in names) runCatching {
            val c = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == n }
            val caps = c.getCapabilitiesForType(VP8)
            report.put("encoders", n, linkedMapOf("hardware" to c.isHardwareAccelerated,
                "colorFormats" to caps.colorFormats.map { Encoder.colorName(it) },
                "cbr" to caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
                "720p" to caps.videoCapabilities.isSizeSupported(1280, 720),
                "achievable720p" to runCatching { caps.videoCapabilities.getAchievableFrameRatesFor(1280, 720)?.toString() }.getOrNull()))
        }
        // Real pictures of the page (the video playing) when they can be had quickly, else synthetic ones.
        val real = ArrayList<Bitmap>()
        if (frames720.isEmpty()) {
            val decor = window.decorView
            if (decor.width > 0) for (i in 0 until 6) {
                val b = Bitmap.createBitmap(decor.width and 1.inv(), decor.height and 1.inv(), Bitmap.Config.ARGB_8888)
                if (copyOnce(b).first == PixelCopy.SUCCESS) real += Bitmap.createScaledBitmap(b, 1280, 720, true)
                b.recycle()
                delay(300)
            }
        }
        for ((w, h) in listOf(960 to 720, 1280 to 720)) {
            val src = frames720.ifEmpty { real }.ifEmpty { List(6) { synthetic(1280, 720, it) } }
            val frames = if (w == 1280) src else src.map { Bitmap.createScaledBitmap(it, w, h, true) }
            for (name in names) withContext(Dispatchers.Default) { encodeOne(name, frames, VP8, w, h, if (w == 960) workingVp8 else null) }
        }
    }

    /** Video, sound levels and launch of another app's player; the probe needs SYSTEM_ALERT_WINDOW (appop) to start apps from the background. */
    private class OtherApp(val label: String, val pkg: String, val intent: Intent)

    private fun otherApps(): List<OtherApp> {
        val u = android.net.Uri.parse(OTHER_VIDEO)
        fun installed(p: String) = runCatching { packageManager.getPackageInfo(p, 0) }.isSuccess
        return listOfNotNull(
            OtherApp("VLC", "org.videolan.vlc", Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").setPackage("org.videolan.vlc")),
            OtherApp("Brave", "com.brave.browser", Intent(Intent.ACTION_VIEW, u).setPackage("com.brave.browser")),
            OtherApp("Tab Cast browser", "dk.pennico.tabcast", Intent(Intent.ACTION_VIEW, u).setClassName("dk.pennico.tabcast", "dk.pennico.tabcast.ui.BrowserActivity")),
        ).map { it.apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } }.filter { installed(it.pkg).also { ok -> if (!ok) report.put("audiothers", it.label, "not installed") } }
    }

    private fun audioRecorder(mp: MediaProjection): AudioRecord? {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val rate = 48_000
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min * 4, rate * 4 / 5))
            .setAudioPlaybackCaptureConfig(cfg).build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        return rec
    }

    /**
     * AudioPlaybackCapture of OTHER apps (what mirroring needs): VLC, Brave and Tab Cast's browser each open the same
     * archive.org video; 12 s to start, then 5 s of their sound is measured (+ a wav), then a media "pause" key and
     * 2 s to check it went quiet. Results per app: level, peak, share of silent samples.
     */
    private suspend fun audioOthers(mp: MediaProjection) {
        val key = "audiothers"
        report.put(key, "appopSystemAlertWindow", appop("android:system_alert_window"))
        val rec = audioRecorder(mp) ?: run { report.put(key, "error", "no RECORD_AUDIO or AudioRecord not initialised"); return }
        val am = getSystemService(android.media.AudioManager::class.java)
        val pm = getSystemService(android.os.PowerManager::class.java)
        try {
            rec.startRecording()
            for (app in otherApps()) {
                val wav = ByteArrayOutputStream()
                val started = runCatching { startActivity(app.intent) }.exceptionOrNull()?.toString()
                report.line("audio of ${app.label}: opened the test video${started?.let { " (failed: $it)" } ?: ""}; measuring in 12 s")
                withContext(Dispatchers.IO) { readAudio(rec, 12_000, null) }
                val r = withContext(Dispatchers.IO) { readAudio(rec, 5000, wav) }
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                withContext(Dispatchers.IO) { readAudio(rec, 1000, null) }
                val after = withContext(Dispatchers.IO) { readAudio(rec, 2000, null) }
                report.put(key, app.label, linkedMapOf("startError" to started, "playing" to r, "afterPauseKey" to after,
                    "musicActive" to am.isMusicActive, "screenOn" to pm.isInteractive))
                writeWav(File(report.dir, "audio-${app.pkg}.wav"), wav.toByteArray(), 48_000, 2)
                report.line("audio of ${app.label}: ${r["rmsDb"]} dBFS, peak ${r["peak"]}, ${r["silentShare%"]}% silent; after the pause key ${after["rmsDb"]} dBFS")
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            bringBack()
        }
    }

    /** Back to the probe after another app was in front (allowed from the background with the SYSTEM_ALERT_WINDOW appop). */
    private fun bringBack() {
        runCatching { startActivity(Intent(this, ProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * The mirroring sender's hot path, measured: MediaProjection (whole screen) -> ImageReader RGBA -> C convert ->
     * VP8 (the encoder that worked best in "vp8", hardware first) at [w] x [h]. Counts captured and encoded frames,
     * delay capture -> encoded, kbit/s, app CPU; runs for [seconds]. Only changed screens make new images (the
     * VirtualDisplay composes on change), so a still screen costs nothing.
     */
    private inner class MirrorRun(val mp: MediaProjection, val w: Int, val h: Int, val key: String, bitrate: Int = 3_000_000) {
        val enc: Encoder
        val reader: ImageReader
        var vd: VirtualDisplay? = null
        val captured = AtomicInteger()
        val noInput = AtomicInteger()
        val failed = AtomicInteger()
        @Volatile var encoded = 0
        val t0 = System.nanoTime()
        init {
            val name = workingVp8.entries.sortedByDescending { (if (it.value.first) 1000 else 0) + it.value.second }.firstOrNull()?.key
                ?: vp8Encoders().firstOrNull() ?: error("no VP8 encoder")
            report.put(key, "encoder", name)
            enc = Encoder(name, report, key, w, h, bitrate, 30, VP8)
            enc.start(false)
            enc.sink = Sink(File(report.dir, key), report, vp8 = true)
            val decor = window.decorView
            val dm = resources.displayMetrics
            // The screen's aspect ratio inside w x h (the tablet is 4:3: 960x720 fills it, 1280x720 gets side bars).
            val sw = maxOf(dm.widthPixels, decor.width); val sh = maxOf(dm.heightPixels, decor.height)
            val (aw, ah) = Calc.fit(sw, sh, w, h)
            val x0 = ((w - aw) / 2) and 1.inv(); val y0 = ((h - ah) / 2) and 1.inv()
            report.put(key, "picture", "${aw}x$ah at $x0,$y0 in ${w}x$h (screen ${sw}x$sh)")
            reader = ImageReader.newInstance(aw, ah, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    captured.incrementAndGet()
                    val now = System.nanoTime()
                    val idx = enc.codec.dequeueInputBuffer(0)
                    if (idx < 0) noInput.incrementAndGet()
                    else {
                        val p = img.planes[0]
                        try { enc.queueRgba(idx, p.buffer, p.rowStride, aw, ah, x0, y0, (now - t0) / 1000, now) }
                        catch (e: Exception) { if (failed.incrementAndGet() == 1) report.line("$key: $e") }
                    }
                    encoded += enc.drain(0)
                } finally { img.close() }
            }, handler)
            vd = mp.createVirtualDisplay("tabcast-probe-$key", aw, ah, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler) ?: error("createVirtualDisplay returned null")
        }
        /** Drains what the encoder finished while no new picture came (a still screen). */
        fun drainIdle() = handler.post { runCatching { encoded += enc.drain(0) } }
        fun stats(wallMs: Double, cpuMs: Long, bytes0: Long, enc0: Int, cap0: Int): Map<String, Any?> = linkedMapOf(
            "capturedFps" to r1((captured.get() - cap0) * 1000 / wallMs), "encodedFps" to r1((encoded - enc0) * 1000 / wallMs),
            "kbps" to ((enc.sink.bytes - bytes0) * 8 / wallMs).roundToInt(), "latency" to enc.latency.summary(),
            "convertMs" to enc.convert.summary(), "noInputBuffer" to noInput.get(), "failed" to failed.get(),
            "appCpu%" to Calc.cpuPercent(cpuMs, wallMs.toLong()))
        fun close() {
            runCatching { vd?.release() }
            val done = CompletableDeferred<Unit>()
            handler.post { runCatching { reader.close() }; done.complete(Unit) }
            runCatching { kotlinx.coroutines.runBlocking { withTimeoutOrNull(2000) { done.await() } } }
            enc.release()
        }
    }

    /** "mirrorenc": 20 s of the mirror pipeline at 960x720, then at 1280x720, with the probe page's video playing. */
    private suspend fun mirrorEncode(mp: MediaProjection) {
        bringBack()
        delay(1500)
        for ((w, h) in listOf(960 to 720, 1280 to 720)) {
            val key = "mirrorenc-${w}x$h"
            val run = withContext(Dispatchers.Default) { MirrorRun(mp, w, h, key) }
            try {
                delay(2000)
                run.enc.resetStats()
                val cpu0 = Process.getElapsedCpuTime(); val t = SystemClock.elapsedRealtime()
                val b0 = run.enc.sink.bytes; val e0 = run.encoded; val c0 = run.captured.get()
                repeat(40) { delay(500); run.drainIdle() }
                val st = run.stats((SystemClock.elapsedRealtime() - t).toDouble(), Process.getElapsedCpuTime() - cpu0, b0, e0, c0)
                report.put(key, "result", st)
                report.line("mirror → VP8 ${w}x$h: captured ${st["capturedFps"]} fps, encoded ${st["encodedFps"]} fps, " +
                    "delay ${(st["latency"] as Map<*, *>)["p50"]} ms p50 / ${(st["latency"] as Map<*, *>)["p95"]} p95, ${st["kbps"]} kbit/s, " +
                    "convert ${(st["convertMs"] as Map<*, *>)["p50"]} ms, app CPU ${st["appCpu%"]}%")
            } finally {
                withContext(Dispatchers.Default) { run.close() }
            }
            val check = decodeCheck(File(report.dir, "$key.webm"), frames720.ifEmpty { listOf(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)) }, report)
            report.put(key, "decoded", check)
        }
    }

    private fun longSeconds(): Int = intent.getStringExtra("long")?.toIntOrNull()?.coerceIn(30, 3600) ?: 240

    private fun batteryTemp(): Double? = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10.0 }

    /**
     * "long": mirroring for [longSeconds] (default 4 min) the way 0.5 would: another app's video in front (VLC, else
     * Brave, else the probe page), the screen kept on by a dim wake lock from the probe (in the background) and the
     * backlight at its minimum (Settings.System, needs the WRITE_SETTINGS appop the runner gives), while the system's
     * screen timeout is set low by the runner. Every 15 s: captured/encoded fps, screen on, brightness, battery
     * temperature, app CPU. Restores the brightness at the end.
     */
    @Suppress("DEPRECATION")
    private suspend fun longMirror(mp: MediaProjection) {
        val key = "long"
        val pm = getSystemService(android.os.PowerManager::class.java)
        val cr = contentResolver
        report.put(key, "screenOffTimeoutMs", runCatching { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull())
        val canWrite = android.provider.Settings.System.canWrite(this)
        report.put(key, "canWriteSettings", canWrite)
        val oldMode = runCatching { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrNull()
        val oldLevel = runCatching { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        report.put(key, "brightnessBefore", "mode $oldMode level $oldLevel")
        val wake = pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, "TabCastProbe:mirror")
        val app = otherApps().firstOrNull()
        var run: MirrorRun? = null
        try {
            wake.acquire(longSeconds() * 1000L + 60_000)
            if (canWrite) {
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 1)
            }
            report.put(key, "inFront", app?.label ?: "the probe page")
            app?.let { runCatching { startActivity(it.intent) }.onFailure { e -> report.put(key, "startError", e.toString()) } }
            delay(8000)
            run = withContext(Dispatchers.Default) { MirrorRun(mp, 960, 720, key, 2_500_000) }
            val samples = ArrayList<Map<String, Any?>>()
            val end = SystemClock.elapsedRealtime() + longSeconds() * 1000L
            var cpu0 = Process.getElapsedCpuTime(); var t = SystemClock.elapsedRealtime()
            var b0 = run.enc.sink.bytes; var e0 = run.encoded; var c0 = run.captured.get()
            var gaps = 0
            while (SystemClock.elapsedRealtime() < end) {
                repeat(30) { delay(500); run.drainIdle() }
                val now = SystemClock.elapsedRealtime()
                val st = run.stats((now - t).toDouble(), Process.getElapsedCpuTime() - cpu0, b0, e0, c0) as LinkedHashMap
                st.remove("convertMs"); st["latencyP50"] = (st.remove("latency") as Map<*, *>)["p50"]
                st["atS"] = (longSeconds() - (end - now) / 1000)
                st["screenOn"] = pm.isInteractive
                st["brightness"] = runCatching { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
                st["batteryC"] = batteryTemp()
                if ((st["capturedFps"] as Double) < 1.0) gaps++
                samples += st
                report.line("long ${st["atS"]} s: captured ${st["capturedFps"]} fps, encoded ${st["encodedFps"]} fps, ${st["kbps"]} kbit/s, " +
                    "CPU ${st["appCpu%"]}%, screen ${if (pm.isInteractive) "on" else "OFF"}, battery ${st["batteryC"]} °C")
                report.put(key, "samples", samples)
                report.save()
                run.enc.resetStats()
                cpu0 = Process.getElapsedCpuTime(); t = now; b0 = run.enc.sink.bytes; e0 = run.encoded; c0 = run.captured.get()
            }
            report.put(key, "quietSamples", gaps)
            report.line("long: ${samples.size} samples, $gaps with under 1 captured frame/s (a still screen or capture stopped); " +
                "screen ${if (pm.isInteractive) "still on" else "OFF"} at the end")
        } finally {
            run?.let { r -> withContext(Dispatchers.Default) { r.close() } }
            if (canWrite) runCatching {
                oldLevel?.let { android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, it) }
                oldMode?.let { android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, it) }
            }
            if (wake.isHeld) wake.release()
            val am = getSystemService(android.media.AudioManager::class.java)
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            bringBack()
        }
    }

    // ------------------------------------------------------------------------------------------------------- utils

    private suspend fun savePng(bmp: Bitmap, name: String) = withContext(Dispatchers.IO) {
        runCatching { File(report.dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onFailure { report.line("png $name: $it") }
    }

    private fun r1(x: Double) = (x * 10).roundToInt() / 10.0

    companion object {
        private const val REQ_CONSENT = 41
        private const val REQ_AUDIO = 42
        /** Probe 2: the video other apps open for the sound and long-mirror tests (public domain, archive.org). */
        const val OTHER_VIDEO = "https://archive.org/download/ElephantsDream/ed_1024_512kb.mp4"
    }
}
