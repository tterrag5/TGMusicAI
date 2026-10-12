package com.example.tgmusicai

import com.example.tgmusicai.data.repository.CloudRecommendationSource
import com.example.tgmusicai.data.scrobble.LastFmSimilarClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of Last.fm's `track.getSimilar` / `artist.getSimilar` JSON and its key errors. */
class LastFmSimilarTest {

    @Test
    fun `similar tracks are read best match first`() {
        val root = JSONObject(
            """
            {"similartracks": {"track": [
              {"name": "Lucky", "match": 0.71, "artist": {"name": "Radiohead"}},
              {"name": "No Surprises", "match": 1, "artist": {"name": "Radiohead"}},
              {"name": "", "match": 0.9, "artist": {"name": "Nobody"}},
              {"name": "Teardrop", "match": 0.5}
            ], "@attr": {"artist": "Radiohead"}}}
            """
        )
        val tracks = LastFmSimilarClient.parseSimilarTracks(root)
        assertEquals(listOf("No Surprises", "Lucky"), tracks.map { it.title })
        assertEquals("Radiohead", tracks.first().artist)
        assertEquals(1f, tracks.first().match)
    }

    @Test
    fun `a single result given as an object rather than an array is still read`() {
        val root = JSONObject("""{"similartracks": {"track": {"name": "Only", "match": "0.4", "artist": {"name": "One"}}}}""")
        assertEquals(listOf("Only"), LastFmSimilarClient.parseSimilarTracks(root).map { it.title })
    }

    @Test
    fun `similar artists are read best match first, without duplicates`() {
        val root = JSONObject(
            """
            {"similarartists": {"artist": [
              {"name": "Thom Yorke", "match": "0.8"},
              {"name": "Atoms for Peace", "match": "1"},
              {"name": "Thom Yorke", "match": "0.2"}
            ]}}
            """
        )
        assertEquals(listOf("Atoms for Peace", "Thom Yorke"), LastFmSimilarClient.parseSimilarArtists(root))
    }

    @Test
    fun `an error body parses to nothing`() {
        val root = JSONObject("""{"error": 6, "message": "Track not found"}""")
        assertTrue(LastFmSimilarClient.parseSimilarTracks(root).isEmpty())
        assertTrue(LastFmSimilarClient.parseSimilarArtists(root).isEmpty())
    }

    @Test
    fun `only invalid and suspended key errors count as a rejected key`() {
        // Body captured from the live API with a made-up key, 2026-10-11 (HTTP 403).
        val invalid = JSONObject("""{"message":"Invalid API key - You must be granted a valid key by last.fm","error":10}""")
        assertTrue(LastFmSimilarClient.isKeyRejection(invalid))
        assertTrue(LastFmSimilarClient.isKeyRejection(JSONObject("""{"error":26}""")))
        assertFalse(LastFmSimilarClient.isKeyRejection(JSONObject("""{"error":6}""")))
        assertFalse(LastFmSimilarClient.isKeyRejection(JSONObject("""{"similartracks":{}}""")))
    }

    @Test
    fun `round robin takes every seed's best before any second best`() {
        val merged = CloudRecommendationSource.roundRobin(listOf(listOf("a1", "a2", "a3"), listOf("b1"), listOf("c1", "c2")))
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3"), merged)
    }
}
