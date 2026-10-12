package com.example.tgmusicai

import com.example.tgmusicai.data.repository.LyricLine
import com.example.tgmusicai.data.repository.LyricSegmenter
import com.example.tgmusicai.data.repository.LyricsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Karaoke segmentation and word-timing synthesis.
 *
 * The fixtures are deliberately Japanese Vocaloid lines rather than English pop. An availability
 * survey of ten niche Vocaloid tracks against LrcLib returned one line-synced result, one
 * plain-text-only result and eight outright misses, while mainstream controls returned synced
 * lyrics every time -- so this music is both the hardest case for the feature and the case it was
 * asked for. Testing with English would have hidden the defect these tests exist to pin down:
 * Japanese has no spaces, so a whitespace word-splitter returns the whole line as one unit and the
 * karaoke highlight silently does nothing.
 *
 * Lyric text is kept to short phrases, since the point is the segmentation, not the lyrics.
 */
class KaraokeWordTimingTest {

    // --- Segmentation -------------------------------------------------------------------------

    @Test
    fun `japanese line splits into many units where a whitespace split would yield one`() {
        // MARETU - "脳漿炎" (Noushouen): an LrcLib miss, so this is exactly the kind of
        // track that only gets karaoke if the segmentation handles spaceless text.
        val line = "愛して愛して"  // aishite aishite

        val whitespaceSplit = line.split(" ").filter { it.isNotBlank() }
        assertEquals("precondition: a whitespace split sees one unit", 1, whitespaceSplit.size)

        val segments = LyricSegmenter.segment(line)
        assertEquals(6, segments.size)
        assertNotEquals(
            "segmentation must beat a whitespace split on spaceless text",
            whitespaceSplit.size,
            segments.size,
        )
    }

    @Test
    fun `small kana joins the character it is sung with`() {
        // "きょ" is one beat ("kyo"), not two.
        val segments = LyricSegmenter.segment("きょう")  // kyo u
        assertEquals(listOf("きょ", "う"), segments.map { it.text })
    }

    @Test
    fun `long vowel mark joins the mora it extends`() {
        val segments = LyricSegmenter.segment("ラーメン")  // ra- me n
        assertEquals(listOf("ラー", "メ", "ン"), segments.map { it.text })
    }

    @Test
    fun `sokuon attaches forward to the mora it doubles`() {
        // "いった" is sung "i-tta": the small tsu belongs to the mora after it, so the
        // grouping is "い" + "った" and never "いっ" + "た".
        val segments = LyricSegmenter.segment("いった")
        assertEquals(listOf("い", "った"), segments.map { it.text })
    }

    @Test
    fun `combining voicing mark stays with its base character`() {
        val segments = LyricSegmenter.segment("がい")  // ka + dakuten, i
        assertEquals(listOf("が", "い"), segments.map { it.text })
    }

    @Test
    fun `mixed script line splits at the script boundary`() {
        // Vocaloid titles and lyrics mix Latin into Japanese constantly.
        val segments = LyricSegmenter.segment("カレンダー girl")
        assertEquals(listOf("カ", "レ", "ン", "ダー", "girl"), segments.map { it.text })
    }

    @Test
    fun `space delimited text splits on words and keeps punctuation attached`() {
        val segments = LyricSegmenter.segment("hello, cruel world")
        assertEquals(listOf("hello,", "cruel", "world"), segments.map { it.text })
    }

    @Test
    fun `korean splits on spaces rather than per character`() {
        // Hangul is not a spaceless script; splitting it per character would break words apart.
        val segments = LyricSegmenter.segment("사랑해 줄게")
        assertEquals(listOf("사랑해", "줄게"), segments.map { it.text })
    }

    @Test
    fun `segments reproduce the original text apart from whitespace`() {
        val line = "ダーリン, って don't go"
        val rejoined = LyricSegmenter.segment(line).joinToString("") { it.text }
        assertEquals(line.filterNot { it.isWhitespace() }, rejoined)
    }

    @Test
    fun `blank and whitespace only lines segment to nothing`() {
        assertTrue(LyricSegmenter.segment("").isEmpty())
        assertTrue(LyricSegmenter.segment("   \t ").isEmpty())
    }

    @Test
    fun `cjk characters carry more weight than latin ones`() {
        val kana = LyricSegmenter.segment("あ").single()
        val latin = LyricSegmenter.segment("a").single()
        assertTrue(
            "a sung kana is held longer than one latin letter",
            kana.weight > latin.weight,
        )
    }

    // --- Timing synthesis ---------------------------------------------------------------------

