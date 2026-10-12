package com.example.tgmusicai.playback

/**
 * How much autoplay adds to the queue, and from where. Kept apart from [PlaybackService] so the
 * arithmetic can be unit-tested without a service.
 *
 * Autoplay keeps a standing reserve of upcoming tracks rather than waiting for the queue to run
 * out: when fewer than [MIN_UPCOMING] are left after the playing track, it tops the queue back up
 * to [TARGET_UPCOMING]. Up Next is therefore never empty, and a run of skips never lands on the
 * end of the queue.
 */
object AutoplayPlanner {

    /** Below this many tracks after the current one, autoplay tops the queue up. */
    const val MIN_UPCOMING = 10

    /** What a top-up fills the upcoming part of the queue back to. */
    const val TARGET_UPCOMING = 20

    /**
     * Share of a top-up that is cloud recommendations rather than tracks already on the device.
     * Less than half: each cloud track costs a stream resolution, and a queue that keeps the user
     * in music they own reads as their library continuing.
     */
    const val CLOUD_SHARE = 0.4

    /**
     * Ceiling on cloud tracks resolved in one top-up. Resolutions run one after another inside a
     * single time budget, and YouTube throttles a burst of them from one address.
     */
    const val MAX_CLOUD_PER_TOP_UP = 8

    /** Tracks after the playing one in a queue of [queueSize] with the player at [currentIndex]. */
    fun upcoming(queueSize: Int, currentIndex: Int): Int =
        if (currentIndex < 0) 0 else (queueSize - 1 - currentIndex).coerceAtLeast(0)

    /** How many tracks to add now: none while the reserve holds, otherwise back up to the target. */
    fun topUpCount(upcoming: Int): Int =
        if (upcoming >= MIN_UPCOMING) 0 else TARGET_UPCOMING - upcoming

    /**
     * Cloud tracks to ask for in a top-up of [need] when [localAvailable] on-device tracks could be
     * added. The cloud share, raised to cover whatever the device cannot, capped per top-up.
     */
    fun cloudCount(need: Int, localAvailable: Int): Int {
        if (need <= 0) return 0
        val share = Math.round(need * CLOUD_SHARE).toInt()
        val shortfall = (need - localAvailable).coerceAtLeast(0)
        return maxOf(share, shortfall).coerceAtMost(MAX_CLOUD_PER_TOP_UP).coerceAtMost(need)
    }

    /**
     * Mixes [cloud] into [local] so cloud tracks are spread through the top-up instead of bunched,
     * then trims to [need]. When one side runs short the other fills the rest.
     */
    fun <T> mix(cloud: List<T>, local: List<T>, need: Int): List<T> {
        if (need <= 0) return emptyList()
        val cloudTaken = cloud.take(need)
        val localTaken = local.take(need - cloudTaken.size)
        val total = cloudTaken.size + localTaken.size
        if (cloudTaken.isEmpty()) return localTaken
        val mixed = ArrayList<T>(total)
        var c = 0
        var l = 0
        for (position in 0 until total) {
            // Place cloud track c at the position proportional to its share of the whole.
            val cloudDue = c < cloudTaken.size && (l >= localTaken.size || c * total <= position * cloudTaken.size)
            if (cloudDue) mixed += cloudTaken[c++] else mixed += localTaken[l++]
        }
        return mixed
    }
}
