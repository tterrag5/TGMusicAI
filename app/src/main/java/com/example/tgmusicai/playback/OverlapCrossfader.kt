package com.example.tgmusicai.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-power crossfade arithmetic, apart from the players so it can be unit-tested.
 *
 * Gains are linear amplitude, which is what `Player.volume` is: cos/sin keeps the summed power
 * constant through the fade (cos² + sin² = 1), so the overlap neither dips nor swells.
 */
object CrossfadeCurve {

    /** Outgoing track's gain at [fraction] (0..1) of the fade. */
    fun outGain(fraction: Float): Float = cos(fraction.coerceIn(0f, 1f) * PI / 2).toFloat()

    /** Incoming track's gain at [fraction] (0..1) of the fade. */
    fun inGain(fraction: Float): Float = sin(fraction.coerceIn(0f, 1f) * PI / 2).toFloat()

    /**
     * Where in the outgoing track the handover happens, or null when there is no room for a fade:
     * [fadeMs] before the end, but never earlier than [leadMs] after [positionMs], which the tail
     * player needs to buffer before it can take over.
     */
    fun handoverAt(positionMs: Long, durationMs: Long, fadeMs: Long, leadMs: Long): Long? {
        if (durationMs <= 0 || fadeMs <= 0) return null
        val at = maxOf(durationMs - fadeMs, positionMs + leadMs)
        return if (durationMs - at >= MIN_FADE_MS) at else null
    }

    /** A fade shorter than this is not worth a second player; the track just ends. */
    const val MIN_FADE_MS = 1_000L
}

/**
 * True overlapping crossfade between two tracks.
 *
 * The main player stays the only thing the session, the queue, Cast and every controller know
 * about. Near the end of a track, a second "tail" player picks up the outgoing track at the same
 * position, and at the handover instant the main player moves to the next item. For the fade both
 * play at once: the tail fading out, the main player fading in. The tail is then stopped. Nothing
 * else sees the tail, so nothing else had to change.
 *
 * The tail has its own [ReplayGainAudioProcessor] carrying the outgoing track's gain, since the
 * main player's processor switches to the incoming track's gain at the handover. It never takes
 * audio focus; the main player holds it throughout.
 *
 * Any interruption -- the user skipping, pausing or seeking during the fade, an error, the queue
 * changing -- cancels the fade: the tail stops and the main player returns to full volume.
 */
