package com.example.tgmusicai

import com.example.tgmusicai.data.local.AiMetadataCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMetadataCleanerTest {

    @Test
    fun testProducerAndOfficialAudioTagParsing() {
        val rawTitle = "Drake - God's Plan (prod. Metro Boomin & Cardo) [Official Audio]"
        val cleaned = AiMetadataCleaner.cleanOffline(rawTitle)

        assertEquals("God's Plan", cleaned.cleanTitle)
        assertEquals("Drake", cleaned.artist)
        assertEquals("Metro Boomin & Cardo", cleaned.producer)
    }

    @Test
    fun testProducerAndFeaturedArtistParsing() {
        val rawTitle = "Travis Scott - SICKO MODE ft. Drake (Produced by Hit-Boy)"
        val cleaned = AiMetadataCleaner.cleanOffline(rawTitle)

        assertEquals("SICKO MODE", cleaned.cleanTitle)
        assertEquals("Travis Scott", cleaned.artist)
        assertEquals("Hit-Boy", cleaned.producer)
        assertEquals("Drake", cleaned.featuredArtist)
    }

    @Test
    fun testVideoTagAndRawArtistPreservation() {
        val rawTitle = "God's Plan (prod. by Cardo) (Official Video)"
        val rawArtist = "Drake"
        val cleaned = AiMetadataCleaner.cleanOffline(rawTitle, rawArtist)

        assertEquals("God's Plan", cleaned.cleanTitle)
        assertEquals("Drake", cleaned.artist)
        assertEquals("Cardo", cleaned.producer)
    }

    @Test
    fun testInlineProducerAndFeatParsing() {
        val rawTitle = "Starboy feat. Daft Punk prod. Doc McKinney"
        val rawArtist = "The Weeknd"
        val cleaned = AiMetadataCleaner.cleanOffline(rawTitle, rawArtist)

        assertEquals("Starboy", cleaned.cleanTitle)
        assertEquals("The Weeknd", cleaned.artist)
        assertEquals("Doc McKinney", cleaned.producer)
        assertEquals("Daft Punk", cleaned.featuredArtist)
    }

    @Test
    fun testSimpleSongWithNoExtraTags() {
        val rawTitle = "Blinding Lights"
        val rawArtist = "The Weeknd"
        val cleaned = AiMetadataCleaner.cleanOffline(rawTitle, rawArtist)

        assertEquals("Blinding Lights", cleaned.cleanTitle)
        assertEquals("The Weeknd", cleaned.artist)
        assertNull(cleaned.producer)
        assertNull(cleaned.featuredArtist)
    }

    @Test
    fun testParsingPerformance() {
        val rawTitle = "Artist Name - Song Title (feat. Feature Artist) (prod. Producer Name) [Official Video]"
        val startTime = System.currentTimeMillis()
        
        repeat(1000) {
            AiMetadataCleaner.cleanOffline(rawTitle)
        }
        
        val duration = System.currentTimeMillis() - startTime
        // 1000 iterations should take well under 100ms (< 0.1ms per iteration)
        assertTrue("Parsing 1000 titles took $duration ms (expected < 100ms)", duration < 100)
    }
}
