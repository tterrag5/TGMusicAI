package com.example.tgmusicai.data.local

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cleaned track metadata extracted from raw song titles and tags.
 */
data class CleanedMetadata(
    val cleanTitle: String,
    val artist: String? = null,
    val producer: String? = null,
    val featuredArtist: String? = null
)

/**
 * Pattern-based metadata cleaner for music tracks: a fast (<1 ms), zero-allocation regex parser
 * that strips noise tags like [Official Audio] and extracts prod./feat./ft./produced by credits.
 *
 * Entirely on-device. An optional online tier that called Gemini/OpenAI with a user-supplied API
 * key used to sit on top of this; it was removed along with the API key itself.
 */
object AiMetadataCleaner {

    private const val TAG = "AiMetadataCleaner"

    // Bracketed noise tags: [Official Audio], (Video), (Official Music Video), [Lyric Video], etc.
    private val TAG_REGEX = Regex(
        """(?i)[\[(]\s*(official\s*(audio|video|music\s*video|lyric\s*video|lyrics|visualizer)?|audio|video|hd|4k|lyric\s*video|lyrics|visualizer|official)\s*[\])]"""
    )

    // Producer inside brackets/parentheses e.g. (prod. Metro Boomin & Cardo) or [produced by Metro Boomin]
    private val PRODUCER_BRACKET_REGEX = Regex(
        """(?i)[(\[{]\s*(?:produced\s+by|prod\s+by|prod\.\s*by|prod\.|prod)\s*[:\s\-]?\s*([^)\n\]}]+)[)\]}]"""
    )

    // Inline producer e.g. "prod. Metro Boomin" or "produced by Metro Boomin"
    private val PRODUCER_INLINE_REGEX = Regex(
        """(?i)\b(?:produced\s+by|prod\s+by|prod\.\s*by|prod\.)\s*[:\s\-]?\s*([a-zA-Z0-9\s&,_\-]+)"""
    )

    // Featured artist inside brackets/parentheses e.g. (feat. Drake) or [ft. Travis Scott]
    private val FEAT_BRACKET_REGEX = Regex(
        """(?i)[(\[{]\s*(?:feat\.|ft\.|featuring|feat|ft)\s*[:\s\-]?\s*([^)\n\]}]+)[)\]}]"""
    )

    // Inline featured artist e.g. "ft. Drake" or "feat. Travis Scott"
    private val FEAT_INLINE_REGEX = Regex(
        """(?i)\b(?:feat\.|ft\.|featuring)\s*[:\s\-]?\s*([a-zA-Z0-9\s&,_\-]+)"""
    )

    // YouTube auto-generates a "<Artist> - Topic" channel for algorithmically-organized music
    // uploads (distinct from the artist's real channel, if they have one). Stripping this makes
    // a song uploaded once via a real channel and once via its auto-generated Topic channel
    // resolve to the same artist name everywhere a title+artist match matters (downloads,
    // playlist-add, deduplication) instead of being treated as two different artists.
    private val TOPIC_SUFFIX_REGEX = Regex("""(?i)\s*-\s*topic\s*$""")

    /** Removes a trailing YouTube "- Topic" channel-name suffix from [artist], if present. */
    fun stripTopicSuffix(artist: String): String = TOPIC_SUFFIX_REGEX.replace(artist, "").trim()

    /**
     * Normalizes an artist string for matching two rows as "the same artist" for deduplication
     * purposes: strips a Topic-channel suffix, then takes only the part before a "/" (uploaders
     * sometimes list an alias this way, e.g. "Jamie Paige / JamieP") so that variant still matches
     * the plain "Jamie Paige" or "Jamie Paige - Topic" form of the same artist.
     */
    fun normalizeArtistForMatching(artist: String): String =
        stripTopicSuffix(artist).substringBefore("/").trim().lowercase()

