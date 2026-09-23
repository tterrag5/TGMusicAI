package com.example.tgmusicai

import com.example.tgmusicai.ai.AudioProfileCodec
import com.example.tgmusicai.data.local.dao.HistoryEntry
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.RecommendationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/**
 * Unit tests for the pure halves of the recommendation work: the acoustic profile codec, the
 * session reconstruction that turns the listening-time log into behavioural signal, and the
 * diversity re-rank.
 *
 * The scoring blend itself needs four DAOs and is covered by using the app; these are the parts
 * where a silent bug would be invisible in the UI but would quietly wreck the results.
 */
class RecommendationEngineTest {

    // ---- AudioProfileCodec ------------------------------------------------------------------

    @Test
    fun codecRoundTripPreservesSimilarity() {
        val random = Random(7)
        val a = FloatArray(521) { random.nextFloat() }
        val b = FloatArray(521) { random.nextFloat() }

        val decodedA = AudioProfileCodec.decode(AudioProfileCodec.encode(a))
        val decodedB = AudioProfileCodec.decode(AudioProfileCodec.encode(b))
        assertNotNull(decodedA)
        assertNotNull(decodedB)

        // Quantization is lossy by design; what must survive is the similarity the ranking uses.
        val before = AudioProfileCodec.cosineSimilarity(a, b)
        val after = AudioProfileCodec.cosineSimilarity(decodedA, decodedB)
        assertTrue(
            "Quantization shifted similarity from $before to $after, which would reorder results",
            abs(before - after) < 0.01f,
        )
    }

    @Test
    fun codecScoresAnIdenticalProfileAsMaximallySimilar() {
        val random = Random(11)
        val scores = FloatArray(521) { random.nextFloat() }
        val decoded = AudioProfileCodec.decode(AudioProfileCodec.encode(scores))

        assertTrue(
            "A song must be maximally similar to itself",
            AudioProfileCodec.cosineSimilarity(decoded, decoded) > 0.999f,
        )
    }

    @Test
    fun codecEncodingIsCompact() {
        val scores = FloatArray(521) { 0.5f }
        val encoded = AudioProfileCodec.encode(scores)
        assertNotNull(encoded)
        // Two hex characters per component. The point of quantizing at all is that this stays far
        // under the ~5KB the same vector costs as comma-separated floats.
        assertEquals(521 * 2, encoded!!.length)
    }

    @Test
    fun codecRejectsUnusableInput() {
        assertNull("An all-zero vector has no direction to preserve", AudioProfileCodec.encode(FloatArray(521)))
        assertNull(AudioProfileCodec.encode(FloatArray(0)))
        assertNull(AudioProfileCodec.decode(null))
        assertNull(AudioProfileCodec.decode(""))
        // A truncated or corrupted column value must decode to nothing rather than throw -- the
        // acoustic signal drops to zero and the recommendation still happens.
        assertNull(AudioProfileCodec.decode("abc"))
        assertNull(AudioProfileCodec.decode("zzzz"))
    }

    @Test
    fun codecTreatsMismatchedLengthsAsNoSignal() {
        // Real case during a model swap: two profiles of different dimensions mean different
        // things, so comparing them must yield no signal rather than a meaningless number.
        assertEquals(0f, AudioProfileCodec.cosineSimilarity(FloatArray(521) { 1f }, FloatArray(1024) { 1f }), 0f)
        assertEquals(0f, AudioProfileCodec.cosineSimilarity(null, FloatArray(521) { 1f }), 0f)
    }

    // ---- session reconstruction --------------------------------------------------------------

    @Test
    fun sessionsCollapseTheRepeatedChunksOfOneSong() {
        // listening_history logs a row every 10 seconds, so one three-minute song writes many rows.
        val entries = (0 until 18).map { HistoryEntry(songId = 1L, timestampMs = it * 10_000L) }

        val sessions = RecommendationEngine.sessionsFrom(entries)

        assertEquals(1, sessions.size)
        assertEquals(
            "Eighteen chunks of one song are one play, not eighteen",
            setOf(1L),
            sessions.first(),
        )
    }

