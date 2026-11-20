// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.content.Context
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.core.Urls
import dk.pennico.tabcast.link.MiniHttp
import dk.pennico.tabcast.link.MiniHttp.Req
import dk.pennico.tabcast.link.MiniHttp.Resp
import dk.pennico.tabcast.link.Pairing
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web remote (0.3.0): Tab Cast TV serves a page on port [PORT] that does what the tablet's
 * TV-browser remote does, for Safari on the iPhone (also as a Home Screen web app) and browsers on the Mac.
 *
 * Every API call needs a paired device ([Pairing]): the token comes as the `X-TabCast-Token` header (Apple Shortcuts)
 * or the `tc` cookie set at pairing. A POST with only the cookie must also carry `X-Requested-With: tabcast`, which
 * other web sites can't send cross-site without a CORS preflight that this server never grants: no CSRF.
 */
class WebRemote(private val ctx: Context, private val pairing: Pairing, private val control: Control) {

    /** What the remote reads and does; implemented by TvActivity (calls go to the main thread there). */
    interface Control {
        fun tvName(): String
        fun state(): Map<String, String>
        fun caught(): List<String>
        /** The newest picture of the TV page (JPEG), and keep making them for a few seconds. */
        fun preview(): ByteArray?
        fun open(url: String, from: String)
        fun cmd(c: String, v: String?)
        fun tap(x: Float, y: Float, long: Boolean)
        fun scroll(dx: Float, dy: Float)
        fun text(t: String, enter: Boolean)
        /** Start view: {sites, recent, tests} (0.3.2). */
        fun startJson(): String
        /** Adds a site (the TV's open page when [url] is null). */
        fun addSite(title: String?, url: String?)
        fun removeSite(url: String)
    }

    private val http = MiniHttp(PORT_SETTING, ::handle) { AppLog.i(it) }
    val port: Int get() = http.localPort

    fun start() = http.start()
    fun stop() = http.stop()

    private fun asset(path: String, type: String): Resp =
        try { Resp(200, type, ctx.assets.open("remote/$path").use { it.readBytes() }) } catch (e: Exception) { Resp.text(404, "not found") }

    private fun auth(r: Req): Pairing.Device? {
        r.header("x-tabcast-token")?.let { return pairing.check(it) }
        val cookie = r.cookie("tc") ?: return null
        if (r.method != "GET" && r.header("x-requested-with") != "tabcast") return null
        return pairing.check(cookie)
    }

    private fun body(r: Req): JSONObject = try { JSONObject(r.text().ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }

    private fun ok(extra: JSONObject.() -> Unit = {}) = Resp.json(200, JSONObject().put("ok", true).apply(extra).toString())

    private fun handle(r: Req): Resp {
        when (r.path) {
            "/", "/index.html" -> return asset("index.html", "text/html; charset=utf-8")
            "/manifest.webmanifest" -> return asset("manifest.webmanifest", "application/manifest+json")
            "/icon-180.png", "/icon-512.png" -> return asset(r.path.removePrefix("/"), "image/png")
            "/open" -> return asset("index.html", "text/html; charset=utf-8") // the page shows "Open … on the TV?" itself
            "/api/pair/start" -> {
                if (r.method != "POST") return Resp.text(405, "POST")
                val name = body(r).optString("name").take(40).ifEmpty { "a browser" }
                val pin = pairing.showPin("$name at ${r.remote} wants to pair", fromDevice = true)
                AppLog.i("web remote: pairing asked by $name (${r.remote})${if (pin == null) ", no new PIN (locked or asked too often)" else ""}")
                return Resp.json(200, JSONObject().put("shown", pin != null).toString())
            }
            "/api/pair" -> {
                if (r.method != "POST") return Resp.text(405, "POST")
                val b = body(r)
                val name = b.optString("name").take(60).ifEmpty { "browser" }
                return when (val res = pairing.pair(b.optString("pin"), name, b.optString("kind").ifEmpty { "web" })) {
                    is Pairing.Result.Ok -> {
                        AppLog.i("web remote: paired \"${res.device.name}\" (${r.remote})")
                        Resp.json(200, JSONObject().put("ok", true).put("token", res.token).toString(),
                            mapOf("Set-Cookie" to "tc=${res.token}; Path=/; Max-Age=315360000; HttpOnly; SameSite=Lax"))
                    }
                    is Pairing.Result.Wrong -> Resp.json(200, JSONObject().put("ok", false).put("tries", res.triesLeft).toString())
                    is Pairing.Result.Locked -> Resp.json(423, JSONObject().put("ok", false).put("wait", res.seconds).toString())
                    Pairing.Result.NoPin -> Resp.json(409, JSONObject().put("ok", false).put("error", "No code on the TV: tap \"Show a code on the TV\" first").toString())
                }
            }
        }
        if (!r.path.startsWith("/api/")) return Resp.text(404, "not found")
        val dev = auth(r) ?: return Resp.json(401, """{"ok":false,"error":"not paired"}""")
        val from = "${dev.name} (${r.remote})"
        if (r.method == "GET") return when (r.path) {
            "/api/state" -> Resp.json(200, JSONObject()
                .put("tv", control.tvName()).put("device", dev.name)
                .put("status", JSONObject(control.state()))
                .put("caught", JSONArray(control.caught())).toString())
            "/api/preview.jpg" -> control.preview()?.let { Resp(200, "image/jpeg", it) } ?: Resp.text(204, "")
            "/api/start" -> Resp.json(200, control.startJson())
            "/api/devices" -> Resp.json(200, JSONArray(pairing.devices().map {
                JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("lastSeen", it.lastSeen).put("you", it.id == dev.id)
            }).toString())
            else -> Resp.text(404, "not found")
        }
        if (r.method != "POST") return Resp.text(405, "POST")
        val b = body(r)
        return when (r.path) {
            "/api/open" -> {
                val url = b.optString("url").trim().let { u -> Urls.firstUrlIn(u) ?: u }
                if (!Urls.isHttp(url)) Resp.json(400, """{"ok":false,"error":"not a web address"}""")
                else { control.open(url, from); ok { put("url", url) } }
            }
            "/api/cmd" -> { control.cmd(b.optString("c"), b.optString("v").ifEmpty { null }); ok() }
            "/api/tap" -> { control.tap(b.optDouble("x", .5).toFloat(), b.optDouble("y", .5).toFloat(), b.optBoolean("long")); ok() }
            "/api/scroll" -> { control.scroll(b.optDouble("dx", 0.0).toFloat(), b.optDouble("dy", 0.0).toFloat()); ok() }
            "/api/text" -> { control.text(b.optString("text"), b.optBoolean("enter")); ok() }
            "/api/sites/add" -> { control.addSite(b.optString("title").ifBlank { null }, b.optString("url").ifBlank { null }); ok() }
            "/api/sites/remove" -> { control.removeSite(b.optString("url")); ok() }
            "/api/token" -> {
                val t = pairing.issue(b.optString("name").ifEmpty { "Shortcut" }, "shortcut")
                AppLog.i("web remote: ${dev.name} made a token \"${t.device.name}\"")
                ok { put("token", t.token); put("name", t.device.name) }
            }
            "/api/devices/revoke" -> {
                val id = b.optString("id")
                val gone = pairing.revoke(id)
                AppLog.i("web remote: ${dev.name} removed a paired device ($gone)")
                ok { put("removed", gone) }
            }
            else -> Resp.text(404, "not found")
        }
    }

    companion object {
        const val PORT = 46897
        /** The port to listen on (tests use 0 = any free port). */
        @androidx.annotation.VisibleForTesting var PORT_SETTING = PORT
    }
}
