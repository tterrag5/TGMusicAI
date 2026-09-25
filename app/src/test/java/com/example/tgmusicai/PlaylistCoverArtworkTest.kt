package com.example.tgmusicai

import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistPlayCount
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.PlaylistCoverArtwork
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers how a playlist's four cover tiles are chosen. The rules that matter are that ranking is by
 * plays *from that playlist*, that ties keep the playlist's own order (an unstable sort would make
 * covers reshuffle on every re-emission), and that a playlist with no recorded plays still gets a
 * cover.
 */
class PlaylistCoverArtworkTest {

    private fun song(id: Long, artwork: String? = "file:///art/$id.jpg") = Song(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMs = 1000L,
        mediaUri = "file:///music/$id.mp3",
        artworkUri = artwork
    )

    private fun playlist(id: Long, songs: List<Song>) =
        PlaylistWithSongs(Playlist(playlistId = id, name = "Playlist $id"), songs)

    @Test
    fun `covers rank by plays from that playlist`() {
        val covers = PlaylistCoverArtwork.build(
            playlists = listOf(playlist(1, listOf(song(1), song(2), song(3), song(4), song(5)))),
            counts = listOf(
                PlaylistPlayCount(playlistId = 1, songId = 5, playCount = 9),
                PlaylistPlayCount(playlistId = 1, songId = 3, playCount = 4)
            )
        )

        assertEquals(
            listOf("file:///art/5.jpg", "file:///art/3.jpg", "file:///art/1.jpg", "file:///art/2.jpg"),
            covers[1]
        )
    }

    @Test
    fun `plays recorded against another playlist do not rank this one`() {
        val songs = listOf(song(1), song(2), song(3), song(4))
        val covers = PlaylistCoverArtwork.build(
            playlists = listOf(playlist(1, songs), playlist(2, songs)),
            counts = listOf(PlaylistPlayCount(playlistId = 2, songId = 4, playCount = 30))
        )

        assertEquals("file:///art/1.jpg", covers[1]?.first())
        assertEquals("file:///art/4.jpg", covers[2]?.first())
    }

    @Test
    fun `a playlist nobody has played keeps its own order`() {
        val covers = PlaylistCoverArtwork.build(
            playlists = listOf(playlist(7, listOf(song(3), song(1), song(2), song(4), song(5)))),
            counts = emptyList()
        )

        assertEquals(
            listOf("file:///art/3.jpg", "file:///art/1.jpg", "file:///art/2.jpg", "file:///art/4.jpg"),
            covers[7]
        )
    }

    @Test
    fun `equal counts keep the playlist order rather than reshuffling`() {
        val counts = (1L..4L).map { PlaylistPlayCount(playlistId = 1, songId = it, playCount = 2) }
        val covers = PlaylistCoverArtwork.build(
            playlists = listOf(playlist(1, listOf(song(4), song(3), song(2), song(1)))),
            counts = counts
        )

        assertEquals(
            listOf("file:///art/4.jpg", "file:///art/3.jpg", "file:///art/2.jpg", "file:///art/1.jpg"),
            covers[1]
        )
    }

    @Test
    fun `songs without artwork are skipped rather than leaving a blank tile`() {
        val covers = PlaylistCoverArtwork.build(
            playlists = listOf(
                playlist(1, listOf(song(1, null), song(2, ""), song(3), song(4), song(5), song(6)))
            ),
            counts = emptyList()
        )

        assertEquals(
            listOf("file:///art/3.jpg", "file:///art/4.jpg", "file:///art/5.jpg", "file:///art/6.jpg"),
            covers[1]
        )
    }

    @Test
    fun `one artwork shared by several songs fills only one tile`() {
        val shared = "file:///art/same.jpg"
        val covers = PlaylistCoverArtwork.build(
            playlists = listOf(
                playlist(1, listOf(song(1, shared), song(2, shared), song(3, shared)))
            ),
            counts = emptyList()
        )

        assertEquals(listOf(shared), covers[1])
    }

    @Test
    fun `a playlist with no songs still has an entry`() {
        val covers = PlaylistCoverArtwork.build(
            playlists = listOf(playlist(1, emptyList())),
            counts = emptyList()
        )

        assertEquals(emptyList<String>(), covers[1])
    }
}