    @Test
    fun sessionsSplitOnALongGap() {
        val entries = listOf(
            HistoryEntry(songId = 1L, timestampMs = 0L),
            HistoryEntry(songId = 2L, timestampMs = 10_000L),
            // Over the 30-minute session gap: a new sitting.
            HistoryEntry(songId = 3L, timestampMs = 10_000L + 45L * 60_000L),
            HistoryEntry(songId = 4L, timestampMs = 20_000L + 45L * 60_000L),
        )

        val sessions = RecommendationEngine.sessionsFrom(entries)

        assertEquals(listOf(setOf(1L, 2L), setOf(3L, 4L)), sessions)
    }

    @Test
    fun sessionsKeepASongThatComesBackLaterInTheSameSitting() {
        val entries = listOf(
            HistoryEntry(songId = 1L, timestampMs = 0L),
            HistoryEntry(songId = 2L, timestampMs = 10_000L),
            HistoryEntry(songId = 1L, timestampMs = 20_000L),
        )

        // One session, and the set holds both songs -- the collapse only removes *consecutive*
        // repeats, so a genuine replay still counts toward co-occurrence.
        assertEquals(listOf(setOf(1L, 2L)), RecommendationEngine.sessionsFrom(entries))
    }

    @Test
    fun sessionsHandleAnEmptyLog() {
        assertEquals(emptyList<Set<Long>>(), RecommendationEngine.sessionsFrom(emptyList()))
    }

    // ---- diversity ---------------------------------------------------------------------------

    @Test
    fun diversifyCapsOneArtistsShareOfTheQueue() {
        // Score order alone would return ten tracks by the same artist, because artist match,
        // acoustic similarity and co-occurrence all correlate with artist.
        val scored = (1..10).map {
            RecommendationEngine.Scored(song(id = it.toLong(), artist = "One Artist"), score = 1f / it)
        } + (11..20).map {
            RecommendationEngine.Scored(song(id = it.toLong(), artist = "Artist $it"), score = 0.01f)
        }

        val result = RecommendationEngine.diversify(scored, limit = 10)

        assertEquals(10, result.size)
        val fromOneArtist = result.count { it.artist == "One Artist" }
        assertTrue("One artist took $fromOneArtist of 10 slots", fromOneArtist <= 3)
    }

    @Test
    fun diversifyStillFillsTheQueueForASingleArtistLibrary() {
        // Over-quota tracks are deferred, not dropped: a library with one artist must still
        // produce a full queue rather than three songs.
        val scored = (1..10).map {
            RecommendationEngine.Scored(song(id = it.toLong(), artist = "Only Artist"), score = 1f / it)
        }

        assertEquals(10, RecommendationEngine.diversify(scored, limit = 10).size)
    }

    @Test
    fun diversifyIsDeterministicForTiedScores() {
        // Evaluating the engine against real history has to be repeatable, which means ties cannot
        // resolve arbitrarily.
        val scored = (1..8).map {
            RecommendationEngine.Scored(song(id = it.toLong(), artist = "Artist $it"), score = 0.5f)
        }

        val first = RecommendationEngine.diversify(scored, limit = 5).map { it.id }
        val second = RecommendationEngine.diversify(scored.shuffled(), limit = 5).map { it.id }

        assertEquals(first, second)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), first)
    }

    @Test
    fun diversifyReturnsWhatExistsForATinyLibrary() {
        val scored = listOf(RecommendationEngine.Scored(song(id = 1L, artist = "A"), score = 1f))
        assertEquals(1, RecommendationEngine.diversify(scored, limit = 20).size)
    }

    private fun song(id: Long, artist: String) = Song(
        id = id,
        title = "Song $id",
        artist = artist,
        album = "Album",
        durationMs = 180_000L,
        mediaUri = "file:///music/$id.mp3",
    )
}
