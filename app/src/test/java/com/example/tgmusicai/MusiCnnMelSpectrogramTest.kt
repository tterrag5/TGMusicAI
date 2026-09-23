package com.example.tgmusicai

import com.example.tgmusicai.ai.MusiCnnMelSpectrogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tests for the mel front-end feeding the EffNet-Discogs model.
 *
 * This is the part of the acoustic pipeline that fails silently. A wrong window, mel scale or
 * compression still produces a well-shaped array of plausible numbers, the model still returns
 * 1280 floats, and nothing looks broken -- the recommendations are just quietly worse. These tests
 * pin the geometry and check the output actually responds to the audio.
 */
class MusiCnnMelSpectrogramTest {

    @Test
    fun patchGeometryMatchesWhatTheModelExpects() {
        // The model's input is [n, 128, 96] at 16kHz. These are not tunable: they are the values
        // the model was trained with.
        assertEquals(16000, MusiCnnMelSpectrogram.SAMPLE_RATE)
        assertEquals(128, MusiCnnMelSpectrogram.PATCH_FRAMES)
        assertEquals(96, MusiCnnMelSpectrogram.MEL_BANDS)
        // 512-sample frames hopping 256 samples: the first frame plus a hop each for the rest.
        assertEquals(512 + 127 * 256, MusiCnnMelSpectrogram.PATCH_SAMPLES)
    }

    @Test
    fun producesCorrectlyShapedPatches() {
        val samples = tone(hz = 440.0, sampleCount = MusiCnnMelSpectrogram.PATCH_SAMPLES * 3)

        val patches = MusiCnnMelSpectrogram.patchesFrom(samples)

        assertEquals(3, patches.size)
        patches.forEach { patch ->
            assertEquals(128, patch.size)
            patch.forEach { frame -> assertEquals(96, frame.size) }
        }
    }

    @Test
    fun dropsAudioTooShortForAWholePatch() {
        // Padding a partial patch would feed the model silence it never saw in training.
        val tooShort = tone(hz = 440.0, sampleCount = MusiCnnMelSpectrogram.PATCH_SAMPLES - 1)
        assertTrue(MusiCnnMelSpectrogram.patchesFrom(tooShort).isEmpty())
        assertTrue(MusiCnnMelSpectrogram.patchesFrom(FloatArray(0)).isEmpty())
    }

    @Test
    fun outputIsFiniteAndNonNegative() {
        // The compression is log10(1 + 10000 * power) over non-negative power, so every value is
        // at least 0. A NaN here would poison the embedding and every similarity computed from it.
        val samples = noise(MusiCnnMelSpectrogram.PATCH_SAMPLES)

        val patch = MusiCnnMelSpectrogram.patchesFrom(samples).single()

        patch.forEach { frame ->
            frame.forEach { value ->
                assertTrue("Mel value $value is not finite", value.isFinite())
                assertTrue("Mel value $value is negative", value >= 0f)
            }
        }
    }

    @Test
    fun silenceCompressesToZero() {
        val patch = MusiCnnMelSpectrogram.patchesFrom(FloatArray(MusiCnnMelSpectrogram.PATCH_SAMPLES)).single()

        patch.forEach { frame ->
            frame.forEach { value -> assertEquals(0f, value, 1e-6f) }
        }
    }

    @Test
    fun aLowToneAndAHighToneLandInDifferentBands() {
        // The point of a mel filterbank is that pitch moves energy between bands. If the filters
        // were built wrong -- HTK instead of Slaney, unnormalized, misaligned -- this is where it
        // shows up.
        val low = MusiCnnMelSpectrogram.patchesFrom(tone(220.0, MusiCnnMelSpectrogram.PATCH_SAMPLES)).single()
        val high = MusiCnnMelSpectrogram.patchesFrom(tone(3520.0, MusiCnnMelSpectrogram.PATCH_SAMPLES)).single()

        val lowPeak = peakBand(low)
        val highPeak = peakBand(high)

        assertTrue(
            "A 220Hz tone peaked at band $lowPeak, a 3520Hz tone at band $highPeak",
            highPeak > lowPeak,
        )
        // Four octaves up should be a long way up the filterbank, not a band or two.
        assertTrue("Peaks are only ${highPeak - lowPeak} bands apart", highPeak - lowPeak > 20)
    }

    @Test
    fun theSameAudioAlwaysProducesTheSameMel() {
        // Recommendations have to be reproducible, and everything downstream of here is
        // deterministic only if this is.
        val samples = noise(MusiCnnMelSpectrogram.PATCH_SAMPLES)

        val first = MusiCnnMelSpectrogram.patchesFrom(samples).single()
        val second = MusiCnnMelSpectrogram.patchesFrom(samples).single()

        first.indices.forEach { row ->
            assertTrue(first[row].contentEquals(second[row]))
        }
    }

    private fun peakBand(patch: Array<FloatArray>): Int {
        val summed = FloatArray(MusiCnnMelSpectrogram.MEL_BANDS)
        patch.forEach { frame -> frame.forEachIndexed { band, value -> summed[band] += value } }
        return summed.indices.maxBy { summed[it] }
    }

    private fun tone(hz: Double, sampleCount: Int) = FloatArray(sampleCount) { i ->
        (0.5 * sin(2.0 * PI * hz * i / MusiCnnMelSpectrogram.SAMPLE_RATE)).toFloat()
    }

    private fun noise(sampleCount: Int): FloatArray {
        val random = Random(42)
        return FloatArray(sampleCount) { (random.nextGaussian() * 0.2).toFloat() }
    }
}
