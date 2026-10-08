package com.example.tgmusicai

import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.RecommendationEngine
import com.example.tgmusicai.data.repository.VarietyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The variety modes behind the radio builder.
 *
 * The three modes move the same dials rather than switching algorithms, so what is worth pinning
 * down is that each dial actually reaches [RecommendationEngine.diversify], and that [BLEND] is
 * byte-for-byte the behaviour the engine had before modes existed -- every existing caller passes
 * no mode and must be unaffected.
 */
class VarietyModeTest {

    private val FAVOURITE = "Kikuo"

    private fun song(id: Long, artist: String) = Song(
        id = id,
        title = "Track $id",
        artist = artist,
        album = "Album",
        durationMs = 200_000L,
        mediaUri = "file:///music/$id.mp3",
    )

    /** Descending scores so the ordering is unambiguous, all from one artist unless stated. */
    private fun scoredFrom(artists: List<String>) = artists.mapIndexed { index, artist ->
        RecommendationEngine.Scored(song(index + 1L, artist), 1f - index * 0.01f)
    }

    @Test
    fun discoverAllowsOneTrackPerArtistBeforeDeferring() {
        val scored = scoredFrom(List(6) { "Kikuo" } + List(6) { "MARETU" })

        val picked = RecommendationEngine.diversify(
            scored,
            limit = 4,
            maxPerArtist = VarietyMode.DISCOVER.maxPerArtist,
        )

        // Two artists can each contribute one before the cap defers the rest; the queue is still
        // filled from the deferred pile rather than coming back short.
        assertEquals(4, picked.size)
        val leadingArtists = picked.take(2).map { it.artist }.toSet()
        assertEquals("the first picks must come from different artists", 2, leadingArtists.size)
    }

    @Test
    fun eachModeGivesOneArtistExactlyItsShareOfTheQueue() {
        // One favourite artist alternating with distinct others. The interleaving matters: a solid
        // run of one artist is stopped by the consecutive-run rule long before the per-artist cap
        // binds, so a naive fixture measures that rule instead of the mode. Spacing them out leaves
        // the cap as the only thing that can stop the artist, which is what is under test. There
        // are also enough other artists to fill the queue, so the result never reaches the
        // deferred-overflow path, which would hand the capped artist its slots back.
        val artists = (1..14).flatMap { listOf(FAVOURITE, "Other $it") }
        val scored = scoredFrom(artists)

        fun shareOf(mode: VarietyMode): Int =
            RecommendationEngine.diversify(scored, limit = 10, maxPerArtist = mode.maxPerArtist)
                .count { it.artist == FAVOURITE }

        assertEquals("DISCOVER should admit the artist once", 1, shareOf(VarietyMode.DISCOVER))
        assertEquals("BLEND should admit the artist three times", 3, shareOf(VarietyMode.BLEND))
        assertEquals("FAMILIAR should admit the artist five times", 5, shareOf(VarietyMode.FAMILIAR))
    }

    @Test
    fun blendIsExactlyTheOriginalBehaviour() {
        val scored = scoredFrom(List(10) { "Kikuo" } + List(10) { "MARETU" } + List(5) { "DECO*27" })

        // The no-argument call is what every existing caller makes.
        val original = RecommendationEngine.diversify(scored, limit = 12).map { it.id }
        val blend = RecommendationEngine.diversify(
            scored,
            limit = 12,
            maxPerArtist = VarietyMode.BLEND.maxPerArtist,
        ).map { it.id }

        assertEquals("BLEND must not change the default run at all", original, blend)
    }

    @Test
    fun modesOrderTheDialsTheWayTheirNamesClaim() {
        // Guards against someone retuning one constant and inverting a mode's meaning.
        assertTrue(
            "novelty should rise from familiar to discover",
            VarietyMode.FAMILIAR.noveltyWeight < VarietyMode.BLEND.noveltyWeight &&
                VarietyMode.BLEND.noveltyWeight < VarietyMode.DISCOVER.noveltyWeight,
        )
        assertTrue(
            "shared artist/album should matter most in familiar",
            VarietyMode.FAMILIAR.metadataWeight > VarietyMode.BLEND.metadataWeight &&
                VarietyMode.BLEND.metadataWeight > VarietyMode.DISCOVER.metadataWeight,
        )
        assertTrue(
            "the per-artist cap should tighten towards discover",
            VarietyMode.FAMILIAR.maxPerArtist > VarietyMode.BLEND.maxPerArtist &&
                VarietyMode.BLEND.maxPerArtist > VarietyMode.DISCOVER.maxPerArtist,
        )
    }

    @Test
    fun everyModeStillFillsTheQueueForASingleArtistLibrary() {
        // The deferral path matters most under DISCOVER, whose cap is 1: a one-artist library must
        // still return a full queue rather than a single track.
        val scored = scoredFrom(List(20) { "Kikuo" })

        for (mode in VarietyMode.entries) {
            assertEquals(
                "${mode.name} returned a short queue for a single-artist library",
                10,
                RecommendationEngine.diversify(scored, limit = 10, maxPerArtist = mode.maxPerArtist).size,
            )
        }
    }
}
