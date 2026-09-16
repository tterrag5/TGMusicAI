package com.example.tgmusicai.data.local.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * A helper class for Room to retrieve a [Playlist] along with all of its associated [Song]s in a single query.
 * This is not a direct database table, but rather a view model for Room queries.
 */
data class PlaylistWithSongs(
    // The playlist information
    @Embedded val playlist: Playlist,
    
    // The songs associated with this playlist via the cross-reference table
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "id",
        associateBy = Junction(PlaylistSongCrossRef::class, parentColumn = "playlistId", entityColumn = "songId")
    )
    val songs: List<Song>
)