@UnstableApi
class OverlapCrossfader(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
    private val scope: CoroutineScope,
) {

    private val tailGain = ReplayGainAudioProcessor()
    private var tail: ExoPlayer? = null
    private var job: Job? = null

    /** Volume the main player had before the fade, restored when it ends or is cancelled. */
    private var baseVolume = 1f

    /** The media id the current fade was started for, so it starts once per track. */
    private var startedForMediaId: String? = null

    /** The item the main player is moved to at the handover, so that transition is known to be ours. */
    private var handoverTargetId: String? = null

    /** Whether [mediaItem] arriving on the main player is this fade's own handover. */
    fun isOwnTransition(mediaItem: MediaItem?): Boolean =
        isActive && mediaItem != null && mediaItem.mediaId == handoverTargetId

    val isActive: Boolean get() = job?.isActive == true

    /**
     * Starts a crossfade into the next item if [main] is within reach of its handover point.
     * Call on the playback ticker; it does nothing when a fade is not due or not possible.
     */
    fun maybeStart(main: ExoPlayer, fadeMs: Long, outgoingScale: Float) {
        if (isActive) return
        val item = main.currentMediaItem ?: return
        if (item.mediaId == startedForMediaId) return
        if (!main.isPlaying || !main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) return
        // Positions only line up between the two players at normal speed.
        if (main.playbackParameters.speed != 1f) return
        val next = main.getMediaItemAt(main.nextMediaItemIndex)
        // An unresolved watch URL would leave the incoming side silent while the outgoing fades.
        val nextUri = next.localConfiguration?.uri?.toString() ?: next.mediaId
        if (nextUri.startsWith("https://www.youtube.com/watch") || nextUri.startsWith("https://youtu.be/")) return

        val position = main.currentPosition
        val duration = main.duration
        if (duration == C.TIME_UNSET) return
        if (duration - position > fadeMs + START_AHEAD_MS) return
        val handover = CrossfadeCurve.handoverAt(position, duration, fadeMs, LEAD_MS) ?: run {
            startedForMediaId = item.mediaId
            return
        }
        startedForMediaId = item.mediaId
        run(main, item, handover, duration - handover, outgoingScale)
    }

    private fun run(main: ExoPlayer, item: MediaItem, handoverMs: Long, fadeMs: Long, outgoingScale: Float) {
        handoverTargetId = main.getMediaItemAt(main.nextMediaItemIndex).mediaId
        job = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val tailPlayer = tail ?: buildTail().also { tail = it }
            try {
                tailGain.setTrackScale(outgoingScale)
                baseVolume = main.volume
                tailPlayer.volume = baseVolume
                tailPlayer.setMediaItem(item, handoverMs)
                tailPlayer.playWhenReady = false
                tailPlayer.prepare()

                // Wait for the tail to be buffered at the handover point and for the main player
                // to reach it. Bail out if the main player stops being on this track.
                while (isActive) {
                    if (main.currentMediaItem?.mediaId != item.mediaId || !main.isPlaying) {
                        cancel(main); return@launch
                    }
                    if (main.currentPosition >= handoverMs) break
                    delay(POLL_MS)
                }
                if (tailPlayer.playbackState != Player.STATE_READY) {
                    Log.d(TAG, "Tail not ready at handover; letting the track end normally")
                    cancel(main); return@launch
                }

                // The handover: the tail continues the outgoing track, the main player moves on.
                tailPlayer.play()
                main.volume = 0f
                main.seekToNextMediaItem()
                Log.d(TAG, "Handover at ${handoverMs}ms into ${item.mediaId}; fading over ${fadeMs}ms")

                val startedAt = System.currentTimeMillis()
                while (isActive) {
                    val fraction = ((System.currentTimeMillis() - startedAt).toFloat() / fadeMs).coerceIn(0f, 1f)
                    main.volume = baseVolume * CrossfadeCurve.inGain(fraction)
                    tailPlayer.volume = baseVolume * CrossfadeCurve.outGain(fraction)
                    if (fraction >= 1f || tailPlayer.playbackState == Player.STATE_ENDED) break
                    delay(FADE_STEP_MS)
                }
                Log.d(TAG, "Fade complete")
                finish(main)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Crossfade failed; restoring normal playback", e)
                finish(main)
            }
        }
    }

    /** Ends a fade early, e.g. on a user skip, pause or seek: the tail stops, the main player is restored. */
    fun cancel(main: Player) {
        val wasActive = job?.isActive == true
        job?.cancel()
        job = null
        if (wasActive || tail?.isPlaying == true) finish(main)
    }

    private fun finish(main: Player) {
        tail?.let {
            it.stop()
            it.clearMediaItems()
        }
        main.volume = baseVolume
    }

    fun release() {
        job?.cancel()
        job = null
        tail?.release()
        tail = null
    }

    private fun buildTail(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        arrayOf(tailGain),
                        SilenceSkippingAudioProcessor(),
                        SonicAudioProcessor()
                    )
                )
                .build()
        }
        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // The main player owns audio focus; a second request would make the two duck or
                // pause each other.
                false
            )
            .build()
    }

    companion object {
        private const val TAG = "OverlapCrossfader"

        /** Time the tail gets to buffer the outgoing track before the handover. */
        const val LEAD_MS = 1_500L

        /** How far ahead of the handover [maybeStart] begins preparing, given a 500ms ticker. */
        const val START_AHEAD_MS = 2_500L

        private const val POLL_MS = 10L
        private const val FADE_STEP_MS = 40L
    }
}
