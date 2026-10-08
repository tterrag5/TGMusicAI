package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.tgmusicai.data.local.entity.PendingScrobble
import kotlinx.coroutines.flow.Flow

/**
 * DAO for listens that still have to reach ListenBrainz.
 *
 * Deliberately not joined into any core query -- the same rule `ai_song_tags`, `song_fingerprints`
 * and `playlist_play_counts` follow. The table can be dropped wholesale without affecting the
 * library, and nothing about playback depends on reading it.
 */
@Dao
interface PendingScrobbleDao {
    @Insert
    suspend fun insert(pendingScrobble: PendingScrobble)

    /**
     * Listens that are due a submission attempt now, oldest first.
     *
     * Ordered by when the track was played rather than when the row was written, so a user's
     * history is rebuilt in the order they actually listened. Capped so one flush cannot turn a
     * long offline stretch into hundreds of back-to-back requests.
     */
    @Query(
        """
        SELECT * FROM pending_scrobbles
        WHERE nextAttemptAtMs <= :nowMs
        ORDER BY startedAtEpochSeconds ASC
        LIMIT :limit
        """
    )
    suspend fun getDue(nowMs: Long, limit: Int): List<PendingScrobble>

    /** Accepted by the server, so the row has done its job. */
    @Query("DELETE FROM pending_scrobbles WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Records a failed attempt and when the next one may happen. */
    @Query("UPDATE pending_scrobbles SET attempts = :attempts, nextAttemptAtMs = :nextAttemptAtMs WHERE id = :id")
    suspend fun recordAttempt(id: Long, attempts: Int, nextAttemptAtMs: Long)

    /** Drops listens old enough that retrying is no longer worthwhile. */
    @Query("DELETE FROM pending_scrobbles WHERE startedAtEpochSeconds < :cutoffEpochSeconds")
    suspend fun deleteOlderThan(cutoffEpochSeconds: Long)

    /** How many listens are still waiting, for the count shown in Settings. */
    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    fun countFlow(): Flow<Int>
}
