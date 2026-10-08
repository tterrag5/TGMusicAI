package com.example.tgmusicai

import com.example.tgmusicai.data.scrobble.ListenBrainzScrobbler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backoff for listens queued after a failed submission.
 *
 * The delay is stored on the row rather than held in memory, so it survives a process kill -- which
 * means getting this arithmetic wrong is not a transient annoyance. A delay that overflows negative
 * makes a row permanently due and spins the queue; one that grows without bound means a listen
 * queued during a flight never gets sent.
 */
class ScrobbleRetryTest {

    @Test
    fun theFirstRetryWaitsTheBaseDelay() {
        assertEquals(ListenBrainzScrobbler.RETRY_BASE_MS, ListenBrainzScrobbler.retryDelayMs(1))
    }

    @Test
    fun aZeroOrNegativeAttemptCountStillGivesTheBaseDelay() {
        // Defensive: a row read back with a corrupt attempt count must not produce a zero delay and
        // retry in a tight loop.
        assertEquals(ListenBrainzScrobbler.RETRY_BASE_MS, ListenBrainzScrobbler.retryDelayMs(0))
        assertEquals(ListenBrainzScrobbler.RETRY_BASE_MS, ListenBrainzScrobbler.retryDelayMs(-5))
    }

    @Test
    fun theDelayDoublesWithEachFailure() {
        assertEquals(ListenBrainzScrobbler.RETRY_BASE_MS * 2, ListenBrainzScrobbler.retryDelayMs(2))
        assertEquals(ListenBrainzScrobbler.RETRY_BASE_MS * 4, ListenBrainzScrobbler.retryDelayMs(3))
        assertEquals(ListenBrainzScrobbler.RETRY_BASE_MS * 8, ListenBrainzScrobbler.retryDelayMs(4))
    }

    @Test
    fun theDelayStopsGrowingAtTheCeiling() {
        assertEquals(ListenBrainzScrobbler.RETRY_MAX_MS, ListenBrainzScrobbler.retryDelayMs(20))
        assertEquals(ListenBrainzScrobbler.RETRY_MAX_MS, ListenBrainzScrobbler.retryDelayMs(100))
    }

    @Test
    fun noAttemptCountEverProducesANonPositiveDelay() {
        // The failure that matters: a delay of zero or less makes the row due immediately, forever.
        // A bit-shift implementation overflows into exactly that at high attempt counts.
        for (attempts in -10..200) {
            val delay = ListenBrainzScrobbler.retryDelayMs(attempts)
            assertTrue(
                "attempts=$attempts produced a delay of $delay",
                delay >= ListenBrainzScrobbler.RETRY_BASE_MS,
            )
            assertTrue("attempts=$attempts exceeded the ceiling", delay <= ListenBrainzScrobbler.RETRY_MAX_MS)
        }
    }

    @Test
    fun theDelayNeverDecreasesAsFailuresAccumulate() {
        var previous = 0L
        for (attempts in 1..50) {
            val delay = ListenBrainzScrobbler.retryDelayMs(attempts)
            assertTrue("delay went backwards at attempts=$attempts", delay >= previous)
            previous = delay
        }
    }

    @Test
    fun theCeilingIsLongEnoughToBeWorthHavingAndShortEnoughToRecover() {
        // Sanity on the tuning rather than the arithmetic: retries should back off to hours, not to
        // days -- a listen queued overnight should still go out the next morning unprompted.
        assertTrue(ListenBrainzScrobbler.RETRY_MAX_MS >= 60L * 60L * 1000L)
        assertTrue(ListenBrainzScrobbler.RETRY_MAX_MS <= 24L * 60L * 60L * 1000L)
    }
}
