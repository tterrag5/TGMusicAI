package com.example.tgmusicai.data.repository

import com.example.tgmusicai.data.local.entity.PlaylistPlayCount
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs

/**
 * Chooses the artwork a playlist's cover is built from: up to four of its songs, most-played first.
 *
 * "Most played" means most played *from this playlist*, not across the app. A song can be the
 * user's most-played track overall and still be the one they always skip in a particular playlist,
 * so ranking by the app-wide count would put the wrong music on the cover. Equal counts keep the
 * playlist's own order, which matters for more than tidiness: without a stable tiebreak the four
 * tiles would reshuffle every time the counts flow re-emitted.
 *
 * A playlist nobody has played from yet has no counts at all and falls through to its first four
 * songs -- which is the right answer for a fresh import, and the only possible one for a smart
 * playlist like Cloud Nine, whose contents are by definition unlistened.
 *
 * Pure and Android-free so it can be unit-tested; both the Playlists grid and the Home rows use it,
 * rather than each deriving covers its own way and disagreeing about the same playlist.
 */
object PlaylistCoverArtwork {
    /** How many tiles a cover mosaic shows: a 2x2 grid. */
    const val TILE_COUNT = 4

    fun build(
        playlists: List<PlaylistWithSongs>,
        counts: List<PlaylistPlayCount>,
        limit: Int = TILE_COUNT
    ): Map<Long, List<String>> {
        val countsByPlaylist = counts.groupBy { it.playlistId }
        return playlists.associate { pws ->
            val plays = countsByPlaylist[pws.playlist.playlistId]
                ?.associate { it.songId to it.playCount }
                .orEmpty()
            // sortedByDescending is stable, so songs nobody has played keep their playlist order.
            val ranked =
                if (plays.isEmpty()) pws.songs else pws.songs.sortedByDescending { plays[it.id] ?: 0 }
            pws.playlist.playlistId to ranked
                .mapNotNull { song -> song.artworkUri?.takeIf { it.isNotBlank() } }
                .distinct()
                .take(limit)
        }
    }
}
