// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/**
 * 0.5.1: Tab Cast TV updates itself. Pure JVM, so it is unit-tested against the real index.
 *
 * [RepoIndex]: Tab Cast TV reads the F-Droid repo it is already published in (GitHub Pages). The index jar is signed
 * with the repo key; only an index whose signer certificate has the pinned SHA-256 fingerprint counts, then the APK's
 * SHA-256 from that index is checked after the download, and Android itself checks the APK's signer.
 */
data class Release(val code: Long, val name: String, val file: String, val sha256: String, val size: Long,
                   val whatsNew: String = "")

object RepoIndex {
    /** The F-Droid repo of Tab Cast TV and its index-signing certificate (FDROID.md). */
    const val REPO = "https://pennico.github.io/tab-cast-fdroid/repo"
    const val FINGERPRINT = "8cf18bc2195244674b0df5759728e6bd49dba3867954a258b52b10db9d47f0dd"

    /**
     * Opens a downloaded index-v1.jar and returns index-v1.json, only if it is signed by exactly the certificate
     * with [pin] (SHA-256 of the DER certificate, hex). Throws [SecurityException] otherwise.
     */
    fun verifiedJson(jar: File, pin: String = FINGERPRINT): String {
        JarFile(jar, true).use { jf ->
            val e = jf.getJarEntry("index-v1.json") ?: throw SecurityException("no index-v1.json in the index")
            // Reading to the end is what makes JarFile check the digest; a mismatch throws SecurityException.
            val bytes = jf.getInputStream(e).use { it.readBytes() }
            val certs = e.certificates ?: throw SecurityException("the index isn't signed")
            if (certs.size != 1) throw SecurityException("the index has ${certs.size} signers")
            val got = sha256Hex((certs[0] as X509Certificate).encoded)
            if (!got.equals(pin, ignoreCase = true)) throw SecurityException("the index is signed by another key ($got)")
            return String(bytes, Charsets.UTF_8)
        }
    }

    /** The newest version of [pkg] in an index-v1.json (null when the package isn't there). */
    fun latest(json: String, pkg: String): Release? {
        val root = JSONObject(json)
        val list = root.optJSONObject("packages")?.optJSONArray(pkg) ?: return null
        var best: JSONObject? = null
        for (i in 0 until list.length()) {
            val p = list.getJSONObject(i)
            if (best == null || p.optLong("versionCode") > best.optLong("versionCode")) best = p
        }
        val b = best ?: return null
        if (b.optString("hashType") != "sha256") return null
        var whatsNew = ""
        val apps = root.optJSONArray("apps")
        if (apps != null) for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            if (a.optString("packageName") != pkg) continue
            val loc = a.optJSONObject("localized")
            val en = loc?.optJSONObject("en-US") ?: loc?.let { l -> l.keys().asSequence().firstOrNull()?.let { l.optJSONObject(it) } }
            // F-Droid's whatsNew belongs to the suggested (newest) version.
            if (a.optString("suggestedVersionCode") == b.optLong("versionCode").toString()) whatsNew = en?.optString("whatsNew").orEmpty()
        }
        return Release(b.optLong("versionCode"), b.optString("versionName"), b.getString("apkName"),
            b.getString("hash").lowercase(), b.optLong("size"), whatsNew.trim())
    }
}

fun sha256Hex(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

fun sha256Hex(inp: InputStream): String {
    val d = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(1 shl 16)
    while (true) { val n = inp.read(buf); if (n < 0) break; d.update(buf, 0, n) }
    return hex(d.digest())
}

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

