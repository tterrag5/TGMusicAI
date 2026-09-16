package com.example.tgmusicai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity that stores playback statistics for a given [Song].
 * Separating this from the Song table is good practice to avoid updating the main Song record 
 * every time it is played, reducing contention.
 * Useful for building features like "Most Played" or "Recently Played" lists.
 */
@Entity(tableName = "song_stats")
data class SongStats(
    /**
     * The ID of the song these statistics apply to.
     * It functions as both the primary key of this table and a foreign key to the [Song] table.
     */
    @PrimaryKey
    val songId: Long,
    
    /**
     * The number of times this song has been played.
     */
    val playCount: Int = 0,
    
    /**
     * Timestamp (in milliseconds) of when this song was last played.
     */
    val lastPlayedAt: Long? = null,

    /**
     * Cumulative milliseconds of actual playback time for this song, flushed periodically during
     * playback rather than on every position tick (see [com.example.tgmusicai.playback.PlaybackService]).
     * Powers "total listening time" stats that a bare play count can't express.
     */
    val totalListenTimeMs: Long = 0L
)
