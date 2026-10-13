package com.example.tgmusicai.data.repository

import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.local.entity.SongStats

/** The Library's sort dropdown, like YT Music's ("Recently played" by default). */
enum class LibrarySort(val label: String) {
    RECENTLY_PLAYED("Recently played"),
    MOST_PLAYED("Most played"),
    RECENTLY_ADDED("Recently added"),
    A_TO_Z("A-Z"),
}

/** An artist in the library, grouped from its songs. */
data class LibraryArtist(
    val name: String,
    val songCount: Int,
    val artworkUri: String?,
    val plays: Int,
    val lastPlayedAt: Long,
    val newestSongId: Long,
)

/** An album in the library, grouped from its songs. */
data class LibraryAlbum(
    val title: String,
    val artist: String,
    val songCount: Int,
    val artworkUri: String?,
    val plays: Int,
    val lastPlayedAt: Long,
    val newestSongId: Long,
)

/**
 * Sorting and grouping behind the Library's pills. Pure, so it is unit-tested.
 *
 * Liked Music always comes first among playlists (user, T031); everything else follows the chosen
 * sort. A playlist's plays are the plays started from it ([playlistPlays]); its "last played" is
 * the most recent play of any of its songs, since plays are not timestamped per playlist.
 */
object LibraryOrganizer {

    const val LIKED_MUSIC = "Liked Music"

    fun sortPlaylists(
        playlists: List<PlaylistWithSongs>,
        playlistPlays: Map<Long, Int>,
        stats: Map<Long, SongStats>,
        sort: LibrarySort,
    ): List<PlaylistWithSongs> {
        val (liked, rest) = playlists.partition { it.playlist.isSmart && it.playlist.name == LIKED_MUSIC }
        val sorted = when (sort) {
            LibrarySort.RECENTLY_PLAYED -> rest.sortedWith(
                compareByDescending<PlaylistWithSongs> { pws -> pws.songs.maxOfOrNull { stats[it.id]?.lastPlayedAt ?: 0L } ?: 0L }
                    .thenByDescending { playlistPlays[it.playlist.playlistId] ?: 0 }
            )
            LibrarySort.MOST_PLAYED -> rest.sortedWith(
                compareByDescending<PlaylistWithSongs> { playlistPlays[it.playlist.playlistId] ?: 0 }
                    .thenBy { it.playlist.name.lowercase() }
            )
            LibrarySort.RECENTLY_ADDED -> rest.sortedByDescending { it.playlist.createdAt }
            LibrarySort.A_TO_Z -> rest.sortedBy { it.playlist.name.lowercase() }
        }
        return liked + sorted
    }

    fun sortSongs(songs: List<Song>, stats: Map<Long, SongStats>, sort: LibrarySort): List<Song> = when (sort) {
        LibrarySort.RECENTLY_PLAYED -> songs.sortedWith(
            compareByDescending<Song> { stats[it.id]?.lastPlayedAt ?: 0L }.thenByDescending { it.id }
        )
        LibrarySort.MOST_PLAYED -> songs.sortedWith(
            compareByDescending<Song> { stats[it.id]?.playCount ?: 0 }.thenBy { it.title.lowercase() }
        )
        LibrarySort.RECENTLY_ADDED -> songs.sortedByDescending { it.id }
        LibrarySort.A_TO_Z -> songs.sortedBy { it.title.lowercase() }
    }

    fun artists(songs: List<Song>, stats: Map<Long, SongStats>, sort: LibrarySort): List<LibraryArtist> {
        val grouped = songs
            .filter { it.artist.isNotBlank() && !it.artist.equals("<unknown>", true) && !it.artist.equals("Unknown Artist", true) }
            .groupBy { it.artist.trim() }
            .map { (name, group) ->
                LibraryArtist(
                    name = name,
                    songCount = group.size,
                    artworkUri = group.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) },
                    plays = group.sumOf { stats[it.id]?.playCount ?: 0 },
                    lastPlayedAt = group.maxOf { stats[it.id]?.lastPlayedAt ?: 0L },
                    newestSongId = group.maxOf { it.id },
                )
            }
        return when (sort) {
            LibrarySort.RECENTLY_PLAYED -> grouped.sortedWith(compareByDescending<LibraryArtist> { it.lastPlayedAt }.thenByDescending { it.plays })
            LibrarySort.MOST_PLAYED -> grouped.sortedWith(compareByDescending<LibraryArtist> { it.plays }.thenBy { it.name.lowercase() })
            LibrarySort.RECENTLY_ADDED -> grouped.sortedByDescending { it.newestSongId }
            LibrarySort.A_TO_Z -> grouped.sortedBy { it.name.lowercase() }
        }
    }

    fun albums(songs: List<Song>, stats: Map<Long, SongStats>, sort: LibrarySort): List<LibraryAlbum> {
        val grouped = songs
            .filter { it.album.isNotBlank() && !it.album.equals("<unknown>", true) && !it.album.equals("Unknown Album", true) }
            .groupBy { it.album.trim().lowercase() to it.artist.trim().lowercase() }
            .map { (_, group) ->
                LibraryAlbum(
                    title = group.first().album.trim(),
                    artist = group.first().artist.trim(),
                    songCount = group.size,
                    artworkUri = group.firstNotNullOfOrNull { it.artworkUri?.takeIf(String::isNotBlank) },
                    plays = group.sumOf { stats[it.id]?.playCount ?: 0 },
                    lastPlayedAt = group.maxOf { stats[it.id]?.lastPlayedAt ?: 0L },
                    newestSongId = group.maxOf { it.id },
                )
            }
        return when (sort) {
            LibrarySort.RECENTLY_PLAYED -> grouped.sortedWith(compareByDescending<LibraryAlbum> { it.lastPlayedAt }.thenByDescending { it.plays })
            LibrarySort.MOST_PLAYED -> grouped.sortedWith(compareByDescending<LibraryAlbum> { it.plays }.thenBy { it.title.lowercase() })
            LibrarySort.RECENTLY_ADDED -> grouped.sortedByDescending { it.newestSongId }
            LibrarySort.A_TO_Z -> grouped.sortedBy { it.title.lowercase() }
        }
    }
}
