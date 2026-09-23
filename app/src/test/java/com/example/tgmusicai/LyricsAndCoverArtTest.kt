package com.example.tgmusicai

import com.example.tgmusicai.data.repository.CoverArtScraper
import com.example.tgmusicai.data.repository.LyricLine
import com.example.tgmusicai.data.repository.LyricsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LyricsRepository] LRC timestamp parsing and [CoverArtScraper] iTunes artwork URL resolution.
 */
class LyricsAndCoverArtTest {

    @Test
    fun testLrcLyricsParsing_validTimestamps() {
        val lrcInput = """
            [00:12.34] First line of lyrics
            [01:05.10] Second line of lyrics
            [02:30.00] Third line of lyrics
        """.trimIndent()

        val parsed = LyricsRepository.parseLyrics(lrcInput)

        assertEquals(3, parsed.size)
        assertEquals(12340L, parsed[0].timestampMs)
        assertEquals("First line of lyrics", parsed[0].text)

        assertEquals(65100L, parsed[1].timestampMs)
        assertEquals("Second line of lyrics", parsed[1].text)

        assertEquals(150000L, parsed[2].timestampMs)
        assertEquals("Third line of lyrics", parsed[2].text)
    }

    @Test
    fun testLrcLyricsParsing_plainLyricsWithoutTimestamps() {
        val plainInput = """
            Verse 1
            Line without timestamp
            Another line
        """.trimIndent()

        val parsed = LyricsRepository.parseLyrics(plainInput)

        assertEquals(3, parsed.size)
        assertEquals("Verse 1", parsed[0].text)
        assertEquals("Line without timestamp", parsed[1].text)
        assertEquals("Another line", parsed[2].text)
    }

    @Test
    fun testItunesCoverArt_highResUrlReplacement() {
        val rawUrl = "https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/12/34/56/123456/100x100bb.jpg"
        val highResUrl = CoverArtScraper.getHighResItunesUrl(rawUrl)

        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/12/34/56/123456/1000x1000bb.jpg", highResUrl)
    }

    @Test
    fun testItunesSearchJsonParsing() {
        val jsonString = """
            {
              "resultCount": 1,
              "results": [
                {
                  "trackName": "Test Song",
                  "artistName": "Test Artist",
                  "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/00/11/22/001122/100x100bb.jpg"
                }
              ]
            }
        """.trimIndent()

        val regex = "\"artworkUrl100\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val match = regex.find(jsonString)
        val rawArtworkUrl = match?.groupValues?.get(1) ?: ""

        val highResUrl = CoverArtScraper.getHighResItunesUrl(rawArtworkUrl)

        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music123/v4/00/11/22/001122/1000x1000bb.jpg", highResUrl)
    }

    @Test
    fun instrumentalMarkers_fillIntroGapBetweenGapAndOutro() {
        val lines = listOf(
            LyricLine(timestampMs = 20_000L, text = "First line"),
            LyricLine(timestampMs = 30_000L, text = "Second line"),
        )

        val padded = LyricsRepository.withInstrumentalMarkers(lines, totalDurationMs = 50_000L)

        // Every marker is a marker, sits strictly inside the gap it fills, and the real lines
        // survive in order.
        assertEquals(
            listOf("First line", "Second line"),
            padded.filter { !it.isInstrumental }.map { it.text },
        )
        assertTrue("Intro gap should be filled", padded.any { it.isInstrumental && it.timestampMs < 20_000L })
        assertTrue(
            "Gap between the two lines should be filled",
            padded.any { it.isInstrumental && it.timestampMs in 20_001L..29_999L },
        )
        assertTrue("Outro should be filled", padded.any { it.isInstrumental && it.timestampMs > 30_000L })
        assertTrue(
            "Markers should carry the music glyph",
            padded.filter { it.isInstrumental }.all { it.text == LyricsRepository.MUSIC_MARKER },
        )
        // Timestamps must stay sorted, or the highlight would jump backwards mid-gap.
        assertEquals(padded.map { it.timestampMs }.sorted(), padded.map { it.timestampMs })
    }

