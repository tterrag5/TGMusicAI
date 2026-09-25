package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.tgmusicai.data.local.entity.PlaylistPlayCount
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [PlaylistPlayCount]: how often each song is played from each playlist.
 */
@Dao
interface PlaylistPlayCountDao {
    /**
     * Counts one play of [songId] from [playlistId], creating the row if this is the first.
     *
     * Written as an upsert in SQL rather than a read-modify-write in Kotlin so two plays landing at
     * once cannot both read the same old count and store the same new one.
     */
    @Query(
        """
        INSERT INTO playlist_play_counts (playlistId, songId, playCount) VALUES (:playlistId, :songId, 1)
        ON CONFLICT(playlistId, songId) DO UPDATE SET playCount = playCount + 1
        """
    )
    suspend fun incrementPlay(playlistId: Long, songId: Long)

    /** Every recorded per-playlist count, for building playlist covers in one pass. */
    @Query("SELECT * FROM playlist_play_counts WHERE playCount > 0")
    fun observeAll(): Flow<List<PlaylistPlayCount>>

    /** Drops a playlist's counts, so deleting and recreating a playlist does not inherit them. */
    @Query("DELETE FROM playlist_play_counts WHERE playlistId = :playlistId")
    suspend fun clearForPlaylist(playlistId: Long)
}
