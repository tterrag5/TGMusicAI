package com.example.tgmusicai.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache

/**
 * Routes each [open] to the disk-cached HTTP data source for `http(s)://` streams (YouTube audio)
 * and a plain, uncached data source for everything else -- local `file://` (downloaded tracks) and
 * `content://` (device-scanned MediaStore tracks) URIs. Without this, wiring the stream cache in
 * as ExoPlayer's single global [DataSource.Factory] would also churn every locally
 * downloaded/scanned song's bytes through the 500MB LRU cache on every play -- pointless (those
 * bytes are already on local disk) and it would pressure real streams out of the cache sooner.
 */
private class SchemeAwareCacheDataSource(
    private val cachedHttpDataSource: DataSource,
    private val plainDataSource: DataSource
) : DataSource {

    private var active: DataSource = plainDataSource

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        active = if (scheme == "http" || scheme == "https") cachedHttpDataSource else plainDataSource
        return active.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)
    override fun getUri(): Uri? = active.uri
    override fun close() = active.close()
    override fun addTransferListener(transferListener: TransferListener) {
        cachedHttpDataSource.addTransferListener(transferListener)
        plainDataSource.addTransferListener(transferListener)
    }
}

/** [DataSource.Factory] for [SchemeAwareCacheDataSource] -- see its doc comment. */
class SchemeAwareCacheDataSourceFactory(context: Context, cache: SimpleCache) : DataSource.Factory {
    private val cachedHttpFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    private val plainFactory = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource =
        SchemeAwareCacheDataSource(cachedHttpFactory.createDataSource(), plainFactory.createDataSource())
}
