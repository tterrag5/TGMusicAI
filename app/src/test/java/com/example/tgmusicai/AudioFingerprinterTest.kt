package com.example.tgmusicai

import com.example.tgmusicai.ai.AudioFingerprinter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Covers acoustic fingerprinting end to end against synthetic audio.
 *
 * The property that matters is not that any single hash is right but that a distorted, noisy,
 * offset excerpt of a track still matches the original and that unrelated audio does not. Those
 * are testable without a microphone, and they are the two ways this feature fails in a way no
 * other test would catch: silently never recognising anything, or confidently naming the wrong
 * song.
 */
class AudioFingerprinterTest {

    private val sampleRate = AudioFingerprinter.SAMPLE_RATE

    @Test
    fun `audio shorter than one analysis frame produces no fingerprint`() {
        // Better than fingerprinting zero-padding, which would produce landmarks describing the
        // padding rather than the audio.
        val tooShort = FloatArray(AudioFingerprinter.FRAME_SIZE - 1)
        assertTrue(AudioFingerprinter.fingerprint(tooShort).isEmpty())
    }

    @Test
    fun `silence produces no usable landmarks`() {
        val silence = FloatArray(sampleRate * 3)
        assertTrue(AudioFingerprinter.fingerprint(silence).isEmpty())
    }

    @Test
    fun `peak density stays near the configured budget`() {
        // The budget is what bounds the index's size across a whole library, so a regression here
        // is a storage regression before it is anything else.
        val audio = syntheticMusic(seconds = 10.0, seed = 1)
        val peaks = AudioFingerprinter.findPeaks(audio)
        val expected = 10 * AudioFingerprinter.TARGET_PEAKS_PER_SECOND
        assertTrue(
            "Expected roughly $expected peaks, got ${peaks.size}",
            peaks.size in (expected * 3 / 4)..expected
        )
    }

    @Test
    fun `peaks come back in time order`() {
        // The pairing step walks forward through time and silently produces nothing useful if the
        // list is unordered.
        val peaks = AudioFingerprinter.findPeaks(syntheticMusic(seconds = 5.0, seed = 2))
        val frames = peaks.map { it.frame }
        assertEquals(frames.sorted(), frames)
    }

    @Test
    fun `the same audio always fingerprints identically`() {
        // Indexing and recognition run the same code over different recordings of the same thing;
        // if the function were not deterministic, nothing downstream could match.
        val audio = syntheticMusic(seconds = 5.0, seed = 3)
        assertEquals(AudioFingerprinter.fingerprint(audio), AudioFingerprinter.fingerprint(audio))
    }

    @Test
    fun `hash packing keeps its three fields separable`() {
        val hash = AudioFingerprinter.packHash(anchorBin = 100, targetBin = 250, gapFrames = 40)
        assertEquals(100, (hash shr 16) and 0x1FF)
        assertEquals(250, (hash shr 7) and 0x1FF)
        assertEquals(40, hash and 0x7F)
    }

    @Test
    fun `adjacent bins produce different hashes`() {
        // Full bin resolution is deliberate. Halving the bins for drift tolerance was measurably
        // worse: it collapsed this corpus to a few hundred distinct hashes, and the collisions
        // sent three of eight genuine matches to the wrong track.
        assertNotEquals(
            AudioFingerprinter.packHash(100, 250, 40),
            AudioFingerprinter.packHash(101, 250, 40)
        )
    }

    @Test
    fun `the whole bin range fits in the hash without wrapping`() {
        // 9 bits per bin exactly covers a 512-bin spectrum. One bit less would silently alias the
        // top half of the spectrum onto the bottom half.
        val low = AudioFingerprinter.packHash(anchorBin = 0, targetBin = 0, gapFrames = 0)
        val high = AudioFingerprinter.packHash(anchorBin = 511, targetBin = 511, gapFrames = 64)
        assertNotEquals(low, high)
        assertEquals(511, (high shr 16) and 0x1FF)
        assertEquals(511, (high shr 7) and 0x1FF)
    }

