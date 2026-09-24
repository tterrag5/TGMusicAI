package com.example.tgmusicai

import com.example.tgmusicai.data.local.entity.AiSongTags
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.LibraryTag
import com.example.tgmusicai.data.repository.MusicTagIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the grouping behind the Library's tag browser -- the view that replaced browsing by
 * folder.
 *
 * The cases that matter are the ones real tag data produces rather than the happy path: a genre
 * field holding several genres at once, the same genre spelled two ways, and placeholder values
 * that mean "untagged" and must not become a tag of their own.
 */
class MusicTagIndexTest {

    private fun song(id: Long, genre: String? = null) = Song(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMs = 1000L,
        mediaUri = "file:///music/$id.mp3",
        genre = genre
    )

    @Test
    fun `a genre field holding several genres becomes several tags`() {
        val tags = MusicTagIndex.build(listOf(song(1, "Hip-Hop/Rap"), song(2, "rock; alternative")))

        val names = tags.map { it.name.lowercase() }.toSet()
        assertEquals(setOf("hip-hop", "rap", "rock", "alternative"), names)
    }

    @Test
    fun `the same genre spelled differently is one tag`() {
        val tags = MusicTagIndex.build(listOf(song(1, "Shoegaze"), song(2, "shoegaze")))

        assertEquals(1, tags.size)
        assertEquals(2, tags.single().songCount)
        // The first spelling seen is what is shown: inventing a canonical casing would rewrite the
        // user's own labels back at them.
        assertEquals("Shoegaze", tags.single().name)
    }

    @Test
    fun `placeholder genres are not tags`() {
        val tags = MusicTagIndex.build(
            listOf(song(1, "Unknown"), song(2, "other"), song(3, "  "), song(4, null), song(5, "Jazz"))
        )

        assertEquals(listOf("Jazz"), tags.map { it.name })
    }

    @Test
    fun `tags are ordered by how many songs carry them`() {
        val tags = MusicTagIndex.build(
            listOf(song(1, "Jazz"), song(2, "Jazz"), song(3, "Jazz"), song(4, "Funk"), song(5, "Funk"), song(6, "Blues"))
        )

        assertEquals(listOf("Jazz", "Funk", "Blues"), tags.map { it.name })
    }

    @Test
    fun `inferred tags are kept apart from the file's own genres`() {
        val tags = MusicTagIndex.build(
            songs = listOf(song(1, "Jazz")),
            aiTags = listOf(
                AiSongTags(songId = 1, tags = "Saxophone,Double bass", lyricThemes = "Loss")
            )
        )

        assertEquals(LibraryTag.Kind.GENRE, tags.first { it.name == "Jazz" }.kind)
        assertEquals(LibraryTag.Kind.SOUND, tags.first { it.name == "Saxophone" }.kind)
        assertEquals(LibraryTag.Kind.THEME, tags.first { it.name == "Loss" }.kind)
    }

    @Test
    fun `analysis left behind by a deleted song contributes nothing`() {
        // ai_song_tags has no foreign key to songs, so rows outlive the songs they describe. A tag
        // built from one would show a count no song can satisfy.
        val tags = MusicTagIndex.build(
            songs = listOf(song(1, "Jazz")),
            aiTags = listOf(AiSongTags(songId = 99, tags = "Bagpipes"))
        )

        assertTrue(tags.none { it.name == "Bagpipes" })
    }

    @Test
    fun `searching tags finds every song carrying a match`() {
        val tags = MusicTagIndex.build(listOf(song(1, "Jazz"), song(2, "Jazz Fusion"), song(3, "Metal")))

        assertEquals(setOf(1L, 2L), MusicTagIndex.songIdsMatching(tags, "jazz"))
        assertEquals(emptySet<Long>(), MusicTagIndex.songIdsMatching(tags, "polka"))
        // A blank query means "no tag filter", not "every song".
        assertEquals(emptySet<Long>(), MusicTagIndex.songIdsMatching(tags, "   "))
    }

    @Test
    fun `filtering the tag list is case-insensitive and keeps everything when blank`() {
        val tags = MusicTagIndex.build(listOf(song(1, "Jazz"), song(2, "Metal")))

        assertEquals(listOf("Jazz"), MusicTagIndex.matching(tags, "JAZ").map { it.name })
        assertEquals(2, MusicTagIndex.matching(tags, "").size)
    }
}
