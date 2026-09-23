package com.example.tgmusicai.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Applies a per-track volume scale to 16-bit PCM audio on its way to the audio sink, so a quiet
 * local FLAC and a loud YouTube stream play back at the same perceived loudness.
 *
 * This replaces what a fixed [android.media.audiofx.LoudnessEnhancer] gain could do. That effect
 * applies the *same* boost to everything, which raises the whole library's volume without making
 * any two tracks match each other -- the loud track stays louder by exactly as much as before.
 * Normalization has to be per-track, which means the scale changes as the queue advances: call
 * [setTrackGain] on every media item transition.
 *
 * The scale is read on the audio thread and written from the playback thread, hence [Volatile].
 * A gain of exactly 1.0 still flows through this processor rather than bypassing it -- toggling
 * [isActive] mid-stream would force the sink to reconfigure between tracks, and the copy costs
 * far less than that does.
 */
@UnstableApi
class ReplayGainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var scale: Float = 1f

    /**
     * Sets the linear amplitude multiplier for the track that is about to play, derived from its
     * ReplayGain value by [gainToScale]. Takes effect on the next buffer processed; there is no
     * ramp, because the change lands during the silence between tracks.
     */
    fun setTrackScale(scale: Float) {
        this.scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** Restores unmodified playback, for a track whose loudness is unknown or when the user turns normalization off. */
    fun clearTrackScale() {
        scale = 1f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Only 16-bit PCM is handled. Returning NOT_SET marks this processor inactive so the sink
        // bypasses it entirely rather than failing playback -- which is what would happen on a
        // device negotiating float or 24-bit output for a high-resolution track.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val byteCount = limit - position
        if (byteCount <= 0) return

        val output = replaceOutputBuffer(byteCount).order(ByteOrder.LITTLE_ENDIAN)
        val currentScale = scale

        if (currentScale == 1f) {
            output.put(inputBuffer)
        } else {
            val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            var i = position
            while (i < limit - 1) {
                val sample = input.getShort(i)
                val scaled = sample * currentScale
                // Hard-clamp rather than wrap: an int16 overflow wraps to the opposite polarity,
                // which is a loud click, while a clamp is at worst mild distortion on a peak the
                // limiter in gainToScale should already have prevented reaching.
                val clamped = when {
                    scaled > Short.MAX_VALUE -> Short.MAX_VALUE
                    scaled < Short.MIN_VALUE -> Short.MIN_VALUE
                    else -> scaled.toInt().toShort()
                }
                output.putShort(clamped)
                i += 2
            }
            inputBuffer.position(limit)
        }

        output.flip()
    }

    companion object {
        /**
         * ReplayGain 2.0 measures loudness against a -18 LUFS reference, so a track's stored gain
         * brings it to -18 LUFS. Streaming services target roughly -14 LUFS, which is what the
         * YouTube half of this app's library is already mastered toward, so the two halves only
         * match if the local half is lifted by the 4 dB difference.
         */
        const val REFERENCE_OFFSET_DB = 4f

        /**
         * Ceiling on how much a track may be boosted, in dB. A very quiet recording can carry a
         * gain of +15 dB or more, and applying all of it amplifies its noise floor along with the
         * music. Capping trades perfect loudness matching on outliers for never making a track
         * sound worse than it does unprocessed.
         */
        const val MAX_BOOST_DB = 12f

        /** Matching floor on attenuation, which mainly guards against a corrupt or absurd tag value. */
        const val MAX_CUT_DB = -24f

        /**
         * Peak assumed for a track whose real peak was never measured. Most modern masters sit
         * very close to full scale, so assuming they do is the safe guess: it under-boosts a
         * quiet track slightly rather than clipping a loud one.
         */
        const val ASSUMED_PEAK = 0.99f

        /** Headroom left below full scale, so a clamp in [queueInput] stays a last resort. */
        private const val PEAK_CEILING = 0.99f

        private val MIN_SCALE = 10f.pow(MAX_CUT_DB / 20f)
        private val MAX_SCALE = 10f.pow(MAX_BOOST_DB / 20f)

        /**
         * Converts a track's ReplayGain value into the linear multiplier [setTrackScale] takes.
         *
         * [gainDb] is the track's stored ReplayGain (negative for loud tracks). [peak] is its
         * highest sample as a fraction of full scale, used as a limiter: boosting a track whose
         * peak is already at 0.95 by 6 dB would drive it well past full scale and clip, so the
         * boost is cut to whatever still fits under [PEAK_CEILING]. Attenuation is never limited,
         * since making a track quieter cannot clip.
         */
        fun gainToScale(gainDb: Float, peak: Float?): Float {
            val requestedDb = (gainDb + REFERENCE_OFFSET_DB).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
            val requestedScale = 10f.pow(requestedDb / 20f)
            if (requestedScale <= 1f) return requestedScale
            val effectivePeak = (peak ?: ASSUMED_PEAK).coerceIn(0.001f, 1f)
            val peakLimitedScale = PEAK_CEILING / effectivePeak
            return minOf(requestedScale, maxOf(peakLimitedScale, 1f))
        }
    }
}