    @Test
    fun `an excerpt matches the track it came from`() {
        val track = syntheticMusic(seconds = 30.0, seed = 4)
        val index = indexOf(songId = 7L, samples = track)

        // Six seconds taken from ten seconds in, as a recording made partway through would be.
        val excerptStart = (sampleRate * 10.0).toInt()
        val excerpt = track.copyOfRange(excerptStart, excerptStart + sampleRate * 6)

        val match = AudioFingerprinter.bestMatch(AudioFingerprinter.fingerprint(excerpt), index)

        assertNotNull("An exact excerpt should have matched its source", match)
        assertEquals(7L, match!!.songId)
    }

    @Test
    fun `a match reports where in the track the excerpt came from`() {
        // This is what lets the UI say "40 seconds in" and, more usefully, what would let playback
        // resume from the right place.
        val track = syntheticMusic(seconds = 40.0, seed = 5)
        val index = indexOf(songId = 1L, samples = track)

        val excerptStartSeconds = 20.0
        val start = (sampleRate * excerptStartSeconds).toInt()
        val excerpt = track.copyOfRange(start, start + sampleRate * 6)

        val match = AudioFingerprinter.bestMatch(AudioFingerprinter.fingerprint(excerpt), index)!!
        val reported = AudioFingerprinter.framesToSeconds(match.offsetFrames)

        assertEquals(excerptStartSeconds, reported, 1.0)
    }

    @Test
    fun `an excerpt still matches through noise and level change`() {
        // Stands in for the real journey: speakers, a room, and a phone microphone. If this fails
        // the feature does not work in the only situation it is ever used in.
        val track = syntheticMusic(seconds = 30.0, seed = 6)
        val index = indexOf(songId = 3L, samples = track)

        val start = (sampleRate * 8.0).toInt()
        val clean = track.copyOfRange(start, start + sampleRate * 8)
        val degraded = addNoise(clean.map { it * 0.4f }.toFloatArray(), noiseLevel = 0.03f, seed = 99)

        val match = AudioFingerprinter.bestMatch(AudioFingerprinter.fingerprint(degraded), index)

        assertNotNull("A noisy, quieter excerpt should still have matched", match)
        assertEquals(3L, match!!.songId)
    }

    @Test
    fun `an excerpt starting off the frame grid still matches`() {
        // A real recording never begins on an analysis-frame boundary, so the query's spectrogram
        // is computed over a different window alignment than the indexed track's. If that broke
        // matching, every test above would pass and the feature would still never work in use.
        val track = syntheticMusic(seconds = 30.0, seed = 31)
        val index = indexOf(songId = 5L, samples = track)

        val start = (sampleRate * 9.0).toInt() + 137
        val excerpt = track.copyOfRange(start, start + sampleRate * 8)

        val match = AudioFingerprinter.bestMatch(AudioFingerprinter.fingerprint(excerpt), index)

        assertNotNull("An excerpt not aligned to the frame grid should still have matched", match)
        assertEquals(5L, match!!.songId)
    }

    @Test
    fun `a single indexed track does not match unrelated audio`() {
        // With one track indexed there is no runner-up to measure against, so the margin test
        // cannot fire and the coverage floor is the only thing standing between the user and a
        // confidently wrong answer.
        val index = indexOf(songId = 42L, samples = syntheticMusic(seconds = 30.0, seed = 41))
        val unrelated = syntheticMusic(seconds = 8.0, seed = 777)

        assertNull(AudioFingerprinter.bestMatch(AudioFingerprinter.fingerprint(unrelated), index))
    }

    @Test
    fun `unrelated audio does not match`() {
        // Naming the wrong song is worse than admitting uncertainty, so this is the failure mode
        // the confidence threshold exists to prevent.
        val indexed = syntheticMusic(seconds = 30.0, seed = 7)
        val index = indexOf(songId = 11L, samples = indexed)

        val unrelated = syntheticMusic(seconds = 8.0, seed = 12345)

        assertNull(AudioFingerprinter.bestMatch(AudioFingerprinter.fingerprint(unrelated), index))
    }

