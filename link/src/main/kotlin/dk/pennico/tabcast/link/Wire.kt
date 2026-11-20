// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.link

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Tablet <-> Tab Cast TV wire format, version 1. One TCP connection: the tablet connects, the TV listens on [PORT].
 * Used by the TV browser (open a page, remote, live preview + taps) and later by mirroring (the tab's picture and
 * sound), so one TV app serves both.
 *
 * The stream starts with the 4 bytes "TCM1", then messages in both directions. Each message has a 16-byte header,
 * big-endian: type u8, flags u8, reserved u16, payload length u32, pts i64 (microseconds, sender clock). The payload
 * follows.
 *
 * Text payloads (HELLO, configs, STATE, STATS) are `key=value` lines ([kv]). There is no JSON, so this module needs no
 * library and runs the same on the tablet, the TV and the JVM tests.
 */
object Wire {
    const val PORT = 46898
    val MAGIC = byteArrayOf('T'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())
    const val HEADER = 16
    const val MAX_PAYLOAD = 8 shl 20

    // tablet -> TV
    const val HELLO = 1          // kv: name, app, token (0.3.0: from pairing; without a valid one only PAIR is accepted)
    const val VIDEO_CONFIG = 2   // mirroring: kv codec (jpeg | vp8), w, h
    const val VIDEO = 3          // mirroring: one frame (JPEG file or VP8 frame); FLAG_KEY on key frames
    const val AUDIO_CONFIG = 4   // mirroring: kv codec (pcm_s16le), rate, channels
    const val AUDIO = 5          // mirroring: interleaved PCM
    const val STATE = 6          // mirroring: kv mirror=off (0.5.0: the tablet stopped mirroring)
    const val PING = 7           // 8 bytes: sender's nanoTime, echoed in PONG (also the keep-alive)
    const val BYE = 8
    const val OPEN = 10          // TV browser: kv url, cookies (optional "a=b; c=d" for that url), title
    const val CMD = 11           // kv c (see [Cmd]), v (value), id (optional, 0.3.3: the TV answers ACK)
    const val TAP = 12           // kv x, y (0..1 of the page view), long (true/false)
    const val SCROLL = 13        // kv dx, dy (fraction of the page view; positive = content moves up/left)
    const val TEXT = 14          // kv text, enter (true/false): typed into the focused field
    const val PREVIEW_ON = 15    // kv fps, w (max width in px)
    const val PREVIEW_OFF = 16
    const val PAIR = 17          // kv pin, name: the PIN the TV shows (0.3.0)
    const val SITES = 19         // the tablet's start-page sites, lines "title<TAB>url" (0.3.2: the web remote shows them)
    /**
     * 0.4.0: play a file the tablet serves (FileServer): kv url, name, size (bytes), id (stable per file: the TV
     * remembers the position by it), mime, subs (optional URL of a sidecar subtitle file), subsmime, rate (download
     * cap, Mbit/s). The TV copies it into its rolling cache ([FilmCache]) and plays from there. Only sent to a TV whose
     * HELLO_ACK says files=1.
     */
    const val FILE = 20

    // TV -> tablet
    const val HELLO_ACK = 101    // kv: name, app, screen, paired (true/false), acks=1 (0.3.3), files=1 + free (bytes, 0.4.0), mirror=1 (0.5.0)
    const val KEYFRAME = 102     // mirroring: the TV needs a key frame
    const val PONG = 103
    const val STATS = 104        // mirroring: kv, logged by the tablet
    const val STATUS = 105       // TV browser: kv, see [Status]
    const val PREVIEW = 106      // a JPEG of the TV page (sent while the preview is on)
    const val CAUGHT = 107       // the videos the TV page has, best first: lines "label" (index = line number)
    const val PAIR_RESULT = 108  // kv ok, token, tries (left), wait (seconds locked), error
    const val ACK = 109          // kv id: the CMD with that id was carried out (0.3.3; HELLO_ACK says acks=1)
    const val REPORT = 110       // 0.5.0: answer to CMD report: the TV's state and log as text (the tablet saves it)

    /** CMD names. */
    object Cmd {
        const val PLAY = "play"; const val PAUSE = "pause"; const val TOGGLE = "toggle"
        const val SEEK = "seek"            // v = seconds (absolute)
        const val SEEK_BY = "seekby"       // v = seconds (relative)
        const val VOLUME = "volume"        // v = 0..1 of the TV's media volume
        const val VOLUME_BY = "volumeby"   // v = steps (+1 / -1)
        const val BACK = "back"; const val RELOAD = "reload"; const val CLOSE = "close"
        const val PLAY_NATIVE = "native"   // play the best caught video in the TV's own player, full screen
        const val PLAY_IN_PAGE = "inpage"  // leave the player; the page's own video full screen
        const val CANDIDATE = "candidate"  // v = index into the caught list: play that one natively
        const val OPEN_BLOCKED = "openblocked" // open the page the TV blocked (a jump to another site without a click)
        const val AD_LEVEL = "adlevel"     // v = off | standard | aggressive: ad blocking on all sites (the TV's setting)
        const val AD_SITE = "adsite"       // v = off | standard | aggressive | default: for the open page's site
        const val AUDIO = "audio"          // 0.4.0, file: v = index into STATUS atracks
        const val SUBS = "subs"            // 0.4.0, file: v = index into STATUS stracks, or "off"
        const val REPORT = "report"        // 0.5.0: "This didn't work": the TV answers with Wire.REPORT (v = what failed, free text)
        const val UPDATE = "update"        // 0.5.1: v = check (look at the repo now) | install (update Tab Cast TV to STATUS update)
    }

