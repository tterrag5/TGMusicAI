package com.example.tgmusicai.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Owns a single process-wide [SimpleCache] for YouTube audio streams, keyed by URL and backed by
 * an LRU-evicted directory (`cache/media_stream_cache/`, 500MB cap). [PlaybackService] wires this
 * into ExoPlayer's [androidx.media3.datasource.cache.CacheDataSource] so a previously-played
 * stream replays from disk instead of re-downloading -- zero data usage and offline-capable for
 * anything already heard once. A single shared [SimpleCache] instance is required: opening the
 * same cache directory from two instances at once throws.
 */
object AudioCacheManager {
    private const val CACHE_DIR_NAME = "media_stream_cache"
    private const val CACHE_MAX_BYTES = 500L * 1024L * 1024L // 500MB

    private var simpleCache: SimpleCache? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        return simpleCache ?: run {
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES)
            val databaseProvider = StandaloneDatabaseProvider(context)
            SimpleCache(cacheDir, evictor, databaseProvider).also { simpleCache = it }
        }
    }
}
