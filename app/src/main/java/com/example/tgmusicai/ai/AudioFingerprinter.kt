package com.example.tgmusicai.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Turns audio into a set of landmark hashes that survive being played through speakers and
 * recorded back through a phone microphone.
 *
 * This is the constellation approach every acoustic fingerprinter uses. The spectrogram's local
 * peaks are found, pairs of nearby peaks are combined into a hash of
 * `(frequency A, frequency B, time between them)`, and the hash is stored with the time it
 * occurred. Peaks are used rather than the spectrogram itself because they are what survives the
 * journey: room reverb, background noise, EQ and lossy re-encoding all move energy around, but the
 * loudest points in a spectrogram stay the loudest points.
 *
 * Matching then looks for many hashes that agree not just on which track they came from but on
 * *how far apart in time* the recording and the original are. Coincidental hash collisions scatter
 * across time offsets; a real match stacks up at one.
 *
 * All pure computation with no Android dependencies, so the whole thing is unit-testable against
 * synthetic audio.
 */
object AudioFingerprinter {

    /** Sample rate everything here assumes, matching what [PcmDecoder] produces. */
    const val SAMPLE_RATE = 16000

    /** FFT window. At 16 kHz this is 64 ms, long enough to resolve musical pitch. */
    const val FRAME_SIZE = 1024

    /** Window advance, giving 62.5 frames per second. */
    const val HOP_SIZE = 256

    /**
     * Spectrogram bands peaks are picked from, one peak per band per frame at most.
     *
     * Banding rather than a global threshold is what keeps a loud bass line from crowding out
     * every other peak in the track. The bands are geometric because pitch is: the same musical
     * interval spans far more hertz at the top of the range than at the bottom.
     */
    private val BAND_EDGES = intArrayOf(0, 10, 20, 40, 80, 160, 320, 512)

    /**
     * Peaks kept per second of audio, across all bands.
     *
     * The tuning knob that matters most. Higher means more reliable recognition from a noisy
     * recording and a proportionally larger index; lower means a smaller index that needs a
     * cleaner recording.
     *
     * Measured rather than guessed. At a sixth of this, a test corpus produced so few distinct
     * hashes that collisions sent three of eight genuine matches to the wrong track; at this
     * value all eight resolved correctly. The index costs roughly a few thousand rows per track,
     * which is why building it is opt-in.
     */
    const val TARGET_PEAKS_PER_SECOND = 24

    /** How many later peaks each peak is paired with. Each pair becomes one hash. */
    const val FAN_OUT = 5

    /** Time window, in frames, within which a later peak may be paired with an anchor. */
    private const val TARGET_ZONE_FRAMES = 64

    /** A pair closer than this in time carries too little information to be worth storing. */
    private const val MIN_PAIR_GAP_FRAMES = 2

    /** One landmark: a hash of two peaks and their spacing, plus when in the track it occurred. */
    data class Landmark(val hash: Int, val frameIndex: Int)

    /** One spectrogram peak, in frame and frequency-bin coordinates. */
    data class Peak(val frame: Int, val bin: Int, val magnitude: Float)

    /** How strongly a query matched one track, and by how much the two are offset in time. */
    data class Match(val songId: Long, val alignedHashes: Int, val offsetFrames: Int)

    /** Converts a frame index to seconds, for reporting where in a track a match landed. */
    fun framesToSeconds(frames: Int): Double = frames.toDouble() * HOP_SIZE / SAMPLE_RATE

    /**
     * Fingerprints [samples] (mono, [SAMPLE_RATE], in `[-1, 1]`), returning its landmarks.
     *
     * Returns an empty list for audio too short to yield a single frame, which is the honest
     * answer rather than a fingerprint built from padding.
     */
    fun fingerprint(samples: FloatArray): List<Landmark> {
        if (samples.size < FRAME_SIZE) return emptyList()
        val peaks = findPeaks(samples)
        return pairPeaks(peaks)
    }

