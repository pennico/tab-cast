// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceView
import android.view.View
import android.webkit.CookieManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dk.pennico.tabcast.AppLog
import dk.pennico.tabcast.core.Candidate
import dk.pennico.tabcast.core.Kind

/**
 * The TV's own player for a caught video, full screen. It is the same device that loaded the page, so the requests
 * carry the TV WebView's cookies (per request URL, also for segments on other hosts), its user agent and the page's
 * Referer: what the site expects, without a relay.
 */
@OptIn(UnstableApi::class)
class TvPlayer(private val ctx: Context, private val view: PlayerView, private val onChange: () -> Unit,
               private val onError: (String) -> Unit) {
    private var player: ExoPlayer? = null
    var label: String? = null
        private set
    /** Our own playback level 0..1 (the Chromecast's HDMI volume is fixed for apps, see TvActivity.volume). */
    var volume: Float = 1f
        set(v) { field = v.coerceIn(0f, 1f); player?.volume = field }

    init {
        // a page's poster/video stayed on top of the playing video. Our video goes above other media
        // layers (the page's own video surface), no artwork over it, and a spinner while it buffers.
        (view.videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
        view.setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_OFF)
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        view.setShutterBackgroundColor(android.graphics.Color.BLACK)
    }
    val active: Boolean get() = player != null
    val playing: Boolean get() = player?.isPlaying == true
    val position: Double get() = (player?.currentPosition ?: 0L) / 1000.0
    val duration: Double? get() = player?.duration?.takeIf { it > 0 }?.let { it / 1000.0 }
    val buffering: Boolean get() = player?.playbackState == Player.STATE_BUFFERING
    val ended: Boolean get() = player?.playbackState == Player.STATE_ENDED
    /** 0.4.0: the film reached its end (the activity cleans up its cache and position). */
    var onEnded: () -> Unit = {}
    /** 0.4.0: the film's tracks are known or changed (audio / subtitle lists for the remote). */
    var onTracks: () -> Unit = {}
    /** 0.5.4: the chosen subtitle track failed while playing; subtitles are off and the film plays on. */
    var onSubtitlesFailed: () -> Unit = {}

    /**
     * 0.4.0: a film from the tablet, read through the TV's rolling cache ([factory] routes `tabcast-film:` there),
     * from [startMs], with an optional sidecar subtitle file.
     */
    fun playFilm(uri: String, mime: String?, subs: MediaItem.SubtitleConfiguration?, factory: androidx.media3.datasource.DataSource.Factory,
                 startMs: Long, title: String) {
        release()
        val p = ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(factory)).build()
        val item = MediaItem.Builder().setUri(uri).apply {
            mime?.let { setMimeType(it) }
            subs?.let { setSubtitleConfigurations(listOf(it)) }
        }.build()
        listen(p, uri)
        p.volume = volume
        p.setMediaItem(item, startMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        view.player = p
        view.visibility = View.VISIBLE
        view.requestFocus()
        player = p
        label = title
        AppLog.i("TV player: film \"$title\" from ${startMs / 1000} s${if (subs != null) " with subtitles from the tablet" else ""}")
    }

    /** After an error: prepare again where it was (a film keeps going when the cache has the bytes again). */
    fun retry() { player?.let { it.prepare(); it.playWhenReady = true } }

    // ---------------------------------------------------------------------------------------- tracks (0.4.0)

    class Track(val label: String, val supported: Boolean, val selected: Boolean, val mime: String?)

    private fun groups(type: Int) = player?.currentTracks?.groups?.filter { it.type == type } ?: emptyList()

    private fun describe(g: androidx.media3.common.Tracks.Group): Track {
        val f = g.getTrackFormat(0)
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
        val ch = when (f.channelCount) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; -1, 0 -> null; else -> "${f.channelCount} ch" }
        val supported = (0 until g.length).any { g.isTrackSupported(it) }
        val parts = listOfNotNull(f.label?.takeIf { it.isNotBlank() }, lang, ch).distinct()
        val name = parts.joinToString(" · ").ifEmpty { "Track" } + if (!supported) " (${codecName(f.sampleMimeType)}: can't play here)" else ""
        return Track(name, supported, g.isSelected, f.sampleMimeType)
    }

    fun audioTracks(): List<Track> = groups(C.TRACK_TYPE_AUDIO).map { describe(it) }
    fun textTracks(): List<Track> = groups(C.TRACK_TYPE_TEXT).map { describe(it) }

    /** 0.5.4: the first track of [g] this TV can play, or -1 (e.g. VobSub subtitles, DTS sound). */
    private fun playable(g: androidx.media3.common.Tracks.Group) = (0 until g.length).firstOrNull { g.isTrackSupported(it) } ?: -1

    /**
     * 0.5.4: false (and nothing changes) for a track this TV can't play. Forcing one used to stop the film: the
     * player failed on it, and every retry failed again with the same track forced.
     */
    fun selectAudio(i: Int): Boolean {
        val p = player ?: return false
        val g = groups(C.TRACK_TYPE_AUDIO).getOrNull(i) ?: return false
        val t = playable(g).takeIf { it >= 0 } ?: return false
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)).build()
        return true
    }

    /** [i] < 0: subtitles off. 0.5.4: false (and nothing changes) for a track this TV can't show, as [selectAudio]. */
    fun selectText(i: Int): Boolean {
        val p = player ?: return false
        if (i < 0) { subtitlesOff(); return true }
        val g = groups(C.TRACK_TYPE_TEXT).getOrNull(i) ?: return false
        val t = playable(g).takeIf { it >= 0 } ?: return false
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)).build()
        return true
    }

    private fun subtitlesOff() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    /**
     * 0.5.4: the error came from the subtitles: the text renderer failed (Media3 throws "Legacy decoding is
     * disabled" for VobSub), or a runtime check failed while a subtitle track was forced. Turning subtitles off fixes it.
     */
    private fun subtitleFault(p: ExoPlayer, e: PlaybackException): Boolean {
        if (p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return false
        val x = e as? ExoPlaybackException
        if (x?.type == ExoPlaybackException.TYPE_RENDERER && MimeTypes.isText(x.rendererFormat?.sampleMimeType)) return true
        return e.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK &&
            p.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_TEXT }
    }

    /** Why the film plays without sound here (every audio track is in a format this TV can't decode, e.g. DTS), or null. */
    fun noSound(): String? {
        val a = groups(C.TRACK_TYPE_AUDIO)
        if (a.isEmpty() || a.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }) return null
        val codecs = a.mapNotNull { codecName(it.getTrackFormat(0).sampleMimeType) }.distinct().joinToString(", ")
        return "This film's sound ($codecs) can't play on this TV"
    }

    private fun codecName(mime: String?): String = when (mime) {
        MimeTypes.AUDIO_DTS -> "DTS"; MimeTypes.AUDIO_DTS_HD -> "DTS-HD"; MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        "audio/vnd.dts.uhd;profile=p2" -> "DTS:X"; MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"; MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"; MimeTypes.AUDIO_AC4 -> "AC-4"; MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_MPEG -> "MP3"; MimeTypes.AUDIO_FLAC -> "FLAC"; MimeTypes.AUDIO_VORBIS -> "Vorbis"
        null -> "unknown"; else -> mime.substringAfter('/')
    }

    private fun listen(p: ExoPlayer, what: String) {
        val t0 = SystemClock.elapsedRealtime()
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                AppLog.i("TV player: first frame after ${SystemClock.elapsedRealtime() - t0} ms")
                onChange()
            }
            override fun onPlayerError(error: PlaybackException) {
                AppLog.i("TV player error ${error.errorCodeName}: ${error.message} (${what.take(160)})")
                if (player === p && subtitleFault(p, error)) {
                    // Subtitles off and on at once: the film itself is fine.
                    subtitlesOff()
                    AppLog.i("TV player: the subtitle track can't be shown here; subtitles off, playing on")
                    p.prepare(); p.playWhenReady = true
                    onSubtitlesFailed()
                    return
                }
                onError("${error.errorCodeName}${error.cause?.message?.let { ": $it" } ?: ""}".take(200))
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = onChange()
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
                onChange()
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { onTracks(); onChange() }
        })
    }

    fun play(c: Candidate, userAgent: String) {
        release()
        // The page's own request headers, minus those the HTTP stack sets itself (cookies are added per URL below).
        val skip = setOf("cookie", "range", "host", "user-agent", "accept-encoding", "connection", "content-length")
        val headers = c.headers.filterKeys { it.lowercase() !in skip }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent.ifEmpty { null })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
        val cookies = ResolvingDataSource.Factory(http) { spec ->
            val ck = try { CookieManager.getInstance().getCookie(spec.uri.toString()) } catch (_: Exception) { null }
            if (ck.isNullOrEmpty()) spec else spec.withAdditionalHeaders(mapOf("Cookie" to ck))
        }
        val p = ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(cookies)).build()
        val item = MediaItem.Builder().setUri(c.url).apply {
            when (c.kind) {
                Kind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                Kind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                else -> {}
            }
        }.build()
        listen(p, c.url)
        p.volume = volume
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        view.player = p
        view.visibility = View.VISIBLE
        view.requestFocus()
        player = p
        label = c.describe()
        AppLog.i("TV player: ${c.kind} ${c.url.take(160)} (headers ${headers.keys.joinToString(",")})")
    }

    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun toggle() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(s: Double) { player?.seekTo((s * 1000).toLong().coerceAtLeast(0)) }
    fun seekBy(s: Double) { player?.let { it.seekTo((it.currentPosition + (s * 1000).toLong()).coerceAtLeast(0)) } }

    fun release() {
        player?.release()
        player = null
        label = null
        view.player = null
        view.visibility = View.GONE
    }
}

/** 0.5.1: subtitles readable from the sofa: larger, white with an outline on a dark box, the film's own sizes ignored. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun styleSubtitles(v: androidx.media3.ui.PlayerView) {
    v.subtitleView?.apply {
        setStyle(androidx.media3.ui.CaptionStyleCompat(android.graphics.Color.WHITE, 0x99000000.toInt(), android.graphics.Color.TRANSPARENT,
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
        setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.2f)
        setApplyEmbeddedFontSizes(false)
        setBottomPaddingFraction(0.06f)
    }
}
