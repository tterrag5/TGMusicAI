package com.example.tgmusicai

import com.example.tgmusicai.ai.LoudnessAnalyzer
import com.example.tgmusicai.playback.ReplayGainAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Covers the two halves of volume normalization that are pure computation: turning a stored
 * ReplayGain value into a playback multiplier, and measuring loudness from raw samples.
 *
 * Both are worth testing precisely because their failures are quiet. A gain applied in the wrong
 * direction, or a limiter that fails to hold a boost under full scale, does not throw -- it just
 * makes playback sound wrong, which no other test in this suite would catch.
 */
class VolumeNormalizationTest {

    // --- ReplayGainAudioProcessor.gainToScale -------------------------------------------------

    @Test
    fun `a track at the reference level is still lifted to the streaming target`() {
        // A gain of 0 dB means "already at the ReplayGain reference of -18 LUFS". Playback targets
        // roughly -14 LUFS, so such a track must come up by the 4 dB between the two, not stay put.
        val scale = ReplayGainAudioProcessor.gainToScale(0f, peak = 0.1f)
        val expected = Math.pow(10.0, (ReplayGainAudioProcessor.REFERENCE_OFFSET_DB / 20f).toDouble()).toFloat()
        assertEquals(expected, scale, 0.001f)
    }

    @Test
    fun `a loud track is attenuated`() {
        // Modern masters are typically several dB above the reference, carrying a negative gain.
        val scale = ReplayGainAudioProcessor.gainToScale(-10f, peak = 1.0f)
        assertTrue("A track louder than reference must be turned down, got $scale", scale < 1f)
    }

    @Test
    fun `attenuation is never limited by the peak`() {
        // Making a track quieter cannot clip, so a peak already at full scale must not interfere.
        val withFullPeak = ReplayGainAudioProcessor.gainToScale(-10f, peak = 1.0f)
        val withLowPeak = ReplayGainAudioProcessor.gainToScale(-10f, peak = 0.1f)
        assertEquals(withFullPeak, withLowPeak, 0.0001f)
    }

    @Test
    fun `boosting a track whose peak is near full scale cannot drive it past full scale`() {
        val peak = 0.95f
        val scale = ReplayGainAudioProcessor.gainToScale(12f, peak = peak)
        assertTrue(
            "Scaled peak ${peak * scale} exceeded full scale",
            peak * scale <= 1.0f
        )
    }

    @Test
    fun `an unknown peak still leaves headroom`() {
        // With no measured peak the limiter has to assume the track is close to full scale, which
        // is what almost every modern master is.
        val scale = ReplayGainAudioProcessor.gainToScale(12f, peak = null)
        assertTrue(
            "Assumed-peak boost of $scale would clip a typical master",
            ReplayGainAudioProcessor.ASSUMED_PEAK * scale <= 1.0f
        )
    }

    @Test
    fun `an absurd tag value is clamped rather than trusted`() {
        // Corrupt or mis-parsed tags do occur; applying +80 dB would be deafening.
        val scale = ReplayGainAudioProcessor.gainToScale(80f, peak = 0.01f)
        val ceiling = Math.pow(10.0, (ReplayGainAudioProcessor.MAX_BOOST_DB / 20f).toDouble()).toFloat()
        assertTrue("Boost $scale exceeded the $ceiling ceiling", scale <= ceiling + 0.001f)
    }

    // --- LoudnessAnalyzer ---------------------------------------------------------------------

    @Test
    fun `silence yields no measurement rather than a bogus one`() {
        val silence = FloatArray(16000 * 2)
        assertNull(LoudnessAnalyzer.analyzeSamples(silence, 16000))
    }

    @Test
    fun `a buffer too short for one analysis block yields no measurement`() {
        // R128 measures over 400ms windows; anything shorter cannot produce even one.
        val tooShort = sineWave(seconds = 0.1, amplitude = 0.5f)
        assertNull(LoudnessAnalyzer.analyzeSamples(tooShort, 16000))
    }

