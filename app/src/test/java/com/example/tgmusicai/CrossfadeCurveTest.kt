package com.example.tgmusicai

import com.example.tgmusicai.playback.CrossfadeCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossfadeCurveTest {

    @Test
    fun `equal power holds across the fade`() {
        for (step in 0..20) {
            val f = step / 20f
            val out = CrossfadeCurve.outGain(f)
            val inn = CrossfadeCurve.inGain(f)
            assertEquals(1f, out * out + inn * inn, 1e-5f)
        }
        assertEquals(1f, CrossfadeCurve.outGain(0f), 1e-6f)
        assertEquals(0f, CrossfadeCurve.inGain(0f), 1e-6f)
        assertEquals(0f, CrossfadeCurve.outGain(1f), 1e-6f)
        assertEquals(1f, CrossfadeCurve.inGain(1f), 1e-6f)
    }

    @Test
    fun `fractions outside the fade are clamped`() {
        assertEquals(1f, CrossfadeCurve.outGain(-1f), 1e-6f)
        assertEquals(1f, CrossfadeCurve.inGain(2f), 1e-6f)
    }

    @Test
    fun `handover is the fade length before the end`() {
        assertEquals(195_000L, CrossfadeCurve.handoverAt(positionMs = 190_000, durationMs = 200_000, fadeMs = 5_000, leadMs = 1_500))
    }

    @Test
    fun `handover leaves the tail time to buffer, shortening the fade`() {
        assertEquals(197_500L, CrossfadeCurve.handoverAt(positionMs = 196_000, durationMs = 200_000, fadeMs = 5_000, leadMs = 1_500))
    }

    @Test
    fun `no handover when there is no room for a fade`() {
        assertNull(CrossfadeCurve.handoverAt(positionMs = 199_000, durationMs = 200_000, fadeMs = 5_000, leadMs = 1_500))
        assertNull(CrossfadeCurve.handoverAt(positionMs = 0, durationMs = 0, fadeMs = 5_000, leadMs = 1_500))
        assertNull(CrossfadeCurve.handoverAt(positionMs = 0, durationMs = 200_000, fadeMs = 0, leadMs = 1_500))
    }
}
