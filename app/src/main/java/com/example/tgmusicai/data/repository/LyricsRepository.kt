package com.example.tgmusicai.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.example.tgmusicai.data.lyrics.LyricsProvider
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
 * One karaoke highlight unit within a line: the text to light up, when, and for how long.
 *
 * Real when the lyrics carry them: enhanced-LRC word tags (`<mm:ss.xx>word`), which BetterLyrics
 * (word-synced TTML) is stored as and some LrcLib entries use. Otherwise estimated: the line's span
 * divided by how long each unit takes to sing ([LyricsRepository.withWordTimings]), which is close
 * enough to follow along with and not presented as anything more precise.
 */
data class WordTiming(
    val text: String,
    val startMs: Long,
    val durationMs: Long,
) {
    /** Exclusive end of this unit's highlight window. */
    val endMs: Long get() = startMs + durationMs
}

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
    val isInstrumental: Boolean = false,
    /**
     * Per-unit highlight timings for karaoke rendering, or empty when the line can't be broken into
     * more than one unit (or has no timestamp to divide). Empty means "highlight the whole line at
     * once", which is what the display did before karaoke existed.
     */
    val words: List<WordTiming> = emptyList(),
    /**
     * When the line stops being sung, if the source says (YouTube Music's timed lyrics do; stored
     * as a trailing `<mm:ss.xx>` tag). Bounds the estimated word sweep so it finishes with the
     * singer instead of stretching into the pause before the next line.
     */
    val endMs: Long? = null,
    /** True when [words] came from the source rather than being estimated. */
    val hasRealWordTimings: Boolean = false,
)

/**
 * Repository for fetching, parsing, and caching song lyrics and transcripts.
 * Supports LrcLib API (synced & plain), YouTube captions parser, and embedded local ID3 lyric tags.
 */
