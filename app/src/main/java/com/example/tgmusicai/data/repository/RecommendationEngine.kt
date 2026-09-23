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
 * Signals blended: how two songs *sound* (EffNet-Discogs music embeddings), how similar their
 * *lyrics* read (MiniLM embeddings), what they are *about* (themes tagged from those embeddings),
 * what *language* they are in, how often they are actually *played together* (co-occurrence within
 * listening sessions), and plain *metadata* overlap (artist, producer, album). The result is
 * re-ranked so one artist cannot take over the queue, and nudged toward tracks the user has not
 * worn out.
 *
 * Sound and meaning carry equal weight. An audio model hears a furious song about class and a
 * furious song about a breakup as near-identical, and a listener rarely agrees.
 *
 * The design and the reasoning behind every weight are written up in
 * `MD Files/RECOMMENDATIONS_IMPLEMENTATION_SPEC.md`. Two points are worth repeating here because
 * they explain choices that look odd in isolation:
 *
 * - **There is only one user**, so the collaborative filtering that drives commercial
 *   recommenders has nothing to work with. Listening *sessions* stand in for users instead, which
 *   is why co-occurrence is measured per session rather than per play.
 * - **The acoustic signal is the one that carries the feature.** It is the only input that
 *   describes the music rather than its paperwork or its play count, which is why it holds half
 *   the weight and why it is worth a dedicated model.
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

                // Cross-language lyric similarity is not trustworthy: the sentence model is
                // English-centric, so two songs in different languages can score high for
                // reasons that have nothing to do with meaning. Attenuate rather than zero,
                // since a translated or bilingual song is a real case.
                val sameLanguage = sameLanguage(seedProfiles[index], candidateProfile)
                val languagePenalty = if (sameLanguage == false) CROSS_LANGUAGE_ATTENUATION else 1f

                val lyrical = cosineOrZero(seedEmbeddings[index], candidateEmbedding) * languagePenalty
                val thematic = themeOverlap(seedProfiles[index], candidateProfile) * languagePenalty
                val language = if (sameLanguage == true) 1f else 0f
                val behaviour = cooccurrenceScore(seed.id, candidate.id, cooccurrence)
                val metadata = metadataAffinity(seed, candidate)

                val combined = W_ACOUSTIC * acoustic +
                    W_LYRICAL * lyrical +
                    W_THEMATIC * thematic +
                    W_LANGUAGE * language +
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

    /**
     * How much two songs are about the same things, as the share of the seed's themes the
     * candidate also carries.
     *
     * This is the signal that makes "another song arguing the same thing" findable. Raw embedding
     * similarity blends subject with vocabulary, register and imagery all at once, so two songs
     * can read alike and mean nothing in common. A theme is a statement about what a song is
     * about, and matching on it is what groups a song about class and work with another one,
     * however differently the two are written or sung.
     *
     * Scaled by the seed's theme count rather than the union, so a candidate about many things
     * does not dilute a strong match on the few the seed is about.
     */
    private fun themeOverlap(
        seed: com.example.tgmusicai.data.local.entity.AiSongTags?,
        candidate: com.example.tgmusicai.data.local.entity.AiSongTags?,
    ): Float {
        val seedThemes = themesOf(seed)
        if (seedThemes.isEmpty()) return 0f
        val candidateThemes = themesOf(candidate)
        if (candidateThemes.isEmpty()) return 0f

        val shared = seedThemes.count { it in candidateThemes }
        return shared.toFloat() / seedThemes.size
    }

    private fun themesOf(tags: com.example.tgmusicai.data.local.entity.AiSongTags?): Set<String> =
        tags?.lyricThemes
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    /**
     * True when both songs' lyrics are in the same language, false when they are in different
     * ones, and null when either is unknown -- which must not be read as "different", or every
     * un-analyzed song would be penalized for it.
     */
    private fun sameLanguage(
        seed: com.example.tgmusicai.data.local.entity.AiSongTags?,
        candidate: com.example.tgmusicai.data.local.entity.AiSongTags?,
    ): Boolean? {
        val seedLanguage = seed?.lyricsLanguage?.takeIf { it.isNotBlank() } ?: return null
        val candidateLanguage = candidate?.lyricsLanguage?.takeIf { it.isNotBlank() } ?: return null
        return seedLanguage.equals(candidateLanguage, ignoreCase = true)
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

        // What a song sounds like and what it is about are weighted the same: acoustic is 0.38,
        // and the three lyric terms together are 0.38 as well. That is deliberate. Two songs can
        // be acoustically almost identical and be arguing opposite things, and a listener who
        // plays one song about class and work generally wants another -- which nothing about the
        // sound, the genre tag or the artist will ever reveal.
        //
        // Within the lyric side, raw embedding similarity carries the most because it is the most
        // general; themes add the sharp, explicit "about the same thing" match that embeddings
        // blur; language is small because it is a coarse grouping, not a statement of meaning.
        //
        // Behaviour sits below both: with one user the co-occurrence counts are small, and
        // over-weighting them collapses every recommendation onto whatever was played most
        // recently. Metadata is kept deliberately small -- artist matching is what the old
        // implementation did on its own, and leaning on it just returns the same artist over.
        private const val W_ACOUSTIC = 0.38f
        private const val W_LYRICAL = 0.20f
        private const val W_THEMATIC = 0.14f
        private const val W_LANGUAGE = 0.04f
        private const val W_BEHAVIOUR = 0.16f
        private const val W_METADATA = 0.05f
        private const val W_NOVELTY = 0.03f

        /**
         * What cross-language lyric similarity is multiplied by.
         *
         * The sentence-embedding model is English-centric, so comparing lyrics across languages
         * measures something other than meaning. Attenuating rather than zeroing keeps translated
         * and bilingual songs reachable.
         */
        private const val CROSS_LANGUAGE_ATTENUATION = 0.4f

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
