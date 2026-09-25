package com.example.tgmusicai.data.local.entity

import androidx.room.Entity

/**
 * How many times a song has been played *as part of a particular playlist*.
 *
 * Deliberately separate from [SongStats.playCount], which is the song's total across the whole
 * app. A playlist's identity comes from what people actually reach for inside it, and that is not
 * the same as what they play most overall: a track can be a user's most-played song and still be
 * the one they skip in this playlist. The cover mosaic ranks by this, so a playlist ends up
 * showing the four songs it is actually played for.
 *
 * A row appears the first time a song is played from a playlist, so the table stays proportional
 * to listening rather than to library size.
 */
@Entity(tableName = "playlist_play_counts", primaryKeys = ["playlistId", "songId"])
data class PlaylistPlayCount(
    val playlistId: Long,
    val songId: Long,
    val playCount: Int = 0
)
