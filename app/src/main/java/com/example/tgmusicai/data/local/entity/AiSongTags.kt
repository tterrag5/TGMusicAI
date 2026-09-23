package com.example.tgmusicai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Best-effort AI-derived metadata for a song: audio tags (from [com.example.tgmusicai.ai.SongTaggingEngine])
 * and/or a lyrics embedding (from [com.example.tgmusicai.ai.LyricsEmbeddingEngine]), used for grouping/
 * radio-mode similarity. Deliberately its own standalone table rather than columns on [Song] -- the AI
 * container writes here and nowhere else, so a bug in that code can never corrupt the core `songs` table.
 * Rows are optional and disposable: missing/stale rows just mean "not analyzed yet," never a broken state.
 */
@Entity(tableName = "ai_song_tags")
data class AiSongTags(
    @PrimaryKey
    val songId: Long,
    /** Comma-separated tag names from [com.example.tgmusicai.ai.SongTaggingEngine], e.g. "Rock music,Electric guitar". */
    val tags: String? = null,
    /** 384-dim MiniLM sentence embedding of the song's lyrics, comma-separated floats. Null if no lyrics were available. */
    val lyricsEmbedding: String? = null,
    /**
     * Acoustic profile: the song's mean-pooled YAMNet class-score vector, quantized and hex-encoded
     * by [com.example.tgmusicai.ai.AudioProfileCodec]. Null for a song with no local audio file
     * (cloud-only tracks are never tagged) or one analyzed before this column existed.
     */
    val audioProfile: String? = null,
    /**
     * Comma-separated themes the lyrics are about, from [com.example.tgmusicai.ai.LyricThemes].
     * Null when the song has no lyrics, or when none cleared the tagging threshold.
     */
    val lyricThemes: String? = null,
    /**
     * BCP-47 tag of the language the lyrics are in (`en`, `es`, `pt`, ...), or null when it could
     * not be determined. Never guessed: grouping songs by a wrongly detected language is worse
     * than not grouping them at all.
     */
    val lyricsLanguage: String? = null,
    /** Epoch millis when this row was last (re)computed; used to detect stale analysis after song edits. */
    val computedAt: Long = System.currentTimeMillis()
)
