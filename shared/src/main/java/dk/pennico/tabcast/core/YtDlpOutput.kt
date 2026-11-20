// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.core

/**
 * Reading a yt-dlp run that has `--dump-single-json --no-quiet` with stderr merged into stdout: the step messages
 * ("[youtube] abc: Downloading webpage") arrive line by line while it works, the JSON comes last on one line.
 * The steps drive the progress text and a timeline in the log, so slow phases on the tablet can be measured.
 */
object YtDlpOutput {
    /** The info JSON: the last line that starts with '{'. */
    fun jsonLine(out: String): String? =
        out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") && it.endsWith("}") }

    /** yt-dlp's own error message, without the "ERROR: [extractor] id:" prefix. */
    fun error(lines: List<String>): String? {
        val l = lines.lastOrNull { it.trimStart().startsWith("ERROR:") } ?: return null
        return l.trim().removePrefix("ERROR:").trim().replace(Regex("""^\[[^\]]+]\s+[^:\s]+:\s+"""), "").take(300)
    }

    /** "[youtube] abc123: " or "[youtube] [jsc:quickjs] " in front of the message. */
    private val prefix = Regex("""^(?:\[[^\]]+]\s+)+(?:[\w.@/-]{3,}:\s+)?""")

    /** Short text for the progress line, or null for lines that aren't steps (JSON, debug, blank, progress bars). */
    fun step(line: String): String? {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("{") || l.startsWith("[debug]") || l.startsWith("[download]")) return null
        if (l.startsWith("WARNING:") || l.startsWith("ERROR:")) return null
        if (l.startsWith("[info]")) return null
        val m = prefix.find(l) ?: return null
        val rest = l.substring(m.range.last + 1).trim()
        return when {
            rest.startsWith("Solving JS challenges") -> "solving the site's JavaScript challenge (slow on this tablet)"
            rest.startsWith("Downloading player ") -> "downloading the site's player script"
            rest.isEmpty() -> null
            else -> rest.replaceFirstChar { it.lowercase() }.take(80)
        }
    }

    /** Collects (ms since start, step) pairs; [summary] is one log line. */
    class Timeline(private val now: () -> Long = System::currentTimeMillis) {
        private val start = now()
        private val steps = ArrayList<Pair<Long, String>>()

        @Synchronized fun add(step: String) { steps += (now() - start) to step }

        @Synchronized fun summary(end: String = "done"): String {
            val all = steps + ((now() - start) to end)
            return all.joinToString(" · ") { (t, s) -> "%.1f s %s".format(java.util.Locale.ROOT, t / 1000.0, s) }
        }
    }
}
