package com.example.tgmusicai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tgmusicai.playback.AudioCacheManager
import com.example.tgmusicai.playback.StreamPrefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that prefetching actually writes bytes into the shared audio cache.
 *
 * Worth asserting rather than assuming: [StreamPrefetcher] swallows every failure by design, since
 * a prefetch problem must never disturb playback. That makes a broken prefetcher completely silent
 * -- it would simply cache nothing, while playback kept working and quietly lost the protection
 * against a signed URL expiring mid-song that prefetching exists to provide.
 *
 * Two things are deliberately *not* done here, both learned from watching this suite fail:
 *
 *  - No full music track is prefetched. A track is several megabytes and an emulator sustains only
 *    tens of KB/s, so asserting on completion measures the emulator rather than the code. A small
 *    resource exercises the identical path (same HTTP factory, same cache, same sink) and finishes.
 *  - No YouTube stream is downloaded here at all. Doing so raced the download in
 *    PlaybackVerificationTest for the same track, and YouTube answers the resulting duplicate
 *    fetches with HTTP 403 -- which failed the suite for reasons unrelated to either feature.
 */
@RunWith(AndroidJUnit4::class)
class StreamPrefetchTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun completedPrefetchCommitsBytesToTheCache() = runBlocking {
        val cache = AudioCacheManager.getCache(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefetcher = StreamPrefetcher(context, scope)

        try {
            // Measured with cacheSpace rather than getCachedBytes(key, ...): the latter counts only
            // bytes contiguous from the queried position in committed spans, so it still reads 0
            // while a fragment is mid-write even though the cache is filling correctly.
            val spaceBefore = cache.cacheSpace
            prefetcher.prefetch(SMALL_RESOURCE_URL)
            // Calling again with the same URL must not start a second, duplicate download.
            prefetcher.prefetch(SMALL_RESOURCE_URL)

            var spaceNow = spaceBefore
            val deadline = System.currentTimeMillis() + CACHE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                spaceNow = cache.cacheSpace
                if (spaceNow > spaceBefore) break
                delay(250)
            }

            Log.i(TAG, "Prefetch grew the audio cache from $spaceBefore to $spaceNow bytes")
            assertTrue(
                "A completed prefetch committed nothing to the cache, so the protection against " +
                    "mid-song URL expiry is not actually in effect",
                spaceNow > spaceBefore,
            )
        } finally {
            prefetcher.cancelAll()
            scope.cancel()
        }
    }

    /** Local tracks are already on disk; prefetching them would just churn the LRU cache. */
    @Test
    fun ignoresLocalUris() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefetcher = StreamPrefetcher(context, scope)
        try {
            // Neither should throw, and neither should attempt any network work.
            prefetcher.prefetch("file:///storage/emulated/0/Music/whatever.mp3")
            prefetcher.prefetch("content://media/external/audio/media/42")
        } finally {
            prefetcher.cancelAll()
            scope.cancel()
        }
    }

    private companion object {
        const val TAG = "TGMusicCloud"
        const val CACHE_TIMEOUT_MS = 60_000L

        /** Small, stable, and served over plain HTTPS -- enough to finish inside the timeout. */
        const val SMALL_RESOURCE_URL = "https://www.youtube.com/robots.txt"
    }
}
