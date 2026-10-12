package com.example.tgmusicai

import com.example.tgmusicai.data.repository.CloudRecommendationSource
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSeedMatchTest {

    private fun result(title: String, artist: String) = YouTubeSearchResult("id", title, artist, 200, "")

    private fun same(title: String, artist: String, candidate: YouTubeSearchResult) =
        CloudRecommendationSource.isSameTrack(title, artist, candidate)

    @Test
    fun `annotations and punctuation do not prevent a match`() {
        assertTrue(same("Karma Police", "Radiohead", result("Karma Police (Remastered)", "Radiohead")))
        assertTrue(same("Don't Stop Me Now", "Queen", result("Dont Stop Me Now [Official Audio]", "Queen")))
        assertTrue(same("Rolling in the Deep feat. Nobody", "Adele", result("Rolling In The Deep", "Adele")))
    }

    @Test
    fun `collaborations match either way round`() {
        assertTrue(same("Amor Factura", "Ricardo Arjona", result("Amor Factura", "Ricardo Arjona & Grupo Firme")))
        assertTrue(same("Amor Factura", "Ricardo Arjona & Grupo Firme", result("Amor Factura", "Ricardo Arjona")))
    }

    @Test
    fun `a different song or artist is not a match`() {
        assertFalse(same("Karma Police", "Radiohead", result("Street Spirit", "Radiohead")))
        assertFalse(same("Karma Police", "Radiohead", result("Karma Police", "Zoom Karaoke")))
    }

    @Test
    fun `no artist needs a distinctive title`() {
        assertFalse(same("Intro", "<unknown>", result("Intro", "Someone")))
        assertTrue(same("Never Gonna Give You Up", "", result("Never Gonna Give You Up", "Rick Astley")))
    }
}
