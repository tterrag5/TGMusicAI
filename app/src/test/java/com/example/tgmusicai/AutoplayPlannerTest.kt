package com.example.tgmusicai

import com.example.tgmusicai.playback.AutoplayPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoplayPlannerTest {

    @Test
    fun `upcoming counts tracks after the playing one`() {
        assertEquals(4, AutoplayPlanner.upcoming(queueSize = 5, currentIndex = 0))
        assertEquals(0, AutoplayPlanner.upcoming(queueSize = 5, currentIndex = 4))
        assertEquals(0, AutoplayPlanner.upcoming(queueSize = 0, currentIndex = -1))
    }

    @Test
    fun `no top-up while the reserve holds, otherwise back up to the target`() {
        assertEquals(0, AutoplayPlanner.topUpCount(AutoplayPlanner.MIN_UPCOMING))
        assertEquals(0, AutoplayPlanner.topUpCount(50))
        assertEquals(AutoplayPlanner.TARGET_UPCOMING - 9, AutoplayPlanner.topUpCount(9))
        // A single song played on its own gets a full reserve behind it.
        assertEquals(AutoplayPlanner.TARGET_UPCOMING, AutoplayPlanner.topUpCount(0))
    }

    @Test
    fun `cloud share rises to cover a device without enough tracks, within the cap`() {
        assertEquals(4, AutoplayPlanner.cloudCount(need = 10, localAvailable = 10))
        assertEquals(7, AutoplayPlanner.cloudCount(need = 10, localAvailable = 3))
        assertEquals(AutoplayPlanner.MAX_CLOUD_PER_TOP_UP, AutoplayPlanner.cloudCount(need = 20, localAvailable = 0))
        assertEquals(0, AutoplayPlanner.cloudCount(need = 0, localAvailable = 0))
        assertEquals(1, AutoplayPlanner.cloudCount(need = 1, localAvailable = 0))
    }

    @Test
    fun `cloud tracks are spread through the top-up`() {
        val mixed = AutoplayPlanner.mix(listOf("C1", "C2"), listOf("L1", "L2", "L3"), need = 5)
        assertEquals(listOf("C1", "L1", "L2", "C2", "L3"), mixed)
    }

    @Test
    fun `one side fills in when the other runs short`() {
        assertEquals(listOf("L1", "L2"), AutoplayPlanner.mix(emptyList(), listOf("L1", "L2"), need = 5))
        assertEquals(listOf("C1", "C2"), AutoplayPlanner.mix(listOf("C1", "C2"), emptyList(), need = 5))
        assertEquals(3, AutoplayPlanner.mix(listOf("C1", "C2"), listOf("L1", "L2", "L3"), need = 3).size)
    }
}