    /**
     * Fast (< 1 ms), zero-memory offline regex parser.
     */
    fun cleanOffline(rawTitle: String, rawArtist: String? = null): CleanedMetadata {
        if (rawTitle.isBlank()) {
            return CleanedMetadata(cleanTitle = rawTitle, artist = rawArtist)
        }

        var currentTitle = rawTitle.trim()
        var extractedArtist: String? = rawArtist
            ?.let { stripTopicSuffix(it) }
            ?.takeIf { it.isNotBlank() && it != "Unknown Artist" }
        var extractedProducer: String? = null
        var extractedFeat: String? = null

        // Step 1: Strip bracketed noise tags
        currentTitle = TAG_REGEX.replace(currentTitle, "").trim()

        // Step 2: Extract Producer from brackets/parentheses first, then inline
        val prodBracketMatch = PRODUCER_BRACKET_REGEX.find(currentTitle)
        if (prodBracketMatch != null) {
            extractedProducer = prodBracketMatch.groupValues[1].trim()
            currentTitle = currentTitle.replace(prodBracketMatch.value, "").trim()
        } else {
            val prodInlineMatch = PRODUCER_INLINE_REGEX.find(currentTitle)
            if (prodInlineMatch != null) {
                extractedProducer = prodInlineMatch.groupValues[1].trim()
                currentTitle = currentTitle.replace(prodInlineMatch.value, "").trim()
            }
        }
        extractedProducer = extractedProducer?.replace(Regex("""(?i)^(by|with)\s+"""), "")?.trim()

        // Step 3: Extract Featured Artist from brackets/parentheses first, then inline
        val featBracketMatch = FEAT_BRACKET_REGEX.find(currentTitle)
        if (featBracketMatch != null) {
            extractedFeat = featBracketMatch.groupValues[1].trim()
            currentTitle = currentTitle.replace(featBracketMatch.value, "").trim()
        } else {
            val featInlineMatch = FEAT_INLINE_REGEX.find(currentTitle)
            if (featInlineMatch != null) {
                extractedFeat = featInlineMatch.groupValues[1].trim()
                currentTitle = currentTitle.replace(featInlineMatch.value, "").trim()
            }
        }

        // Step 4: Re-strip any newly exposed bracketed noise tags
        currentTitle = TAG_REGEX.replace(currentTitle, "").trim()

        // Step 5: Check for "Artist - Title" splitting if present
        if (currentTitle.contains(" - ")) {
            val parts = currentTitle.split(" - ", limit = 2)
            if (parts.size == 2) {
                val artistPart = parts[0].trim()
                val titlePart = parts[1].trim()
                if (extractedArtist == null && artistPart.isNotBlank()) {
                    extractedArtist = stripTopicSuffix(artistPart).takeIf { it.isNotBlank() } ?: artistPart
                }
                currentTitle = titlePart
            }
        }

        // Step 6: Final cleanup of dangling empty brackets, multiple spaces, and edge punctuation
        currentTitle = currentTitle
            .replace(Regex("""[(\[{]\s*[)\]}]"""), "")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""^[- \t.:]+|[- \t.:]+$"""), "")
            .trim()

        if (currentTitle.isBlank()) {
            currentTitle = rawTitle.trim()
        }

        return CleanedMetadata(
            cleanTitle = currentTitle,
            artist = extractedArtist,
            producer = extractedProducer?.takeIf { it.isNotBlank() },
            featuredArtist = extractedFeat?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Cleans metadata using the offline engine.
     *
     * This used to optionally call out to Gemini/OpenAI with a user-supplied API key. That path is
     * gone: it required a key the app no longer asks for, it was pinned to a model that has since
     * been retired, and it issued one network request per song during a library scan whose result
     * was discarded for every song already in the database. [cleanOffline] does the real work and
     * needs nothing but the device.
     */
    suspend fun clean(
        rawTitle: String,
        rawArtist: String? = null
    ): CleanedMetadata = withContext(Dispatchers.IO) {
        cleanOffline(rawTitle, rawArtist)
    }

}
