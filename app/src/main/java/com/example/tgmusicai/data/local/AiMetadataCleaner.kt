package com.example.tgmusicai.data.local

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
 * Lightweight AI & Pattern Metadata Cleaner for music tracks.
 * Features:
 * 1. Offline Pattern Engine: Fast (<1 ms), zero-memory regex parser extracting terms like prod., feat., ft., produced by, [Official Audio], etc.
 * 2. Optional Online AI Engine: If a Gemini/OpenAI API key is present, executes a single transient HTTP POST request on Dispatchers.IO.
 * Discards all HTTP connections immediately afterwards for zero idle memory footprint.
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
     * Cleans metadata offline, and if an API key is provided, attempts an online AI enrichment request on [Dispatchers.IO].
     */
    suspend fun clean(
        rawTitle: String,
        rawArtist: String? = null,
        apiKey: String? = null
    ): CleanedMetadata = withContext(Dispatchers.IO) {
        val offlineResult = cleanOffline(rawTitle, rawArtist)
        if (apiKey.isNullOrBlank()) {
            return@withContext offlineResult
        }

        try {
            val onlineResult = fetchOnlineMetadata(rawTitle, rawArtist, apiKey)
            if (onlineResult != null) {
                CleanedMetadata(
                    cleanTitle = onlineResult.cleanTitle.ifBlank { offlineResult.cleanTitle },
                    artist = onlineResult.artist ?: offlineResult.artist,
                    producer = onlineResult.producer ?: offlineResult.producer,
                    featuredArtist = onlineResult.featuredArtist ?: offlineResult.featuredArtist
                )
            } else {
                offlineResult
            }
        } catch (e: Exception) {
            Log.e(TAG, "Online AI metadata cleaning failed, falling back to offline", e)
            offlineResult
        }
    }

    /**
     * Sends one blocking HTTP request to Gemini (if [apiKey] looks like a Google API key, i.e.
     * starts with "AIza") or OpenAI otherwise, asking the model to clean/split the raw title.
     * Deliberately synchronous (called from within [clean]'s `withContext(Dispatchers.IO)`) with
     * short 3s connect / 5s read timeouts -- this runs per-song during a library scan, so a slow
     * or hanging API must not stall the whole scan. Returns null (never throws past this function)
     * on any non-200 response or unparseable body, letting [clean] fall back to the offline result.
     */
    private fun fetchOnlineMetadata(
        rawTitle: String,
        rawArtist: String?,
        apiKey: String
    ): CleanedMetadata? {
        val isGemini = apiKey.startsWith("AIza")
        val urlString = if (isGemini) {
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        } else {
            "https://api.openai.com/v1/chat/completions"
        }

        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 5000
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        if (!isGemini) {
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        connection.doOutput = true

        val prompt = "Clean audio track title and metadata. Raw Title: \"$rawTitle\", Raw Artist: \"${rawArtist ?: ""}\". Respond JSON ONLY with keys: \"title\", \"artist\", \"producer\", \"featuredArtist\". No markdown."

        val jsonPayload = if (isGemini) {
            JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                ))
            }.toString()
        } else {
            JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", "Respond ONLY with JSON with keys: title, artist, producer, featuredArtist."))
                    put(JSONObject().put("role", "user").put("content", prompt))
                })
            }.toString()
        }

        try {
            connection.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonObject = JSONObject(responseText)
                val contentText = if (isGemini) {
                    jsonObject.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                } else {
                    jsonObject.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }

                val cleanedJsonText = contentText
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                val parsedJson = JSONObject(cleanedJsonText)
                return CleanedMetadata(
                    cleanTitle = parsedJson.optString("title"),
                    artist = parsedJson.optString("artist").takeIf { it.isNotBlank() },
                    producer = parsedJson.optString("producer").takeIf { it.isNotBlank() },
                    featuredArtist = parsedJson.optString("featuredArtist").takeIf { it.isNotBlank() }
                )
            } else {
                Log.w(TAG, "Online AI response code: ${connection.responseCode}")
                return null
            }
        } finally {
            // Immediately close and discard HTTP connection to guarantee zero idle memory footprint
            connection.disconnect()
        }
    }
}
