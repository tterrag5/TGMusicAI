package com.example.tgmusicai.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
    val text: String,
    /**
     * True for a filler line standing in for a stretch of music with no words (see
     * [LyricsRepository.withInstrumentalMarkers]). These are display-only: they are never saved
     * back to the song's stored lyrics, and they are not worth translating.
     */
    val isInstrumental: Boolean = false
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
     * timeouts are short on purpose -- these are lightweight text/JSON requests, not the whole-
     * track audio [audioFetchClient] pulls down, so a hung request should fail fast and let
     * [fetchAndSaveLyrics] move on to the next fallback source rather than stall.
     */
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Separate client for pulling a cloud track's whole audio stream down before transcription.
     * A full track is megabytes over a possibly slow link, so [okHttpClient]'s deliberately short
     * 10s read timeout would abort it; this one is patient instead, and is never used for the
     * lyric lookups that depend on failing fast.
     */
    private val audioFetchClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

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

        /**
         * A stretch with no words has to run this long before it counts as instrumental.
         *
         * Deliberately well past the length of a held note or a drawn-out delivery: those leave a
         * gap of a few seconds between LRC timestamps despite the singer never stopping, and
         * marking them as "music only" is wrong.
         */
        const val INSTRUMENTAL_GAP_MS = 10_000L

        /** Roughly how far apart markers sit inside a gap long enough to earn more than one. */
        const val INSTRUMENTAL_MARKER_SPACING_MS = 5_000L

        /**
         * Splits happen at these: a comma or semicolon, or a dash used as a pause. A lyric line
         * transcribed as one run of text ("make me sweat, make me hotter") is really two phrases,
         * and showing it as one long line means the highlight sits still while both are sung.
         */
        private val LINE_SPLIT_REGEX = Regex("""(?<=[,;])\s+|\s+[-\u2013\u2014]{1,2}\s+""")

        /** A fragment shorter than this is joined back onto the phrase before it, not left alone. */
        private const val MIN_SPLIT_PART_CHARS = 6

        /**
         * The longest stretch split phrases are spread over. Without a cap, a line followed by a
         * long instrumental would smear its phrases across the whole break.
         */
        private const val MAX_SPLIT_SPAN_MS = 8_000L

        /**
         * Returns [lines] with each multi-phrase line broken into one line per phrase, timed by
         * splitting the original line's span in proportion to each phrase's length.
         *
         * LRC files give one timestamp per line, so a line holding two phrases leaves the
         * highlight parked on it through both. Proportional timing is an estimate -- nothing in
         * the file says when the second phrase starts -- but it tracks singing far better than
         * holding one line for its whole duration.
         *
         * [totalDurationMs] is the track length, used only to bound the final line's span; pass 0
         * when it isn't known.
         */
        fun splitDenseLines(lines: List<LyricLine>, totalDurationMs: Long = 0L): List<LyricLine> {
            if (lines.none { it.timestampMs >= 0 }) return lines

            val result = mutableListOf<LyricLine>()
            lines.forEachIndexed { index, line ->
                val parts = if (line.timestampMs < 0 || line.isInstrumental) {
                    listOf(line.text)
                } else {
                    splitIntoPhrases(line.text)
                }
                if (parts.size < 2) {
                    result.add(line)
                    return@forEachIndexed
                }

                val nextMs = lines.drop(index + 1).firstOrNull { it.timestampMs >= 0 }?.timestampMs
                    ?: totalDurationMs.takeIf { it > line.timestampMs }
                    ?: (line.timestampMs + MAX_SPLIT_SPAN_MS)
                val span = (nextMs - line.timestampMs).coerceAtMost(MAX_SPLIT_SPAN_MS)
                val totalChars = parts.sumOf { it.length }.coerceAtLeast(1)

                var consumedChars = 0
                for (part in parts) {
                    result.add(
                        line.copy(
                            timestampMs = line.timestampMs + span * consumedChars / totalChars,
                            text = part,
                        )
                    )
                    consumedChars += part.length
                }
            }
            return result
        }

        /** Breaks [text] at its phrase boundaries, or returns it whole if it has none worth using. */
        private fun splitIntoPhrases(text: String): List<String> {
            val parts = LINE_SPLIT_REGEX.split(text)
                .map { it.trim().trimEnd(',', ';') }
                .filter { it.isNotEmpty() }
            if (parts.size < 2) return listOf(text)

            val merged = mutableListOf<String>()
            for (part in parts) {
                if (part.length < MIN_SPLIT_PART_CHARS && merged.isNotEmpty()) {
                    merged[merged.lastIndex] = merged.last() + " " + part
                } else {
                    merged.add(part)
                }
            }
            return if (merged.size < 2) listOf(text) else merged
        }

        /** Stands in for singing during an intro, instrumental break, or outro. */
        const val MUSIC_MARKER = "\u266a"

        /**
         * Returns [lines] with [MUSIC_MARKER] lines filling every stretch of [INSTRUMENTAL_GAP_MS]
         * or longer that has no words -- the intro before the first line, each gap between two
         * lines, and the outro after the last one.
         *
         * Without these, a long instrumental break leaves the highlight parked on the last line
         * sung, which reads as the lyrics having frozen or drifted out of sync. Markers are spread
         * evenly across the gap they fill, roughly one every [INSTRUMENTAL_MARKER_SPACING_MS], so the
         * highlight keeps moving at a steady pace instead of sitting still and then jumping.
         *
         * A no-op for unsynced lyrics (every timestamp is the `-1` sentinel), which have no gaps to
         * measure. [totalDurationMs] is the track length, used only for the outro; pass 0 when it
         * isn't known and the outro is skipped.
         */
        fun withInstrumentalMarkers(lines: List<LyricLine>, totalDurationMs: Long = 0L): List<LyricLine> {
            if (lines.isEmpty() || lines.none { it.timestampMs >= 0 }) return lines

            val result = mutableListOf<LyricLine>()

            fun fillGap(fromMs: Long, toMs: Long) {
                val gap = toMs - fromMs
                if (gap < INSTRUMENTAL_GAP_MS) return
                // Aim for one marker per INSTRUMENTAL_MARKER_SPACING_MS, then space that many evenly across
                // the gap so the first and last never land on the words at either end.
                val count = ((gap / INSTRUMENTAL_MARKER_SPACING_MS).toInt() - 1).coerceAtLeast(1)
                for (k in 1..count) {
                    result.add(
                        LyricLine(
                            timestampMs = fromMs + (gap * k) / (count + 1),
                            text = MUSIC_MARKER,
                            isInstrumental = true,
                        )
                    )
                }
            }

            fillGap(0L, lines.first().timestampMs)

            lines.forEachIndexed { index, line ->
                result.add(line)
                val next = lines.getOrNull(index + 1) ?: return@forEachIndexed
                fillGap(line.timestampMs, next.timestampMs)
            }

            if (totalDurationMs > 0L) fillGap(lines.last().timestampMs, totalDurationMs)

            return result
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

    /**
     * On-device Whisper-tiny.en engine used by [transcribeWithWhisper] -- see
     * [com.example.tgmusicai.ai.WhisperTranscriptionEngine]'s doc comment for why this replaced an
     * OpenAI Whisper API call: no API key, no per-call cost, works fully offline. Lazily
     * constructed since most sessions never trigger a transcription.
     */
    private val whisperEngine by lazy { com.example.tgmusicai.ai.WhisperTranscriptionEngine(context) }

    /**
     * Transcribes [song]'s downloaded audio on-device via a bundled, quantized Whisper-tiny.en
     * model and returns the result as an LRC-formatted string, or null on any failure (song not
     * downloaded, audio couldn't be decoded, or the model produced no text at all). English-only
     * model -- unlike the old cloud OpenAI Whisper path this replaced, it won't transcribe
     * non-English singing into its native script; that trade-off is what buys running fully
     * offline with no API key and no per-call cost.
     */
    suspend fun transcribeWithWhisper(song: Song): String? = withContext(Dispatchers.IO) {
        val mediaUri = song.mediaUri
        val isLocal = mediaUri.startsWith("content://") || mediaUri.startsWith("file://") || mediaUri.startsWith("/")

        // A cloud track has no local file, so fetch its audio to a temporary one and transcribe
        // that. Downloading rather than decoding the URL in place is deliberate: MediaExtractor
        // would have to re-range-request a signed googlevideo URL repeatedly through a whole-track
        // decode, and those URLs expire and throttle mid-use.
        var scratchFile: java.io.File? = null
        val audioPath = if (isLocal) {
            mediaUri
        } else {
            val videoId = song.youtubeId?.takeIf { it.isNotBlank() }
            if (videoId == null) {
                Log.w(TAG, "No audio available to transcribe for '${song.title}'")
                return@withContext null
            }
            scratchFile = downloadStreamToScratchFile(videoId)
            if (scratchFile == null) {
                Log.w(TAG, "Could not fetch cloud audio to transcribe for '${song.title}'")
                return@withContext null
            }
            scratchFile.absolutePath
        }

        try {
            transcribeDecodedAudio(song, audioPath)
        } finally {
            scratchFile?.let { file ->
                if (!file.delete()) Log.w(TAG, "Could not delete transcription scratch file ${file.name}")
            }
        }
    }

    /**
     * Resolves [videoId] to an audio stream and writes it to a file in the cache directory,
     * returning that file or null on any failure. The caller owns the file and must delete it.
     *
     * The request carries [YouTubeExtractor.REALISTIC_USER_AGENT] for the same reason playback
     * does: signed `googlevideo` URLs answer 403 to a default agent.
     */
    private suspend fun downloadStreamToScratchFile(videoId: String): java.io.File? {
        return try {
            val stream = youtubeExtractor.extractAudioStream(videoId)
            if (stream == null || stream.url.isBlank()) {
                Log.w(TAG, "No audio stream resolved for transcription of $videoId")
                return null
            }

            val request = Request.Builder()
                .url(stream.url)
                .header("User-Agent", YouTubeExtractor.REALISTIC_USER_AGENT)
                .build()

            val target = java.io.File.createTempFile("transcribe_", ".${stream.format}", context.cacheDir)
            audioFetchClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Transcription audio fetch for $videoId returned HTTP ${response.code}")
                    target.delete()
                    return null
                }
                val body = response.body ?: run {
                    target.delete()
                    return null
                }
                body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }

            if (target.length() == 0L) {
                target.delete()
                Log.w(TAG, "Transcription audio fetch for $videoId produced an empty file")
                null
            } else {
                target
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not fetch cloud audio for transcription of $videoId", e)
            null
        }
    }

    /** Runs the on-device model over [audioPath] and maps its result to lyrics text or null. */
    private fun transcribeDecodedAudio(song: Song, audioPath: String): String? {
        return when (val result = whisperEngine.transcribeFile(audioPath)) {
            is com.example.tgmusicai.ai.AiModelResult.Success -> result.value.ifBlank { null }
            is com.example.tgmusicai.ai.AiModelResult.Unavailable -> {
                Log.w(TAG, "On-device transcription unavailable for '${song.title}': ${result.reason}")
                null
            }
            is com.example.tgmusicai.ai.AiModelResult.Error -> {
                Log.e(TAG, "On-device transcription failed for '${song.title}'", result.throwable)
                null
            }
        }
    }

    /**
     * Translates [lines] into [targetLanguage] entirely on the device using ML Kit's translation
     * models, preserving line count and order so each translated string still lines up with its
     * original [LyricLine]'s timestamp for tap-to-seek.
     *
     * This replaced a Gemini/OpenAI implementation that required the user to supply their own API
     * key. ML Kit needs no key and no account: it downloads a small language model once (over any
     * connection, Wi-Fi not required) and then translates offline forever after. Translating line
     * by line makes the old "the model merged or dropped lines" failure mode structurally
     * impossible, so the alignment guard that used to discard mismatched responses is no longer
     * needed -- the result always has exactly as many entries as the input.
     *
     * Returns null if the language isn't supported or the model can't be downloaded.
     */
    suspend fun translateLyrics(lines: List<String>, targetLanguage: String): List<String>? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext null

        val targetCode = TranslateLanguage.fromLanguageTag(languageTagFor(targetLanguage))
        if (targetCode == null) {
            Log.w(TAG, "ML Kit has no on-device model for '$targetLanguage'")
            return@withContext null
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetCode)
            .build()

        val translator = Translation.getClient(options)
        try {
            // No DownloadConditions restrictions: the models are a few MB, and silently refusing to
            // translate on mobile data would look identical to the feature being broken.
            translator.downloadModelIfNeeded().await()
            lines.map { line ->
                // Blank lines are separators in lyrics; translating them wastes work and ML Kit
                // returns them unchanged anyway.
                if (line.isBlank()) line else translator.translate(line).await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "On-device lyrics translation failed", e)
            null
        } finally {
            // Frees the loaded model; a Translator holds native resources until closed.
            translator.close()
        }
    }

    /**
     * Maps the human-readable language names the UI offers onto the BCP-47 tags ML Kit expects.
     * Anything unrecognised is passed through lowercased, which still resolves for callers that
     * already hand over a tag like "es".
     */
    private fun languageTagFor(language: String): String = when (language.trim().lowercase()) {
        "spanish" -> "es"
        "french" -> "fr"
        "german" -> "de"
        "italian" -> "it"
        "portuguese" -> "pt"
        "dutch" -> "nl"
        "russian" -> "ru"
        "japanese" -> "ja"
        "korean" -> "ko"
        "chinese" -> "zh"
        "arabic" -> "ar"
        "hindi" -> "hi"
        "polish" -> "pl"
        "turkish" -> "tr"
        "swedish" -> "sv"
        "english" -> "en"
        else -> language.trim().lowercase()
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
