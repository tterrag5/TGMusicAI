package com.example.tgmusicai.ai

import com.example.tgmusicai.ai.whisper.WhisperFft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.exp

/**
 * Builds the mel-spectrogram patches that [MusicEmbeddingEngine]'s EffNet-Discogs model expects.
 *
 * These parameters are not tunable. They mirror Essentia's `TensorflowInputMusiCNN`, which is what
 * the model was trained against, and that algorithm hardcodes them for exactly this reason -- the
 * source comment says "hardcoded to make sure they match the values used on training". Feeding the
 * model a mel spectrogram built any other way produces embeddings that look plausible and mean
 * nothing.
 *
 * The chain is: 512-sample Hann-windowed frames every 256 samples at 16kHz, magnitude spectrum,
 * squared to power, 96 Slaney-scale mel bands with unit-triangle normalization, then
 * `log10(1 + 10000 * band)`. Frames are grouped into patches of 128.
 *
 * Separate from [com.example.tgmusicai.ai.whisper.WhisperMelSpectrogram], which implements a
 * different model's front-end (80 HTK-scale bands, different frame geometry, different
 * compression). The FFT underneath is shared.
 */
internal object MusiCnnMelSpectrogram {

    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE = 512
    const val HOP_SIZE = 256
    const val MEL_BANDS = 96

    /** Frames per patch. One patch is about 2.1 seconds of audio. */
    const val PATCH_FRAMES = 128

    /** Samples one patch consumes: the first frame plus a hop for each frame after it. */
    const val PATCH_SAMPLES = FRAME_SIZE + (PATCH_FRAMES - 1) * HOP_SIZE

    private const val LOG_SHIFT = 1.0
    private const val LOG_SCALE = 10000.0

    private val hannWindow = DoubleArray(FRAME_SIZE) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / FRAME_SIZE)
    }

    private val melFilters: Array<DoubleArray> by lazy { buildSlaneyMelFilters() }

    private val fft = WhisperFft(FRAME_SIZE)

    /**
     * Turns [samples] (16kHz mono) into patches of shape `[PATCH_FRAMES][MEL_BANDS]`, ready to be
     * batched into the model. Returns an empty list when there is not enough audio for one patch.
     *
     * A trailing partial patch is dropped rather than zero-padded: padding would feed the model
     * silence it was never trained to see, and a track long enough to matter always yields whole
     * patches anyway.
     */
    fun patchesFrom(samples: FloatArray): List<Array<FloatArray>> {
        if (samples.size < PATCH_SAMPLES) return emptyList()

        val frameCount = (samples.size - FRAME_SIZE) / HOP_SIZE + 1
        val patchCount = frameCount / PATCH_FRAMES
        if (patchCount == 0) return emptyList()

        val re = DoubleArray(FRAME_SIZE)
        val im = DoubleArray(FRAME_SIZE)
        val windowed = DoubleArray(FRAME_SIZE)
        val spectrumBins = FRAME_SIZE / 2 + 1
        val power = DoubleArray(spectrumBins)

        val patches = ArrayList<Array<FloatArray>>(patchCount)
        var frameIndex = 0
        repeat(patchCount) {
            val patch = Array(PATCH_FRAMES) { FloatArray(MEL_BANDS) }
            for (row in 0 until PATCH_FRAMES) {
                val offset = frameIndex * HOP_SIZE
                for (i in 0 until FRAME_SIZE) {
                    windowed[i] = samples[offset + i].toDouble() * hannWindow[i]
                }
                fft.transform(windowed, re, im)
                for (bin in 0 until spectrumBins) {
                    // Essentia's Spectrum emits magnitude; MelBands then squares it to power.
                    power[bin] = re[bin] * re[bin] + im[bin] * im[bin]
                }
                for (band in 0 until MEL_BANDS) {
                    val filter = melFilters[band]
                    var sum = 0.0
                    for (bin in 0 until spectrumBins) {
                        val weight = filter[bin]
                        if (weight != 0.0) sum += weight * power[bin]
                    }
                    patch[row][band] = log10(LOG_SHIFT + LOG_SCALE * sum).toFloat()
                }
                frameIndex++
            }
            patches.add(patch)
        }
        return patches
    }

    /**
     * Slaney-scale triangular mel filters, area-normalized ("unit_tri" in Essentia's terms).
     *
     * Slaney's scale, not the HTK formula: linear spacing below 1kHz and logarithmic above it.
     * Getting this wrong is silent -- the filters still look like filters, the model still returns
     * numbers, and only the quality of the results suffers.
     */
    private fun buildSlaneyMelFilters(): Array<DoubleArray> {
        val spectrumBins = FRAME_SIZE / 2 + 1
        val lowMel = hzToSlaneyMel(0.0)
        val highMel = hzToSlaneyMel(SAMPLE_RATE / 2.0)

        // Band edges: a triangle needs its two neighbours, so there are MEL_BANDS + 2 of them.
        val edgeHz = DoubleArray(MEL_BANDS + 2) { i ->
            slaneyMelToHz(lowMel + (highMel - lowMel) * i / (MEL_BANDS + 1))
        }
        val binHz = DoubleArray(spectrumBins) { i -> i.toDouble() * SAMPLE_RATE / FRAME_SIZE }

        return Array(MEL_BANDS) { band ->
            val left = edgeHz[band]
            val centre = edgeHz[band + 1]
            val right = edgeHz[band + 2]
            // Unit-triangle normalization: each filter integrates to 1 regardless of its width,
            // so wide high-frequency bands do not drown out narrow low-frequency ones.
            val area = 2.0 / (right - left)

            DoubleArray(spectrumBins) { bin ->
                val hz = binHz[bin]
                when {
                    hz in left..centre && centre > left -> area * (hz - left) / (centre - left)
                    hz in centre..right && right > centre -> area * (right - hz) / (right - centre)
                    else -> 0.0
                }
            }
        }
    }

    // Slaney's mel scale: linear up to 1kHz, logarithmic above.
    private const val SLANEY_MIN_HZ = 0.0
    private const val SLANEY_LINEAR_SLOPE = 3.0 / 200.0
    private const val SLANEY_BREAK_HZ = 1000.0
    private const val SLANEY_BREAK_MEL = (SLANEY_BREAK_HZ - SLANEY_MIN_HZ) * SLANEY_LINEAR_SLOPE
    private val SLANEY_LOG_STEP = ln(6.4) / 27.0

    private fun hzToSlaneyMel(hz: Double): Double =
        if (hz < SLANEY_BREAK_HZ) {
            (hz - SLANEY_MIN_HZ) * SLANEY_LINEAR_SLOPE
        } else {
            SLANEY_BREAK_MEL + ln(hz / SLANEY_BREAK_HZ) / SLANEY_LOG_STEP
        }

    private fun slaneyMelToHz(mel: Double): Double =
        if (mel < SLANEY_BREAK_MEL) {
            SLANEY_MIN_HZ + mel / SLANEY_LINEAR_SLOPE
        } else {
            SLANEY_BREAK_HZ * exp(SLANEY_LOG_STEP * (mel - SLANEY_BREAK_MEL))
        }
}
