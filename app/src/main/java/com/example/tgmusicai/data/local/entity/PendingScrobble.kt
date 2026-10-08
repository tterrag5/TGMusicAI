package com.example.tgmusicai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A finished listen that could not be submitted to ListenBrainz yet.
 *
 * A listen is a permanent record of something the user actually did, and the moment it is worth
 * recording -- halfway through a track -- is exactly the moment a phone is most likely to be
 * somewhere with no signal. Before this table a network failure logged a line and dropped the
 * listen for good, so a commute's worth of listening simply never reached the user's history.
 *
 * Rows are deleted once accepted, and also once a listen is too old to be worth submitting: the
 * server stores the timestamp the track *started*, so a very late submission does not corrupt the
 * history, but a row that has failed for a week is failing for a reason retrying will not fix.
 */
@Entity(tableName = "pending_scrobbles")
data class PendingScrobble(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    /** Seconds since the epoch at which the track started, which is what the API stores. */
    val startedAtEpochSeconds: Long,
    /** How many submissions have been tried and failed, which sets how long to wait before the next. */
    val attempts: Int = 0,
    /**
     * Epoch millis before which no submission should be attempted.
     *
     * Holding the next attempt time on the row rather than in memory is what makes the backoff
     * survive a process kill. Without it, a service restart would retry everything immediately, and
     * a queue built up while offline would hammer the server the instant the app came back.
     */
    val nextAttemptAtMs: Long = 0L,
)
