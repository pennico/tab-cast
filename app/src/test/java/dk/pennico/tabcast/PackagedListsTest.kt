// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast

import dk.pennico.tabcast.core.adblock.FilterEngine
import dk.pennico.tabcast.core.adblock.FilterLists
import dk.pennico.tabcast.core.adblock.T
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 0.2.2-0.3.0 shipped the filter lists as .gz assets, the APK build stored them without the suffix,
 * and ad blocking never loaded on the devices, while every test passed on the source tree. This test opens the APK
 * that was just built (Gradle builds it first, see build.gradle.kts) and loads every list from its assets with the
 * same loader the app uses.
 */
class PackagedListsTest {
    @Test fun everyListLoadsFromTheBuiltApk() {
        val apk = File(System.getProperty("tabcast.apk") ?: error("tabcast.apk not set (run through Gradle)"))
        assertTrue("built APK missing: $apk", apk.exists())
        ZipFile(apk).use { zip ->
            fun asset(path: String) = zip.getEntry("assets/$path")
            for (a in listOf("inject.js", "site-rules.json", FilterLists.MANIFEST)) assertNotNull("asset $a missing from ${apk.name}", asset(a))
            val lists = FilterLists.parseManifest(zip.getInputStream(asset(FilterLists.MANIFEST)).bufferedReader().readText())
            assertEquals(listOf("easylist", "easyprivacy", "ubo-filters", "ubo-privacy", "peterlowe"), lists.map { it.name })
            // Peter Lowe's list is downloaded by the app, never shipped (its terms don't clearly allow bundling).
            assertEquals(listOf("peterlowe"), lists.filter { !it.shipped }.map { it.name })
            for (l in lists) assertEquals("assets/${FilterLists.assetPath(l.name)} in the APK", l.shipped, asset(FilterLists.assetPath(l.name)) != null)
            val (e, _) = FilterLists.build(lists) { name -> asset(FilterLists.assetPath(name))?.let { zip.getInputStream(it).bufferedReader() } }
            val network = e.info["count.network"]!!.toInt()
            println("PACKAGED ${apk.name}: ${FilterLists.counts(e)}")
            assertTrue("only $network network rules", network > 100_000)
            assertTrue(e.info["count.hiding"]!!.toInt() > 20_000)
            val ad = FilterEngine.Request("https://securepubads.g.doubleclick.net/tag/js/gpt.js", T.SCRIPT, "https://www.dr.dk/")
            assertEquals(FilterEngine.Verdict.BLOCK, e.check(ad, FilterEngine.Level.STANDARD))
        }
    }
}
