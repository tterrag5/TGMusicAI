package com.example.tgmusicai.data.repository

/**
 * Smart Shuffle: a playlist played shuffled, with a recommended track after every few playlist
 * tracks, so the playlist is heard with new music woven through it rather than tacked on the end.
 */
object SmartShuffle {

    /** Playlist tracks between two recommendations. */
    const val PLAYLIST_TRACKS_PER_RECOMMENDATION = 3

    /** Recommendations wanted for a playlist of [playlistSize] tracks. */
    fun recommendationsWanted(playlistSize: Int): Int =
        if (playlistSize <= 0) 0 else (playlistSize + PLAYLIST_TRACKS_PER_RECOMMENDATION - 1) / PLAYLIST_TRACKS_PER_RECOMMENDATION

    /**
     * [playlist] (already in play order) with one of [recommendations] after every
     * [PLAYLIST_TRACKS_PER_RECOMMENDATION] tracks. Recommendations that are in the playlist are
     * skipped, and leftover recommendations are not appended: the playlist sets the length.
     */
    fun <T> interleave(playlist: List<T>, recommendations: List<T>, key: (T) -> Any): List<T> {
        val inPlaylist = playlist.map(key).toSet()
        val extras = recommendations.filter { key(it) !in inPlaylist }.distinctBy(key).iterator()
        val result = ArrayList<T>(playlist.size + playlist.size / PLAYLIST_TRACKS_PER_RECOMMENDATION + 1)
        playlist.forEachIndexed { index, track ->
            result += track
            if ((index + 1) % PLAYLIST_TRACKS_PER_RECOMMENDATION == 0 && extras.hasNext()) {
                result += extras.next()
            }
        }
        return result
    }
}