    @Test
    fun instrumentalMarkers_leaveShortGapsAlone() {
        val lines = listOf(
            LyricLine(timestampMs = 1_000L, text = "First line"),
            LyricLine(timestampMs = 3_000L, text = "Second line"),
        )

        val padded = LyricsRepository.withInstrumentalMarkers(lines, totalDurationMs = 4_000L)

        // Nothing here reaches the threshold, so no filler should appear.
        assertEquals(lines, padded)
    }

    @Test
    fun instrumentalMarkers_ignoreAHeldNoteSizedGap() {
        // A singer drawing a word out leaves several seconds between timestamps without the music
        // ever going wordless. That must not be marked as instrumental.
        val lines = listOf(
            LyricLine(timestampMs = 0L, text = "First line"),
            LyricLine(timestampMs = 7_000L, text = "Second line"),
        )

        val padded = LyricsRepository.withInstrumentalMarkers(lines)

        assertEquals(lines, padded)
    }

    @Test
    fun splitDenseLines_breaksALineAtItsComma() {
        val lines = listOf(
            LyricLine(timestampMs = 10_000L, text = "make me sweat, make me hotter"),
            LyricLine(timestampMs = 14_000L, text = "Next line"),
        )

        val split = LyricsRepository.splitDenseLines(lines)

        assertEquals(
            listOf("make me sweat", "make me hotter", "Next line"),
            split.map { it.text },
        )
        // The first phrase keeps the original timestamp; the second lands inside the line's span,
        // never on or past the line that follows.
        assertEquals(10_000L, split[0].timestampMs)
        assertTrue("Second phrase should start after the first", split[1].timestampMs > 10_000L)
        assertTrue("Second phrase should start before the next line", split[1].timestampMs < 14_000L)
    }

    @Test
    fun splitDenseLines_leaveShortFragmentsAttached() {
        // "oh" is too short to stand as its own line, which leaves nothing to split -- so the
        // line is passed through exactly as written, punctuation included.
        val lines = listOf(LyricLine(timestampMs = 0L, text = "hold me close, oh"))

        assertEquals(lines, LyricsRepository.splitDenseLines(lines, totalDurationMs = 30_000L))
    }

    @Test
    fun splitDenseLines_leaveSinglePhraseLinesAlone() {
        val lines = listOf(
            LyricLine(timestampMs = 0L, text = "just one phrase here"),
            LyricLine(timestampMs = 5_000L, text = "and another"),
        )

        assertEquals(lines, LyricsRepository.splitDenseLines(lines, totalDurationMs = 30_000L))
    }

    @Test
    fun instrumentalMarkers_spreadEvenlyAcrossALongGap() {
        val lines = listOf(
            LyricLine(timestampMs = 0L, text = "First line"),
            LyricLine(timestampMs = 40_000L, text = "Second line"),
        )

        val markers = LyricsRepository.withInstrumentalMarkers(lines).filter { it.isInstrumental }

        // A 40s break at roughly one marker per 5s, spaced so the highlight keeps moving at a
        // steady pace rather than sitting still and then jumping.
        assertEquals(7, markers.size)
        val spacings = markers.map { it.timestampMs }.zipWithNext { a, b -> b - a }.distinct()
        assertEquals(1, spacings.size)
    }

    @Test
    fun instrumentalMarkers_skipUnsyncedLyrics() {
        // Plain (unsynced) lyrics carry the -1 sentinel, so there are no real gaps to measure.
        val lines = listOf(
            LyricLine(timestampMs = -1L, text = "First line"),
            LyricLine(timestampMs = -1L, text = "Second line"),
        )

        assertEquals(lines, LyricsRepository.withInstrumentalMarkers(lines, totalDurationMs = 60_000L))
    }
}
