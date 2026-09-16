package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tgmusicai.data.local.entity.AiSongTags

/**
 * DAO for [AiSongTags]. Entirely owned by the `ai` package's containment layer -- nothing else in
 * the app should read or write this table.
 */
@Dao
interface AiSongTagsDao {
    /** Inserts a new [AiSongTags] row, or overwrites the existing one for that `songId` (one row per song). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(tags: AiSongTags)

    /** Reads the AI analysis row for one song, or null if it hasn't been analyzed (or tagging failed) yet. */
    @Query("SELECT * FROM ai_song_tags WHERE songId = :songId")
    suspend fun getForSong(songId: Long): AiSongTags?

    /** Reads every AI analysis row in the table, e.g. for radio-mode similarity search across the whole library. */
    @Query("SELECT * FROM ai_song_tags")
    suspend fun getAll(): List<AiSongTags>

    /** Removes the AI analysis row for one song, e.g. so it gets re-analyzed after the song's audio/lyrics change. */
    @Query("DELETE FROM ai_song_tags WHERE songId = :songId")
    suspend fun deleteForSong(songId: Long)

    // Cleans up rows left behind after a song was deleted from the `songs` table (this table has no
    // Room foreign key, so deleting a song does not cascade here automatically). Safe to call anytime.
    @Query("DELETE FROM ai_song_tags WHERE songId NOT IN (SELECT id FROM songs)")
    suspend fun deleteOrphaned()
}
