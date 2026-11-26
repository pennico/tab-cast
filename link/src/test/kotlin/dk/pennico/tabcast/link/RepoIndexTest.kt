// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.security.Security
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RepoIndexTest {
    companion object {
        /** The live index of 2026-11-18 (Tab Cast TV 0.5.0, code 9), as GitHub Pages served it. */
        private val indexJar = File(RepoIndexTest::class.java.getResource("/index-v1.jar")!!.toURI())

        @BeforeClass @JvmStatic fun sha1Jars() {
            // F-Droid signs its index with SHA-1 digests (old clients); Android's jar verifier accepts them, recent JDKs
            // treat such jars as unsigned unless told otherwise. Must run before the first JarFile verification.
            Security.setProperty("jdk.jar.disabledAlgorithms", "MD2, MD5, RSA keySize < 1024")
        }
    }

    @Test fun signedIndexIsReadAndItsNewestVersionFound() {
        val json = RepoIndex.verifiedJson(indexJar)
        val r = RepoIndex.latest(json, "dk.pennico.tabcast.tv")!!
        assertEquals(9L, r.code)
        assertEquals("0.5.0", r.name)
        assertEquals("dk.pennico.tabcast.tv_9.apk", r.file)
        assertEquals("fafb1950573a148ae5c26f4913b99f2758db9674162be1d610ae1037adcfeadf", r.sha256)
        assertNull(RepoIndex.latest(json, "dk.pennico.tabcast"))
    }

    @Test fun anotherPinIsRefused() {
        try { RepoIndex.verifiedJson(indexJar, "00".repeat(32)); fail() } catch (e: SecurityException) {}
    }

    private fun rewritten(change: (String, ByteArray) -> ByteArray?): File {
        val out = File.createTempFile("idx", ".jar").apply { deleteOnExit() }
        ZipFile(indexJar).use { z ->
            ZipOutputStream(out.outputStream()).use { zo ->
                for (e in z.entries()) {
                    val b = change(e.name, z.getInputStream(e).readBytes()) ?: continue
                    zo.putNextEntry(ZipEntry(e.name)); zo.write(b); zo.closeEntry()
                }
            }
        }
        return out
    }

    @Test fun changedIndexIsRefused() {
        val bad = rewritten { n, b -> if (n == "index-v1.json") String(b).replace("fafb1950", "00000000").toByteArray() else b }
        try { RepoIndex.verifiedJson(bad); fail("a changed index was accepted") } catch (e: SecurityException) {}
    }

    @Test fun unsignedIndexIsRefused() {
        val bad = rewritten { n, b -> if (n.startsWith("META-INF/FDROID")) null else b }
        try { RepoIndex.verifiedJson(bad); fail("an unsigned index was accepted") } catch (e: SecurityException) {}
    }

    @Test fun whatsNewOfTheSuggestedVersion() {
        val json = """{"apps":[{"packageName":"p","suggestedVersionCode":"12","localized":{"en-US":{"whatsNew":" Updates itself. "}}}],
            "packages":{"p":[{"versionCode":11,"versionName":"a","apkName":"p_11.apk","hash":"AA","hashType":"sha256","size":1},
            {"versionCode":12,"versionName":"b","apkName":"p_12.apk","hash":"BB","hashType":"sha256","size":2}]}}"""
        val r = RepoIndex.latest(json, "p")!!
        assertEquals(Release(12, "b", "p_12.apk", "bb", 2, "Updates itself."), r)
    }

    /** 0.5.4.1: the update site carries both apps; each finds its own newest version. */
    @Test fun bothAppsInOneIndex() {
        fun apk(code: Int, name: String, file: String) =
            """{"versionCode": $code, "versionName": "$name", "apkName": "$file", "hash": "${"ab".repeat(32)}", "hashType": "sha256", "size": 1000}"""
        val json = """{"repo": {}, "apps": [], "packages": {
            "dk.pennico.tabcast.tv": [${apk(13, "0.5.4", "dk.pennico.tabcast.tv_13.apk")}, ${apk(12, "0.5.3", "dk.pennico.tabcast.tv_12.apk")}],
            "dk.pennico.tabcast": [${apk(15, "0.5.4", "dk.pennico.tabcast_15.apk")}, ${apk(16, "0.5.4.1", "dk.pennico.tabcast_16.apk")}]}}"""
        assertEquals(13L, RepoIndex.latest(json, "dk.pennico.tabcast.tv")!!.code)
        val t = RepoIndex.latest(json, "dk.pennico.tabcast")!!
        assertEquals(16L, t.code); assertEquals("0.5.4.1", t.name); assertEquals("dk.pennico.tabcast_16.apk", t.file)
    }
}