class LyricsRepository(
    private val context: Context,
    private val musicRepository: MusicRepository,
    private val youtubeExtractor: YouTubeExtractor = YouTubeExtractor(),
    private val youTubeMusic: com.example.tgmusicai.data.youtube.YouTubeMusicBrowser =
        com.example.tgmusicai.data.youtube.YouTubeMusicBrowser(),
    private val betterLyrics: com.example.tgmusicai.data.lyrics.BetterLyricsClient =
        com.example.tgmusicai.data.lyrics.BetterLyricsClient(),
    private val kuGou: com.example.tgmusicai.data.lyrics.KuGouLyricsClient =
        com.example.tgmusicai.data.lyrics.KuGouLyricsClient(),
    private val qq: com.example.tgmusicai.data.lyrics.QQLyricsClient =
        com.example.tgmusicai.data.lyrics.QQLyricsClient(),
    private val netEase: com.example.tgmusicai.data.lyrics.NetEaseLyricsClient =
        com.example.tgmusicai.data.lyrics.NetEaseLyricsClient(),
    /** The user's provider order and on/off switches; defaults to Morphe's list when unset. */
    private val providerSettings: suspend () -> List<com.example.tgmusicai.data.lyrics.LyricsProviderSetting> = {
        com.example.tgmusicai.data.local.AppPreferences(context).lyricsProvidersFlow.first()
    },
) {

    /**
     * Songs whose stored lyrics are unsynced and that were already re-checked for synced ones
     * this session. Synced lyrics appear over time, so the check is repeated next session, not on
     * every play.
     */
    private val upgradeAttempted = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    private val TAG = "LyricsRepository"

    /**
     * Shared HTTP client for quick lookups (LrcLib get/search, YouTube caption fetches). 10s
     * timeouts are short on purpose: these are lightweight text/JSON requests, so a hung request
     * should fail fast and let [fetchAndSaveLyrics] move on to the next source rather than stall.
     */
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        /** LrcLib search hits within this many seconds of the track count as the same recording. */
        private const val LRCLIB_DURATION_TOLERANCE_SEC = 4.0

        /** Splits "A, B & C" / "A feat. B" / "A x B" to take the lead artist. */
        private val LEAD_ARTIST_SPLIT = Regex("""\s*(,|&|\bfeat\.?|\bft\.?|\bx\b|/)\s*""", RegexOption.IGNORE_CASE)

        private fun normaliseForMatch(text: String): String =
            text.lowercase()
                .replace(Regex("""[(\[（【][^)\]）】]*[)\]）】]"""), " ")
                .replace(Regex("""\b(feat|ft)\.?\s.*$"""), " ")
                .replace(Regex("""['’]"""), "")
                .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
                .trim()

        /**
         * Whether a YouTube Music song is the library track [title] by [artist], for borrowing its
         * lyrics. Looser than recommendation matching, because YouTube titles are often bilingual
         * ("メズマライザー - Mesmerizer") and credit different artists than the file's tags
         * ("32ki, Hatsune Miku" for a track tagged "Hiiragi Magnetite"):
         * - the title must match whole, or match one " - " / " / " part of the candidate's title;
         * - then either the artists overlap, or the durations agree within [LYRICS_DURATION_TOLERANCE_SEC]
         *   (a strong signal on its own; a cover or remix is rarely within three seconds).
         */
        fun isLyricsMatch(
            title: String,
            artist: String,
            durationSec: Long,
            candidateTitle: String,
            candidateArtist: String,
            candidateDurationSec: Long,
        ): Boolean {
            val wanted = normaliseForMatch(title)
            if (wanted.isBlank()) return false
            val parts = (listOf(candidateTitle) + candidateTitle.split(" - ", " / ", " | "))
                .map(::normaliseForMatch)
                .filter { it.isNotBlank() }
            val titleMatches = parts.any { it == wanted } ||
                (wanted.length >= 4 && parts.any { it.startsWith("$wanted ") })
            if (!titleMatches) return false

            val a = normaliseForMatch(artist)
            val b = normaliseForMatch(candidateArtist)
            val artistsOverlap = a.isNotBlank() && b.isNotBlank() &&
                (a.contains(b) || b.contains(a) || a.split(" ").any { word -> word.length >= 3 && b.split(" ").contains(word) })
            if (artistsOverlap) return true
            return durationSec > 0 && candidateDurationSec > 0 &&
                kotlin.math.abs(durationSec - candidateDurationSec) <= LYRICS_DURATION_TOLERANCE_SEC
        }

        const val LYRICS_DURATION_TOLERANCE_SEC = 3L

        /** True when [lyrics] carry LRC line timestamps. */
        fun isSynced(lyrics: String?): Boolean =
            !lyrics.isNullOrBlank() && lyrics.lineSequence().any { LRC_TIMESTAMP_REGEX.containsMatchIn(it) }

        // Matches [mm:ss.fff], [mm:ss:fff] (colon-separated centiseconds, common from some LRC
        // providers), and [mmm:ss...] (3-digit minutes for long tracks) -- the old pattern only
        // matched the dot-separated form and silently dropped every other line as "unparsed",
        // which (see below) corrupted the whole song's line order.
        private val LRC_TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{1,3})\]""")
        // Non-lyric metadata tags (artist/title/album/offset/etc.) that some LRC files include --
        // must be dropped entirely rather than kept as a zero-timestamp "lyric" line.
        /** An enhanced-LRC inline word tag, `<mm:ss.xx>`. */
        private val WORD_TAG_REGEX = Regex("""<(\d{1,3}):(\d{2})[.:](\d{1,3})>""")

        private fun tagMillis(minutes: String, seconds: String, fraction: String): Long {
            val millis = when (fraction.length) {
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.take(3).toLong()
            }
            return minutes.toLong() * 60_000 + seconds.toLong() * 1000 + millis
        }

        /**
         * Splits a line's text on its inline `<mm:ss.xx>` tags.
         *
         * Returns the plain text, the real word timings when the tags mark words (each tag starts
         * the text after it; a tag after the last word ends it), and the line end when the only
         * tag is a trailing one.
         */
        internal fun parseInlineTags(body: String): Triple<String, List<WordTiming>, Long?> {
            val tags = WORD_TAG_REGEX.findAll(body).toList()
            if (tags.isEmpty()) return Triple(body.trim(), emptyList(), null)
            val plain = WORD_TAG_REGEX.replace(body, "").replace(Regex("""\s+"""), " ").trim()
            val leading = body.substring(0, tags.first().range.first)
            // Only a trailing tag: the text before it is the line, the tag is its end.
            if (tags.size == 1 && body.substring(tags.first().range.last + 1).isBlank()) {
                val (m, sec, f) = tags.first().destructured
                return Triple(plain, emptyList(), tagMillis(m, sec, f))
            }
            val words = mutableListOf<WordTiming>()
            var lineEnd: Long? = null
            tags.forEachIndexed { i, tag ->
                val (m, sec, f) = tag.destructured
                val start = tagMillis(m, sec, f)
                val textEnd = tags.getOrNull(i + 1)?.range?.first ?: body.length
                val text = body.substring(tag.range.last + 1, textEnd)
                if (text.isBlank()) {
                    if (i == tags.lastIndex) lineEnd = start
                    return@forEachIndexed
                }
                val nextStart = tags.getOrNull(i + 1)?.let { next ->
                    val (nm, ns, nf) = next.destructured
                    tagMillis(nm, ns, nf)
                }
                words += WordTiming(text, start, ((nextStart ?: start) - start).coerceAtLeast(0))
            }
            // Text before the first tag has no timing of its own; it rides with the first word.
            if (leading.isNotBlank() && words.isNotEmpty()) {
                words[0] = words[0].copy(text = leading + words[0].text)
            }
            // The last word runs to the line end when there is one, else it is given no length
            // here and bounded later like any other line.
            if (words.isNotEmpty() && lineEnd != null) {
                val last = words.last()
                words[words.lastIndex] = last.copy(durationMs = (lineEnd!! - last.startMs).coerceAtLeast(0))
            }
            // Spacing is the renderer's job (it lays words back into the line's own text).
            return Triple(plain, words.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }, lineEnd)
        }

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
                val (text, words, lineEnd) = parseInlineTags(LRC_TIMESTAMP_REGEX.replace(trimmed, ""))
                for (match in matches) {
                    val totalMs = tagMillis(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                    // Word tags are absolute times, so they belong to the first stamp only; a
                    // repeat of the same line elsewhere gets estimated timing like any other.
                    val real = words.isNotEmpty() && match == matches.first()
                    parsedLines.add(
                        LyricLine(
                            timestampMs = totalMs,
                            text = text,
                            words = if (real) words else emptyList(),
                            endMs = if (match == matches.first()) lineEnd else null,
                            hasRealWordTimings = real,
                        )
                    )
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

        /** The same, measured from where a line is known to end rather than from where it starts. */
        const val INSTRUMENTAL_GAP_AFTER_END_MS = 6_000L

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

        /** What ML Kit's language identifier returns when it cannot tell. */
        private const val UNDETERMINED_LANGUAGE = "und"

        /**
         * How much lyric text to hand the language identifier. Deliberately a lot: one line is
         * often too short to identify, and a chorus on its own identifies as nothing.
         */
        private const val MAX_LANGUAGE_SAMPLE_CHARS = 2000

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
                val parts = if (line.timestampMs < 0 || line.isInstrumental || line.hasRealWordTimings) {
                    listOf(line.text)
                } else {
                    splitIntoPhrases(line.text)
                }
                if (parts.size < 2) {
                    result.add(line)
                    return@forEachIndexed
                }

                val nextMs = line.endMs?.takeIf { it > line.timestampMs }
                    ?: lines.drop(index + 1).firstOrNull { it.timestampMs >= 0 }?.timestampMs
                    ?: totalDurationMs.takeIf { it > line.timestampMs }
                    ?: (line.timestampMs + MAX_SPLIT_SPAN_MS)
                val span = (nextMs - line.timestampMs).coerceAtMost(MAX_SPLIT_SPAN_MS)
                val partEnds = parts.runningFold(0) { acc, part -> acc + part.length }.drop(1)
                val totalChars = parts.sumOf { it.length }.coerceAtLeast(1)

                var consumedChars = 0
                parts.forEachIndexed { partIndex, part ->
                    result.add(
                        line.copy(
                            timestampMs = line.timestampMs + span * consumedChars / totalChars,
                            text = part,
                            // Only the last phrase inherits the line's end; earlier ones run to
                            // the next phrase.
                            endMs = if (partIndex == parts.lastIndex) line.endMs else
                                line.timestampMs + span * partEnds[partIndex] / totalChars,
                        )
                    )
                    consumedChars += part.length
                }
            }
            return result
        }

        /**
         * Returns [lines] with [LyricLine.words] filled in, so the display can advance a highlight
         * through each line instead of lighting the whole line at once.
         *
         * Runs last in the parse chain, after [splitDenseLines] and [withInstrumentalMarkers]: both
         * of those insert and re-time lines, and a timing computed before them would describe a line
         * that no longer exists. A line keeps empty [LyricLine.words] -- meaning "highlight it
         * whole" -- when there is nothing to divide: no timestamp (the `-1` sentinel used for
         * unsynced lyrics), an instrumental marker, or a line that segments into a single unit.
         *
         * [totalDurationMs] is the track length, used to bound the final line the same way
         * [splitDenseLines] uses it; pass 0 when it isn't known.
         */
        fun withWordTimings(lines: List<LyricLine>, totalDurationMs: Long = 0L): List<LyricLine> {
            if (lines.none { it.timestampMs >= 0 }) return lines

            return lines.mapIndexed { index, line ->
                if (line.timestampMs < 0 || line.isInstrumental) return@mapIndexed line
                if (line.hasRealWordTimings) {
                    // Real timings need only their last word bounded when the source gave no end.
                    val last = line.words.last()
                    if (last.durationMs > 0) return@mapIndexed line
                    val nextMs = lines.drop(index + 1).firstOrNull { it.timestampMs >= 0 }?.timestampMs
                        ?: totalDurationMs.takeIf { it > last.startMs }
                        ?: (last.startMs + MAX_SPLIT_SPAN_MS / 4)
                    val bounded = (nextMs - last.startMs).coerceIn(0L, MAX_SPLIT_SPAN_MS / 4)
                    return@mapIndexed line.copy(words = line.words.dropLast(1) + last.copy(durationMs = bounded))
                }

                val segments = LyricSegmenter.segment(line.text)
                if (segments.size < 2) return@mapIndexed line

                // Same bounding as splitDenseLines: run to the next timestamped line, else to the
                // end of the track, else assume a default span -- and cap it either way, or a line
                // followed by a long instrumental stretch would hold its last word lit for minutes.
                val nextMs = line.endMs?.takeIf { it > line.timestampMs }
                    ?: lines.drop(index + 1).firstOrNull { it.timestampMs >= 0 }?.timestampMs
                    ?: totalDurationMs.takeIf { it > line.timestampMs }
                    ?: (line.timestampMs + MAX_SPLIT_SPAN_MS)
                val span = (nextMs - line.timestampMs).coerceAtMost(MAX_SPLIT_SPAN_MS)
                if (span <= 0L) return@mapIndexed line

                val totalWeight = segments.sumOf { it.weight }.coerceAtLeast(1)
                var consumedWeight = 0
                val words = segments.map { segment ->
                    val startOffset = span * consumedWeight / totalWeight
                    consumedWeight += segment.weight
                    // End is computed from the running total rather than by adding a rounded
                    // duration, so the units stay flush against each other and the last one lands
                    // exactly on the line's end instead of drifting by the accumulated remainders.
                    val endOffset = span * consumedWeight / totalWeight
                    WordTiming(
                        text = segment.text,
                        startMs = line.timestampMs + startOffset,
                        durationMs = endOffset - startOffset,
                    )
                }
                line.copy(words = words)
            }
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

            fun fillGap(fromMs: Long, toMs: Long, threshold: Long = INSTRUMENTAL_GAP_MS) {
                val gap = toMs - fromMs
                if (gap < threshold) return
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
                // With a known line end the silence is measured, not guessed from the next start,
                // so a shorter gap is already certain to be music.
                val end = line.endMs?.takeIf { it > line.timestampMs }
                if (end != null) fillGap(end, next.timestampMs, INSTRUMENTAL_GAP_AFTER_END_MS)
                else fillGap(line.timestampMs, next.timestampMs)
            }

            if (totalDurationMs > 0L) {
                val last = lines.last()
                val end = last.endMs?.takeIf { it > last.timestampMs }
                if (end != null) fillGap(end, totalDurationMs, INSTRUMENTAL_GAP_AFTER_END_MS)
                else fillGap(last.timestampMs, totalDurationMs)
            }

            return result
        }
    }

    /**
     * Lyrics for [song], best available first, cached on the song row.
     *
     * Synced sources are tried in order of how precisely they follow the singer, and the first
     * synced result wins:
     * 1. Better Lyrics: a time on every word (when its cache has the song).
     * 2. YouTube Music's own lyrics: per-line start *and end*, what the YT Music app shows. Tried
     *    for the track's own video id, then for the official song found by name, since music
     *    videos often have no lyrics tab while the song does.
     * 3. LrcLib, 4. KuGou (Chinese/Japanese catalogue), 5. the file's own tags.
     * Unsynced text from any of them is kept as the fallback, then YouTube captions last.
     *
     * Stored lyrics that are synced are returned as they are. Stored lyrics that are *not* synced
     * get one look per session for a synced version, so a library fetched before these sources
     * existed catches up.
     */
    suspend fun fetchAndSaveLyrics(song: Song, forceFetch: Boolean = false): String? = withContext(Dispatchers.IO) {
        val stored = song.lyrics?.takeIf { it.isNotBlank() }
        if (!forceFetch && stored != null) {
            if (isSynced(stored) || !upgradeAttempted.add(song.id)) return@withContext stored
            val synced = firstSynced(song).first
            if (synced != null) {
                musicRepository.updateSongLyrics(song.id, synced)
                return@withContext synced
            }
            return@withContext stored
        }

        val (synced, plain) = firstSynced(song)
        val fetchedLyrics = synced ?: plain

        if (!fetchedLyrics.isNullOrBlank()) {
            musicRepository.updateSongLyrics(song.id, fetchedLyrics)
        }
        return@withContext fetchedLyrics
    }

    /**
     * Walks the sources in order and returns the first synced lyrics, plus the first unsynced
     * text seen on the way as a fallback.
     */
    private suspend fun firstSynced(song: Song): Pair<String?, String?> {
        val cleaned = com.example.tgmusicai.data.local.AiMetadataCleaner.cleanOffline(song.title, song.artist)
        val title = cleaned.cleanTitle.ifBlank { song.title }
        val artist = (cleaned.artist ?: song.artist).takeUnless { it.equals("<unknown>", true) }.orEmpty()
        val durationSec = song.durationMs / 1000
        var plain: String? = null

        val sources: List<Pair<String, suspend () -> String?>> = providerSettings()
            .filter { it.enabled }
            .map { setting ->
                val provider = setting.provider
                provider.label to when (provider) {
                    LyricsProvider.YOUTUBE_MUSIC -> suspend { fetchFromYouTubeMusic(song, title, artist) { plain = plain ?: it } }
                    // Apple Music's word-synced TTML, served by Better Lyrics.
                    LyricsProvider.APPLE_MUSIC -> suspend { betterLyrics.fetch(title, artist, durationSec) }
                    LyricsProvider.LRCLIB -> suspend { fetchFromLrcLib(song) }
                    LyricsProvider.QQ -> suspend { qq.fetch(title, artist, durationSec) }
                    LyricsProvider.NETEASE -> suspend { netEase.fetch(title, artist, durationSec) }
                    LyricsProvider.KUGOU -> suspend { kuGou.fetch(title, artist, durationSec) }
                    LyricsProvider.FILE_TAGS -> suspend { fetchFromEmbeddedId3(song) }
                    LyricsProvider.YOUTUBE_CAPTIONS -> suspend { fetchFromYouTubeCaptions(song) }
                }
            }
        for ((name, fetch) in sources) {
            val result = try {
                fetch()
            } catch (e: Exception) {
                Log.w(TAG, "$name lyrics lookup failed for ${song.title}", e)
                null
            } ?: continue
            if (isSynced(result)) {
                Log.d(TAG, "Synced lyrics for '${song.title}' from $name")
                return result to plain
            }
            if (plain == null) plain = result
        }
        return null to plain
    }

    /**
     * YouTube Music's own lyrics: for the track's video id, then for the official song matching
     * [title] and [artist]. Timed lyrics are returned; plain ones are handed to [onPlain].
     */
    private suspend fun fetchFromYouTubeMusic(
        song: Song,
        title: String,
        artist: String,
        onPlain: (String) -> Unit,
    ): String? {
        val tried = mutableSetOf<String>()
        suspend fun attempt(videoId: String): String? {
            if (!tried.add(videoId)) return null
            val lyrics = youTubeMusic.fetchLyrics(videoId) ?: return null
            if (lyrics.isTimed) return lyrics.text
            onPlain(lyrics.text)
            return null
        }
        song.youtubeId?.takeIf { it.isNotBlank() }?.let { attempt(it)?.let { timed -> return timed } }
        val match = youTubeMusic.searchSongs("$title $artist".trim())
            .firstOrNull { isLyricsMatch(title, artist, song.durationMs / 1000, it.title, it.uploader, it.durationSeconds) }
            ?: return null
        return attempt(match.videoId)
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
        executeLrcLibSearch(lrcLibSearchUrl(query), durationSec)?.let { return it }

        // "A, B & C" or "A feat. B" rarely matches LRCLIB's artist field as a whole; the lead
        // artist usually does.
        val leadArtist = cleanArtist.split(LEAD_ARTIST_SPLIT).first().trim()
        if (leadArtist.isNotBlank() && leadArtist != cleanArtist) {
            executeLrcLibSearch(lrcLibSearchUrl("$leadArtist $cleanTitle"), durationSec)?.let { return it }
        }

        // Title alone, last: only accepted when the duration matches, or it is a guess.
        if (cleanArtist.isNotBlank() && durationSec > 0) {
            executeLrcLibSearch(lrcLibSearchUrl(cleanTitle), durationSec, requireDurationMatch = true)
                ?.let { return it }
        }

        return null
    }

    private fun lrcLibSearchUrl(query: String) =
        "https://lrclib.net/api/search?q=${URLEncoder.encode(query, "UTF-8")}"

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
    /**
     * Runs an LrcLib `/api/search` and picks the best hit: duration within
     * [LRCLIB_DURATION_TOLERANCE_SEC] of [durationSec] first, synced over plain lyrics second,
     * LrcLib's own order last. With [requireDurationMatch], a hit of another length is never used.
     */
    private fun executeLrcLibSearch(url: String, durationSec: Int = 0, requireDurationMatch: Boolean = false): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TGMusicAI/1.0 (https://github.com/tterrag5/TGMusicAI)")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (!body.startsWith("[")) return null
            val jsonArray = JSONArray(body)
            val hits = (0 until jsonArray.length()).map { jsonArray.getJSONObject(it) }
            fun durationMatches(hit: JSONObject) = durationSec > 0 &&
                kotlin.math.abs(hit.optDouble("duration", -1000.0) - durationSec) <= LRCLIB_DURATION_TOLERANCE_SEC
            val best = hits
                .filter { !requireDurationMatch || durationMatches(it) }
                .filter { extractLyricsFromLrcLibJson(it) != null }
                .sortedWith(
                    compareByDescending<JSONObject> { durationMatches(it) }
                        .thenByDescending { it.stringOrEmpty("syncedLyrics").isNotBlank() }
                )
                .firstOrNull() ?: return null
            return extractLyricsFromLrcLibJson(best)
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

        // Nothing to do when the lyrics are already in the target language -- and skipping it
        // avoids downloading a model to translate a language into itself.
        val sourceCode = detectSourceLanguage(lines)
        if (sourceCode == targetCode) return@withContext lines

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
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
     * The ML Kit source language for [lines], identified from the lyrics themselves.
     *
     * The source language used to be pinned to English, which made this feature structurally
     * incapable of the thing it is most wanted for: a song in another language rendered into one the
     * user reads. A Japanese track was translated English-to-English, which ML Kit answers by
     * handing most of the text back unchanged -- indistinguishable, on screen, from the translator
     * being broken. Nothing failed and nothing was logged, so the only way to notice was to try it
     * on a song that wasn't already in English.
     *
     * Falls back to English when identification is inconclusive or ML Kit has no translation model
     * for what it found. That is the old behaviour, and it is still the right guess for the common
     * case; it just isn't an assumption worth making without looking first.
     *
     * The whole lyric is sampled rather than one line, because a single line is often too short to
     * identify and a chorus of "la la la" identifies as nothing at all.
     */
    private suspend fun detectSourceLanguage(lines: List<String>): String {
        val sample = lines.asSequence()
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(MAX_LANGUAGE_SAMPLE_CHARS)
        if (sample.isBlank()) return TranslateLanguage.ENGLISH

        return try {
            val tag = LanguageIdentification.getClient().identifyLanguage(sample).await()
            if (tag == UNDETERMINED_LANGUAGE) {
                TranslateLanguage.ENGLISH
            } else {
                TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not identify the lyrics' language; assuming English", e)
            TranslateLanguage.ENGLISH
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