    @Test
    fun `a louder signal measures as louder`() {
        val quiet = LoudnessAnalyzer.analyzeSamples(sineWave(seconds = 3.0, amplitude = 0.05f), 16000)
        val loud = LoudnessAnalyzer.analyzeSamples(sineWave(seconds = 3.0, amplitude = 0.5f), 16000)
        assertNotNull(quiet)
        assertNotNull(loud)
        assertTrue(
            "Expected the louder signal to measure higher LUFS",
            loud!!.integratedLufs > quiet!!.integratedLufs
        )
    }

    @Test
    fun `the gain points in the direction that evens the two signals out`() {
        // The whole feature rests on this: a quiet track must get a positive gain and a loud one a
        // negative gain, so applying both brings them together.
        val quiet = LoudnessAnalyzer.analyzeSamples(sineWave(seconds = 3.0, amplitude = 0.02f), 16000)!!
        val loud = LoudnessAnalyzer.analyzeSamples(sineWave(seconds = 3.0, amplitude = 0.9f), 16000)!!
        assertTrue("A quiet track should be boosted, got ${quiet.gainDb} dB", quiet.gainDb > 0f)
        assertTrue("A loud track should be cut, got ${loud.gainDb} dB", loud.gainDb < 0f)
    }

    @Test
    fun `applying the measured gains brings two signals to the same loudness`() {
        val quietSamples = sineWave(seconds = 3.0, amplitude = 0.05f)
        val loudSamples = sineWave(seconds = 3.0, amplitude = 0.7f)
        val quiet = LoudnessAnalyzer.analyzeSamples(quietSamples, 16000)!!
        val loud = LoudnessAnalyzer.analyzeSamples(loudSamples, 16000)!!

        // Re-measure each signal with its own gain applied. They started roughly 23 dB apart and
        // should land within a decibel of each other.
        val quietNormalized = LoudnessAnalyzer.analyzeSamples(
            quietSamples.map { it * dbToLinear(quiet.gainDb) }.toFloatArray(), 16000
        )!!
        val loudNormalized = LoudnessAnalyzer.analyzeSamples(
            loudSamples.map { it * dbToLinear(loud.gainDb) }.toFloatArray(), 16000
        )!!

        assertEquals(
            quietNormalized.integratedLufs.toDouble(),
            loudNormalized.integratedLufs.toDouble(),
            1.0
        )
    }

    @Test
    fun `measured loudness lands at the reference level after its own gain is applied`() {
        val samples = sineWave(seconds = 3.0, amplitude = 0.3f)
        val measured = LoudnessAnalyzer.analyzeSamples(samples, 16000)!!
        val normalized = LoudnessAnalyzer.analyzeSamples(
            samples.map { it * dbToLinear(measured.gainDb) }.toFloatArray(), 16000
        )!!
        assertEquals(LoudnessAnalyzer.REFERENCE_LUFS, normalized.integratedLufs.toDouble(), 0.5)
    }

    @Test
    fun `the reported peak is not below the true sample peak`() {
        // The peak feeds the clipping limiter, so reading low is the dangerous direction.
        val amplitude = 0.4f
        val measured = LoudnessAnalyzer.analyzeSamples(sineWave(seconds = 2.0, amplitude = amplitude), 16000)!!
        assertTrue(
            "Reported peak ${measured.peak} understates the true peak $amplitude",
            measured.peak >= amplitude
        )
    }

    private fun dbToLinear(db: Float): Float = Math.pow(10.0, (db / 20f).toDouble()).toFloat()

    /** A 1 kHz tone, which the K-weighting curve treats close to flat, at [amplitude] of full scale. */
    private fun sineWave(seconds: Double, amplitude: Float, sampleRate: Int = 16000): FloatArray {
        val count = (seconds * sampleRate).toInt()
        return FloatArray(count) { i ->
            (amplitude * sin(2.0 * PI * 1000.0 * i / sampleRate)).toFloat()
        }
    }
}
