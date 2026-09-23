package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.tgmusicai.data.local.entity.ListeningHistory

/** One day's summed listening time, grouped in the local timezone so "today" lines up with the device's calendar day. */
data class DailyListenTotal(val day: String, val totalMs: Long)

/** One logged listening chunk, reduced to what session reconstruction needs. */
data class HistoryEntry(val songId: Long, val timestampMs: Long)

/**
 * DAO for [ListeningHistory], the append-only log of listening-time chunks used to build the Stats
 * screen's daily/weekly listening charts. Used by [com.example.tgmusicai.data.repository.MusicRepository].
 */
@Dao
interface ListeningHistoryDao {
    /** Appends one logged chunk of listening time. Never updates or replaces -- this table is append-only. */
    @Insert
    suspend fun insert(entry: ListeningHistory)

    // Buckets every history row from `sinceMs` onward into local-calendar days and sums their
    // durations per day, using SQLite's strftime with 'localtime' so "today" matches the device's
    // timezone/calendar day rather than UTC. Powers the Stats screen's daily listening chart.
    @Query(
        """
        SELECT strftime('%Y-%m-%d', timestampMs / 1000, 'unixepoch', 'localtime') AS day, SUM(durationMs) AS totalMs
        FROM listening_history
        WHERE timestampMs >= :sinceMs
        GROUP BY day
        """
    )
    suspend fun getDailyTotalsSince(sinceMs: Long): List<DailyListenTotal>

    /**
     * Every logged chunk from [sinceMs] onward, oldest first, for reconstructing listening
     * sessions in [com.example.tgmusicai.data.repository.RecommendationEngine].
     *
     * Rows are 10-second chunks rather than one row per play, so a caller must collapse
     * consecutive runs of the same [HistoryEntry.songId] before treating this as a sequence of
     * plays -- without that step a three-minute song looks like eighteen plays of itself.
     */
    @Query(
        """
        SELECT songId, timestampMs FROM listening_history
        WHERE timestampMs >= :sinceMs
        ORDER BY timestampMs ASC
        """
    )
    suspend fun getEntriesSince(sinceMs: Long): List<HistoryEntry>

    // Cleans up history rows left behind after their song was deleted from the `songs` table (this
    // table has no Room foreign key, so deletion does not cascade here automatically).
    @Query("DELETE FROM listening_history WHERE songId NOT IN (SELECT id FROM songs)")
    suspend fun deleteOrphanedHistory()
}
