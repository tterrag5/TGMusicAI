package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.tgmusicai.data.local.entity.SongStats
import kotlinx.coroutines.flow.Flow

/**
 * DAO for interacting with [SongStats].
 */
@Dao
interface SongStatsDao {
    /** Inserts a new stats row, or overwrites the existing one for that `songId` (one row per song). Prefer [incrementPlayCount]/[addListenTime] over calling this directly for updates, so no fields get clobbered. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stats: SongStats)

    /** Reads the stats row for one song, or null if it has never been played/logged yet. */
    @Query("SELECT * FROM song_stats WHERE songId = :songId")
    suspend fun getStatsForSong(songId: Long): SongStats?

    /** Live top-[limit] songs by play count, descending; backs the "Most Played" list, updating automatically as counts change. */
    @Query("SELECT * FROM song_stats ORDER BY playCount DESC LIMIT :limit")
    fun getMostPlayedStats(limit: Int): Flow<List<SongStats>>

    /** Live top-[limit] songs by most-recent play, descending, excluding never-played songs; backs the "Recently Played" list. */
    @Query("SELECT * FROM song_stats WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun getRecentlyPlayedStats(limit: Int): Flow<List<SongStats>>

    // Sync variant of getRecentlyPlayedStats, used by the Android Auto "Listen Again" media tree
    // category, which must build its browse tree with a one-shot suspend query rather than a Flow.
    @Query("SELECT * FROM song_stats WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun getRecentlyPlayedStatsSync(limit: Int): List<SongStats>

    /** Sync (suspend, one-shot) variant of [getMostPlayedStats], for the Android Auto media-tree builder. */
    @Query("SELECT * FROM song_stats ORDER BY playCount DESC LIMIT :limit")
    suspend fun getMostPlayedStatsSync(limit: Int): List<SongStats>

    // Whole-library sum, independent of any top-N list -- summing a limited mostPlayed list
    // (e.g. top 10) instead of this undercounts every library with more than 10 played songs.
    @Query("SELECT COALESCE(SUM(playCount), 0) FROM song_stats")
    fun getTotalPlayCount(): Flow<Int>

    /** Live sum of listening time across every song, in milliseconds; `COALESCE` guards against a null SUM when the table is empty. Powers the Stats screen's total. */
    @Query("SELECT COALESCE(SUM(totalListenTimeMs), 0) FROM song_stats")
    fun getTotalListenTimeMs(): Flow<Long>

    /** Deletes a song's stats row, e.g. when the song itself is deleted (there is no Room-level cascade here). */
    @Query("DELETE FROM song_stats WHERE songId = :songId")
    suspend fun deleteStatsForSong(songId: Long)

    // Deletes rows left over from the old play-tracking bug where a play could be recorded
    // against a bogus id (e.g. 0) that never corresponded to a real song. Safe to call anytime.
    @Query("DELETE FROM song_stats WHERE songId NOT IN (SELECT id FROM songs)")
    suspend fun deleteOrphanedStats()

    /**
     * Bumps a song's [SongStats.playCount] by one and stamps [SongStats.lastPlayedAt], creating the
     * stats row if the song has never been played before. Called once per completed/counted play
     * (see [com.example.tgmusicai.playback.PlaybackService]) -- read-modify-write inside a
     * `@Transaction` so concurrent calls can't race and drop an increment.
     */
    @Transaction
    suspend fun incrementPlayCount(songId: Long, currentTime: Long = System.currentTimeMillis()) {
        val currentStats = getStatsForSong(songId)
        if (currentStats != null) {
            insertOrUpdate(
                currentStats.copy(
                    playCount = currentStats.playCount + 1,
                    lastPlayedAt = currentTime
                )
            )
        } else {
            insertOrUpdate(
                SongStats(
                    songId = songId,
                    playCount = 1,
                    lastPlayedAt = currentTime
                )
            )
        }
    }

    /** Adds [deltaMs] to a song's cumulative listen time, creating the row if it doesn't exist yet. */
    @Transaction
    suspend fun addListenTime(songId: Long, deltaMs: Long) {
        if (deltaMs <= 0L) return
        val currentStats = getStatsForSong(songId)
        if (currentStats != null) {
            insertOrUpdate(currentStats.copy(totalListenTimeMs = currentStats.totalListenTimeMs + deltaMs))
        } else {
            insertOrUpdate(SongStats(songId = songId, totalListenTimeMs = deltaMs))
        }
    }
}
