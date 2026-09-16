package com.example.tgmusicai.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Structured model representing a single line of lyrics with an optional timestamp.
 */
data class LyricLine(
    val timestampMs: Long,
    val text: String
)

/**
 * Repository for fetching, parsing, and caching song lyrics and transcripts.
 * Supports LrcLib API (synced & plain), YouTube captions parser, and embedded local ID3 lyric tags.
 */
class LyricsRepository(
    private val context: Context,
    private val musicRepository: MusicRepository,
    private val youtubeExtractor: YouTubeExtractor = YouTubeExtractor()
) {

    private val TAG = "LyricsRepository"

    /**
     * Shared HTTP client for quick lookups (LrcLib get/search, YouTube caption fetches). 10s
     * timeouts are short on purpose -- these are lightweight text/JSON requests, not the large
     * file uploads [whisperClient] handles, so a hung request should fail fast and let
     * [fetchAndSaveLyrics] move on to the next fallback source rather than stall.
     */
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        // Matches [mm:ss.fff], [mm:ss:fff] (colon-separated centiseconds, common from some LRC
        // providers), and [mmm:ss...] (3-digit minutes for long tracks) -- the old pattern only
        // matched the dot-separated form and silently dropped every other line as "unparsed",
        // which (see below) corrupted the whole song's line order.
        private val LRC_TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{1,3})\]""")
        // Non-lyric metadata tags (artist/title/album/offset/etc.) that some LRC files include --
        // must be dropped entirely rather than kept as a zero-timestamp "lyric" line.
        private val METADATA_TAG_REGEX = Regex("""^\[(ar|ti|al|by|offset|length|re|ve):.*]""", RegexOption.IGNORE_CASE)

        /**
         * Parses raw lyrics string (LRC formatted or plain text) into structured [LyricLine]s.
         *
         * The previous version fell back to `timestampMs = 0L` for any line it couldn't parse a
         * timestamp from (including metadata tags like `[ar: Artist]` and any line the old
         * dot-only regex missed), then sorted the whole list by timestamp whenever *any* real
         * timestamped line existed. Since every unparsed line shared timestamp 0, they all sorted
         * to the very top of the list ahead of the real first lyric -- corrupting playback order
         * for any LRC file with even one line the old regex couldn't match. Unparsed/plain lines
         * now carry a distinct `-1` sentinel and are kept out of the sort entirely.
         */
        fun parseLyrics(rawLyrics: String?): List<LyricLine> {
            if (rawLyrics.isNullOrBlank()) return emptyList()

            val parsedLines = mutableListOf<LyricLine>()

            for (rawLine in rawLyrics.split("\n")) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || METADATA_TAG_REGEX.containsMatchIn(trimmed)) continue

                val matches = LRC_TIMESTAMP_REGEX.findAll(trimmed).toList()
                if (matches.isEmpty()) {
                    parsedLines.add(LyricLine(timestampMs = -1L, text = trimmed))
                    continue
                }

                // A single line can carry multiple timestamp tags (e.g. a repeated chorus line
                // marked "[00:12.34][01:15.20] Chorus") -- each becomes its own LyricLine sharing
                // the same text.
                val text = LRC_TIMESTAMP_REGEX.replace(trimmed, "").trim()
                for (match in matches) {
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = match.groupValues[3]
                    val millis = when (fracStr.length) {
                        1 -> fracStr.toLong() * 100
                        2 -> fracStr.toLong() * 10
                        else -> fracStr.take(3).toLong()
                    }
                    val totalMs = (minutes * 60 * 1000) + (seconds * 1000) + millis
                    parsedLines.add(LyricLine(timestampMs = totalMs, text = text))
                }
            }

            val timestamped = parsedLines.filter { it.timestampMs >= 0 }.sortedBy { it.timestampMs }
            return timestamped.ifEmpty { parsedLines }
        }
    }

    /**
     * Fetches lyrics for a [song] by trying LrcLib API, embedded ID3 tags, or YouTube captions.
     * Caches the fetched lyrics into Room DB.
     */
    suspend fun fetchAndSaveLyrics(song: Song, forceFetch: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!forceFetch && !song.lyrics.isNullOrBlank()) {
            return@withContext song.lyrics
        }

        var fetchedLyrics: String? = null

        // 1. Try LrcLib API
        try {
            fetchedLyrics = fetchFromLrcLib(song)
        } catch (e: Exception) {
            Log.e(TAG, "LrcLib fetch failed for ${song.title}", e)
        }

        // 2. Try Local Embedded ID3 Tags
        if (fetchedLyrics.isNullOrBlank()) {
            try {
                fetchedLyrics = fetchFromEmbeddedId3(song)
            } catch (e: Exception) {
                Log.e(TAG, "Embedded ID3 fetch failed for ${song.title}", e)
            }
        }

        // 3. Try YouTube Captions
        if (fetchedLyrics.isNullOrBlank()) {
            try {
                fetchedLyrics = fetchFromYouTubeCaptions(song)
            } catch (e: Exception) {
                Log.e(TAG, "YouTube captions fetch failed for ${song.title}", e)
            }
        }

        // Cache in Room DB if lyrics were successfully fetched
        if (!fetchedLyrics.isNullOrBlank()) {
            musicRepository.updateSongLyrics(song.id, fetchedLyrics)
        }

        return@withContext fetchedLyrics
    }

    /**
     * Queries LrcLib API for synced or plain lyrics. Uses [com.example.tgmusicai.data.local.AiMetadataCleaner]
     * (the same cleaner used everywhere else in the app) instead of a standalone regex, since a
     * naive `\(.*\)` strip mangles titles where parentheses are part of the real title (e.g.
     * "(Don't Fear) The Reaper") and previously caused exact-match queries to 404 for songs LrcLib
     * actually has. Tries an exact duration-matched lookup first, then the same title/artist
     * without duration (LrcLib's duration match is a narrow +/-2s window that a re-encoded or
     * padded file easily misses), then a free-text search, in that order.
     */
    private fun fetchFromLrcLib(song: Song): String? {
        val cleaned = com.example.tgmusicai.data.local.AiMetadataCleaner.cleanOffline(song.title, song.artist)
        val cleanTitle = cleaned.cleanTitle
        val cleanArtist = cleaned.artist ?: ""

        if (cleanTitle.isBlank()) return null

        val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
        val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")
        val durationSec = (song.durationMs / 1000).toInt()

        if (cleanArtist.isNotBlank() && durationSec > 0) {
            executeLrcLibGet("https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist&duration=$durationSec")
                ?.let { return it }
        }

        if (cleanArtist.isNotBlank()) {
            executeLrcLibGet("https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist")
                ?.let { return it }
        }

        val query = if (cleanArtist.isNotBlank()) "$cleanArtist $cleanTitle" else cleanTitle
        val searchUrl = "https://lrclib.net/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
        executeLrcLibSearch(searchUrl)?.let { return it }

        return null
    }

    /**
     * `JSONObject.optString(key, fallback)` does NOT return [fallback] when [key] is present with
     * an explicit JSON `null` value (only when the key is missing entirely) -- org.json's
     * `JSONObject.NULL` sentinel stringifies to the literal 4-character text "null", which
     * `optString` then happily returns as if it were real content. LrcLib's API returns exactly
     * this shape (`"syncedLyrics": null` when a track only has plain lyrics, or vice versa), so
     * the old unconditional `optString(...)` calls here were caching the literal word "null" as a
     * song's lyrics whenever only one of the two fields was populated -- reproducing exactly the
     * "lyrics just says null" bug.
     */
    private fun JSONObject.stringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key, "")

    /**
     * Picks lyrics text out of an LrcLib API response object, preferring synced (timestamped)
     * lyrics over plain text when both are present. Uses [stringOrEmpty] rather than
     * `optString` so a field that is explicitly JSON `null` is treated as absent, not as the
     * literal string "null".
     */
    private fun extractLyricsFromLrcLibJson(json: JSONObject): String? {
        val synced = json.stringOrEmpty("syncedLyrics")
        val plain = json.stringOrEmpty("plainLyrics")
        if (synced.isNotBlank()) return synced
        if (plain.isNotBlank()) return plain
        return null
    }

    /**
     * Runs a single LrcLib `/api/get` request (exact track/artist/duration lookup) and returns
     * its lyrics, or null on any non-2xx response, empty body, or missing lyrics fields.
     */
    private fun executeLrcLibGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TGMusicAI/1.0 (https://github.com/tterrag5/TGMusicAI)")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (body.isBlank()) return null
            return extractLyricsFromLrcLibJson(JSONObject(body))
        }
    }

    /**
     * Runs a single LrcLib `/api/search` request (free-text fallback, used when the exact
     * `/api/get` lookup finds nothing) and returns the first result's lyrics. The response is a
     * JSON array rather than a single object, so this only proceeds if the body actually starts
     * with `[`.
     */
    private fun executeLrcLibSearch(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TGMusicAI/1.0 (https://github.com/tterrag5/TGMusicAI)")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (!body.startsWith("[")) return null
            val jsonArray = JSONArray(body)
            if (jsonArray.length() == 0) return null
            return extractLyricsFromLrcLibJson(jsonArray.getJSONObject(0))
        }
    }

    /**
     * Extracts embedded ID3 lyric tags using [MediaMetadataRetriever].
     */
    private fun fetchFromEmbeddedId3(song: Song): String? {
        val mediaUri = song.mediaUri
        if (mediaUri.isBlank()) return null

        val retriever = MediaMetadataRetriever()
        try {
            val uri = Uri.parse(mediaUri)
            if (mediaUri.startsWith("content://") || mediaUri.startsWith("file://")) {
                retriever.setDataSource(context, uri)
            } else if (mediaUri.startsWith("/")) {
                retriever.setDataSource(mediaUri)
            } else {
                return null
            }

            // Key 1000 or raw frame metadata for lyrics if present
            val lyrics = try {
                retriever.extractMetadata(1000)
            } catch (_: Exception) {
                null
            }
            if (!lyrics.isNullOrBlank()) {
                return lyrics
            }
        } catch (_: Exception) {
            Log.d(TAG, "No embedded lyrics for ${song.title}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
        return null
    }

    // Whisper transcription can take a while (uploading a multi-MB audio file, then real
    // model inference server-side) -- a dedicated client with much longer timeouts than the
    // 10s used for quick lyrics-API lookups, so a slow-but-working transcription isn't cut off.
    private val whisperClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Resolves [mediaUri] to raw audio bytes for a downloaded local song, or null if it isn't
     * one this can read directly (an un-downloaded cloud song's `mediaUri` is a remote URL/watch
     * page, not something Whisper transcription -- which needs the actual file -- can use).
     */
    private fun readLocalAudioBytes(mediaUri: String): ByteArray? {
        return try {
            when {
                mediaUri.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(mediaUri))?.use { it.readBytes() }
                }
                mediaUri.startsWith("file://") -> {
                    val path = Uri.parse(mediaUri).path ?: return null
                    java.io.File(path).takeIf { it.exists() && it.isFile }?.readBytes()
                }
                mediaUri.startsWith("/") -> {
                    java.io.File(mediaUri).takeIf { it.exists() && it.isFile }?.readBytes()
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read local audio bytes for Whisper transcription", e)
            null
        }
    }

    /**
     * Transcribes [song]'s downloaded audio via OpenAI's Whisper API and returns the result as an
     * LRC-formatted string, or null on any failure (no API key, song not downloaded, network/API
     * error). [apiKey] must be an OpenAI-format key (`sk-...`) -- Gemini keys can't call this
     * endpoint. The `language` parameter is deliberately omitted from the request so Whisper
     * auto-detects the spoken/sung language instead of assuming English, letting it transcribe
     * Japanese, Korean, Spanish, etc. into their native script instead of forcing a bad
     * English-phonetic guess at non-English lyrics.
     */
    suspend fun transcribeWithWhisper(song: Song, apiKey: String): String? = withContext(Dispatchers.IO) {
        if (!apiKey.startsWith("sk-")) {
            Log.w(TAG, "Whisper transcription requires an OpenAI-format API key (sk-...); the configured key is not one")
            return@withContext null
        }
        val audioBytes = readLocalAudioBytes(song.mediaUri)
        if (audioBytes == null || audioBytes.isEmpty()) {
            Log.w(TAG, "No local audio file available to transcribe for '${song.title}' (song must be downloaded first)")
            return@withContext null
        }

        val fileName = Uri.parse(song.mediaUri).lastPathSegment ?: "audio.m4a"
        val mediaType = when {
            fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            fileName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            fileName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
            else -> "audio/mp4"
        }.toMediaTypeOrNull()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, audioBytes.toRequestBody(mediaType))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            // No "language" field: omitting it (rather than forcing "en") is what enables
            // Whisper's automatic language detection for non-English singing.
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            whisperClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Whisper transcription failed for '${song.title}': HTTP ${response.code} ${response.message}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val segments = json.optJSONArray("segments") ?: return@withContext null
                val detectedLanguage = json.stringOrEmpty("language")
                Log.d(TAG, "Whisper transcribed '${song.title}' as language: $detectedLanguage, ${segments.length()} segment(s)")

                val builder = StringBuilder()
                for (i in 0 until segments.length()) {
                    val segment = segments.getJSONObject(i)
                    val text = segment.stringOrEmpty("text").trim()
                    if (text.isBlank()) continue
                    val startMs = (segment.optDouble("start", 0.0) * 1000).toLong()
                    val minutes = startMs / 60000
                    val seconds = (startMs % 60000) / 1000
                    val centis = (startMs % 1000) / 10
                    builder.append(String.format(Locale.US, "[%02d:%02d.%02d] %s\n", minutes, seconds, centis, text))
                }
                builder.toString().ifBlank { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcription request failed for '${song.title}'", e)
            null
        }
    }

    /**
     * Extracts captions transcript from YouTube / Piped API.
     */
    private suspend fun fetchFromYouTubeCaptions(song: Song): String? {
        // song.youtubeId is the reliable source once a track is downloaded -- mediaUri is a local
        // file path at that point (e.g. "file:///.../track.m4a"), which extractVideoIdFromUri's
        // youtube.com/youtu.be pattern can never match, so this used to always fall through to a
        // fresh text search for every already-downloaded song instead of using the video id it
        // already had on hand. That fallback search can also land on the wrong video entirely.
        val videoId = song.youtubeId?.takeIf { it.isNotBlank() }
            ?: extractVideoIdFromUri(song.mediaUri)
            ?: run {
                val searchResults = youtubeExtractor.search("${song.artist} ${song.title}")
                searchResults.firstOrNull()?.videoId
            } ?: return null

        // Try every known Piped mirror (public instances rotate/die frequently) instead of a single
        // hardcoded host, matching the multi-mirror resilience pattern used elsewhere for Piped-backed features.
        for (base in com.example.tgmusicai.data.youtube.YouTubeExtractor.PIPED_ENDPOINTS) {
            try {
                // Piped's actual (documented) subtitle data lives on the /streams/{id} response's
                // "subtitles" array -- a separate top-level /captions/{id} route doesn't exist on
                // this mirror (confirmed: it 404s unconditionally, for every video, regardless of
                // whether captions exist), so this tier previously never worked at all.
                val pipedUrl = "$base/streams/$videoId"
                val request = Request.Builder().url(pipedUrl).build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        if (body.startsWith("{")) {
                            val array = JSONObject(body).optJSONArray("subtitles") ?: JSONArray()
                            if (array.length() > 0) {
                                // Pick English or first track
                                var trackUrl = ""
                                for (i in 0 until array.length()) {
                                    val obj = array.getJSONObject(i)
                                    val code = obj.stringOrEmpty("code")
                                    if (code.contains("en")) {
                                        trackUrl = obj.stringOrEmpty("url")
                                        break
                                    }
                                }
                                if (trackUrl.isBlank() && array.length() > 0) {
                                    trackUrl = array.getJSONObject(0).stringOrEmpty("url")
                                }

                                if (trackUrl.isNotBlank()) {
                                    // The proxy URL defaults to TTML (XML) which nothing here
                                    // parses; YouTube's timedtext endpoint also serves plain
                                    // WebVTT for the same track via fmt=vtt, which the existing
                                    // WebVTT parser below already handles correctly.
                                    val vttUrl = if (trackUrl.contains("fmt=ttml")) {
                                        trackUrl.replace("fmt=ttml", "fmt=vtt")
                                    } else if (trackUrl.contains("fmt=")) {
                                        trackUrl
                                    } else {
                                        "$trackUrl&fmt=vtt"
                                    }
                                    val captionReq = Request.Builder().url(vttUrl).build()
                                    okHttpClient.newCall(captionReq).execute().use { captionResp ->
                                        if (captionResp.isSuccessful) {
                                            val captionContent = captionResp.body?.string() ?: ""
                                            val parsed = parseVttOrJsonCaptions(captionContent)
                                            // Auto-generated captions on sung/vocaloid videos are
                                            // speech-recognition based and typically can't transcribe
                                            // singing at all -- the track exists but every line is just
                                            // a non-speech tag like "[Music]"/"[Applause]". Caching that
                                            // as "lyrics" is worse than caching nothing, so treat it the
                                            // same as no captions and let the caller move on.
                                            if (!parsed.isNullOrBlank() && hasRealSpeechContent(parsed)) return parsed
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Piped captions mirror $base failed for $videoId: ${e.message}")
            }
        }
        return null
    }

    /**
     * Returns false when [lyricsText] consists entirely of non-speech caption tags (e.g. "[Music]",
     * "[Applause]") with no actual transcribed words -- the common outcome for auto-generated
     * captions on sung content, since speech recognition doesn't transcribe singing.
     */
    private fun hasRealSpeechContent(lyricsText: String): Boolean {
        val timestampPrefix = Regex("^\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?\\]\\s*")
        val nonSpeechTag = Regex("^\\[[^\\]]*\\]$")
        var meaningfulChars = 0
        for (rawLine in lyricsText.lines()) {
            val textPart = rawLine.trim().replaceFirst(timestampPrefix, "").trim()
            if (textPart.isBlank() || nonSpeechTag.matches(textPart)) continue
            meaningfulChars += textPart.length
        }
        return meaningfulChars >= 20
    }

    /**
     * Pulls a YouTube video ID out of a `watch?v=`, `/v/`, `embed/`, or `youtu.be/` style URL.
     * Returns null for anything else (e.g. a local file path), letting the caller fall back to
     * a fresh search instead of misreading a path segment as a video ID.
     */
    private fun extractVideoIdFromUri(uri: String): String? {
        val regex = "(?:v=|/v/|embed/|youtu\\.be/)([^&?#/]+)".toRegex()
        val match = regex.find(uri)
        return match?.groupValues?.get(1)
    }

    /**
     * Converts a captions payload into the same `[mm:ss.cc] text` LRC-like line format used
     * elsewhere in this class, handling two possible shapes from Piped/YouTube: a JSON
     * transcript (array of cues or `{"events": [...]}`) and WebVTT. For WebVTT, strips inline
     * cue formatting/speaker tags (`<c...>`, `<v Speaker>`, `<i>`, per-word timestamps) that
     * would otherwise leak into the displayed lyrics text. Falls back to returning the raw
     * [content] unchanged if no cues could be parsed out of it.
     */
    private fun parseVttOrJsonCaptions(content: String): String? {
        if (content.isBlank()) return null
        if (content.startsWith("{") || content.startsWith("[")) {
            // JSON transcript
            try {
                val builder = StringBuilder()
                val json = if (content.startsWith("[")) JSONArray(content) else JSONObject(content).optJSONArray("events") ?: JSONArray()
                for (i in 0 until json.length()) {
                    val item = json.getJSONObject(i)
                    val start = item.optLong("start", item.optLong("tStartMs", 0L))
                    val text = item.stringOrEmpty("text")
                    if (text.isNotBlank()) {
                        val min = start / 60000
                        val sec = (start % 60000) / 1000
                        val ms = (start % 1000) / 10
                        builder.append(String.format("[%02d:%02d.%02d]%s\n", min, sec, ms, text.trim()))
                    }
                }
                if (builder.isNotEmpty()) return builder.toString()
            } catch (_: Exception) {}
        }

        // WebVTT format parser
        val lines = content.split("\n")
        val builder = StringBuilder()
        var currentTimestamp = ""
        val timeRegex = "(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3}) --> .*".toRegex()
        val shortTimeRegex = "(\\d{2}):(\\d{2})\\.(\\d{3}) --> .*".toRegex()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("WEBVTT") || trimmed.all { it.isDigit() }) continue

            val match = timeRegex.find(trimmed) ?: shortTimeRegex.find(trimmed)
            if (match != null) {
                val groups = match.groupValues
                if (groups.size == 5) {
                    val h = groups[1].toInt()
                    val m = groups[2].toInt() + (h * 60)
                    val s = groups[3].toInt()
                    val ms = groups[4].substring(0, 2)
                    currentTimestamp = String.format("[%02d:%02d.%s]", m, s, ms)
                } else if (groups.size == 4) {
                    val m = groups[1].toInt()
                    val s = groups[2].toInt()
                    val ms = groups[3].substring(0, 2)
                    currentTimestamp = String.format("[%02d:%02d.%s]", m, s, ms)
                }
            } else if (currentTimestamp.isNotEmpty() && !trimmed.contains("-->")) {
                // YouTube WebVTT cues carry inline formatting/speaker tags (<c.colorFFFFFF>,
                // <v Speaker>, <i>, <b>, per-word <00:12.345> timestamps) that used to get appended
                // straight into what's shown as lyrics -- strip them down to the actual text.
                val cleanText = trimmed.replace(Regex("<[^>]*>"), "").trim()
                if (cleanText.isNotEmpty()) {
                    builder.append("$currentTimestamp $cleanText\n")
                }
            }
        }

        return if (builder.isNotEmpty()) builder.toString() else content
    }
}
