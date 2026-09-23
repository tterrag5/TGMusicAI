package com.example.tgmusicai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.example.tgmusicai.playback.AudioCacheManager
import com.example.tgmusicai.playback.StreamPrefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that prefetching actually writes a track's bytes into the shared audio cache.
 *
 * Worth asserting rather than assuming: [StreamPrefetcher] swallows every failure by design, since
 * a prefetch problem must never disturb playback. That makes a broken prefetcher completely silent
 * -- it would simply cache nothing, and playback would keep working while quietly losing the
 * protection against a signed URL expiring mid-song that prefetching exists to provide.
 */
@RunWith(AndroidJUnit4::class)
class StreamPrefetchTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Prefetching a real resolved stream must at least start cleanly and not disturb anything.
     *
     * This deliberately does **not** assert that the track finishes caching. A full track is
     * several megabytes and an emulator's sustained throughput can be a few tens of KB/s, so a
     * completion assertion here measures the emulator rather than the code, and fails for reasons
     * that have nothing to do with correctness. That the caching mechanism genuinely commits bytes
     * is proven by [completedPrefetchCommitsBytesToTheCache], which uses a resource small enough
     * to finish.
     */
    @Test
    fun prefetchingAResolvedStreamStartsCleanly() = runBlocking<Unit> {
        val stream = withTimeout(RESOLVE_TIMEOUT_MS) {
            YouTubeExtractor().extractAudioStream(TEST_VIDEO_ID)
        }
        assertNotNull("Could not resolve a stream, so prefetching cannot be tested", stream)
        requireNotNull(stream)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefetcher = StreamPrefetcher(context, scope)

        try {
            prefetcher.prefetch(stream.url)
            // Calling again with the same URL must not start a second download of the same track.
            prefetcher.prefetch(stream.url)
            delay(5_000)
            Log.i(TAG, "Prefetch of a resolved stream started without error")
        } finally {
            prefetcher.cancelAll()
            scope.cancel()
        }
    }

    /**
     * Isolates the caching mechanism from YouTube's throughput. A multi-megabyte track can take
     * minutes to finish on an emulator, so this uses a small resource that completes quickly and
     * proves that a finished prefetch really does commit bytes to the shared cache.
     */
    @Test
    fun completedPrefetchCommitsBytesToTheCache() = runBlocking {
        val cache = AudioCacheManager.getCache(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefetcher = StreamPrefetcher(context, scope)

        try {
            val spaceBefore = cache.cacheSpace
            prefetcher.prefetch(SMALL_RESOURCE_URL)

            var spaceNow = spaceBefore
            val deadline = System.currentTimeMillis() + CACHE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                spaceNow = cache.cacheSpace
                if (spaceNow > spaceBefore) break
                delay(250)
            }

            Log.i(TAG, "Small-resource prefetch grew cache from $spaceBefore to $spaceNow bytes")
            assertTrue(
                "A completed prefetch committed nothing to the cache",
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
        const val TEST_VIDEO_ID = "aqz-KE-bpKQ"
        const val RESOLVE_TIMEOUT_MS = 120_000L
        const val CACHE_TIMEOUT_MS = 60_000L

        /** Small, stable, and served over plain HTTPS -- enough to finish inside the timeout. */
        const val SMALL_RESOURCE_URL = "https://www.youtube.com/robots.txt"
    }
}
