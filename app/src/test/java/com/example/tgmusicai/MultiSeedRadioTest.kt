package com.example.tgmusicai

import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.repository.RecommendationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSeedRadioTest {

    private fun song(id: Long, artist: String = "Artist $id") = Song(
        id = id,
        title = "Track $id",
        artist = artist,
        album = "Album $id",
        durationMs = 200_000L,
        mediaUri = "file:///music/$id.mp3",
    )

    private fun ranked(vararg ids: Long) = ids.mapIndexed { index, id ->
        RecommendationEngine.Scored(song(id), 1f - index * 0.1f)
    }

    @Test
    fun `every seed contributes in turn`() {
        // Seed A's neighbours all outscore seed B's; a max-over-seeds ranking would be all A.
        val forA = ranked(10, 11, 12).map { it.copy(score = it.score + 5f) }
        val forB = ranked(20, 21, 22)

        val blended = RecommendationEngine.blendPerSeed(listOf(forA, forB)).map { it.song.id }

        assertEquals(listOf(10L, 20L, 11L, 21L, 12L, 22L), blended)
    }

    @Test
    fun `a song related to two seeds appears once, at its first turn`() {
        val blended = RecommendationEngine.blendPerSeed(listOf(ranked(10, 30), ranked(10, 20)))
            .map { it.song.id }
        assertEquals(listOf(10L, 30L, 20L), blended)
    }

    @Test
    fun `blend order survives diversify`() {
        val blended = RecommendationEngine.blendPerSeed(listOf(ranked(10, 11), ranked(20, 21)))
        val picked = RecommendationEngine.diversify(blended, limit = 4).map { it.id }
        assertEquals(listOf(10L, 20L, 11L, 21L), picked)
    }

    @Test
    fun `seeds open the queue and are spread through it`() {
        val seeds = listOf(song(1), song(2), song(3))
        val related = (100L..105L).map { song(it) }

        val queue = MusicRepository.spreadSeeds(seeds, related, limit = 20).map { it.id }

        assertEquals(listOf(1L, 100L, 101L, 2L, 102L, 103L, 3L, 104L, 105L), queue)
    }

    @Test
    fun `limit is respected and every seed is kept`() {
        val seeds = listOf(song(1), song(2), song(3))
        val related = (100L..150L).map { song(it) }

        val queue = MusicRepository.spreadSeeds(seeds, related, limit = 10)

        assertEquals(10, queue.size)
        assertTrue(queue.map { it.id }.containsAll(listOf(1L, 2L, 3L)))
        assertEquals(queue.size, queue.distinctBy { it.id }.size)
    }

    @Test
    fun `a seed found among related songs is not duplicated`() {
        val queue = MusicRepository.spreadSeeds(listOf(song(1), song(2)), listOf(song(2), song(100)), limit = 20)
        assertEquals(listOf(1L, 2L, 100L), queue.map { it.id })
    }
}
