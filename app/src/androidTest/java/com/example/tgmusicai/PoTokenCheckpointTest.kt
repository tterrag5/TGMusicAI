package com.example.tgmusicai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tgmusicai.data.youtube.NewPipeOkHttpDownloader
import com.example.tgmusicai.data.youtube.potoken.BadWebViewException
import com.example.tgmusicai.data.youtube.potoken.PoTokenWebViewGenerator
import com.example.tgmusicai.data.youtube.potoken.TGPoTokenProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The checkpoint from the Phase 2 plan: prove a real poToken can be minted on this device before
 * any of it is wired into playback.
 *
 * This exists as a separate, deliberately isolating step because the two ways this can fail look
 * identical from inside the app -- our code being wrong, versus the device's WebView being too old
 * to run BotGuard's JavaScript at all. Integrating first would blend both into a generic "cloud
 * playback still broken", which is far harder to localise. [BadWebViewException] is what separates
 * them, so the tests below report it explicitly rather than letting it fail as a generic error.
 *
 * Run with:
 * ```
 * ./gradlew connectedAndroidTest --tests "com.example.tgmusicai.PoTokenCheckpointTest"
 * ```
 *
 * These tests need real network access to youtube.com and are expected to be skipped or to fail on
 * a machine without it; that is a property of the environment, not a regression.
 */
@RunWith(AndroidJUnit4::class)
class PoTokenCheckpointTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun httpClient() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Stage one: BotGuard alone. Deliberately avoids NewPipeExtractor and visitorData, so a
     * failure here can only mean the WebView could not host BotGuard.
     */
    @Test
    fun mintsPoTokenForAHardcodedVideoId() = runBlocking {
        val generator = try {
            withTimeout(TIMEOUT_MS) {
                PoTokenWebViewGenerator.create(context, httpClient())
            }
        } catch (e: BadWebViewException) {
            Log.e(TAG, "WEBVIEW TOO OLD: this device cannot run BotGuard", e)
            throw AssertionError(
                "The device's WebView cannot run BotGuard. This is an environment limitation, " +
                    "not a defect in the poToken implementation. Update Android System WebView " +
                    "and re-run.",
                e,
            )
        }

        try {
            val poToken = withTimeout(TIMEOUT_MS) { generator.generatePoToken(TEST_VIDEO_ID) }

            Log.i(TAG, "CHECKPOINT PASSED -- poToken for $TEST_VIDEO_ID: $poToken")
            assertNotNull("poToken should not be null", poToken)
            assertTrue("poToken should not be blank", poToken.isNotBlank())
            // Minted tokens are URL-safe base64 and are in practice well over 30 characters; a
            // short value means something returned a placeholder rather than a real token.
            assertTrue("poToken looks too short to be real: $poToken", poToken.length > 30)
            assertTrue(
                "poToken should be URL-safe base64, was: $poToken",
                poToken.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '=' },
            )
        } finally {
            withTimeout(TIMEOUT_MS) { generator.close() }
        }
    }

    /**
     * Stage two: the whole provider, including the visitorData fetched from InnerTube and the
     * ordering requirement that the streaming token is minted before any player token. This is the
     * shape NewPipeExtractor actually calls.
     */
    @Test
    fun providerReturnsACompletePoTokenResult() {
        NewPipe.init(NewPipeOkHttpDownloader(httpClient()))
        TGPoTokenProvider.initialize(context, httpClient())

        // Mirrors reality: NewPipeExtractor never calls this from the main thread, and the
        // provider refuses to run there anyway to avoid deadlocking against its own WebView.
        val result = AtomicReference<PoTokenResult?>(null)
        val worker = Thread { result.set(TGPoTokenProvider.getWebClientPoToken(TEST_VIDEO_ID)) }
        worker.start()
        worker.join(TIMEOUT_MS * 2)

        val poTokenResult = result.get()
        assertNotNull(
            "Provider returned null. Check logcat for BadWebViewException (device WebView too " +
                "old) versus a network or protocol error.",
            poTokenResult,
        )
        requireNotNull(poTokenResult)

        Log.i(
            TAG,
            "CHECKPOINT PASSED -- visitorData=${poTokenResult.visitorData} " +
                "playerPot=${poTokenResult.playerRequestPoToken} " +
                "streamingPot=${poTokenResult.streamingDataPoToken}",
        )

        assertTrue("visitorData should not be blank", poTokenResult.visitorData.isNotBlank())
        assertTrue(
            "playerRequestPoToken should not be blank",
            poTokenResult.playerRequestPoToken.isNotBlank(),
        )
        // streamingDataPoToken is @Nullable in the extractor API because it is optional there, but
        // this provider always supplies one -- a null here means the streaming token was never
        // minted, which would leave stream URLs unsigned.
        assertTrue(
            "streamingDataPoToken should not be null or blank",
            !poTokenResult.streamingDataPoToken.isNullOrBlank(),
        )
        // The two tokens are bound to different identifiers, so identical values would mean the
        // videoId and visitorData got crossed -- the mistake that produces 403ing stream URLs.
        assertTrue(
            "player and streaming poTokens should differ; identical values indicate the " +
                "identifiers were swapped",
            poTokenResult.playerRequestPoToken != poTokenResult.streamingDataPoToken,
        )
    }

    private companion object {
        const val TAG = "TGMusicPoToken"

        /** "Big Buck Bunny", a long-standing Creative Commons upload used as a stable fixture. */
        const val TEST_VIDEO_ID = "aqz-KE-bpKQ"

        const val TIMEOUT_MS = 60_000L
    }
}
