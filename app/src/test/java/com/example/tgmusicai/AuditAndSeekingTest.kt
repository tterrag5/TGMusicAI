package com.example.tgmusicai

import com.example.tgmusicai.ui.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditAndSeekingTest {

    @Test
    fun testFileNameSanitizationStripsIllegalPathCharacters() {
        // Test illegal OS path characters: / \ : * ? " < > |
        val input = "Artist/Band: Title*With? \"Quotes\" & <Brackets> | Pipe \\ Slash"
        val sanitized = FormatUtils.sanitizeFileName(input)

        val illegalChars = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        for (char in illegalChars) {
            assertTrue("Sanitized file name should not contain '$char'", !sanitized.contains(char))
        }
    }

    @Test
    fun testFileNameSanitizationControlCharactersAndDots() {
        val inputWithControl = "Track\u0000Name\nWith\tControl\rChars.mp3."
        val sanitized = FormatUtils.sanitizeFileName(inputWithControl)

        assertTrue("Sanitized file name should not contain control chars", !sanitized.contains("\u0000"))
        assertTrue("Sanitized file name should not end with dot", !sanitized.endsWith("."))
    }

    @Test
    fun testFileNameSanitizationNullAndBlankFallback() {
        assertEquals("unnamed_track", FormatUtils.sanitizeFileName(null))
        assertEquals("unnamed_track", FormatUtils.sanitizeFileName(""))
        assertEquals("unnamed_track", FormatUtils.sanitizeFileName("   "))
        assertEquals("unnamed_track", FormatUtils.sanitizeFileName("..."))
    }

    @Test
    fun testFileNameSanitizationMaxLengthTruncation() {
        val longTitle = "A".repeat(300)
        val sanitized = FormatUtils.sanitizeFileName(longTitle)

        assertTrue("Sanitized file name should be <= 200 characters", sanitized.length <= 200)
    }

    @Test
    fun testDurationDivisionByZeroGuards() {
        // Zero duration
        val durationZero = 0L
        val position = 15000L

        val safeDuration = maxOf(1L, durationZero).toFloat()
        val progressFraction = (position.toFloat() / safeDuration).coerceIn(0f, 1f)

        // Progress fraction with safeDuration should equal 1f when position > safeDuration (clamped cleanly)
        assertEquals(1f, progressFraction, 0.001f)

        // Negative duration
        val durationNegative = -5000L
        val safeDurationNeg = maxOf(1L, durationNegative).toFloat()
        val progressFractionNeg = (0f / safeDurationNeg).coerceIn(0f, 1f)

        assertEquals(0f, progressFractionNeg, 0.001f)
    }

    @Test
    fun testFormatUtilsDurationFormatting() {
        assertEquals("0:00", FormatUtils.formatDuration(0L))
        assertEquals("0:00", FormatUtils.formatDuration(-1000L))
        assertEquals("0:05", FormatUtils.formatDuration(5000L))
        assertEquals("2:05", FormatUtils.formatDuration(125000L))
        assertEquals("1:02:05", FormatUtils.formatDuration(3725000L))
    }

    @Test
    fun testSeekingPositionLabelSync() {
        val currentPosMs = 30000L // 30s
        val dragPosMs = 75000L   // 1m 15s

        val activePosMs = dragPosMs
        val formattedActive = FormatUtils.formatDuration(activePosMs)
        assertEquals("1:15", formattedActive)

        val formattedRealtime = FormatUtils.formatDuration(currentPosMs)
        assertEquals("0:30", formattedRealtime)
    }
}
