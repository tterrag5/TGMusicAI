package com.example.tgmusicai

import androidx.media3.common.Player
import com.example.tgmusicai.playback.MediaControllerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class LoopModeTest {

    @Test
    fun testThreeStateLoopCycle() {
        // Default initial state must be REPEAT_MODE_OFF (State 0)
        var currentMode = Player.REPEAT_MODE_OFF
        assertEquals(Player.REPEAT_MODE_OFF, currentMode)

        // 1st tap: Transition from OFF to REPEAT_ALL (State 1: Loop Playlist/Queue)
        currentMode = MediaControllerManager.getNextRepeatMode(currentMode)
        assertEquals(Player.REPEAT_MODE_ALL, currentMode)

        // 2nd tap: Transition from REPEAT_ALL to REPEAT_ONE (State 2: Loop Current Song)
        currentMode = MediaControllerManager.getNextRepeatMode(currentMode)
        assertEquals(Player.REPEAT_MODE_ONE, currentMode)

        // 3rd tap: Transition from REPEAT_ONE back to REPEAT_OFF (State 0: Off)
        currentMode = MediaControllerManager.getNextRepeatMode(currentMode)
        assertEquals(Player.REPEAT_MODE_OFF, currentMode)
    }

    @Test
    fun testMultipleCyclesStayConsistent() {
        var mode = Player.REPEAT_MODE_OFF
        val expectedSequence = listOf(
            Player.REPEAT_MODE_ALL,
            Player.REPEAT_MODE_ONE,
            Player.REPEAT_MODE_OFF,
            Player.REPEAT_MODE_ALL,
            Player.REPEAT_MODE_ONE,
            Player.REPEAT_MODE_OFF
        )

        for (expected in expectedSequence) {
            mode = MediaControllerManager.getNextRepeatMode(mode)
            assertEquals(expected, mode)
        }
    }
}
