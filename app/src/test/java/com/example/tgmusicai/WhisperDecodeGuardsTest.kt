package com.example.tgmusicai

import com.example.tgmusicai.ai.whisper.WhisperDecodeGuards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guards that stop on-device transcription from filling a lyrics pane with one line repeated
 * hundreds of times -- the behaviour that made the feature unusable, and the reason it was also
 * slow, since a stuck decode spends its entire token budget before giving up.
 *
 * The balance each test pins down is the same: catch the model looping without censoring a song
 * that genuinely repeats itself.
 */
class WhisperDecodeGuardsTest {

    // ---- endsInRepeatedCycle ---------------------------------------------------------------

    @Test
    fun `a single token repeated is caught`() {
        assertTrue(WhisperDecodeGuards.endsInRepeatedCycle(listOf(5, 9, 7, 7, 7)))
    }

    @Test
    fun `a repeated phrase is caught, not just a repeated token`() {
        // "I don't know" three times over -- the classic stuck greedy decode.
        val tokens = listOf(40, 2) + listOf(1, 2, 3, 1, 2, 3, 1, 2, 3)
        assertTrue(WhisperDecodeGuards.endsInRepeatedCycle(tokens))
    }

    @Test
    fun `a phrase sung twice is left alone`() {
        // Two repetitions is a chorus; three back-to-back is the model.
        assertTrue(WhisperDecodeGuards.endsInRepeatedCycle(listOf(1, 2, 3, 1, 2, 3, 1, 2, 3)))
        assertFalse(WhisperDecodeGuards.endsInRepeatedCycle(listOf(1, 2, 3, 1, 2, 3)))
    }

    @Test
    fun `a repeat earlier in the line does not stop a decode that has moved on`() {
        assertFalse(WhisperDecodeGuards.endsInRepeatedCycle(listOf(7, 7, 7, 1, 2, 3, 4)))
    }

    @Test
    fun `ordinary text is not flagged`() {
        assertFalse(WhisperDecodeGuards.endsInRepeatedCycle(listOf(11, 4, 92, 7, 33, 8, 1, 60)))
    }

    @Test
    fun `too few tokens to judge is not a repeat`() {
        assertFalse(WhisperDecodeGuards.endsInRepeatedCycle(emptyList()))
        assertFalse(WhisperDecodeGuards.endsInRepeatedCycle(listOf(4, 4)))
    }

    @Test
    fun `a cycle longer than the window is not searched for`() {
        // 13 distinct tokens repeated three times exceeds MAX_CYCLE_LEN, so it is not claimed --
        // the guard is deliberately bounded rather than scanning arbitrarily far back.
        val phrase = (1..13).toList()
        assertFalse(WhisperDecodeGuards.endsInRepeatedCycle(phrase + phrase + phrase))
    }

    // ---- isEffectivelySilent ---------------------------------------------------------------

    @Test
    fun `digital silence is silent`() {
        assertTrue(WhisperDecodeGuards.isEffectivelySilent(FloatArray(16_000)))
    }

    @Test
    fun `an empty window is silent rather than a crash`() {
        assertTrue(WhisperDecodeGuards.isEffectivelySilent(FloatArray(0)))
    }

    @Test
    fun `faint hiss is silent`() {
        val hiss = FloatArray(16_000) { if (it % 2 == 0) 0.0005f else -0.0005f }
        assertTrue(WhisperDecodeGuards.isEffectivelySilent(hiss))
    }

    @Test
    fun `music is not silent`() {
        val tone = FloatArray(16_000) { Math.sin(it * 0.05).toFloat() * 0.3f }
        assertFalse(WhisperDecodeGuards.isEffectivelySilent(tone))
    }

    @Test
    fun `one loud sample in a silent window does not make it audible`() {
        // A real window is Whisper's own 30 seconds at 16kHz, and the length is the point: RMS
        // spreads a single click across all of it, which is exactly why a click cannot pass for
        // singing. (In a window a thirtieth that size the same click would clear the threshold --
        // the guard is calibrated for the windows it is actually handed.)
        val blip = FloatArray(30 * 16_000)
        blip[0] = 1f
        assertTrue(WhisperDecodeGuards.isEffectivelySilent(blip))
    }

    // ---- collapseRepeatedPhrases -----------------------------------------------------------

    @Test
    fun `a sentence repeated back to back collapses to one`() {
        assertEquals("Oh no.", WhisperDecodeGuards.collapseRepeatedPhrases("Oh no. Oh no. Oh no."))
    }

    @Test
    fun `a repeat separated by other words is kept`() {
        assertEquals(
            "Oh no. Here we go. Oh no.",
            WhisperDecodeGuards.collapseRepeatedPhrases("Oh no. Here we go. Oh no.")
        )
    }

    @Test
    fun `casing does not hide a repeat`() {
        assertEquals("Hello there.", WhisperDecodeGuards.collapseRepeatedPhrases("Hello there. HELLO THERE."))
    }

    @Test
    fun `a line with no punctuation is returned as-is`() {
        assertEquals(
            "just one long sung line",
            WhisperDecodeGuards.collapseRepeatedPhrases("  just one long sung line  ")
        )
    }

    @Test
    fun `blank text stays blank`() {
        assertEquals("", WhisperDecodeGuards.collapseRepeatedPhrases("   "))
    }
}
