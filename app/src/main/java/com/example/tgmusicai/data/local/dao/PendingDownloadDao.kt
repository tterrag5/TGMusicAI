package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tgmusicai.data.local.entity.PendingDownload

/**
 * DAO for tracking in-flight cloud downloads so they can be resumed if the app process is killed
 * before they finish.
 */
@Dao
interface PendingDownloadDao {
    /** Records a download as queued/in-progress, or overwrites its row if it was already queued (e.g. re-queued after a retry). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pendingDownload: PendingDownload)

    /** Removes a download's pending-row once it finishes (successfully or is cancelled), so it's no longer offered for resume on next launch. */
    @Query("DELETE FROM pending_downloads WHERE videoId = :videoId")
    suspend fun deleteByVideoId(videoId: String)

    /** Reads every still-pending download, oldest first; called on app start to resume downloads that were interrupted last session. */
    @Query("SELECT * FROM pending_downloads ORDER BY queuedAt ASC")
    suspend fun getAll(): List<PendingDownload>
}
