package com.example.tgmusicai.data.repository

import android.util.Log
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An endless feed of cloud tracks, walked outwards from what the user actually listens to.
 *
 * ## How it stays endless without being random
 *
 * The feed is a breadth-first walk of YouTube Music's artist graph. It starts at the artists behind
 * the user's recent listening, and each artist visited contributes their top tracks and pushes their
 * related artists onto the frontier. Because the frontier grows faster than it is consumed, the walk
 * does not run out; because it started from the user's own listening, page five is still reachable
 * from their taste rather than being whatever YouTube promotes today. YouTube's own home-feed
 * recommendations seed the first page, so the feed has something to show before any of the graph
 * walking has happened.
 *
 * ## Why paging is stateful rather than index-addressed
 *
 * A page cannot be computed from its number: which artists are on the frontier depends on every
 * artist visited before it. So this holds the walk's state and hands out the next page on request.
 * One instance serves one feed; a new one starts the walk again.
 *
 * ## What it guarantees
 *
 * Every page is filtered against the library (a feed suggesting music the user already owns is
 * worse than no feed) and against everything already emitted, so a track never appears twice.
 * [nextPage] never throws, is serialised so two scroll-triggered calls cannot interleave and hand
 * out the same tracks twice, and bounds its own work: at most [ARTISTS_PER_PAGE] artist fetches per
 * page, so one page is a predictable cost rather than however long the graph takes to yield enough.
 */
class CloudFeedPager(
    private val browser: YouTubeMusicBrowser,
    private val songDao: SongDao,
    private val recommendationEngine: RecommendationEngine? = null,
) {
    private val lock = Mutex()

    /** Artist browse ids still to visit. Grows as visited artists contribute their related artists. */
    private val frontier = ArrayDeque<String>()
    private val visitedArtists = mutableSetOf<String>()
    private val emittedVideoIds = mutableSetOf<String>()

    /** Tracks fetched but not yet handed out, so a fetch that overshoots a page is not wasted. */
    private val buffer = mutableListOf<YouTubeSearchResult>()

    private var started = false

    /**
     * True once the walk has nowhere left to go. Distinct from "this page came back empty", which
     * can happen simply because everything a page found was already owned.
     */
    var exhausted: Boolean = false
        private set

    /**
     * The next [pageSize] tracks, or fewer (including none) when the walk is running out. Callers
     * should stop asking once [exhausted] is true.
     */
    suspend fun nextPage(pageSize: Int = PAGE_SIZE): List<YouTubeSearchResult> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    if (!started) {
                        started = true
                        seedWalk()
                    }
                    fillBuffer(pageSize)
                    takeFromBuffer(pageSize)
                } catch (e: Throwable) {
                    // A feed is not worth a crash, and the user can always scroll again.
                    Log.e(TAG, "Could not extend the discover feed", e)
                    emptyList()
                }
            }
        }

    /**
     * Primes the walk: YouTube's home feed for something to show immediately, and the user's own
     * recent artists as the graph's starting points.
     *
     * A fresh install has no listening to start from. Rather than showing nothing, the home feed's
     * own uploaders become the starting artists -- generic, but it is generic music the user is
     * being shown anyway, and one tap of listening replaces it.
     */
    private suspend fun seedWalk() {
        val homeTracks = try {
            browser.fetchHomeFeed().flatMap { it.tracks }
        } catch (e: Throwable) {
            Log.w(TAG, "Home feed unavailable while seeding the feed", e)
            emptyList()
        }
        buffer += homeTracks

        val tasteArtists = try {
            recommendationEngine?.tasteSeeds(SEED_SONG_COUNT).orEmpty()
                .map { it.artist.trim() }
                .filter { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                .distinct()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not read taste seeds while seeding the feed", e)
            emptyList()
        }

        val startingNames = tasteArtists.ifEmpty {
            homeTracks.map { it.uploader.trim() }.filter { it.isNotBlank() }.distinct()
        }.take(STARTING_ARTIST_COUNT)

        for (name in startingNames) {
            val ref = try {
                browser.searchArtists(name).firstOrNull()
            } catch (e: Throwable) {
                Log.w(TAG, "Artist lookup failed for '$name'", e)
                null
            } ?: continue
            if (visitedArtists.add(ref.browseId)) frontier.addLast(ref.browseId)
        }

        if (frontier.isEmpty() && buffer.isEmpty()) exhausted = true
    }

    /** Visits artists until the buffer can satisfy a page, or the per-page fetch budget runs out. */
    private suspend fun fillBuffer(pageSize: Int) {
        var fetches = 0
        while (buffer.size < pageSize && fetches < ARTISTS_PER_PAGE) {
            val browseId = frontier.removeFirstOrNull() ?: break
            fetches++
            val page = try {
                browser.fetchArtist(browseId)
            } catch (e: Throwable) {
                Log.w(TAG, "Artist page failed for $browseId", e)
                null
            } ?: continue

            buffer += page.topTracks.take(TRACKS_PER_ARTIST)
            // The related artists are what keeps the walk going. Pushed at the back, so the feed
            // broadens steadily outwards instead of diving down one artist's chain.
            page.relatedArtists.forEach { related ->
                if (visitedArtists.add(related.browseId)) frontier.addLast(related.browseId)
            }
        }
        if (frontier.isEmpty() && buffer.isEmpty()) exhausted = true
    }

    /** Hands out a page, dropping duplicates and anything the library already has. */
    private suspend fun takeFromBuffer(pageSize: Int): List<YouTubeSearchResult> {
        val page = mutableListOf<YouTubeSearchResult>()
        while (page.size < pageSize && buffer.isNotEmpty()) {
            val track = buffer.removeAt(0)
            if (track.videoId.isBlank()) continue
            if (!emittedVideoIds.add(track.videoId)) continue
            if (isOwned(track)) continue
            page += track
        }
        return page
    }

    private suspend fun isOwned(track: YouTubeSearchResult): Boolean = try {
        songDao.getSongByYoutubeId(track.videoId) != null ||
            songDao.findByTitleAndNormalizedArtist(track.title, track.uploader) != null
    } catch (e: Throwable) {
        Log.w(TAG, "Could not check '${track.title}' against the library", e)
        // A failed check must not empty the feed; an occasional duplicate is the lesser problem.
        false
    }

    companion object {
        private const val TAG = "CloudFeedPager"

        /** Tracks per page. Two full rows of a three-column grid, plus enough to scroll into. */
        const val PAGE_SIZE = 12

        /** Recently played songs asked of [RecommendationEngine] before de-duplicating artists. */
        private const val SEED_SONG_COUNT = 12

        /** Artists the walk starts from. The frontier supplies everything after these. */
        private const val STARTING_ARTIST_COUNT = 6

        /** Artist fetches one page is allowed, so a page costs a predictable amount of time. */
        private const val ARTISTS_PER_PAGE = 3

        private const val TRACKS_PER_ARTIST = 6
    }
}
