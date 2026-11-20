// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core.adblock

import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.Reader

/**
 * The shipped filter lists (assets/filters/lists.txt) and one way to load them, used by the apps (assets or the weekly
 * downloads), the source-tree tests and the packaged-APK test. 0.2.2–0.3.0 shipped `<name>.txt.gz`, which the APK build
 * silently turned into `<name>.txt`; the lists never loaded on the devices. They ship as plain
 * `<name>.txt` since 0.3.1 and PackagedListsTest loads them from the built APK.
 *
 * A list marked `download` in the manifest's 4th field is not shipped (its terms don't clearly allow bundling it): the
 * apps fetch it with the weekly update, the first time about a minute after the first start on Wi-Fi, and compile
 * without it until then.
 */
object FilterLists {
    class Entry(val name: String, val url: String, val licence: String, val shipped: Boolean = true)

    const val MANIFEST = "filters/lists.txt"

    /** Where a shipped list is inside the assets (and inside the APK under "assets/"). */
    fun assetPath(name: String) = "filters/$name.txt"

    fun parseManifest(text: String): List<Entry> = text.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { l ->
            l.split('|').let {
                Entry(it[0].trim(), it.getOrElse(1) { "" }.trim(), it.getOrElse(2) { "" }.trim(), it.getOrElse(3) { "" }.trim() != "download")
            }
        }

    /**
     * Compiles the [lists]; [open] gives a list's text by name, null when it is missing: then this fails, naming it,
     * unless the list isn't shipped (not downloaded yet), which is skipped.
     */
    fun build(lists: List<Entry>, open: (String) -> Reader?): Pair<FilterEngine, FilterBuilder> {
        require(lists.isNotEmpty()) { "no filter lists in $MANIFEST" }
        val b = FilterBuilder()
        for (l in lists) {
            val r = open(l.name) ?: if (l.shipped) throw FileNotFoundException("filter list ${l.name} (${assetPath(l.name)}) is missing") else continue
            (r as? BufferedReader ?: r.buffered()).use { b.addList(l.name, it) }
        }
        return b.build() to b
    }

    /** "112,912 network rules, 25,137 hiding rules" for the log and the settings. */
    fun counts(e: FilterEngine): String =
        "%,d network rules, %,d hiding rules".format(e.info["count.network"]?.toIntOrNull() ?: 0, e.info["count.hiding"]?.toIntOrNull() ?: 0)
}
