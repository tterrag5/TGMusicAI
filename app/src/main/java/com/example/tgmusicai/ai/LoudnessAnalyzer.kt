package com.example.tgmusicai.ai

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Measures how loud a track actually sounds, so playback can even out the volume difference
 * between a quiet local rip and a loud YouTube stream.
 *
 * Implements the gated loudness measurement from ITU-R BS.1770 / EBU R128: audio is K-weighted
 * (a high-shelf plus a high-pass, approximating how the ear weights frequency), split into
 * overlapping 400 ms blocks, and averaged with two gates applied -- an absolute one that discards
 * near-silence, and a relative one that discards quiet passages so a track's loudness reflects its
 * body rather than its fade-outs. A plain RMS average, which is the obvious thing to reach for
 * here, rates a bass-heavy track far louder than it sounds and would normalize it wrongly.
 *
 * Two deliberate approximations, both of which cost accuracy but not usefulness:
 *
 * 1. The measurement runs on the same 16 kHz mono PCM the other on-device analyses already decode,
 *    rather than decoding the track a third time at full rate. Content above 8 kHz is therefore
 *    missing from the measurement. This biases every track's reading the same direction by roughly
 *    the same amount, and what matters here is how tracks compare to each other, not the absolute
 *    LUFS figure -- so the bias largely cancels out of the gain differences that get applied.
 * 2. Peak is the highest sample of the mono downmix, not a true inter-sample peak. A downmix can
 *    read lower than the loudest individual channel, which would let the limiter allow too much
 *    boost, so [SAFETY_MARGIN] inflates the measured value before it is used.
 *
 * Pure computation with no model behind it: it cannot fail the way a TFLite or ONNX engine can,
 * but it still returns null rather than throwing, matching how everything else in this package
 * behaves toward its caller.
 */
object LoudnessAnalyzer {

    private const val TAG = "LoudnessAnalyzer"

    /**
     * Loudness that a ReplayGain value is defined to bring a track to. ReplayGain 2.0 fixes this
     * at -18 LUFS, and [com.example.tgmusicai.playback.ReplayGainAudioProcessor] applies the
     * remaining offset to reach a streaming-style target, so values measured here and values read
     * from a file's own tag mean the same thing and can be mixed freely in one library.
     */
    const val REFERENCE_LUFS = -18.0

    /** Blocks quieter than this contribute nothing -- silence must not drag a track's loudness down. */
    private const val ABSOLUTE_GATE_LUFS = -70.0

    /** Blocks more than this far below the ungated average are dropped, per EBU R128's relative gate. */
    private const val RELATIVE_GATE_LU = -10.0

    /** R128 measures over 400 ms windows, advancing a quarter of a window at a time (75% overlap). */
    private const val BLOCK_MS = 400
    private const val OVERLAP_DIVISOR = 4

    /** Offset in the BS.1770 loudness formula, calibrating K-weighted mean square to LKFS. */
    private const val LOUDNESS_OFFSET = -0.691

    /** See the class docs: compensates for a mono downmix reading below the true per-channel peak. */
    private const val SAFETY_MARGIN = 1.414f

    /**
     * How much of a track to measure, in seconds. [PcmDecoder] samples from a quarter of the way
     * in for anything longer than twice this, which skips intros and lands on the track's body.
     * Measuring the whole file would be more faithful and several times slower for a result that
     * barely moves.
     */
    const val ANALYSIS_WINDOW_SEC = 120

    /** A track's measured loudness and peak, in the units [com.example.tgmusicai.data.local.entity.Song] stores. */
    data class Loudness(
        /** Gain in dB that brings this track to [REFERENCE_LUFS]. Negative for loud tracks. */
        val gainDb: Float,
        /** Highest sample seen, as a fraction of full scale, already including [SAFETY_MARGIN]. */
        val peak: Float,
        /** The raw integrated loudness the gain was derived from, kept for logging and diagnostics. */
        val integratedLufs: Float
    )

    /**
     * Measures [filePath], returning null if it cannot be decoded, is too short to yield a single
     * 400 ms block, or contains nothing above the silence gate.
     */
    fun analyzeFile(filePath: String): Loudness? {
        val samples = try {
            PcmDecoder.decodeToMonoPcm16k(filePath, ANALYSIS_WINDOW_SEC)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not decode $filePath for loudness measurement", e)
            null
        } ?: return null
        return analyzeSamples(samples, SAMPLE_RATE)
    }

    private const val SAMPLE_RATE = 16000

