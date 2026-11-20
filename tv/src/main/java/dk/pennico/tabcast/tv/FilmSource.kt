// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.tv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import dk.pennico.tabcast.link.FilmCache

/**
 * 0.4.0: the player reads a film from the TV's rolling cache ([FilmCache]): `tabcast-film:` URIs. A read waits until
 * the bytes are on the TV (the cache fetches them from the tablet), so a sleeping tablet only makes the player wait
 * ("buffering"), never fail. Anything else (a sidecar subtitle file on the tablet) goes over plain HTTP.
 */
@OptIn(UnstableApi::class)
class FilmDataSource(private val cache: FilmCache) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var pos = 0L
    private var left = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        pos = dataSpec.position
        if (pos > cache.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, cache.size - pos) else cache.size - pos
        opened = true
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val n = cache.read(pos, buffer, offset, minOf(length.toLong(), left).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        pos += n
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) { opened = false; transferEnded() }
        uri = null
    }

    companion object { const val SCHEME = "tabcast-film" }
}

/** `tabcast-film:` → the cache, `file:` → the TV's own files (0.5.1: the film's subtitles), everything else → HTTP. */
@OptIn(UnstableApi::class)
class FilmRouting(private val cache: FilmCache, private val http: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private val film = FilmDataSource(cache)
        private val web = http.createDataSource()
        private val local = androidx.media3.datasource.FileDataSource()
        private var cur: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) {
            film.addTransferListener(transferListener); web.addTransferListener(transferListener); local.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val d = when (dataSpec.uri.scheme) { FilmDataSource.SCHEME -> film; "file" -> local; else -> web }
            cur = d
            return d.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = cur?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = cur?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = cur?.responseHeaders ?: emptyMap()
        override fun close() { cur?.close(); cur = null }
    }

    companion object {
        fun http(): DataSource.Factory = DefaultHttpDataSource.Factory().setConnectTimeoutMs(8000).setReadTimeoutMs(15_000)
    }
}
