package com.example.tgmusicai.ai

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.tasks.await

/**
 * Works out what a song's lyrics are *about* and what language they are in.
 *
 * Both answers feed recommendations. Sound similarity groups songs that resemble each other
 * acoustically, which says nothing about subject: a furious song about class and a furious song
 * about a breakup can be near-identical to an audio model. Themes are what let the engine put two
 * songs together because they are arguing the same thing.
 *
 * Themes are zero-shot, using the sentence-embedding model already bundled for lyrics: each theme
 * in [LyricThemes] is described in a phrase, those phrases are embedded once, and a song's lyrics
 * are tagged with whichever descriptions they sit closest to. No classifier is trained and no
 * model ships for this.
 *
 * Language comes from ML Kit's on-device identifier -- no API key, no network once installed.
 *
 * Follows the containment rules the rest of `ai/` follows: every failure degrades to "unknown"
 * rather than throwing, and nothing here can reach playback.
 */
class LyricSemanticsEngine(private val embeddingEngine: LyricsEmbeddingEngine) {

    /** Theme descriptions embedded once, mean-centred, and reused for every song. */
    @Volatile
    private var themeVectors: List<Pair<String, FloatArray>>? = null

    /** The average theme vector, subtracted from everything before comparison. See [ensureThemeVectors]. */
    @Volatile
    private var themeMean: FloatArray? = null
    private val themeLock = Any()

    /**
     * Themes [lyricsEmbedding] stands out towards, best first, or an empty list when none clears
     * [LyricThemes.STANDOUT_THRESHOLD].
     *
     * Takes the song's already-computed embedding rather than its text, so tagging costs a handful
     * of dot products on top of an embedding the analyzer produced anyway.
     */
    fun themesFor(lyricsEmbedding: FloatArray): List<String> {
        val vectors = ensureThemeVectors() ?: return emptyList()
        return try {
            // The song is centred by the same mean as the themes, so both sides are compared on
            // what distinguishes them rather than on what every English sentence has in common.
            val centredLyrics = centre(lyricsEmbedding)
            val scores = vectors.map { (name, vector) ->
                name to LyricsEmbeddingEngine.cosineSimilarity(centredLyrics, vector)
            }

            // Score against the spread of this song's own similarities rather than against an
            // absolute cut-off. Sentence embeddings are anisotropic: a few vectors sit close to
            // everything, so raw cosine ranked hub themes above the right ones -- lyrics about a
            // bombed village came back tagged "travel and escape" while "war and conflict" never
            // appeared at all. Measuring how far a theme stands out from the rest for this song
            // removes the hubs, because a theme close to every song is close to this one too and
            // so gains nothing.
            val mean = scores.sumOf { it.second.toDouble() } / scores.size
            val variance = scores.sumOf { (it.second - mean) * (it.second - mean) } / scores.size
            val deviation = kotlin.math.sqrt(variance)
            if (deviation <= 0.0) return emptyList()

            scores
                .map { (name, score) -> name to ((score - mean) / deviation).toFloat() }
                .filter { it.second >= LyricThemes.STANDOUT_THRESHOLD }
                .sortedByDescending { it.second }
                .take(LyricThemes.MAX_THEMES_PER_SONG)
                .map { it.first }
        } catch (e: Throwable) {
            Log.e(TAG, "Theme tagging failed; the song simply goes untagged", e)
            emptyList()
        }
    }

    /**
     * The BCP-47 language tag of [lyricsText] (for example `en`, `es`, `pt`), or null when ML Kit
     * cannot tell. Returns null rather than guessing: a wrong language is worse than no language,
     * because it would group songs by a property they do not share.
     */
    suspend fun languageOf(lyricsText: String): String? {
        if (lyricsText.isBlank()) return null
        return try {
            val identified = LanguageIdentification.getClient()
                .identifyLanguage(lyricsText.take(MAX_LANGUAGE_SAMPLE_CHARS))
                .await()
            identified.takeIf { it != UNDETERMINED_LANGUAGE }
        } catch (e: Throwable) {
            Log.e(TAG, "Language identification failed for this song", e)
            null
        }
    }

    /** Embeds every theme description once, or returns null if the embedding model is unavailable. */
    private fun ensureThemeVectors(): List<Pair<String, FloatArray>>? {
        themeVectors?.let { return it }
        synchronized(themeLock) {
            themeVectors?.let { return it }
            val raw = try {
                LyricThemes.DESCRIPTIONS.mapNotNull { (name, description) ->
                    when (val result = embeddingEngine.embed(description)) {
                        is AiModelResult.Success -> name to result.value
                        else -> null
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Could not embed the theme descriptions; theme tagging stays off", e)
                emptyList()
            }
            if (raw.isEmpty()) return null

            // Subtract the average theme vector from every theme vector.
            //
            // Sentence embeddings share a large common component -- every English sentence points
            // broadly the same way -- and it swamps the part that actually distinguishes one
            // subject from another. Removing it is what makes the comparison about subject rather
            // than about being an English sentence. Without this, lyrics about a bombed village
            // matched "travel and escape" and "home and belonging" while "war and conflict" never
            // ranked at all.
            themeMean = meanOf(raw.map { it.second })
            val centred = raw.map { (name, vector) -> name to centre(vector) }

            themeVectors = centred
            return centred
        }
    }

    private fun meanOf(vectors: List<FloatArray>): FloatArray {
        val mean = FloatArray(LyricsEmbeddingEngine.EMBEDDING_DIM)
        vectors.forEach { vector ->
            for (i in mean.indices) mean[i] += vector[i]
        }
        for (i in mean.indices) mean[i] /= vectors.size
        return mean
    }

    /** Subtracts the shared component and re-normalizes, so cosine stays meaningful afterwards. */
    private fun centre(vector: FloatArray): FloatArray {
        val mean = themeMean ?: return vector
        if (mean.size != vector.size) return vector
        val centred = FloatArray(vector.size) { vector[it] - mean[it] }
        var sum = 0.0
        for (v in centred) sum += v.toDouble() * v
        val norm = kotlin.math.sqrt(sum).toFloat()
        if (norm <= 0f || !norm.isFinite()) return centred
        for (i in centred.indices) centred[i] /= norm
        return centred
    }

    private companion object {
        const val TAG = "LyricSemanticsEngine"

        /** ML Kit's sentinel for "could not tell". */
        const val UNDETERMINED_LANGUAGE = "und"

        /** Enough text to identify a language without handing the identifier a whole transcript. */
        const val MAX_LANGUAGE_SAMPLE_CHARS = 2000
    }
}