    /** STATUS keys. */
    object Status {
        const val URL = "url"; const val TITLE = "title"
        const val MODE = "mode"            // idle | page | player | inpage | mirror (0.5.0)
        const val PLAYING = "playing"; const val POS = "pos"; const val DUR = "dur"   // seconds
        const val VOLUME = "vol"           // 0..1
        const val CAUGHT = "caught"        // number of caught videos
        const val VIDEO = "video"          // label of what the player plays
        const val ERROR = "error"; const val LOADING = "loading"
        const val BLOCKED = "blocked"      // last blocked jump (url), for "open anyway"
        const val ADS = "ads"              // ad-blocking level on this page (off | standard | aggressive)
        const val ADS_GLOBAL = "adsglobal" // the TV's level for all sites
        const val ADS_SITE = "adssite"     // this site's own level, or empty (it follows the TV's)
        const val ADS_BLOCKED = "adsblocked" // requests blocked on this page
        const val ADS_SUGGEST = "adssuggest" // true: the video doesn't start and things were blocked: suggest a lower level
        const val ADS_READY = "adsready"   // false: the filter lists aren't loaded (nothing is blocked whatever the level)
        // 0.4.0: a file from the tablet (mode "player", kind "file")
        const val KIND = "kind"            // "file" while a file plays (else absent: a page's video)
        const val FILE_ID = "fileid"
        const val HELD_TO = "heldto"       // seconds: the TV holds the film up to here (from the play position on)
        const val HELD = "held"            // 0..1: share of the whole file on the TV
        const val COMPLETE = "complete"    // true: the whole file is on the TV (the tablet can sleep, stop serving)
        const val WAITING = "waiting"      // true: playback reached the end of what the TV holds and the tablet doesn't answer
        const val FREE = "free"            // bytes free on the TV's disk (also in HELLO_ACK)
        const val CACHE = "cache"          // bytes the TV may use for the film
        const val A_TRACKS = "atracks"     // audio tracks "label|label…" (index = position); "(can't play)" marks unsupported ones
        const val A_TRACK = "atrack"       // index of the one playing, or -1
        const val S_TRACKS = "stracks"     // subtitle tracks, same form
        const val S_TRACK = "strack"       // index shown, or -1 (off)
        const val NO_SOUND = "nosound"     // why the film has no sound on this TV (e.g. DTS), or absent
        // 0.5.1: Tab Cast TV updates itself (older TVs send neither key)
        const val UPDATE = "update"        // versionName of the newer Tab Cast TV on the repo, or "" (none / not checked)
        const val UPDATE_STATE = "updatestate" // one plain line: downloading 40%, "Press Install on the TV", an error, or ""
    }

    const val FLAG_KEY = 1

    class Msg(val type: Int, val flags: Int, val pts: Long, val payload: ByteArray) {
        val key get() = flags and FLAG_KEY != 0
        fun kv(): Map<String, String> = parseKv(payload)
        override fun toString() = "Msg(type=$type, flags=$flags, pts=$pts, ${payload.size} B)"
    }

    private val EMPTY = ByteArray(0)

    fun writeMagic(out: OutputStream) { out.write(MAGIC); out.flush() }

    /** Reads the 4 magic bytes; false for anything else (a port scan, another protocol). */
    fun readMagic(inp: InputStream): Boolean {
        val b = ByteArray(4)
        var n = 0
        while (n < 4) { val r = inp.read(b, n, 4 - n); if (r < 0) return false; n += r }
        return b.contentEquals(MAGIC)
    }

    /** Writes one message (no flush). Callers serialise writes on one stream. */
    fun write(out: DataOutputStream, type: Int, flags: Int = 0, pts: Long = 0, payload: ByteArray = EMPTY,
              off: Int = 0, len: Int = payload.size - off) {
        require(len in 0..MAX_PAYLOAD) { "payload of $len bytes" }
        out.writeByte(type)
        out.writeByte(flags)
        out.writeShort(0)
        out.writeInt(len)
        out.writeLong(pts)
        out.write(payload, off, len)
    }

    /** Reads one message; EOFException at a clean end, IOException for a broken stream. */
    fun read(inp: DataInputStream): Msg {
        val type = inp.read()
        if (type < 0) throw EOFException()
        val flags = inp.readUnsignedByte()
        inp.readUnsignedShort()
        val len = inp.readInt()
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("bad message length $len (type $type)")
        val pts = inp.readLong()
        val p = if (len == 0) EMPTY else ByteArray(len).also { inp.readFully(it) }
        return Msg(type, flags, pts, p)
    }

    /** `key=value` lines; line breaks inside values become spaces, '=' is allowed in values. */
    fun kv(vararg pairs: Pair<String, Any?>): ByteArray = pairs.filter { it.second != null }.joinToString("\n") { (k, v) ->
        require(k.isNotEmpty() && '=' !in k && '\n' !in k) { "bad key $k" }
        k + "=" + v.toString().replace('\n', ' ').replace('\r', ' ')
    }.toByteArray(Charsets.UTF_8)

    fun parseKv(b: ByteArray): Map<String, String> = String(b, Charsets.UTF_8).split('\n')
        .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()

    fun long(v: Long): ByteArray = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }
    fun long(b: ByteArray): Long = if (b.size < 8) 0 else (0 until 8).fold(0L) { a, i -> (a shl 8) or (b[i].toLong() and 0xff) }
}
