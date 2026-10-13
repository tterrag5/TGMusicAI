package com.example.tgmusicai

import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import com.example.tgmusicai.playback.PlayRequestMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRequestMatcherTest {

    private fun song(title: String, artist: String, downloaded: Boolean = false, id: Long = title.hashCode().toLong()) =
        Song(id = id, title = title, artist = artist, album = "", durationMs = 0, mediaUri = "file://$title", isDownloaded = downloaded)

    private fun yt(title: String, uploader: String, id: String = title) =
        YouTubeSearchResult(videoId = id, title = title, uploader = uploader, durationSeconds = 200, thumbnailUri = "")

    @Test
    fun `title and artist together find the library song`() {
        val library = listOf(song("When Spring Comes", "Jamie Paige"), song("Spring Day", "BTS"))
        assertEquals("When Spring Comes", PlayRequestMatcher.localMatches(library, "when spring comes", "jamie paige").single().title)
    }

    @Test
    fun `a different artist with the same title is not a match`() {
        val library = listOf(song("Home", "Edward Sharpe"))
        assertTrue(PlayRequestMatcher.localMatches(library, "home", "jamie paige").isEmpty())
    }

    @Test
    fun `exact title beats contains, downloaded beats streamed`() {
        val library = listOf(
            song("When Spring Comes (Remastered)", "Jamie Paige", id = 1),
            song("When Spring Comes", "Jamie Paige", downloaded = false, id = 2),
            song("When Spring Comes", "Jamie Paige", downloaded = true, id = 3),
        )
        assertEquals(listOf(3L, 2L, 1L), PlayRequestMatcher.localMatches(library, "When Spring Comes", null).map { it.id })
    }

    @Test
    fun `the official upload wins over covers and nightcore`() {
        val results = listOf(
            yt("When Spring Comes (Nightcore)", "Some Fan", "a"),
            yt("when spring comes - cover", "Singer", "b"),
            yt("When Spring Comes", "Jamie Paige - Topic", "c"),
        )
        assertEquals("c", PlayRequestMatcher.bestRemote(results, "when spring comes", "jamie paige")!!.videoId)
    }

    @Test
    fun `a cover is fine when the request asks for one`() {
        val results = listOf(yt("When Spring Comes", "Jamie Paige", "a"), yt("When Spring Comes cover", "Singer", "b"))
        assertEquals("b", PlayRequestMatcher.bestRemote(results, "when spring comes cover", null)!!.videoId)
    }

    @Test
    fun `nothing mentioning the title means no pick`() {
        assertNull(PlayRequestMatcher.bestRemote(listOf(yt("Totally Different", "Jamie Paige")), "when spring comes", "jamie paige"))
    }

    @Test
    fun `normalize drops official-video noise and punctuation`() {
        assertEquals("when spring comes", PlayRequestMatcher.normalize("When Spring Comes! (Official Music Video)"))
    }
}
