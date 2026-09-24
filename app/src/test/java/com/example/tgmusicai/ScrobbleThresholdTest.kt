package com.example.tgmusicai

import com.example.tgmusicai.data.scrobble.ListenBrainzScrobbler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers when a track counts as listened to.
 *
 * The threshold is the one every scrobbler has used since the practice existed -- half the track,
 * or four minutes, whichever comes first. Getting it wrong is invisible locally but shows up as a
 * listening history that disagrees with every other client the user has ever used.
 */
class ScrobbleThresholdTest {

    @Test
    fun `a short track is scrobbled at its halfway point`() {
        // Three minutes: half of it is well under the four-minute ceiling, so half wins.
        val threeMinutes = 3 * 60 * 1000L
        assertEquals(threeMinutes / 2, ListenBrainzScrobbler.scrobbleThresholdMs(threeMinutes))
    }

    @Test
    fun `a long track is scrobbled at four minutes rather than halfway`() {
        // A twenty-minute mix should not need ten minutes of listening to count.
        val twentyMinutes = 20 * 60 * 1000L
        assertEquals(
            ListenBrainzScrobbler.SCROBBLE_CEILING_MS,
            ListenBrainzScrobbler.scrobbleThresholdMs(twentyMinutes)
        )
    }

    @Test
    fun `the ceiling applies from exactly eight minutes onward`() {
        // Eight minutes is the crossover: half of it is precisely the ceiling.
        val eightMinutes = 8 * 60 * 1000L
        assertEquals(
            ListenBrainzScrobbler.SCROBBLE_CEILING_MS,
            ListenBrainzScrobbler.scrobbleThresholdMs(eightMinutes)
        )
        assertTrue(
            ListenBrainzScrobbler.scrobbleThresholdMs(eightMinutes - 1000L) <
                ListenBrainzScrobbler.SCROBBLE_CEILING_MS
        )
    }

    @Test
    fun `an unknown duration falls back to the ceiling rather than scrobbling immediately`() {
        // A live stream or an unresolved track reports no duration. Treating that as zero length
        // would make half of it zero, and every such track would scrobble the instant it started.
        assertEquals(ListenBrainzScrobbler.SCROBBLE_CEILING_MS, ListenBrainzScrobbler.scrobbleThresholdMs(0L))
        assertEquals(ListenBrainzScrobbler.SCROBBLE_CEILING_MS, ListenBrainzScrobbler.scrobbleThresholdMs(-1L))
    }

    @Test
    fun `the minimum track length is below the shortest plausible song`() {
        // Guards the constant itself: the API rejects submissions under 30 seconds, and raising
        // this past a real song length would silently drop legitimate listens.
        assertTrue(ListenBrainzScrobbler.MIN_TRACK_LENGTH_MS <= 30_000L)
    }
}
