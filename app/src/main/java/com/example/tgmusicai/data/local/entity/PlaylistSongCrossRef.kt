package com.example.tgmusicai.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A junction table entity that represents the many-to-many relationship between [Playlist] and [Song].
 * We use this because a single playlist can contain multiple songs, and a single song can be in multiple playlists.
 * This is the standard relational database way of handling many-to-many relationships.
 */
@Entity(
    tableName = "playlist_song_cross_ref",
    primaryKeys = ["playlistId", "songId"],
    // Indexing songId improves query performance when finding all playlists for a specific song.
    indices = [
        Index(value = ["songId"]) 
    ]
)
data class PlaylistSongCrossRef(
    /**
     * ID of the playlist this entry refers to.
     */
    val playlistId: Long,
    
    /**
     * ID of the song this entry refers to.
     */
    val songId: Long,
    
    /**
     * The order of the song within the playlist.
     * This allows us to keep the user's custom ordering of songs in a playlist.
     */
    val position: Int = 0
)