    /**
     * Finds the spectrogram's local peaks.
     *
     * Split out from [fingerprint] because it is the step worth inspecting on its own when
     * recognition quality is in question -- a fingerprint is only ever as good as these.
     */
    fun findPeaks(samples: FloatArray): List<Peak> {
        val window = hannWindow(FRAME_SIZE)
        val fft = Radix2Fft(FRAME_SIZE)
        val spectrumBins = FRAME_SIZE / 2
        val re = DoubleArray(FRAME_SIZE)
        val im = DoubleArray(FRAME_SIZE)

        val frameCount = (samples.size - FRAME_SIZE) / HOP_SIZE + 1
        val candidates = ArrayList<Peak>(frameCount * (BAND_EDGES.size - 1))

        for (frame in 0 until frameCount) {
            val offset = frame * HOP_SIZE
            for (i in 0 until FRAME_SIZE) {
                re[i] = samples[offset + i].toDouble() * window[i]
                im[i] = 0.0
            }
            fft.transform(re, im)

            // One peak per band per frame. Taking the top N of the whole frame instead lets a
            // single loud band supply all of them, which makes the fingerprint describe the
            // bass line rather than the music.
            for (band in 0 until BAND_EDGES.size - 1) {
                val from = BAND_EDGES[band]
                val to = minOf(BAND_EDGES[band + 1], spectrumBins)
                var bestBin = -1
                var bestMagnitude = 0.0
                for (bin in from until to) {
                    val magnitude = hypot(re[bin], im[bin])
                    if (magnitude > bestMagnitude) {
                        bestMagnitude = magnitude
                        bestBin = bin
                    }
                }
                if (bestBin >= 0 && bestMagnitude > 0.0) {
                    // Log magnitude, so a quiet passage's peaks compete fairly with a loud one's
                    // when the global cut below is applied.
                    candidates.add(Peak(frame, bestBin, ln(1.0 + bestMagnitude).toFloat()))
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // Keep the strongest peaks overall, budgeted by how long the audio actually is, then
        // restore time order -- the pairing step below walks forward through time.
        val durationSeconds = samples.size.toDouble() / SAMPLE_RATE
        val budget = (durationSeconds * TARGET_PEAKS_PER_SECOND).toInt().coerceAtLeast(1)
        return candidates
            .sortedByDescending { it.magnitude }
            .take(budget)
            .sortedWith(compareBy({ it.frame }, { it.bin }))
    }

    /** Pairs each peak with the next few after it, turning each pair into one hash. */
    fun pairPeaks(peaks: List<Peak>): List<Landmark> {
        val landmarks = ArrayList<Landmark>(peaks.size * FAN_OUT)
        for (i in peaks.indices) {
            val anchor = peaks[i]
            var paired = 0
            var j = i + 1
            while (j < peaks.size && paired < FAN_OUT) {
                val target = peaks[j]
                val gap = target.frame - anchor.frame
                if (gap < MIN_PAIR_GAP_FRAMES) {
                    j++
                    continue
                }
                if (gap > TARGET_ZONE_FRAMES) break
                landmarks.add(Landmark(packHash(anchor.bin, target.bin, gap), anchor.frame))
                paired++
                j++
            }
        }
        return landmarks
    }

    /**
     * Packs a peak pair into one integer: 9 bits of anchor bin, 9 of target bin, 7 of time gap.
     *
     * Full bin resolution, which 9 bits exactly accommodates for a 512-bin spectrum. An earlier
     * version halved the bins for drift tolerance and was measurably worse: it collapsed a test
     * corpus to a few hundred distinct hashes, and the resulting collisions sent three of eight
     * genuine matches to the wrong track. Tolerating drift is not worth a hash space too small to
     * tell tracks apart.
     */
    fun packHash(anchorBin: Int, targetBin: Int, gapFrames: Int): Int {
        val a = anchorBin and 0x1FF
        val b = targetBin and 0x1FF
        val gap = gapFrames and 0x7F
        return (a shl 16) or (b shl 7) or gap
    }

    /**
     * Picks the best match for [queryLandmarks] among [candidates], which maps each hash to the
     * `(songId, frameIndex)` occurrences found for it.
     *
     * The alignment histogram is the whole trick. Any two pieces of music share some hashes by
     * chance, and those land at random time offsets. Only a genuine match produces many hashes
     * that all say "this recording starts N frames into that track" -- so the answer is the
     * strongest single `(song, offset)` bucket, not the song with the most hashes in common.
     *
     * Returns null when nothing reaches [minAlignedHashes], which is the common and correct
     * outcome for audio that is not in the library at all.
     */
    fun bestMatch(
        queryLandmarks: List<Landmark>,
        candidates: Map<Int, List<Pair<Long, Int>>>,
        minAlignedHashes: Int = MIN_ALIGNED_HASHES,
        minScoreMargin: Double = MIN_SCORE_MARGIN
    ): Match? {
        if (queryLandmarks.isEmpty()) return null

        val histogram = HashMap<Long, HashMap<Int, Int>>()
        for (landmark in queryLandmarks) {
            val occurrences = candidates[landmark.hash] ?: continue
            for ((songId, songFrame) in occurrences) {
                val offset = songFrame - landmark.frameIndex
                val perSong = histogram.getOrPut(songId) { HashMap() }
                perSong[offset] = (perSong[offset] ?: 0) + 1
            }
        }
        if (histogram.isEmpty()) return null

        // Each track's single best time offset. Comparing tracks by their best alignment, rather
        // than by how many hashes they share in total, is what makes the comparison meaningful:
        // a track that shares a lot of hashes scattered across every offset shares nothing real.
        val perSongBest = histogram.map { (songId, offsets) ->
            val best = offsets.maxByOrNull { it.value }!!
            Match(songId, best.value, best.key)
        }.sortedByDescending { it.alignedHashes }

        val top = perSongBest.first()
        if (top.alignedHashes < minAlignedHashes) return null

        // First test: how much of the recording the match actually accounts for. An absolute
        // score cannot separate a real match from a coincidental one, because how many hashes a
        // genuine match produces depends on how long and how clean the recording was -- measured
        // across a test corpus, true and false raw scores overlap heavily. What the recording
        // explains does separate them, and unlike the margin below it still works when only one
        // track is indexed and there is nothing to compare against.
        val coverage = top.alignedHashes.toDouble() / queryLandmarks.size
        if (coverage < MIN_QUERY_COVERAGE) return null

        // Second test: how far the best track beats the second-best. A real match stands clear of
        // the field; a coincidental one sits in a crowd of tracks that all matched about as
        // poorly. Skipped when there is no second track, which is what the coverage test covers.
        val runnerUp = perSongBest.getOrNull(1)?.alignedHashes ?: 0
        if (runnerUp > 0 && top.alignedHashes.toDouble() / runnerUp < minScoreMargin) return null

        return top
    }

    /**
     * Aligned hashes required before a match is considered at all.
     *
     * A floor rather than the real test -- [MIN_SCORE_MARGIN] does that work. This exists so a
     * tiny match cannot pass on margin alone: three aligned hashes against one is a ratio of 3,
     * and means nothing.
     */
    const val MIN_ALIGNED_HASHES = 20

    /**
     * How far the best track must beat the second-best before a match is reported.
     *
     * Chosen from measured separation on a deliberately hard test corpus -- tracks built from one
     * scale on one rhythmic grid, so they share far more structure than real music does. There,
     * coincidental matches topped out at a margin of 1.39 and genuine ones started at 1.43. This
     * sits above the first and below the second, and errs toward saying "not sure": failing to
     * name a track the user can identify some other way is a much smaller cost than confidently
     * naming the wrong one.
     */
    const val MIN_SCORE_MARGIN = 1.6

    /**
     * Fraction of the recording's landmarks that must line up with the matched track.
     *
     * The other half of the same measurement. On the test corpus, coincidental matches accounted
     * for at most 32% of a recording while genuine ones -- including deliberately quiet and noisy
     * ones -- accounted for 48% to 96%. This sits in the gap. It also covers the case the margin
     * test cannot: with a single track indexed there is no second-best to measure against, and
     * without this floor that lone track would match anything.
     */
    const val MIN_QUERY_COVERAGE = 0.35

    private fun hannWindow(size: Int): DoubleArray =
        DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }

    /**
     * In-place radix-2 FFT for power-of-two sizes.
     *
     * A dedicated one rather than reusing the transcription engine's Bluestein FFT: that exists to
     * handle Whisper's non-power-of-two window and costs three larger transforms to do it, which
     * is several times slower per frame. Fingerprinting a whole music library runs this on the
     * order of ten thousand times per track, so the difference is minutes rather than noise.
     */
    private class Radix2Fft(private val size: Int) {
        private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }

        fun transform(re: DoubleArray, im: DoubleArray) {
            // Bit-reversal permutation.
            var j = 0
            for (i in 1 until size) {
                var bit = size shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j or bit
                if (i < j) {
                    val tmpRe = re[i]; re[i] = re[j]; re[j] = tmpRe
                    val tmpIm = im[i]; im[i] = im[j]; im[j] = tmpIm
                }
            }

            var length = 2
            while (length <= size) {
                val step = size / length
                var i = 0
                while (i < size) {
                    var k = 0
                    for (offset in i until i + length / 2) {
                        val partner = offset + length / 2
                        val wRe = cosTable[k]
                        val wIm = -sinTable[k]
                        val tRe = re[partner] * wRe - im[partner] * wIm
                        val tIm = re[partner] * wIm + im[partner] * wRe
                        re[partner] = re[offset] - tRe
                        im[partner] = im[offset] - tIm
                        re[offset] += tRe
                        im[offset] += tIm
                        k += step
                    }
                    i += length
                }
                length = length shl 1
            }
        }
    }
}