    /**
     * The measurement itself, split out from decoding so it can be unit-tested against synthetic
     * signals of known loudness.
     */
    fun analyzeSamples(samples: FloatArray, sampleRate: Int): Loudness? {
        if (samples.isEmpty() || sampleRate <= 0) return null

        var rawPeak = 0f
        for (sample in samples) {
            val magnitude = abs(sample)
            if (magnitude > rawPeak) rawPeak = magnitude
        }
        if (rawPeak <= 0f) return null

        val weighted = applyKWeighting(samples, sampleRate)

        val blockSize = sampleRate * BLOCK_MS / 1000
        val step = blockSize / OVERLAP_DIVISOR
        if (weighted.size < blockSize || step <= 0) return null

        // Mean square per block, which both gating passes then filter before averaging.
        val blockMeanSquares = ArrayList<Double>()
        var start = 0
        while (start + blockSize <= weighted.size) {
            var sum = 0.0
            for (i in start until start + blockSize) {
                val value = weighted[i].toDouble()
                sum += value * value
            }
            blockMeanSquares.add(sum / blockSize)
            start += step
        }
        if (blockMeanSquares.isEmpty()) return null

        val aboveAbsolute = blockMeanSquares.filter { meanSquareToLufs(it) > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) return null

        val relativeThreshold = meanSquareToLufs(aboveAbsolute.average()) + RELATIVE_GATE_LU
        val gated = aboveAbsolute.filter { meanSquareToLufs(it) > relativeThreshold }
        val finalBlocks = gated.ifEmpty { aboveAbsolute }

        val integrated = meanSquareToLufs(finalBlocks.average())
        if (!integrated.isFinite()) return null

        return Loudness(
            gainDb = (REFERENCE_LUFS - integrated).toFloat(),
            peak = (rawPeak * SAFETY_MARGIN).coerceIn(0f, 1f),
            integratedLufs = integrated.toFloat()
        )
    }

    private fun meanSquareToLufs(meanSquare: Double): Double =
        if (meanSquare <= 0.0) Double.NEGATIVE_INFINITY else LOUDNESS_OFFSET + 10.0 * log10(meanSquare)

    /**
     * Runs the two BS.1770 pre-filter stages over [samples].
     *
     * The spec publishes its coefficients for 48 kHz only. Rather than resample to meet them, both
     * stages are rebuilt from the analog prototypes they come from, which yields the published
     * numbers at 48 kHz and correct ones at any other rate -- necessary here, where the audio
     * arrives at 16 kHz.
     */
    private fun applyKWeighting(samples: FloatArray, sampleRate: Int): FloatArray {
        val shelf = highShelfStage(sampleRate)
        val highPass = highPassStage(sampleRate)
        return biquad(biquad(samples, shelf), highPass)
    }

    /** Normalized biquad coefficients, with `a0` already divided out of every term. */
    private data class Biquad(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    /** Stage 1: the high-frequency shelf accounting for the acoustic effect of the head. */
    private fun highShelfStage(sampleRate: Int): Biquad {
        val f0 = 1681.974450955533
        val gainDb = 3.999843853973347
        val q = 0.7071752369554196

        val k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(gainDb / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val denominator = 1.0 + k / q + k * k

        return Biquad(
            b0 = (vh + vb * k / q + k * k) / denominator,
            b1 = 2.0 * (k * k - vh) / denominator,
            b2 = (vh - vb * k / q + k * k) / denominator,
            a1 = 2.0 * (k * k - 1.0) / denominator,
            a2 = (1.0 - k / q + k * k) / denominator
        )
    }

    /** Stage 2: the RLB high-pass, which removes low frequencies the ear barely registers as loudness. */
    private fun highPassStage(sampleRate: Int): Biquad {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773

        val k = tan(PI * f0 / sampleRate)
        val denominator = 1.0 + k / q + k * k

        return Biquad(
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a1 = 2.0 * (k * k - 1.0) / denominator,
            a2 = (1.0 - k / q + k * k) / denominator
        )
    }

    /** Direct Form I, which keeps the published coefficients readable against the spec. */
    private fun biquad(input: FloatArray, coefficients: Biquad): FloatArray {
        val output = FloatArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in input.indices) {
            val x0 = input[i].toDouble()
            val y0 = coefficients.b0 * x0 + coefficients.b1 * x1 + coefficients.b2 * x2 -
                coefficients.a1 * y1 - coefficients.a2 * y2
            output[i] = y0.toFloat()
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return output
    }
}
