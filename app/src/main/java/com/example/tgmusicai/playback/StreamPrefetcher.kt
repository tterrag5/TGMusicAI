package com.example.tgmusicai.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Pulls whole YouTube tracks into the existing 500MB [AudioCacheManager] disk cache ahead of, and
 * alongside, playback.
 *
 * ## Why
 *
 * A resolved stream is a signed `googlevideo` URL carrying an `expire=` parameter, and it is
 * throttled. ExoPlayer normally fetches a track in byte ranges as it plays, so a long track can
 * have its later ranges requested minutes after resolution -- by which point the URL may be
 * expired or the connection throttled, and playback dies partway through a song that started
 * fine. Fetching the whole track up front converts that into a single early failure at worst, and
 * usually into no failure at all.
 *
 * ## Design notes
 *
 * Disk-backed rather than in memory, deliberately: the cache survives a process kill, is shared
 * with the player (a prefetched track replays with no network at all), and does not grow with
 * queue length. It reuses the player's own [CacheDataSource], so a prefetched byte range is
 * exactly the byte range playback will look for.
 *
 * Prefetching is best-effort and must never affect playback: every failure is swallowed, and the
 * player is never told about any of this. Local `file://` and `content://` tracks are skipped --
 * their bytes are already on disk.
 */
class StreamPrefetcher(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /** In-flight prefetches, keyed by URL, so the same track is never fetched twice at once. */
    private val inFlight = ConcurrentHashMap<String, Job>()

    /**
     * Caps concurrent prefetches. Two covers "current plus next" without turning a tapped playlist
     * into a burst of parallel downloads competing with the track the user is actually hearing.
     */
    private val permits = Semaphore(2)

    /**
     * Caches [url] in the background if it is a remote stream. Safe to call repeatedly with the
     * same URL, and safe to call for local tracks.
     */
    fun prefetch(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (inFlight.containsKey(url)) return

        val job = scope.launch(Dispatchers.IO) {
            try {
                permits.withPermit {
                    // The queue may have moved on while this waited for a permit.
                    coroutineContext.ensureActive()
                    cacheFully(url)
                }
            } catch (t: Throwable) {
                // Deliberately swallowed. Prefetching is an optimisation; if it fails the player
                // simply streams normally, and surfacing this would be noise to the user.
                Log.d(TAG, "Prefetch did not complete for $url: ${t.message}")
            } finally {
                inFlight.remove(url)
            }
        }
        inFlight[url] = job
    }

    /**
     * Drops prefetches for anything not in [keepUrls]. Called when the queue changes, so data is
     * not spent finishing tracks the user has navigated away from.
     */
    fun retainOnly(keepUrls: Collection<String>) {
        val keep = keepUrls.toSet()
        inFlight.keys.filterNot { it in keep }.forEach { url ->
            inFlight.remove(url)?.cancel()
        }
    }

    fun cancelAll() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
    }

    private fun cacheFully(url: String) {
        val cache = AudioCacheManager.getCache(context)

        // Cached bytes only become visible to the cache -- and therefore only survive the process
        // dying -- when a fragment is committed. Media3's default fragment is 5MB, so a prefetch
        // interrupted at 4.9MB contributes nothing at all. Committing more often bounds that loss,
        // which matters precisely in the situation prefetching exists for: a long track being
        // pulled down over a slow connection.
        val sinkFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(FRAGMENT_SIZE_BYTES)

        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(youTubeHttpDataSourceFactory())
            .setCacheWriteDataSinkFactory(sinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

        // Length LENGTH_UNSET means "to the end of the resource", which is what a whole track is.
        val dataSpec = DataSpec.Builder().setUri(Uri.parse(url)).build()

        CacheWriter(dataSource, dataSpec, null, null).cache()
        Log.d(TAG, "Prefetched a full track into the audio cache")
    }

    private companion object {
        const val TAG = "TGMusicCloud"

        /** How much is written before a fragment is committed to the cache. */
        const val FRAGMENT_SIZE_BYTES = 512L * 1024
    }
}
