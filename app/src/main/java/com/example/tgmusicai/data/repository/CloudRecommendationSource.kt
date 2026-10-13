package com.example.tgmusicai.data.repository

import android.util.Log
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.scrobble.LastFmSimilarClient
import com.example.tgmusicai.data.scrobble.LastFmTrack
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
    private val lastFm: LastFmSimilarClient? = null,
    /** The user's own Last.fm key when Last.fm recommendations are on, otherwise null. */
    private val lastFmApiKey: suspend () -> String? = { null },
) {

    private val cacheLock = Mutex()
    private var cached: List<YouTubeSearchResult> = emptyList()
    private var cachedAtMs: Long = 0L
    // The Last.fm key the cached list was built with, so turning Last.fm on or off in Settings
    // takes effect on the next request instead of after the cache expires.
    private var cachedWithLastFmKey: String? = null

    // The last radio fetched, keyed by its seed. Autoplay can ask twice for the same seed (queue
    // extension, then the end-of-queue fallback), and a radio is ~50 tracks in one response.
    private var radioSeed: String? = null
    private var radioTracks: List<YouTubeSearchResult> = emptyList()

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
            val currentLastFmKey = if (lastFm == null) null else try { lastFmApiKey() } catch (e: Throwable) { null }
            if (!forceRefresh && cached.isNotEmpty() && age < CACHE_TTL_MS &&
                currentLastFmKey == cachedWithLastFmKey
            ) {
                return@withContext cached.take(limit)
            }

            val fetched = try {
                val seeded = tasteSeededTracks()
                // Last.fm's picks lead when the user has turned them on: they come from what
                // listeners of the seed actually play, a different signal from artist pages.
                val lastFmPicks = lastFmTracks()
                // The generic feed both fills the list out when the seeded lookups came back thin
                // and stands in entirely when they came back empty.
                val combined = (lastFmPicks + seeded + homeFeedTracks()).distinctBy { it.videoId }
                excludeOwned(combined)
            } catch (e: Throwable) {
                Log.e(TAG, "Could not build cloud recommendations", e)
                emptyList()
            }

            if (fetched.isNotEmpty()) {
                cached = fetched
                cachedAtMs = System.currentTimeMillis()
                cachedWithLastFmKey = currentLastFmKey
            }
            // On a failed refresh the previous list is better than nothing, and it is already
            // filtered, so it is served again rather than blanking the row.
            (if (fetched.isNotEmpty()) fetched else cached).take(limit)
        }
    }

    /**
     * YouTube Music's radio seeded from one track ([seedVideoId]), with owned tracks removed: what
     * should follow *this* song, rather than the user's taste in general. Up to [limit] tracks in
     * YouTube's own order, which is built to flow on from the seed. Empty on any failure, so
     * callers fall back to [recommendedTracks].
     */
    suspend fun radioFor(seedVideoId: String, limit: Int = DEFAULT_LIMIT): List<YouTubeSearchResult> =
        withContext(Dispatchers.IO) {
            if (limit <= 0 || seedVideoId.isBlank()) return@withContext emptyList()
            cacheLock.withLock {
                if (radioSeed != seedVideoId || radioTracks.isEmpty()) {
                    val fetched = try {
                        excludeOwned(browser.fetchRadio(seedVideoId))
                    } catch (e: Throwable) {
                        Log.e(TAG, "Could not build the radio for $seedVideoId", e)
                        emptyList()
                    }
                    radioSeed = seedVideoId
                    radioTracks = fetched
                }
                radioTracks.take(limit)
            }
        }

    /**
     * [radioFor] a track known only by name -- a local file with no video id. The track is looked
     * up on YouTube Music first, and only a confident match ([isSameTrack]) is used as the seed:
     * a radio built from the wrong song is worse than none. Empty when there is no such match.
     */
    suspend fun radioForTrack(title: String, artist: String, limit: Int = DEFAULT_LIMIT): List<YouTubeSearchResult> {
        if (title.isBlank()) return emptyList()
        val match = try {
            browser.searchSongs("$title $artist").firstOrNull { isSameTrack(title, artist, it) }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not look up '$title' for a radio", e)
            null
        } ?: return emptyList()
        return radioFor(match.videoId, limit)
    }

    /** Throws away the cached list, so the next request goes back to the network. */
    suspend fun invalidate() {
        cacheLock.withLock {
            cached = emptyList()
            cachedAtMs = 0L
            radioSeed = null
            radioTracks = emptyList()
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
                matchingArtist(artistName, browser.searchArtists(artistName))
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
     * Tracks Last.fm lists as similar to the user's recent listening, each found on YouTube Music
     * by name. Empty unless the user has supplied a Last.fm key and turned this on.
     *
     * Per seed song, `track.getSimilar` first; when Last.fm knows nothing similar to that track,
     * the closest similar artist's top tracks stand in. Seeds are taken in turn so one seed cannot
     * fill the list, and the YouTube lookups are capped because each one is a request.
     */
    private suspend fun lastFmTracks(): List<YouTubeSearchResult> {
        val client = lastFm ?: return emptyList()
        val apiKey = try { lastFmApiKey() } catch (e: Throwable) { null }
        if (apiKey.isNullOrBlank()) return emptyList()

        val seeds = try {
            recommendationEngine?.tasteSeeds(SEED_SONG_COUNT).orEmpty()
        } catch (e: Throwable) {
            Log.e(TAG, "Could not read taste seeds for Last.fm", e)
            emptyList()
        }
            .filter { it.artist.isNotBlank() && !it.artist.equals("<unknown>", ignoreCase = true) }
            .distinctBy { it.artist.lowercase() to it.title.lowercase() }
            .take(LASTFM_SEED_COUNT)
        if (seeds.isEmpty()) return emptyList()

        val found = mutableListOf<YouTubeSearchResult>()
        val perSeedNames = mutableListOf<List<LastFmTrack>>()
        for (seed in seeds) {
            val similar = client.similarTracks(apiKey, seed.artist, seed.title, LASTFM_TRACKS_PER_SEED)
            if (similar.isNotEmpty()) {
                perSeedNames += similar
                continue
            }
            // No track-level data: the nearest similar artist's own top tracks, via the catalogue.
            val artistName = client.similarArtists(apiKey, seed.artist, 1).firstOrNull() ?: continue
            val artistRef = matchingArtist(artistName, browser.searchArtists(artistName)) ?: continue
            found += browser.fetchArtist(artistRef.browseId)?.topTracks.orEmpty().take(TRACKS_PER_RELATED_ARTIST)
        }

        for (named in roundRobin(perSeedNames).take(LASTFM_YOUTUBE_LOOKUPS)) {
            val match = browser.searchSongs("${named.title} ${named.artist}")
                .let { results ->
                    results.firstOrNull { it.uploader.equals(named.artist, ignoreCase = true) }
                        ?: results.firstOrNull()
                }
                ?: continue
            found += match
        }
        return found.distinctBy { it.videoId }
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

    internal companion object {
        /**
         * The search result that really is [name], or null. Taking YouTube's top result whatever
         * it was turned a library artist called "Delta" into Delta Goodrem and filled the
         * recommendations with an artist the user never played (T038).
         */
        fun matchingArtist(
            name: String,
            results: List<com.example.tgmusicai.data.youtube.YouTubeArtistRef>,
        ): com.example.tgmusicai.data.youtube.YouTubeArtistRef? {
            val wanted = normaliseArtist(name)
            if (wanted.isBlank()) return null
            return results.firstOrNull { normaliseArtist(it.name) == wanted }
        }

        private fun normaliseArtist(name: String): String =
            name.lowercase()
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .replace(Regex("^the "), "")
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun normalise(text: String): String =
            text.lowercase()
                // "(Remastered 2011)", "[Official Audio]", "feat. X" say nothing about which song it is.
                .replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
                .replace(Regex("""\b(feat|ft)\.?\s.*$"""), " ")
                .replace(Regex("""['’]"""), "")
                .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
                .trim()

        /**
         * Whether [candidate] is the song [title] by [artist]: titles equal once bracketed
         * annotations and punctuation are dropped, and the artists overlap (one contains the
         * other, which covers "A & B" against "A"). A blank or placeholder artist matches on
         * title alone only when the title is long enough to be distinctive.
         */
        internal fun isSameTrack(title: String, artist: String, candidate: YouTubeSearchResult): Boolean {
            val wantedTitle = normalise(title)
            if (wantedTitle.isBlank() || wantedTitle != normalise(candidate.title)) return false
            val wantedArtist = normalise(artist)
            if (wantedArtist.isBlank() || wantedArtist == "unknown" || wantedArtist == "unknown artist") {
                return wantedTitle.length >= MIN_TITLE_ONLY_MATCH_LENGTH
            }
            val candidateArtist = normalise(candidate.uploader)
            return candidateArtist.isNotBlank() &&
                (candidateArtist.contains(wantedArtist) || wantedArtist.contains(candidateArtist))
        }

        const val MIN_TITLE_ONLY_MATCH_LENGTH = 12

        /** Takes each list's next item in turn, so every seed's best match comes before any second-best. */
        internal fun <T> roundRobin(lists: List<List<T>>): List<T> {
            val depth = lists.maxOfOrNull { it.size } ?: 0
            return (0 until depth).flatMap { rank -> lists.mapNotNull { it.getOrNull(rank) } }
        }

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

        /** Seed songs asked of Last.fm. Each costs one Last.fm request (two on the artist fallback). */
        const val LASTFM_SEED_COUNT = 3
        const val LASTFM_TRACKS_PER_SEED = 10

        /** Last.fm tracks looked up on YouTube Music per refresh; each is one search request. */
        const val LASTFM_YOUTUBE_LOOKUPS = 8
    }
}
