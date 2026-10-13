package com.example.tgmusicai

import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.local.entity.SongStats
import com.example.tgmusicai.data.repository.LibraryOrganizer
import com.example.tgmusicai.data.repository.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrganizerTest {

    private fun song(id: Long, title: String, artist: String = "A", album: String = "X") =
        Song(id = id, title = title, artist = artist, album = album, durationMs = 1000, mediaUri = "uri$id")

    private fun playlist(id: Long, name: String, createdAt: Long, smart: Boolean = false, songs: List<Song> = emptyList()) =
        PlaylistWithSongs(Playlist(playlistId = id, name = name, createdAt = createdAt, isSmart = smart), songs)

    private val s1 = song(1, "beta")
    private val s2 = song(2, "alpha")
    private val s3 = song(3, "gamma")
    private val stats = mapOf(
        1L to SongStats(songId = 1, playCount = 5, lastPlayedAt = 100),
        2L to SongStats(songId = 2, playCount = 1, lastPlayedAt = 300),
        3L to SongStats(songId = 3, playCount = 9, lastPlayedAt = 200),
    )

    @Test
    fun likedMusicAlwaysFirstThenMostPlayed() {
        val lists = listOf(
            playlist(10, "Road trip", createdAt = 1),
            playlist(11, "Liked Music", createdAt = 0, smart = true),
            playlist(12, "Gym", createdAt = 2),
        )
        val sorted = LibraryOrganizer.sortPlaylists(lists, mapOf(10L to 3, 12L to 7, 11L to 0), stats, LibrarySort.MOST_PLAYED)
        assertEquals(listOf("Liked Music", "Gym", "Road trip"), sorted.map { it.playlist.name })
    }

    @Test
    fun playlistsByRecentPlayUseTheirSongsLastPlay() {
        val lists = listOf(
            playlist(10, "Old", createdAt = 1, songs = listOf(s1)),
            playlist(12, "Fresh", createdAt = 2, songs = listOf(s2)),
        )
        val sorted = LibraryOrganizer.sortPlaylists(lists, emptyMap(), stats, LibrarySort.RECENTLY_PLAYED)
        assertEquals(listOf("Fresh", "Old"), sorted.map { it.playlist.name })
    }

    @Test
    fun songSorts() {
        val songs = listOf(s1, s2, s3)
        assertEquals(listOf(2L, 3L, 1L), LibraryOrganizer.sortSongs(songs, stats, LibrarySort.RECENTLY_PLAYED).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), LibraryOrganizer.sortSongs(songs, stats, LibrarySort.MOST_PLAYED).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), LibraryOrganizer.sortSongs(songs, stats, LibrarySort.RECENTLY_ADDED).map { it.id })
        assertEquals(listOf("alpha", "beta", "gamma"), LibraryOrganizer.sortSongs(songs, stats, LibrarySort.A_TO_Z).map { it.title })
    }

    @Test
    fun artistsAndAlbumsGroupAndSkipUnknown() {
        val songs = listOf(
            song(1, "a", artist = "Jamie Paige", album = "PPPP"),
            song(2, "b", artist = "Jamie Paige", album = "PPPP"),
            song(3, "c", artist = "ROMA", album = "Unknown Album"),
            song(4, "d", artist = "Unknown Artist", album = "Solo"),
        )
        val artists = LibraryOrganizer.artists(songs, stats, LibrarySort.MOST_PLAYED)
        // ROMA's one song has 9 plays; Jamie Paige's two have 6 between them.
        assertEquals(listOf("ROMA", "Jamie Paige"), artists.map { it.name })
        assertEquals(2, artists[1].songCount)
        assertEquals(6, artists[1].plays)
        val albums = LibraryOrganizer.albums(songs, stats, LibrarySort.A_TO_Z)
        assertEquals(listOf("PPPP", "Solo"), albums.map { it.title })
    }
}
