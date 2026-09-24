package com.example.tgmusicai.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One landmark hash from one track's acoustic fingerprint, for recognising a song by listening.
 *
 * A standalone table with no foreign key to `songs`, following the same containment rule
 * [AiSongTags] does: this is an optional feature the user switches on, and nothing about it should
 * be able to cascade into the core library. Removing the whole table is a supported operation and
 * costs only the ability to recognise songs.
 *
 * The composite primary key means re-indexing a track cannot produce duplicate rows if a previous
 * pass was interrupted partway through. Hashes are indexed because recognition looks up several
 * thousand of them per attempt and a scan would make that unusable.
 *
 * Rows are numerous by nature -- a few thousand per track -- which is why building the index is
 * opt-in rather than something that happens to every library automatically.
 */
@Entity(
    tableName = "song_fingerprints",
    primaryKeys = ["songId", "hash", "frameIndex"],
    indices = [Index(value = ["hash"]), Index(value = ["songId"])]
)
data class SongFingerprint(
    /** The `songs.id` this landmark came from. Not a foreign key, deliberately. */
    val songId: Long,
    /** Packed peak-pair hash; see [com.example.tgmusicai.ai.AudioFingerprinter.packHash]. */
    val hash: Int,
    /** How many spectrogram frames into the track this landmark occurred. */
    val frameIndex: Int
)
