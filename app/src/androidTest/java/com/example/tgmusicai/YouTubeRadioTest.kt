package com.example.tgmusicai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Autoplay's radio source against the real YouTube Music `/next` endpoint, signed out: the radio
 * comes back, leaves the seed out, and its first track resolves to a stream -- which is what
 * autoplay needs before it may append anything.
 *
 * Needs real network access to YouTube.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeRadioTest {

    @Test
    fun radioForATrackReturnsPlayableTracks() = runBlocking {
        val radio = withTimeout(TIMEOUT_MS) { YouTubeMusicBrowser().fetchRadio(SEED_VIDEO_ID) }
        Log.i(TAG, "Radio for $SEED_VIDEO_ID: ${radio.size} tracks; first=${radio.firstOrNull()}")

        assertTrue("Expected a radio of at least 10 tracks, got ${radio.size}", radio.size >= 10)
        assertTrue("The seed must not be in its own radio", radio.none { it.videoId == SEED_VIDEO_ID })
        assertTrue("Every track needs a title", radio.all { it.title.isNotBlank() })

        val stream = withTimeout(TIMEOUT_MS) { YouTubeExtractor().extractAudioStream(radio.first().videoId) }
        assertNotNull("The first radio track did not resolve to a stream", stream)
    }

    private companion object {
        const val TAG = "YouTubeRadioTest"

        // Different from StreamResolutionTest's track, so running both suites does not fetch the
        // same video twice in quick succession (YouTube answers that with 403).
        const val SEED_VIDEO_ID = "fJ9rUzIMcZQ"
        const val TIMEOUT_MS = 60_000L
    }
}
