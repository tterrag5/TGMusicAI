package com.example.tgmusicai.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tgmusicai.data.local.dao.AiSongTagsDao
import com.example.tgmusicai.data.local.entity.AiSongTags
import com.example.tgmusicai.data.local.entity.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * The single entry point the rest of the app should use for on-device AI features (audio tagging
 * via [SongTaggingEngine], lyrics embeddings via [LyricsEmbeddingEngine]). This is the containment
 * boundary: every function here catches everything, on its own [SupervisorJob]-backed scope, and
 * degrades to "did nothing" rather than throwing -- a broken model file, an OOM on a low-end
 * device, or a decode failure on one weird file can only ever cost that one feature for that one
 * song, never the app around it. Results are cached in the standalone `ai_song_tags` table
 * ([AiSongTagsDao]) so nothing here needs to be recomputed once it has succeeded.
 */
class AiFeatureManager(
    context: Context,
    private val aiSongTagsDao: AiSongTagsDao
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val taggingEngine by lazy { SongTaggingEngine(appContext) }
    private val embeddingEngine by lazy { LyricsEmbeddingEngine(appContext) }
    private val musicEmbeddingEngine by lazy { MusicEmbeddingEngine(appContext) }
    private val lyricSemanticsEngine by lazy { LyricSemanticsEngine(embeddingEngine) }

    data class AnalysisOutcome(val tags: List<String>, val hasLyricsEmbedding: Boolean)

    /**
     * Best-effort: tags [song]'s audio and, if it has lyrics, embeds them -- whatever succeeds is
     * cached, whatever fails is simply omitted. Never throws; returns an empty outcome on total
     * failure. Safe to call from a UI action (runs on its own IO scope) or a background job alike.
     */
    suspend fun analyzeSong(song: Song): AnalysisOutcome = withContext(scope.coroutineContext) {
        val localPath = localFilePath(song.mediaUri)

        val tags = try {
            localPath?.let { path ->
                when (val result = taggingEngine.tagAudioFile(path)) {
                    is AiModelResult.Success -> result.value
                    is AiModelResult.Unavailable -> {
                        Log.d(TAG, "Song tagging unavailable for song ${song.id}: ${result.reason}")
                        emptyList()
                    }
                    is AiModelResult.Error -> {
                        Log.e(TAG, "Song tagging failed for song ${song.id}", result.throwable)
                        emptyList()
                    }
                }
            } ?: emptyList()
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error tagging song ${song.id} -- feature stays up for other songs", e)
            emptyList()
        }

        // The acoustic fingerprint recommendations rank on. A separate model from the tagger:
        // YAMNet names sounds, EffNet-Discogs places music in a musical space.
        val audioProfile = try {
            localPath?.let { path ->
                when (val result = musicEmbeddingEngine.embedAudioFile(path)) {
                    is AiModelResult.Success -> AudioProfileCodec.encode(result.value)
                    is AiModelResult.Unavailable -> {
                        Log.d(TAG, "Music embedding unavailable for song ${song.id}: ${result.reason}")
                        null
                    }
                    is AiModelResult.Error -> {
                        Log.e(TAG, "Music embedding failed for song ${song.id}", result.throwable)
                        null
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error embedding song ${song.id} -- feature stays up for other songs", e)
            null
        }

        val embedding = try {
            song.lyrics?.takeIf { it.isNotBlank() }?.let { lyrics ->
                when (val result = embeddingEngine.embed(lyrics)) {
                    is AiModelResult.Success -> result.value
                    else -> null
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error embedding lyrics for song ${song.id} -- feature stays up for other songs", e)
            null
        }

        // What the lyrics are about, and what language they are in. Both come from the embedding
        // that was just computed, so tagging costs a few dot products rather than another model.
        val lyricThemes = try {
            embedding?.let { lyricSemanticsEngine.themesFor(it) }.orEmpty()
        } catch (e: Throwable) {
            Log.e(TAG, "Theme tagging failed for song ${song.id} -- feature stays up for other songs", e)
            emptyList()
        }

        val lyricsLanguage = try {
            song.lyrics?.takeIf { it.isNotBlank() }?.let { lyricSemanticsEngine.languageOf(it) }
        } catch (e: Throwable) {
            Log.e(TAG, "Language identification failed for song ${song.id}", e)
            null
        }

        try {
            if (tags.isNotEmpty() || embedding != null || audioProfile != null) {
                aiSongTagsDao.insertOrUpdate(
                    AiSongTags(
                        songId = song.id,
                        tags = tags.takeIf { it.isNotEmpty() }?.joinToString(","),
                        lyricsEmbedding = embedding?.joinToString(",") { it.toString() },
                        audioProfile = audioProfile,
                        lyricThemes = lyricThemes.takeIf { it.isNotEmpty() }?.joinToString(","),
                        lyricsLanguage = lyricsLanguage
                    )
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist AI tags for song ${song.id} (analysis itself succeeded)", e)
        }

        AnalysisOutcome(tags = tags, hasLyricsEmbedding = embedding != null)
    }

    /**
     * Analyzes [song] only if it hasn't been analyzed before (no cached `ai_song_tags` row yet).
     * Used for auto-tagging a freshly downloaded song without ever redundantly re-analyzing one
     * that's already tagged. Returns null (no-op) if a row already exists or on any failure.
     */
    suspend fun analyzeSongIfNeeded(song: Song): AnalysisOutcome? = withContext(scope.coroutineContext) {
        val alreadyAnalyzed = try {
            val existing = aiSongTagsDao.getForSong(song.id)
            // A row is re-analyzed when its acoustic profile is missing or was written by an older
            // model, but only when there is a local file to analyze -- otherwise every cloud-only
            // song would be retried on every backfill pass, forever, and never gain a profile.
            // The dimension check is what lets a model change heal itself: profiles of the wrong
            // width contribute nothing to similarity, and this replaces them on the next backfill.
            val storedWidth = AudioProfileCodec.decode(existing?.audioProfile)?.size
            val staleProfile = existing != null &&
                storedWidth != MusicEmbeddingEngine.EMBEDDING_DIM &&
                localFilePath(song.mediaUri) != null
            // A row analyzed before lyric themes existed is re-run too, but only when the song has
            // lyrics to read -- an instrumental will never have themes and must not be retried on
            // every pass.
            val missingLyricAnalysis = existing != null &&
                existing.lyricThemes.isNullOrBlank() &&
                !song.lyrics.isNullOrBlank()
            existing != null && !staleProfile && !missingLyricAnalysis
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check existing AI tags for song ${song.id}, skipping to be safe", e)
            true
        }
        if (alreadyAnalyzed) return@withContext null
        analyzeSong(song)
    }

    /**
     * One-time library backfill: analyzes every song in [songs] that doesn't already have a
     * cached result, skipping (and logging, not throwing) any individual failure so one bad file
     * can't stop the rest of the library from being tagged.
     */
    suspend fun backfillAll(songs: List<Song>) {
        for (song in songs) {
            try {
                analyzeSongIfNeeded(song)
            } catch (e: Throwable) {
                Log.e(TAG, "Backfill failed for song ${song.id}, continuing with the rest", e)
            }
        }
    }

    /**
     * Ranks [candidateSongIds] by lyrical similarity to [seedSongId] using cached embeddings only
     * (never triggers new analysis) -- for feeding into "Start Radio"-style grouping. Returns an
     * empty list if the seed has no cached embedding or on any failure.
     */
    suspend fun rankBySimilarLyrics(seedSongId: Long, candidateSongIds: List<Long>, limit: Int = 20): List<Long> =
        withContext(scope.coroutineContext) {
            try {
                // One query for the whole table rather than one per candidate. The previous version
                // made a database round-trip for every song in the library on every call.
                val rows = aiSongTagsDao.getAll().associateBy { it.songId }
                val seedEmbedding = rows[seedSongId]?.lyricsEmbedding?.let(::parseEmbedding)
                    ?: return@withContext emptyList()
                candidateSongIds
                    .mapNotNull { id ->
                        val embedding = rows[id]?.lyricsEmbedding?.let(::parseEmbedding) ?: return@mapNotNull null
                        id to LyricsEmbeddingEngine.cosineSimilarity(seedEmbedding, embedding)
                    }
                    .sortedByDescending { it.second }
                    .take(limit)
                    .map { it.first }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to rank songs by lyrical similarity", e)
                emptyList()
            }
        }

    /**
     * Ranks [candidateSongIds] by how close they *sound* to [seedSongId], using the cached acoustic
     * profiles only -- never triggers new analysis. Returns an empty list when the seed has no
     * profile (a cloud-only track, or one not analyzed yet) or on any failure.
     */
    suspend fun rankBySimilarAudio(seedSongId: Long, candidateSongIds: List<Long>, limit: Int = 20): List<Long> =
        withContext(scope.coroutineContext) {
            try {
                val rows = aiSongTagsDao.getAll().associateBy { it.songId }
                val seedProfile = AudioProfileCodec.decode(rows[seedSongId]?.audioProfile)
                    ?: return@withContext emptyList()
                candidateSongIds
                    .mapNotNull { id ->
                        val profile = AudioProfileCodec.decode(rows[id]?.audioProfile) ?: return@mapNotNull null
                        id to AudioProfileCodec.cosineSimilarity(seedProfile, profile)
                    }
                    .sortedByDescending { it.second }
                    .take(limit)
                    .map { it.first }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to rank songs by acoustic similarity", e)
                emptyList()
            }
        }

    private fun parseEmbedding(csv: String): FloatArray? =
        try {
            csv.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            null
        }

    /** Mirrors the local-file-path resolution already used elsewhere (e.g. `MusicRepository.deleteSongsCompletely`). */
    private fun localFilePath(mediaUri: String): String? = when {
        mediaUri.startsWith("file:") -> Uri.parse(mediaUri).path
        mediaUri.startsWith("/") -> mediaUri
        else -> null // remote/content URI: not supported by this pass, skip rather than risk a network fetch mid-tagging
    }

    companion object {
        private const val TAG = "AiFeatureManager"
    }
}
