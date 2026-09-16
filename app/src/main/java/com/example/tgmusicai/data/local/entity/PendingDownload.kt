package com.example.tgmusicai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A YouTube download that has been queued but not yet finished (or failed mid-download last time
 * the app ran, e.g. the process was killed). Rows are removed once the download completes.
 * Read on app launch to automatically resume any downloads that didn't finish.
 */
@Entity(tableName = "pending_downloads")
data class PendingDownload(
    /** YouTube video ID being downloaded; doubles as the primary key since one video = one queued job. */
    @PrimaryKey
    val videoId: String,
    /** Video title, shown in the downloads/queue UI before the song fully exists locally. */
    val title: String,
    /** Uploading channel name, shown alongside the title in the queue UI. */
    val uploader: String,
    /** Video length in seconds, used for progress/duration display before the audio file is ready. */
    val durationSeconds: Long,
    /** Epoch millis when the download was queued; used to order the pending list. */
    val queuedAt: Long = System.currentTimeMillis()
)
