// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dk.pennico.tabcast.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

enum class Protocol(val label: String, val port: Int) {
    FCAST("FCast Receiver", 46899),
    CHROMECAST("Chromecast built-in", 8009),
}

data class Device(val name: String, val protocol: Protocol, val host: String, val port: Int = protocol.port) {
    val key: String get() = "${protocol.name}@$host:$port"
    val label: String get() = "$name · ${protocol.label}"
}

/** Remembered TVs. mDNS is unreliable on some Wi-Fi networks, so devices are kept by IP and re-found by a port scan. */
class DeviceStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun all(): List<Device> = try {
        val a = JSONArray(sp.getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Device(it.getString("name"), Protocol.valueOf(it.getString("protocol")), it.getString("host"), it.getInt("port"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(list: List<Device>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("protocol", it.protocol.name).put("host", it.host).put("port", it.port)) }
        sp.edit().putString("list", a.toString()).apply()
    }

    fun add(d: Device) { save(all().filter { it.key != d.key } + d) }
    fun remove(d: Device) { save(all().filter { it.key != d.key }) }
    fun replace(old: Device, new: Device) { save(all().map { if (it.key == old.key) new else it }) }
    fun rename(d: Device, name: String) = replace(d, d.copy(name = name))
}

object Net {
    /** The tablet's IPv4 address and prefix length on the active (Wi-Fi) network. */
    fun localIpv4(ctx: Context): Pair<String, Int>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        return la.address.hostAddress!! to la.prefixLength
    }

    fun portOpen(host: String, port: Int, timeoutMs: Int = 600): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) { false }

    /** Chromecast/Google TV friendly name from its local setup API (best effort). */
    fun castName(host: String): String? = try {
        val c = URL("http://$host:8008/setup/eureka_info?params=name").openConnection() as HttpURLConnection
        c.connectTimeout = 1200; c.readTimeout = 1500
        val s = c.inputStream.bufferedReader().use { it.readText() }
        JSONObject(s).optString("name").ifEmpty { null }
    } catch (e: Exception) { null }

    /** Which receivers answer at [host]. */
    suspend fun probeHost(host: String): List<Device> = withContext(Dispatchers.IO) {
        val out = ArrayList<Device>()
        val fc = async { portOpen(host, Protocol.FCAST.port, 1500) }
        val cc = async { portOpen(host, Protocol.CHROMECAST.port, 1500) }
        val name = async { castName(host) }
        val n = name.await() ?: "TV $host"
        if (fc.await()) out += Device(n, Protocol.FCAST, host)
        if (cc.await()) out += Device(n, Protocol.CHROMECAST, host)
        out
    }

    /** Scan the tablet's /24 for FCast (46899) and Cast (8009) ports. ~3 s on a home network. */
    suspend fun scan(ctx: Context, protocols: Collection<Protocol> = Protocol.values().toList()): List<Device> = coroutineScope {
        val (ip, prefix) = localIpv4(ctx) ?: return@coroutineScope emptyList()
        val base = ip.substringBeforeLast('.')
        if (prefix < 24) AppLog.i("scan: network is /$prefix, scanning only $base.0/24")
        val sem = Semaphore(64)
        val hits = (1..254).map { "$base.$it" }.filter { it != ip }.flatMap { h ->
            protocols.map { p ->
                async(Dispatchers.IO) { sem.withPermit { if (portOpen(h, p.port, 400)) h to p else null } }
            }
        }.awaitAll().filterNotNull()
        val names = hits.map { it.first }.distinct().associateWith { h -> async(Dispatchers.IO) { castName(h) } }
        hits.map { (h, p) -> Device(names[h]?.await() ?: "TV $h", p, h) }
    }
}

/** mDNS discovery of _fcast._tcp and _googlecast._tcp (works only some of the time on some home Wi-Fi networks). */
class Discovery(ctx: Context, private val found: (Device) -> Unit) {
    private val app = ctx.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    private var lock: WifiManager.MulticastLock? = null

    @SuppressLint("WakelockTimeout")
    fun start() {
        try {
            lock = app.getSystemService(WifiManager::class.java)?.createMulticastLock("tabcast-mdns")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { AppLog.e("multicast lock", e) }
        for ((type, proto) in listOf("_fcast._tcp" to Protocol.FCAST, "_googlecast._tcp" to Protocol.CHROMECAST)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, code: Int) { AppLog.i("mDNS $t failed: $code") }
                override fun onStopDiscoveryFailed(t: String, code: Int) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) { resolve(info, proto) }
            }
            try { nsd?.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners += l } catch (e: Exception) { AppLog.e("mDNS $type", e) }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, proto: Protocol) {
        try {
            nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = (i.host as? Inet4Address)?.hostAddress ?: return
                    val fn = i.attributes["fn"]?.let { String(it) }
                    found(Device(fn ?: i.serviceName, proto, host, i.port))
                }
            })
        } catch (e: Exception) { AppLog.e("mDNS resolve", e) }
    }

    fun stop() {
        listeners.forEach { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listeners.clear()
        try { lock?.release() } catch (_: Exception) {}
    }
}
