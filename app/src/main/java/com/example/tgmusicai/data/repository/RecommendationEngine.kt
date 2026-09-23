package com.example.tgmusicai.data.repository

import android.util.Log
import com.example.tgmusicai.ai.AudioProfileCodec
import com.example.tgmusicai.ai.LyricsEmbeddingEngine
import com.example.tgmusicai.data.local.dao.AiSongTagsDao
import com.example.tgmusicai.data.local.dao.HistoryEntry
import com.example.tgmusicai.data.local.dao.ListeningHistoryDao
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.dao.SongStatsDao
import com.example.tgmusicai.data.local.entity.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Picks songs related to a seed, or to the library as a whole, entirely on the device.
 *
 * Four signals are blended: how similar two songs *sound* (YAMNet class-score profiles), how
 * similar their *lyrics* are (MiniLM embeddings), how often they are actually *played together*
 * (co-occurrence within listening sessions), and plain *metadata* overlap (artist, producer,
 * album). The result is re-ranked so one artist cannot take over the queue, and nudged toward
 * tracks the user has not worn out.
 *
 * The design and the reasoning behind every weight are written up in
 * `MD Files/RECOMMENDATIONS_IMPLEMENTATION_SPEC.md`. Two points are worth repeating here because
 * they explain choices that look odd in isolation:
 *
 * - **There is only one user**, so the collaborative filtering that drives commercial
 *   recommenders has nothing to work with. Listening *sessions* stand in for users instead, which
 *   is why co-occurrence is measured per session rather than per play.
 * - **No new model ships for this.** The acoustic profile is a by-product of the tagging pass that
 *   already runs, so the strongest signal here costs one nullable column and no extra inference.
 *
 * Every signal degrades to zero independently. A song with no analysis, no lyrics and no history
 * still gets scored and still comes back -- the engine never returns an empty list for a non-empty
 * library, and never throws at its callers.
 */
