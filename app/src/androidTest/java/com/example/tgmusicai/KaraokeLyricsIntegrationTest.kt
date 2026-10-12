package com.example.tgmusicai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.tgmusicai.data.repository.LyricLine
import com.example.tgmusicai.data.repository.LyricsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Karaoke word timings against real lyrics fetched over the real network, on a device.
 *
 * The unit tests pin the segmentation and the timing arithmetic against fixtures. What they cannot
 * cover is whether real LrcLib payloads for this library's actual music survive the parse chain and
 * come out with usable word timings -- the LRC in the wild carries metadata tags, blank lines,
 * repeated-chorus timestamp runs and full-width punctuation that a hand-written fixture does not.
 *
 * The track list is deliberately niche Japanese Vocaloid rather than mainstream pop. A survey of
 * ten such tracks returned one line-synced hit, one plain-text-only hit and eight misses, against
 * two-for-two on mainstream controls, so this is where the feature is actually under strain. The
 * test asserts the pipeline is correct on whichever of them come back synced and degrades quietly
 * on the rest, rather than requiring any particular track to be present -- LrcLib's catalogue is
 * community-contributed and changes.
 *
 * Needs network access to lrclib.net and is skipped rather than failed without it; see the note in
 * `CLAUDE.md` about instrumented tests and the environment. Requests are spaced out on purpose:
 * LrcLib answered HTTP 503 to an unspaced run of the same survey.
 */
@RunWith(AndroidJUnit4::class)
class KaraokeLyricsIntegrationTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Artist to track, as LrcLib indexes them. */
    private val nicheVocaloid = listOf(
        "DECO*27" to "ヴァンパイア",            // Vampire
        "Kikuo" to "愛して愛して愛して",  // Aishite x3
        "MARETU" to "ハートアラモード", // Heart a la Mode
        "PinocchioP" to "ノンセンス文学",   // Nonsense Bungaku
        "Hiiragi Magnetite" to "メズマライザー", // Mesmerizer
    )

    private data class Fetched(val artist: String, val track: String, val syncedLyrics: String)

    @Test
    fun realSyncedVocaloidLyricsProduceUsableWordTimings() {
        val synced = mutableListOf<Fetched>()
        var reachedServer = false

        for ((artist, track) in nicheVocaloid) {
            val result = runCatching { searchSynced(artist, track) }
            if (result.isSuccess) reachedServer = true
            result.getOrNull()?.let { synced.add(Fetched(artist, track, it)) }
            Thread.sleep(REQUEST_SPACING_MS)
        }

        assumeTrue("lrclib.net unreachable from this device; environment, not a regression", reachedServer)
        assumeTrue(
            "no niche Vocaloid track in the sample currently has synced lyrics on LrcLib " +
                "(expected for this catalogue -- the survey saw 1 of 10)",
            synced.isNotEmpty(),
        )

        for (fetched in synced) {
            val label = "${fetched.artist} - ${fetched.track}"

            // The same chain, in the same order, that PlayerViewModel.parsedLyrics runs.
            val parsed = LyricsRepository.parseLyrics(fetched.syncedLyrics)
            val lines = LyricsRepository.withWordTimings(
                LyricsRepository.withInstrumentalMarkers(
                    LyricsRepository.splitDenseLines(parsed, TRACK_DURATION_MS),
                    TRACK_DURATION_MS,
                ),
                TRACK_DURATION_MS,
            )

            val sung = lines.filter { it.timestampMs >= 0 && !it.isInstrumental }
            assertTrue("$label: real LRC produced no sung lines", sung.isNotEmpty())

            val withTimings = sung.filter { it.words.isNotEmpty() }
            assertTrue(
                "$label: not a single line got word timings -- this is the whitespace-split " +
                    "failure mode the segmenter exists to prevent",
                withTimings.isNotEmpty(),
            )

            assertWordTimingsAreSane(label, withTimings)
        }
    }

    @Test
    fun aTrackWithNoLyricsDegradesToNoKaraokeRatherThanFailing() {
        // A deliberately unfindable track: the realistic case for this library, and the path that
        // has to stay quiet rather than throw.
        val lyrics = runCatching {
            searchSynced("Kikuo", "きみがすきだよ")
        }
        assumeTrue("lrclib.net unreachable from this device", lyrics.isSuccess)

        // Whether it misses outright or returns something, the chain must not throw and must not
        // invent timings for lines it has no timestamps for.
        val raw = lyrics.getOrNull()
        val lines = LyricsRepository.withWordTimings(
            LyricsRepository.withInstrumentalMarkers(
                LyricsRepository.splitDenseLines(LyricsRepository.parseLyrics(raw), 0L),
                0L,
            ),
            0L,
        )

        if (raw == null) {
            assertTrue("a miss must parse to nothing at all", lines.isEmpty())
        } else {
            assertTrue(
                "unsynced lines must not carry word timings",
                lines.filter { it.timestampMs < 0 }.all { it.words.isEmpty() },
            )
        }
    }

    /** Properties that have to hold for any line the karaoke highlight will animate. */
    private fun assertWordTimingsAreSane(label: String, lines: List<LyricLine>) {
        for (line in lines) {
            val words = line.words

            assertTrue("$label: a timed line should have more than one unit", words.size > 1)
            assertEquals(
                "$label: the first unit must start with its line",
                line.timestampMs,
                words.first().startMs,
            )
            assertTrue("$label: every unit must occupy real time", words.all { it.durationMs > 0L })

            for (i in 1 until words.size) {
                assertEquals(
                    "$label: unit $i must be flush against the one before it",
                    words[i - 1].endMs,
                    words[i].startMs,
                )
            }

            // The rendered text has to still be the lyric. Segments drop the whitespace between
            // them, so compare with whitespace removed.
            assertEquals(
                "$label: units must reproduce the line's text",
                line.text.filterNot { it.isWhitespace() },
                words.joinToString("") { it.text }.filterNot { it.isWhitespace() },
            )
        }

        // Across the line, time only ever moves forward.
        val starts = lines.flatMap { it.words }.map { it.startMs }
        assertEquals("$label: word timings must be non-decreasing", starts.sorted(), starts)
    }

    /**
     * Synced lyrics for [artist]/[track] from LrcLib's search endpoint, or null when nothing
     * matching has them. Throws when the server can't be reached, which the callers turn into a
     * skip.
     */
    private fun searchSynced(artist: String, track: String): String? {
        val query = URLEncoder.encode("$artist $track", "UTF-8")
        val request = Request.Builder()
            .url("https://lrclib.net/api/search?q=$query")
            .header("User-Agent", "TGMusicAI instrumented test")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("lrclib returned HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val results = JSONArray(body)
            for (i in 0 until results.length()) {
                val entry = results.optJSONObject(i) ?: continue
                entry.syncedOrNull()?.let { return it }
            }
            return null
        }
    }

    private fun JSONObject.syncedOrNull(): String? =
        optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }

    private companion object {
        /** LrcLib 503s on an unspaced run of these. */
        const val REQUEST_SPACING_MS = 1_500L

        /** Stands in for a track length, so the last line and the outro are bounded. */
        const val TRACK_DURATION_MS = 240_000L
    }
}
