package com.example.tgmusicai

import com.example.tgmusicai.data.repository.SmartShuffle
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartShuffleTest {

    @Test
    fun `one recommendation after every three playlist tracks`() {
        val mixed = SmartShuffle.interleave(listOf("p1", "p2", "p3", "p4", "p5", "p6", "p7"), listOf("r1", "r2", "r3")) { it }
        assertEquals(listOf("p1", "p2", "p3", "r1", "p4", "p5", "p6", "r2", "p7"), mixed)
    }

    @Test
    fun `recommendations already in the playlist are skipped, and none are tacked on the end`() {
        val mixed = SmartShuffle.interleave(listOf("p1", "p2", "p3"), listOf("p2", "r1", "r1", "r2")) { it }
        assertEquals(listOf("p1", "p2", "p3", "r1"), mixed)
    }

    @Test
    fun `no recommendations is a plain playlist`() {
        assertEquals(listOf("a", "b", "c", "d"), SmartShuffle.interleave(listOf("a", "b", "c", "d"), emptyList<String>()) { it })
    }

    @Test
    fun `recommendations wanted rounds up`() {
        assertEquals(0, SmartShuffle.recommendationsWanted(0))
        assertEquals(1, SmartShuffle.recommendationsWanted(1))
        assertEquals(1, SmartShuffle.recommendationsWanted(3))
        assertEquals(2, SmartShuffle.recommendationsWanted(4))
    }
}