class RecommendationEngine(
    private val songDao: SongDao,
    private val songStatsDao: SongStatsDao,
    private val aiSongTagsDao: AiSongTagsDao,
    private val listeningHistoryDao: ListeningHistoryDao? = null,
) {

    /** A candidate and the score it earned, kept together so the diversity pass can re-order them. */
    internal data class Scored(val song: Song, val score: Float)

    private val behaviourLock = Mutex()
    private var cachedCooccurrence: Map<Long, Map<Long, Int>>? = null
    private var cachedSessionCounts: Map<Long, Int>? = null

    /**
     * Songs related to [seedSong], best first, capped at [limit] and already diversity-re-ranked.
     * The seed itself is never included. Set [downloadedOnly] to restrict candidates to on-device
     * tracks, matching the app-wide "Downloaded only" preference.
     */
    suspend fun recommendFor(
        seedSong: Song,
        limit: Int = 20,
        downloadedOnly: Boolean = false,
    ): List<Song> = withContext(Dispatchers.IO) {
        val candidates = loadCandidates(downloadedOnly).filter { it.id != seedSong.id }
        if (candidates.isEmpty()) return@withContext emptyList()

        val scored = scoreAgainstSeeds(listOf(seedSong), candidates)
        diversify(scored, limit)
    }

    /**
     * Recommendations with no particular seed, for a "Made for you" style row.
     *
     * Seeds are the user's most recently played songs rather than their most played: recent
     * listening is a better guess at what they want right now, and seeding from all-time favourites
     * would return the same row forever.
     */
    suspend fun recommendForLibrary(
        limit: Int = 20,
        downloadedOnly: Boolean = false,
    ): List<Song> = withContext(Dispatchers.IO) {
        val candidates = loadCandidates(downloadedOnly)
        if (candidates.isEmpty()) return@withContext emptyList()

        val seeds = recentSeeds(candidates)
        if (seeds.isEmpty()) {
            // Nothing has been played yet, so there is no taste to extrapolate from. Least-played
            // first is the honest answer, and it is what the Unplayed playlist would show.
            return@withContext candidates
                .sortedWith(compareByDescending<Song> { noveltyOf(it, emptyMap()) }.thenBy { it.id })
                .take(limit)
        }

        val seedIds = seeds.map { it.id }.toSet()
        val scored = scoreAgainstSeeds(seeds, candidates.filter { it.id !in seedIds })
        diversify(scored, limit)
    }

    /**
     * Drops the cached co-occurrence map so the next request rebuilds it. Call after a play is
     * recorded; the map is derived from listening history and goes stale as soon as that grows.
     */
    suspend fun invalidateBehaviourCache() {
        behaviourLock.withLock {
            cachedCooccurrence = null
            cachedSessionCounts = null
        }
    }

    // ---- scoring ----------------------------------------------------------------------------

    private suspend fun scoreAgainstSeeds(seeds: List<Song>, candidates: List<Song>): List<Scored> {
        val profiles = loadProfiles()
        val playCounts = loadPlayCounts()
        val lastPlayed = loadLastPlayedAt()
        val cooccurrence = loadCooccurrence()
        val nowMs = System.currentTimeMillis()

        val seedProfiles = seeds.map { profiles[it.id] }
        val seedEmbeddings = seeds.map { embeddingOf(profiles[it.id]) }

        return candidates.map { candidate ->
            val candidateProfile = profiles[candidate.id]
            val candidateEmbedding = embeddingOf(candidateProfile)

            var best = 0f
            seeds.forEachIndexed { index, seed ->
                val acoustic = AudioProfileCodec.cosineSimilarity(
                    decodeProfile(seedProfiles[index]),
                    decodeProfile(candidateProfile),
                ).coerceAtLeast(0f)

                val lyrical = cosineOrZero(seedEmbeddings[index], candidateEmbedding)
                val behaviour = cooccurrenceScore(seed.id, candidate.id, cooccurrence)
                val metadata = metadataAffinity(seed, candidate)

                val combined = W_ACOUSTIC * acoustic +
                    W_LYRICAL * lyrical +
                    W_BEHAVIOUR * behaviour +
                    W_METADATA * metadata
                if (combined > best) best = combined
            }

            var score = best + W_NOVELTY * noveltyOf(candidate, playCounts)
            val playedAt = lastPlayed[candidate.id]
            if (playedAt != null && nowMs - playedAt < RECENT_PLAY_WINDOW_MS) {
                score -= PENALTY_RECENT
            }

            Scored(candidate, score)
        }
    }

    /**
     * Highest metadata affinity that applies, or 0. Album ranks below producer because an album
     * match is nearly always an artist match too, while sharing a producer across different
     * artists is the rarer and more telling signal.
     */
    private fun metadataAffinity(seed: Song, candidate: Song): Float = when {
        candidate.artist.equals(seed.artist, ignoreCase = true) -> 1.0f
        !seed.producer.isNullOrBlank() && candidate.producer.equals(seed.producer, ignoreCase = true) -> 0.8f
        candidate.album.equals(seed.album, ignoreCase = true) && seed.album.isNotBlank() -> 0.6f
        else -> 0f
    }

    /** Favours songs the user has not worn out, saturating so a single unplayed track cannot dominate. */
    private fun noveltyOf(song: Song, playCounts: Map<Long, Int>): Float {
        val plays = playCounts[song.id] ?: 0
        return 1f - (plays.toFloat() / NOVELTY_SATURATION).coerceIn(0f, 1f)
    }

    /**
     * How strongly [candidateId] is associated with [seedId], normalized by how many sessions the
     * seed appears in. Without that normalization this would rank by raw popularity -- a song the
     * user plays constantly would co-occur with everything and win every seed.
     */
    private fun cooccurrenceScore(
        seedId: Long,
        candidateId: Long,
        cooccurrence: Map<Long, Map<Long, Int>>,
    ): Float {
        val shared = cooccurrence[seedId]?.get(candidateId) ?: return 0f
        val seedSessions = cachedSessionCounts?.get(seedId) ?: return 0f
        if (seedSessions <= 0) return 0f
        return (shared.toFloat() / seedSessions).coerceIn(0f, 1f)
    }

    // ---- diversity --------------------------------------------------------------------------

    // diversify and sessionsFrom live in the companion: they are pure functions over their
    // arguments, which lets them be unit-tested without standing up four fake DAOs.

    // ---- data loading -----------------------------------------------------------------------

    private suspend fun loadCandidates(downloadedOnly: Boolean): List<Song> = try {
        val all = songDao.getAllSongsList()
        if (downloadedOnly) all.filter { it.isDownloaded } else all
    } catch (e: Throwable) {
        Log.e(TAG, "Could not load candidate songs", e)
        emptyList()
    }

    /**
     * The most recently played songs still present in [candidates], used as seeds for the
     * library-wide row.
     */
    private suspend fun recentSeeds(candidates: List<Song>): List<Song> = try {
        val byId = candidates.associateBy { it.id }
        songStatsDao.getRecentlyPlayedStatsSync(SEED_COUNT)
            .mapNotNull { byId[it.songId] }
    } catch (e: Throwable) {
        Log.e(TAG, "Could not load recent seeds", e)
        emptyList()
    }

    /**
     * Every cached analysis row, keyed by song id.
     *
     * Loaded in one query on purpose. The pre-existing `rankBySimilarLyrics` fetched one row per
     * candidate, which is a database round-trip per song in the library on every request.
     */
    private suspend fun loadProfiles(): Map<Long, com.example.tgmusicai.data.local.entity.AiSongTags> = try {
        aiSongTagsDao.getAll().associateBy { it.songId }
    } catch (e: Throwable) {
        Log.e(TAG, "Could not load AI profiles; acoustic and lyrical signals will be skipped", e)
        emptyMap()
    }

    private suspend fun loadPlayCounts(): Map<Long, Int> = try {
        songStatsDao.getMostPlayedStatsSync(Int.MAX_VALUE).associate { it.songId to it.playCount }
    } catch (e: Throwable) {
        Log.e(TAG, "Could not load play counts; novelty will be treated as uniform", e)
        emptyMap()
    }

    private suspend fun loadLastPlayedAt(): Map<Long, Long> = try {
        songStatsDao.getMostPlayedStatsSync(Int.MAX_VALUE)
            .mapNotNull { stats -> stats.lastPlayedAt?.let { stats.songId to it } }
            .toMap()
    } catch (e: Throwable) {
        Log.e(TAG, "Could not load last-played times; recent tracks will not be penalized", e)
        emptyMap()
    }

    private fun decodeProfile(tags: com.example.tgmusicai.data.local.entity.AiSongTags?): FloatArray? =
        AudioProfileCodec.decode(tags?.audioProfile)

    private fun embeddingOf(tags: com.example.tgmusicai.data.local.entity.AiSongTags?): FloatArray? {
        val csv = tags?.lyricsEmbedding ?: return null
        return try {
            val parts = csv.split(',')
            if (parts.size != LyricsEmbeddingEngine.EMBEDDING_DIM) return null
            FloatArray(parts.size) { parts[it].trim().toFloat() }
        } catch (e: Throwable) {
            null
        }
    }

    private fun cosineOrZero(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return 0f
        return LyricsEmbeddingEngine.cosineSimilarity(a, b).coerceAtLeast(0f)
    }

    // ---- behaviour --------------------------------------------------------------------------

    private suspend fun loadCooccurrence(): Map<Long, Map<Long, Int>> {
        cachedCooccurrence?.let { return it }
        return behaviourLock.withLock {
            cachedCooccurrence ?: buildCooccurrenceMap().also { cachedCooccurrence = it }
        }
    }

    /**
     * Turns the listening-time log into "these two songs get played in the same sitting" counts.
     *
     * `listening_history` holds 10-second chunks, not plays, so consecutive runs of the same song
     * are collapsed first -- without that a three-minute song would look like eighteen plays of
     * itself. A gap longer than [SESSION_GAP_MS] starts a new session, and only the last
     * [COOCCURRENCE_WINDOW_DAYS] of history are considered so the map stays bounded and old taste
     * does not outvote current taste.
     *
     * Also populates the per-song session counts that [cooccurrenceScore] normalizes by.
     */
    internal suspend fun buildCooccurrenceMap(): Map<Long, Map<Long, Int>> {
        val dao = listeningHistoryDao ?: return emptyMap()
        val sinceMs = System.currentTimeMillis() - COOCCURRENCE_WINDOW_DAYS * MILLIS_PER_DAY

        val entries = try {
            dao.getEntriesSince(sinceMs)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not read listening history; the behavioural signal will be skipped", e)
            return emptyMap()
        }
        if (entries.isEmpty()) {
            cachedSessionCounts = emptyMap()
            return emptyMap()
        }

        val sessions = sessionsFrom(entries)

        val cooccurrence = mutableMapOf<Long, MutableMap<Long, Int>>()
        val sessionCounts = mutableMapOf<Long, Int>()
        for (session in sessions) {
            for (songId in session) {
                sessionCounts[songId] = (sessionCounts[songId] ?: 0) + 1
            }
            if (session.size < 2) continue
            for (a in session) {
                val row = cooccurrence.getOrPut(a) { mutableMapOf() }
                for (b in session) {
                    if (a == b) continue
                    row[b] = (row[b] ?: 0) + 1
                }
            }
        }

        cachedSessionCounts = sessionCounts
        return cooccurrence
    }

    companion object {
        private const val TAG = "RecommendationEngine"

        /**
         * Sorts by score and then spreads the result out so no artist takes over.
         *
         * Artist match, acoustic similarity and co-occurrence all correlate with artist, so raw
         * score order tends to produce long same-artist runs. Over-quota tracks are pushed to the
         * back rather than dropped, which keeps a single-artist library from returning a
         * near-empty queue.
         */
        internal fun diversify(scored: List<Scored>, limit: Int): List<Song> {
            val ordered = scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.song.id })

            val picked = mutableListOf<Song>()
            val deferred = mutableListOf<Song>()
            val perArtist = mutableMapOf<String, Int>()

            for (entry in ordered) {
                if (picked.size >= limit) break
                val artistKey = entry.song.artist.lowercase()
                val used = perArtist[artistKey] ?: 0
                val trailingSameArtist = picked.takeLast(MAX_CONSECUTIVE_SAME_ARTIST)
                    .count { it.artist.equals(entry.song.artist, ignoreCase = true) }

                if (used >= MAX_PER_ARTIST || trailingSameArtist >= MAX_CONSECUTIVE_SAME_ARTIST) {
                    deferred.add(entry.song)
                    continue
                }
                picked.add(entry.song)
                perArtist[artistKey] = used + 1
            }

            if (picked.size < limit) {
                picked.addAll(deferred.take(limit - picked.size))
            }
            return picked
        }

        /**
         * Splits [entries] into listening sessions, each the distinct songs played in one sitting.
         *
         * Two things happen here, and both are needed. A gap longer than [SESSION_GAP_MS] ends a
         * session. And consecutive chunks from the same song collapse to one entry, because
         * `listening_history` logs a row every ten seconds rather than one per play -- without the
         * collapse a three-minute song would look like eighteen plays of itself and would
         * co-occur with nothing but itself.
         */
        internal fun sessionsFrom(entries: List<HistoryEntry>): List<Set<Long>> {
            if (entries.isEmpty()) return emptyList()

            val sessions = mutableListOf<Set<Long>>()
            var current = linkedSetOf<Long>()
            var previousTimestamp = entries.first().timestampMs
            var previousSongId = -1L

            for (entry in entries) {
                if (entry.timestampMs - previousTimestamp > SESSION_GAP_MS) {
                    if (current.isNotEmpty()) sessions.add(current)
                    current = linkedSetOf()
                    previousSongId = -1L
                }
                if (entry.songId != previousSongId) {
                    current.add(entry.songId)
                    previousSongId = entry.songId
                }
                previousTimestamp = entry.timestampMs
            }
            if (current.isNotEmpty()) sessions.add(current)
            return sessions
        }

        // Acoustic outweighs lyrical because every analyzed local song has a profile, while only a
        // minority have lyrics to embed. Behaviour sits below acoustic on purpose: with one user
        // the co-occurrence counts are small, and over-weighting them collapses every
        // recommendation onto whatever was played most recently.
        private const val W_ACOUSTIC = 0.35f
        private const val W_LYRICAL = 0.20f
        private const val W_BEHAVIOUR = 0.25f
        private const val W_METADATA = 0.15f
        private const val W_NOVELTY = 0.05f

        /** Subtracted from anything played in the last [RECENT_PLAY_WINDOW_MS], to avoid immediate repeats. */
        private const val PENALTY_RECENT = 0.30f
        private const val RECENT_PLAY_WINDOW_MS = 6L * 60L * 60L * 1000L

        /** Play count at which a song stops counting as novel at all. */
        private const val NOVELTY_SATURATION = 20f

        /** A silence longer than this ends a listening session. */
        private const val SESSION_GAP_MS = 30L * 60L * 1000L

        private const val COOCCURRENCE_WINDOW_DAYS = 180L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /** How many recently played songs seed the library-wide row. */
        private const val SEED_COUNT = 5

        private const val MAX_PER_ARTIST = 3
        private const val MAX_CONSECUTIVE_SAME_ARTIST = 2
    }
}
