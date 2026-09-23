package com.example.tgmusicai

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.example.tgmusicai.playback.AudioCacheManager
import com.example.tgmusicai.playback.SchemeAwareCacheDataSourceFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Proves that audio actually plays, not merely that a URL was resolved.
 *
 * Both cases here are required, and testing only one proves nothing. `CLAUDE.md` records why: a
 * past change to this layer wired the stream cache in as ExoPlayer's single data source, which
 * broke local playback completely while streamed playback kept working. These tests therefore run
 * a real [ExoPlayer] through the real [SchemeAwareCacheDataSourceFactory] -- the component whose
 * whole job is to send `http(s)` through the disk cache and everything else through a plain
 * source -- and assert both paths reach [Player.STATE_READY].
 *
 * Reaching STATE_READY means ExoPlayer opened the source, read a container it understands, and
 * prepared a decoder, which is the meaningful definition of "this would play for a user".
 *
 * The cloud case needs real network access to YouTube; the local case does not.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackVerificationTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /**
     * Resolves a cloud track and plays it.
     *
     * A signed `googlevideo` URL can be rejected with HTTP 403 even when it was valid moments
     * earlier -- YouTube throttles repeated fetches of the same track from one address, which this
     * suite provokes by design. That is a property of the dependency, not a defect, and the
     * correct response to it is to resolve again rather than to declare playback broken, so the
     * test does exactly that once before failing.
     */
    @Test
    fun playsAResolvedCloudStream() = runBlocking {
        val failure = attemptCloudPlayback() ?: return@runBlocking
        Log.w(TAG, "First cloud playback attempt failed (${failure.message}); re-resolving once")

        val secondFailure = attemptCloudPlayback()
        if (secondFailure != null) throw secondFailure
    }

    /** Returns null on success, or the failure to report if playback did not become ready. */
    private suspend fun attemptCloudPlayback(): AssertionError? {
        val stream = withTimeout(RESOLVE_TIMEOUT_MS) {
            YouTubeExtractor().extractAudioStream(TEST_VIDEO_ID)
        }
        assertNotNull("Could not resolve a stream, so playback cannot be tested", stream)
        requireNotNull(stream)

        Log.i(TAG, "Playing resolved cloud stream (${stream.format}, ${stream.bitrate}kbps)")
        return try {
            assertPlaysToReady(Uri.parse(stream.url), "cloud stream")
            null
        } catch (e: AssertionError) {
            e
        }
    }

    /**
     * The other half of the requirement above. Uses a generated WAV rather than relying on
     * whatever happens to be in the emulator's media store, so the test is self-contained and
     * cannot pass or fail for reasons unrelated to the data-source routing.
     */
    @Test
    fun playsALocalFile() {
        val localFile = File(context.cacheDir, "playback_verification_tone.wav")
        localFile.writeBytes(oneSecondOfSilenceAsWav())

        Log.i(TAG, "Playing local file ${localFile.absolutePath} (${localFile.length()} bytes)")
        assertPlaysToReady(Uri.fromFile(localFile), "local file")

        localFile.delete()
    }

    /** Prepares [uri] on a real ExoPlayer and asserts it reaches [Player.STATE_READY]. */
    private fun assertPlaysToReady(uri: Uri, label: String) {
        val ready = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>(null)
        val playerRef = AtomicReference<ExoPlayer?>(null)

        // ExoPlayer is single-threaded and must be built and driven from one looper thread.
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        SchemeAwareCacheDataSourceFactory(
                            context,
                            AudioCacheManager.getCache(context),
                        ),
                    ),
                )
                .build()
            playerRef.set(player)

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure.set(error)
                    ready.countDown()
                }
            })

            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }

        val reachedTerminalState = ready.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        instrumentation.runOnMainSync { playerRef.get()?.release() }

        failure.get()?.let { error ->
            throw AssertionError("ExoPlayer failed to play the $label: ${error.errorCodeName}", error)
        }
        assertTrue(
            "ExoPlayer never became ready for the $label within ${PREPARE_TIMEOUT_SECONDS}s",
            reachedTerminalState,
        )
        Log.i(TAG, "PLAYBACK OK -- $label reached STATE_READY")
    }

    /**
     * Builds a minimal valid 16-bit PCM mono WAV. Silence is sufficient: the assertion is about
     * ExoPlayer opening and preparing the source, not about what can be heard.
     */
    private fun oneSecondOfSilenceAsWav(): ByteArray {
        val sampleRate = 44_100
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = byteRate // exactly one second
        val output = ByteArrayOutputStream()

        fun littleEndianInt(value: Int) = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

        fun littleEndianShort(value: Int) = ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

        output.write("RIFF".toByteArray())
        output.write(littleEndianInt(36 + dataSize))
        output.write("WAVE".toByteArray())
        output.write("fmt ".toByteArray())
        output.write(littleEndianInt(16))          // PCM header size
        output.write(littleEndianShort(1))         // PCM format
        output.write(littleEndianShort(channels))
        output.write(littleEndianInt(sampleRate))
        output.write(littleEndianInt(byteRate))
        output.write(littleEndianShort(channels * bitsPerSample / 8))
        output.write(littleEndianShort(bitsPerSample))
        output.write("data".toByteArray())
        output.write(littleEndianInt(dataSize))
        output.write(ByteArray(dataSize))

        return output.toByteArray()
    }

    private companion object {
        const val TAG = "TGMusicCloud"
        const val TEST_VIDEO_ID = "aqz-KE-bpKQ"
        const val RESOLVE_TIMEOUT_MS = 120_000L
        const val PREPARE_TIMEOUT_SECONDS = 60L
    }
}
