package com.example.tgmusicai

import com.example.tgmusicai.data.repository.CoverArtScraper
import com.example.tgmusicai.data.repository.LyricsRepository
import org.junit.Assert.assertEquals
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
}
