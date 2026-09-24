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
 * Recommends tracks the user does *not* own yet, seeded by what they already listen to.
 *
 * [RecommendationEngine] only ever ranks songs that are already in the library, so on its own it
 * can never suggest anything new. This class closes that gap: it asks that engine for the user's
 * current taste seeds, looks each seed's artist up on YouTube Music, and collects that artist's
 * top tracks plus the top tracks of one related artist -- which is the expansion step, the point
 * where something genuinely new can enter. Everything already in the local library is filtered
 * back out, so the result is only music the user has not got.
 *
 * Three behaviours matter more than the ranking:
 *
 * - **It degrades to YouTube's own home feed.** A fresh install has no taste to extrapolate from,
 *   and a seed artist may simply not be findable. Either way the generic feed is returned rather
 *   than nothing, because a new user is exactly who needs suggestions most.
 * - **It never throws.** Every lookup is a request to someone else's undocumented API over a
 *   network that may not be there. A failure returns an empty list, and the callers (the Home
 *   screen and the playback service's autoplay) treat empty as "show/append nothing".
 * - **Results are cached for the session.** Autoplay asks for recommendations every time a queue
 *   runs dry, which on a long listening session is often; without the cache that would be a burst
 *   of catalogue requests per hour of playback for a list that barely changes.
 */
class CloudRecommendationSource(
    private val browser: YouTubeMusicBrowser,
    private val songDao: SongDao,
    private val recommendationEngine: RecommendationEngine? = null,
) {

    private val cacheLock = Mutex()
    private var cached: List<YouTubeSearchResult> = emptyList()
    private var cachedAtMs: Long = 0L

    /**
     * Up to [limit] recommended cloud tracks, best first, none of which are in the local library.
     *
     * Set [forceRefresh] to bypass the cache -- a pull-to-refresh, not something to do on every
     * call. Returns an empty list when there is no network and nothing cached.
     */
    suspend fun recommendedTracks(
        limit: Int = DEFAULT_LIMIT,
        forceRefresh: Boolean = false,
    ): List<YouTubeSearchResult> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()

        cacheLock.withLock {
            val age = System.currentTimeMillis() - cachedAtMs
            if (!forceRefresh && cached.isNotEmpty() && age < CACHE_TTL_MS) {
                return@withContext cached.take(limit)
            }

            val fetched = try {
                val seeded = tasteSeededTracks()
                // The generic feed both fills the list out when the seeded lookups came back thin
                // and stands in entirely when they came back empty.
                val combined = (seeded + homeFeedTracks()).distinctBy { it.videoId }
                excludeOwned(combined)
            } catch (e: Throwable) {
                Log.e(TAG, "Could not build cloud recommendations", e)
                emptyList()
            }

            if (fetched.isNotEmpty()) {
                cached = fetched
                cachedAtMs = System.currentTimeMillis()
            }
            // On a failed refresh the previous list is better than nothing, and it is already
            // filtered, so it is served again rather than blanking the row.
            (if (fetched.isNotEmpty()) fetched else cached).take(limit)
        }
    }

    /** Throws away the cached list, so the next request goes back to the network. */
    suspend fun invalidate() {
        cacheLock.withLock {
            cached = emptyList()
            cachedAtMs = 0L
        }
    }

    /**
     * Tracks reached by following the user's own listening out into the catalogue: each seed
     * artist's top tracks, then one related artist's, which is where suggestions stop being
     * things the user already knows about.
     */
    private suspend fun tasteSeededTracks(): List<YouTubeSearchResult> {
        val seedArtists = seedArtists()
        if (seedArtists.isEmpty()) return emptyList()

        val collected = mutableListOf<YouTubeSearchResult>()
        for (artistName in seedArtists) {
            val artistRef = try {
                browser.searchArtists(artistName).firstOrNull()
            } catch (e: Throwable) {
                Log.w(TAG, "Artist lookup failed for '$artistName'", e)
                null
            } ?: continue

            val page = browser.fetchArtist(artistRef.browseId) ?: continue
            collected += page.topTracks.take(TRACKS_PER_SEED)

            // One related artist per seed, not all of them: each is another round trip, and the
            // second and third are far enough from the seed that they read as noise.
            val related = page.relatedArtists.firstOrNull() ?: continue
            val relatedPage = browser.fetchArtist(related.browseId) ?: continue
            collected += relatedPage.topTracks.take(TRACKS_PER_RELATED_ARTIST)
        }
        return collected.distinctBy { it.videoId }
    }

    /**
     * The artists worth looking up: the ones behind the user's recent listening, most recent
     * first, with placeholder artist names dropped.
     */
    private suspend fun seedArtists(): List<String> {
        val seeds = try {
            recommendationEngine?.tasteSeeds(SEED_SONG_COUNT).orEmpty()
        } catch (e: Throwable) {
            Log.e(TAG, "Could not read taste seeds", e)
            emptyList()
        }
        return seeds
            .map { it.artist.trim() }
            .filter { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            .distinct()
            .take(SEED_ARTIST_COUNT)
    }

    /** YouTube Music's own recommendations, flattened out of its shelves. */
    private suspend fun homeFeedTracks(): List<YouTubeSearchResult> = try {
        browser.fetchHomeFeed().flatMap { it.tracks }.distinctBy { it.videoId }
    } catch (e: Throwable) {
        Log.e(TAG, "Could not read the YouTube Music home feed", e)
        emptyList()
    }

    /**
     * Drops anything the library already holds -- matched by video id first, then by title and
     * artist, since a track ripped from a CD has no video id but is still the same song.
     */
    private suspend fun excludeOwned(tracks: List<YouTubeSearchResult>): List<YouTubeSearchResult> =
        tracks.filter { track ->
            try {
                songDao.getSongByYoutubeId(track.videoId) == null &&
                    songDao.findByTitleAndNormalizedArtist(track.title, track.uploader) == null
            } catch (e: Throwable) {
                Log.w(TAG, "Could not check '${track.title}' against the library", e)
                // A failed check must not silently empty the row; an occasional duplicate is the
                // lesser problem.
                true
            }
        }

    private companion object {
        const val TAG = "CloudRecommendation"

        /** How long a fetched list stays usable before the next request refreshes it. */
        const val CACHE_TTL_MS = 30 * 60 * 1000L

        const val DEFAULT_LIMIT = 20

        /** Recently played songs asked of [RecommendationEngine] before de-duplicating artists. */
        const val SEED_SONG_COUNT = 8

        /** Distinct artists actually looked up. Each one costs two catalogue requests. */
        const val SEED_ARTIST_COUNT = 3

        const val TRACKS_PER_SEED = 4
        const val TRACKS_PER_RELATED_ARTIST = 4
    }
}
