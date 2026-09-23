package com.example.tgmusicai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end proof that cloud stream resolution works: the whole path a tapped cloud song takes,
 * from a videoId to a URL that ExoPlayer can actually open.
 *
 * These are the tests that decide whether cloud playback is actually fixed, as opposed to merely
 * reaching the resolver. A resolved URL can still be unplayable -- YouTube hands out signed
 * `googlevideo` URLs that 403 when the request is not accepted -- so the extractor's own
 * `verifyStreamUrl` pre-flight probe is deliberately part of the path exercised here, rather than
 * asserting only that a non-null object came back.
 *
 * Needs real network access to YouTube.
 */
@RunWith(AndroidJUnit4::class)
class StreamResolutionTest {

    @Test
    fun resolvesAPlayableAudioStreamForACloudTrack() = runBlocking {
        val extractor = YouTubeExtractor()

        val startedAtMs = System.currentTimeMillis()
        val stream = withTimeout(RESOLUTION_TIMEOUT_MS) {
            extractor.extractAudioStream(TEST_VIDEO_ID)
        }
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        Log.i(TAG, "Resolved $TEST_VIDEO_ID in ${elapsedMs}ms: $stream")

        assertNotNull(
            "Stream resolution returned null, so cloud playback is still broken. Check logcat " +
                "for the tier markers to see which tier was reached.",
            stream,
        )
        requireNotNull(stream)

        assertTrue("Stream URL should not be blank", stream.url.isNotBlank())
        // A real resolved stream is a signed googlevideo URL, not the watch page we started from.
        assertTrue(
            "Expected a direct media URL but got what looks like a watch page: ${stream.url}",
            !stream.url.contains("youtube.com/watch"),
        )
        assertTrue(
            "Expected a recognised audio container, got '${stream.format}'",
            stream.format in setOf("m4a", "webm", "aac", "opus"),
        )
        assertTrue("Expected a positive bitrate, got ${stream.bitrate}", stream.bitrate > 0)

        // The whole point of Tier 0 is that it does not crawl through dead public instances.
        // Exhausting those previously took roughly 110 seconds.
        assertTrue(
            "Resolution took ${elapsedMs}ms, which suggests Tier 0 failed and slower fallback " +
                "tiers were used instead",
            elapsedMs < 30_000,
        )
    }

    /**
     * Cloud search already worked before this change, and a NewPipeExtractor version bump is the
     * kind of thing that can quietly break it. This guards that regression explicitly.
     */
    @Test
    fun cloudSearchStillReturnsResults() = runBlocking {
        val extractor = YouTubeExtractor()

        val results = withTimeout(RESOLUTION_TIMEOUT_MS) { extractor.search("daft punk") }

        Log.i(TAG, "Search returned ${results.size} results; first=${results.firstOrNull()}")
        assertTrue("Search returned no results, which is a regression", results.isNotEmpty())
        assertTrue(
            "Every result should carry a videoId",
            results.all { it.videoId.isNotBlank() },
        )
    }

    /** Downloads share the resolver, so this is what proves they are fixed by the same change. */
    @Test
    fun resolvesCandidateStreamsForDownloads() = runBlocking {
        val extractor = YouTubeExtractor()

        val streams = withTimeout(RESOLUTION_TIMEOUT_MS) {
            extractor.extractAudioStreams(TEST_VIDEO_ID)
        }

        Log.i(TAG, "Download candidates for $TEST_VIDEO_ID: ${streams.size}")
        streams.take(3).forEach { Log.i(TAG, "  candidate: $it") }

        assertTrue(
            "No candidate streams resolved, so downloads would still fail",
            streams.isNotEmpty(),
        )
        assertTrue(
            "Every candidate should carry a usable URL",
            streams.all { it.url.isNotBlank() },
        )
    }

    private companion object {
        const val TAG = "TGMusicCloud"

        /** "Big Buck Bunny", a long-standing Creative Commons upload used as a stable fixture. */
        const val TEST_VIDEO_ID = "aqz-KE-bpKQ"

        const val RESOLUTION_TIMEOUT_MS = 120_000L
    }
}