    @Test
    fun `the right track wins when several are indexed`() {
        val trackA = syntheticMusic(seconds = 20.0, seed = 21)
        val trackB = syntheticMusic(seconds = 20.0, seed = 22)
        val trackC = syntheticMusic(seconds = 20.0, seed = 23)

        val index = HashMap<Int, MutableList<Pair<Long, Int>>>()
        listOf(1L to trackA, 2L to trackB, 3L to trackC).forEach { (id, samples) ->
            AudioFingerprinter.fingerprint(samples).forEach { landmark ->
                index.getOrPut(landmark.hash) { mutableListOf() }.add(id to landmark.frameIndex)
            }
        }

        val start = (sampleRate * 5.0).toInt()
        val excerpt = trackB.copyOfRange(start, start + sampleRate * 6)

        val match = AudioFingerprinter.bestMatch(AudioFingerprinter.fingerprint(excerpt), index)

        assertNotNull(match)
        assertEquals(2L, match!!.songId)
    }

    @Test
    fun `an empty index matches nothing`() {
        val query = AudioFingerprinter.fingerprint(syntheticMusic(seconds = 5.0, seed = 8))
        assertNull(AudioFingerprinter.bestMatch(query, emptyMap()))
    }

    // --- Helpers ------------------------------------------------------------------------------

    /** Builds the hash -> occurrences map the matcher takes, as the database would supply it. */
    private fun indexOf(songId: Long, samples: FloatArray): Map<Int, List<Pair<Long, Int>>> {
        val index = HashMap<Int, MutableList<Pair<Long, Int>>>()
        for (landmark in AudioFingerprinter.fingerprint(samples)) {
            index.getOrPut(landmark.hash) { mutableListOf() }.add(songId to landmark.frameIndex)
        }
        return index
    }

    /**
     * Synthesises something music-like: a chord progression of harmonically rich notes over a
     * beat, changing every couple of seconds.
     *
     * A pure tone would be the easy thing to test with and would prove nothing -- it produces one
     * peak per frame at a constant frequency, so every excerpt of it matches every other excerpt.
     * Recognition depends on a spectrogram that varies over time, so the test signal has to.
     */
    private fun syntheticMusic(seconds: Double, seed: Int): FloatArray {
        val random = Random(seed)
        val total = (seconds * sampleRate).toInt()
        val output = FloatArray(total)

        val scale = listOf(220.0, 246.9, 261.6, 293.7, 329.6, 349.2, 392.0, 440.0, 493.9, 523.3)
        val noteLengthSamples = (sampleRate * 0.5).toInt()
        var position = 0

        while (position < total) {
            // A chord of three notes drawn from the scale, held for half a second.
            val roots = List(3) { scale[random.nextInt(scale.size)] * (1 shl random.nextInt(2)) }
            val end = minOf(position + noteLengthSamples, total)
            for (i in position until end) {
                val t = i.toDouble() / sampleRate
                var value = 0.0
                for (root in roots) {
                    // A few harmonics each, so the spectrum has structure across the bands the
                    // peak picker looks at rather than one line.
                    for (harmonic in 1..4) {
                        value += sin(2.0 * PI * root * harmonic * t) / (harmonic * roots.size)
                    }
                }
                // A percussive click on the beat, which gives the fingerprint sharp time anchors.
                if (i % (sampleRate / 2) < 200) value += 0.4 * random.nextDouble(-1.0, 1.0)
                // Envelope, so notes start and stop rather than running together.
                val progress = (i - position).toDouble() / noteLengthSamples
                output[i] = (value * 0.5 * (1.0 - progress * 0.6)).toFloat()
            }
            position = end
        }
        return output
    }

    private fun addNoise(samples: FloatArray, noiseLevel: Float, seed: Int): FloatArray {
        val random = Random(seed)
        return FloatArray(samples.size) { samples[it] + (random.nextFloat() * 2f - 1f) * noiseLevel }
    }
}