    @Test
    fun `word timings divide the line span flush and in order`() {
        val lines = listOf(
            LyricLine(timestampMs = 1_000L, text = "ラーメン"),  // ra- me n
            LyricLine(timestampMs = 5_000L, text = "次"),
        )

        val words = LyricsRepository.withWordTimings(lines).first().words

        assertEquals(3, words.size)
        assertEquals("first unit starts with the line", 1_000L, words.first().startMs)
        assertEquals("last unit ends with the line", 5_000L, words.last().endMs)
        for (i in 1 until words.size) {
            assertEquals(
                "unit $i must start exactly where unit ${i - 1} ended, with no gap or overlap",
                words[i - 1].endMs,
                words[i].startMs,
            )
        }
        assertTrue("every unit occupies real time", words.all { it.durationMs > 0L })
    }

    @Test
    fun `heavier units get proportionally more of the span`() {
        // "ラー" is two CJK characters; "メ" and "ン" are one each. The first unit
        // should therefore hold roughly twice as long as either of the others.
        val lines = listOf(
            LyricLine(timestampMs = 0L, text = "ラーメン"),
            LyricLine(timestampMs = 4_000L, text = "end"),
        )

        val words = LyricsRepository.withWordTimings(lines).first().words

        assertEquals(2_000L, words[0].durationMs)
        assertEquals(1_000L, words[1].durationMs)
        assertEquals(1_000L, words[2].durationMs)
    }

    @Test
    fun `a line followed by a long instrumental stretch does not hold its last word forever`() {
        val lines = listOf(
            LyricLine(timestampMs = 0L, text = "hello cruel world"),
            LyricLine(timestampMs = 120_000L, text = "back again"),
        )

        val words = LyricsRepository.withWordTimings(lines).first().words

        assertTrue(
            "the span is capped rather than running to the next line two minutes later",
            words.last().endMs <= 8_000L,
        )
    }

    @Test
    fun `the final line is bounded by the track duration`() {
        val lines = listOf(LyricLine(timestampMs = 1_000L, text = "last words here"))

        val words = LyricsRepository.withWordTimings(lines, totalDurationMs = 4_000L).first().words

        assertEquals(3, words.size)
        assertEquals(4_000L, words.last().endMs)
    }

    @Test
    fun `unsynced lines get no word timings`() {
        // Plain lyrics carry the -1 sentinel: there is no timestamp to divide. This is the common
        // outcome for niche Vocaloid -- the survey's one Kikuo hit was plain text, not synced.
        val lines = listOf(
            LyricLine(timestampMs = -1L, text = "愛して愛して"),
            LyricLine(timestampMs = -1L, text = "きみがすきだよ"),
        )

        val result = LyricsRepository.withWordTimings(lines)

        assertTrue("no timestamps means no karaoke", result.all { it.words.isEmpty() })
        assertEquals("the lines themselves are untouched", lines.map { it.text }, result.map { it.text })
    }

    @Test
    fun `instrumental markers get no word timings`() {
        val lines = listOf(
            LyricLine(timestampMs = 0L, text = LyricsRepository.MUSIC_MARKER, isInstrumental = true),
            LyricLine(timestampMs = 5_000L, text = "words at last"),
        )

        val result = LyricsRepository.withWordTimings(lines)

        assertTrue("the marker is not sung", result.first().words.isEmpty())
        assertTrue("the real line still gets timings", result.last().words.isNotEmpty())
    }

    @Test
    fun `a single unit line falls back to whole line highlighting`() {
        val lines = listOf(
            LyricLine(timestampMs = 0L, text = "oh"),
            LyricLine(timestampMs = 2_000L, text = "more"),
        )

        val result = LyricsRepository.withWordTimings(lines)

        assertTrue(
            "one unit means word-level and line-level are the same thing",
            result.first().words.isEmpty(),
        )
    }

    @Test
    fun `the full parse chain produces karaoke timings for a synced japanese lyric`() {
        // End to end over the same stages PlayerViewModel runs, in the same order.
        // Built by concatenation rather than as a raw string: Kotlin leaves \u escapes untouched
        // inside """ """, which would feed the parser literal backslashes instead of kana.
        val lrc = "[00:01.00]愛して愛して\n" +
            "[00:05.00]きみがすきだよ"

        val parsed = LyricsRepository.parseLyrics(lrc)
        val chained = LyricsRepository.withWordTimings(
            LyricsRepository.withInstrumentalMarkers(
                LyricsRepository.splitDenseLines(parsed, 10_000L),
                10_000L,
            ),
            10_000L,
        )

        val sung = chained.filterNot { it.isInstrumental }
        assertTrue("the synced lines survived the chain", sung.isNotEmpty())
        assertTrue(
            "every sung line came out with more than one highlight unit",
            sung.all { it.words.size > 1 },
        )
        assertTrue(
            "timings never run backwards across the song",
            sung.flatMap { it.words }.zipWithNext().all { (a, b) -> a.startMs <= b.startMs },
        )
    }
}
