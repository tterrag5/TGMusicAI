package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tgmusicai.data.local.entity.SongFingerprint
import kotlinx.coroutines.flow.Flow

/** Reads and writes the acoustic fingerprint index used to recognise a song by listening. */
@Dao
interface SongFingerprintDao {

    /**
     * Stores one track's landmarks.
     *
     * IGNORE rather than REPLACE on conflict: the composite key makes a repeated landmark a
     * duplicate of something already correct, so there is nothing to overwrite, and IGNORE lets an
     * interrupted indexing pass be re-run without first deleting what it managed to write.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(fingerprints: List<SongFingerprint>)

    /**
     * Every occurrence of the given hashes, across all indexed tracks.
     *
     * One query for the whole query fingerprint rather than one per hash: a recognition attempt
     * involves a few thousand hashes, and a round trip each would take far longer than the
     * recording did.
     */
    @Query("SELECT * FROM song_fingerprints WHERE hash IN (:hashes)")
    suspend fun findByHashes(hashes: List<Int>): List<SongFingerprint>

    /** Which tracks already have a fingerprint, so indexing can skip them. */
    @Query("SELECT DISTINCT songId FROM song_fingerprints")
    suspend fun indexedSongIds(): List<Long>

    /** How many tracks are indexed, for the progress readout in Settings. */
    @Query("SELECT COUNT(DISTINCT songId) FROM song_fingerprints")
    fun indexedSongCount(): Flow<Int>

    /** Drops one track's landmarks, e.g. before re-indexing it after the file changed. */
    @Query("DELETE FROM song_fingerprints WHERE songId = :songId")
    suspend fun deleteForSong(songId: Long)

    /** Drops the whole index. The table is large, and the user is entitled to reclaim the space. */
    @Query("DELETE FROM song_fingerprints")
    suspend fun deleteAll()
}
