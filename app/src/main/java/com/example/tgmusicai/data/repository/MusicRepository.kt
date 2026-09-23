package com.example.tgmusicai.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tgmusicai.data.local.AiMetadataCleaner
import com.example.tgmusicai.data.local.BackupManager
import com.example.tgmusicai.data.local.dao.AlarmDao
import com.example.tgmusicai.data.local.dao.DailyListenTotal
import com.example.tgmusicai.data.local.dao.ListeningHistoryDao
import com.example.tgmusicai.data.local.dao.PlaylistDao
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.dao.SongStatsDao
import com.example.tgmusicai.data.local.entity.Alarm
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistSongCrossRef
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.local.entity.SongStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream

/**
 * Combined model for displaying song statistics alongside full Song details.
 */
data class SongWithStats(
    val song: Song,
    val stats: SongStats
)

/** An artist's total play count, summed across every one of their songs. */
data class ArtistPlayStats(
    val artist: String,
    val totalPlayCount: Int
)

/** Minutes listened on one day, for the weekly listening trend chart. */
data class DailyListeningData(val dayName: String, val minutes: Int)

/** A producer's play count and track count, aggregated from [Song.producer] credits. */
data class ProducerStat(
    val producer: String,
    val trackCount: Int,
    val totalPlayCount: Int
)

/**
 * Repository layer acting as the single source of truth for music, alarms, backups, and smart playlists.
 */
class MusicRepository(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val songStatsDao: SongStatsDao,
    private val alarmDao: AlarmDao,
    private val listeningHistoryDao: ListeningHistoryDao? = null,
    private val database: com.example.tgmusicai.data.local.AppDatabase? = null
) {
    private val TAG = "MusicRepository"

    // Playlist.name has no DB-level unique constraint, so the check-then-insert pattern below
    // (used by getOrCreateLikedMusicPlaylistId and ensureSmartPlaylistsExist) is racy if two
    // callers hit it concurrently (e.g. a ViewModel collecting isSongLiked() at the same time
    // MainActivity calls ensureSmartPlaylistsExist() on launch) - both could see "not found" and
    // each insert their own "Liked Music" row. Serializing with a Mutex closes that window.
    private val smartPlaylistMutex = Mutex()

    /** Every song in the library, live-updating as the underlying table changes. */
    val allSongs: Flow<List<Song>> = songDao.getAllSongs()
    /** Songs with a local audio file already downloaded (playable offline). */
    val downloadedSongs: Flow<List<Song>> = songDao.getDownloadedSongs()
    /** Songs that exist only as a cloud/YouTube reference and have not been downloaded yet. */
    val notDownloadedSongs: Flow<List<Song>> = songDao.getNotDownloadedSongs()
    /** Songs the user has pinned for quick access. */
    val pinnedSongs: Flow<List<Song>> = songDao.getPinnedSongs()

    /**
     * Every playlist, real and smart. Deduplicated by a kind-aware key (smart playlists by
     * name, normal playlists by ID) rather than by row ID alone, since the racy
     * check-then-insert pattern this class used to have around smart-playlist creation could
     * otherwise surface duplicate rows to the UI.
     */
    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAllPlaylists().map { list ->
        list.distinctBy { if (it.isSmart) "smart_${it.name}" else "playlist_${it.playlistId}" }
    }
    /** Playlists the user has pinned, deduplicated by playlist ID. */
    val pinnedPlaylists: Flow<List<Playlist>> = playlistDao.getPinnedPlaylists().map { list ->
        list.distinctBy { it.playlistId }
    }
    /** Only the auto-generated smart playlists (Liked Music, Top 50, etc.), deduplicated by name. */
    val smartPlaylists: Flow<List<Playlist>> = playlistDao.getSmartPlaylists().map { list ->
        list.filter { it.isSmart }.distinctBy { it.name }
    }

    /** Every alarm the user has configured. */
    val allAlarms: Flow<List<Alarm>> = alarmDao.getAllAlarms()

    /** Handles export/import of the full library (songs, playlists, stats, alarms) to/from a backup file. */
    val backupManager = BackupManager(songDao, playlistDao, songStatsDao, alarmDao, database)

    /**
     * Raw cross-ref-backed lookup of a playlist and its songs by ID. Only correct for normal
     * (non-smart) playlists -- use [playlistWithSongsFlow] instead when the playlist might be a
     * smart playlist, since those aren't backed by cross-ref rows.
     */
    fun getPlaylistWithSongs(playlistId: Long): Flow<PlaylistWithSongs?> {
        return playlistDao.getPlaylistWithSongs(playlistId)
    }

    /**
     * Resolves [playlist]'s song list the correct way for its kind: a real cross-ref join for a
     * normal (or Liked Music) playlist, or the matching computed query for a smart playlist whose
     * contents aren't backed by cross-ref rows at all (Top 50 Most Played, Recently Added,
     * Unplayed, Downloads, Cloud Nine). Centralized here so every screen that needs "the songs in
     * this playlist" (playlist list/detail screens, storage stats, etc.) shares one definition
     * instead of re-implementing this branching and risking it drifting out of sync.
     */
    fun playlistWithSongsFlow(playlist: Playlist): Flow<PlaylistWithSongs> {
        if (!playlist.isSmart) {
            return getPlaylistWithSongs(playlist.playlistId).map {
                it ?: PlaylistWithSongs(playlist = playlist, songs = emptyList())
            }
        }
        return when (playlist.name) {
            TOP_50_MOST_PLAYED_NAME -> getMostPlayedSongsWithStats(50).map { stats ->
                PlaylistWithSongs(playlist = playlist, songs = stats.map { it.song })
            }
            RECENTLY_ADDED_NAME -> getRecentlyAddedSongs(50).map { songs ->
                PlaylistWithSongs(playlist = playlist, songs = songs)
            }
            UNPLAYED_NAME -> getUnplayedSongs().map { songs ->
                PlaylistWithSongs(playlist = playlist, songs = songs)
            }
            DOWNLOADS_NAME -> downloadedSongs.map { songs ->
                PlaylistWithSongs(playlist = playlist, songs = songs)
            }
            CLOUD_NINE_NAME -> notDownloadedSongs.map { songs ->
                PlaylistWithSongs(playlist = playlist, songs = songs)
            }
            else -> getPlaylistWithSongs(playlist.playlistId).map {
                it ?: PlaylistWithSongs(playlist = playlist, songs = emptyList())
            }
        }
    }

    /**
     * Every playlist (real and smart) paired with its resolved song list. See
     * [playlistWithSongsFlow] for how each kind of playlist's songs are resolved.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val allPlaylistsWithSongs: Flow<List<PlaylistWithSongs>> = allPlaylists
        .flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(list.map { playlistWithSongsFlow(it) }) { array -> array.toList() }
            }
        }

    /**
     * Retrieves recently played songs ("Listen Again") ordered by lastPlayedAt timestamp.
     */
    fun getRecentlyPlayedSongs(limit: Int = 15): Flow<List<SongWithStats>> {
        return combine(
            songStatsDao.getRecentlyPlayedStats(limit),
            songDao.getAllSongs()
        ) { statsList, songsList ->
            val songMap = songsList.associateBy { it.id }
            statsList.mapNotNull { stats ->
                songMap[stats.songId]?.let { song ->
                    SongWithStats(song = song, stats = stats)
                }
            }
        }
    }

    /**
     * Retrieves top played songs combined with their stats.
     */
    fun getMostPlayedSongsWithStats(limit: Int = 50): Flow<List<SongWithStats>> {
        return combine(
            songStatsDao.getMostPlayedStats(limit),
            songDao.getAllSongs()
        ) { statsList, songsList ->
            val songMap = songsList.associateBy { it.id }
            statsList.mapNotNull { stats ->
                songMap[stats.songId]?.let { song ->
                    SongWithStats(song = song, stats = stats)
                }
            }
        }
    }

    /** Total plays across the whole library, not just whatever top-N list is currently loaded. */
    fun getTotalPlayCount(): Flow<Int> = songStatsDao.getTotalPlayCount()

    /** Total cumulative listening time across the whole library, in milliseconds. */
    fun getTotalListenTimeMs(): Flow<Long> = songStatsDao.getTotalListenTimeMs()

    /**
     * Minutes listened per day for the last 7 calendar days (local timezone), oldest first,
     * zero-filled for any day with no listening at all -- a raw `GROUP BY day` query alone would
     * just omit those days rather than show them as zero.
     */
    suspend fun getWeeklyListeningTrend(): List<DailyListeningData> {
        val dao = listeningHistoryDao ?: return emptyList()
        val sinceMs = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val totals = dao.getDailyTotalsSince(sinceMs).associateBy(DailyListenTotal::day)
        val dayFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val labelFormat = java.text.SimpleDateFormat("EEE", java.util.Locale.US)
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -6)
        return (0..6).map {
            val key = dayFormat.format(calendar.time)
            val label = labelFormat.format(calendar.time)
            val minutes = ((totals[key]?.totalMs ?: 0L) / 60_000L).toInt()
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
            DailyListeningData(dayName = label, minutes = minutes)
        }
    }

    /**
     * Aggregates every played song's play count by producer credit (from [Song.producer],
     * extracted by `AiMetadataCleaner`) and returns the top [limit]. Songs with no producer
     * credit are excluded rather than lumped into a meaningless "Unknown" bucket.
     */
    fun getTopProducers(limit: Int = 5): Flow<List<ProducerStat>> {
        return combine(
            songStatsDao.getMostPlayedStats(Int.MAX_VALUE),
            songDao.getAllSongs()
        ) { statsList, songsList ->
            val playCountBySong = statsList.associate { it.songId to it.playCount }
            songsList
                .mapNotNull { song -> song.producer?.takeIf { it.isNotBlank() }?.let { it to song } }
                .groupBy({ it.first }, { it.second })
                .map { (producer, songs) ->
                    ProducerStat(
                        producer = producer,
                        trackCount = songs.size,
                        totalPlayCount = songs.sumOf { playCountBySong[it.id] ?: 0 }
                    )
                }
                .sortedByDescending { it.totalPlayCount }
                .take(limit)
        }
    }

    /**
     * Aggregates every played song's play count by artist and returns the top [limit] artists.
     * There's no dedicated artist-stats table, so this combines the whole song_stats table with
     * the song list in memory -- fine at personal-library scale (hundreds, not millions, of rows).
     */
    fun getTopArtistsByPlayCount(limit: Int = 5): Flow<List<ArtistPlayStats>> {
        return combine(
            songStatsDao.getMostPlayedStats(Int.MAX_VALUE),
            songDao.getAllSongs()
        ) { statsList, songsList ->
            val songMap = songsList.associateBy { it.id }
            statsList
                .mapNotNull { stats -> songMap[stats.songId]?.let { it.artist to stats.playCount } }
                .groupBy({ it.first }, { it.second })
                .map { (artist, counts) -> ArtistPlayStats(artist = artist, totalPlayCount = counts.sum()) }
                .sortedByDescending { it.totalPlayCount }
                .take(limit)
        }
    }

    /**
     * Retrieves unplayed songs in the library.
     */
    /**
     * "Start Radio": builds a shuffled queue of songs related to [seedSong] -- same producer
     * first, then same artist, then everything else -- capped at [limit]. Within each tier,
     * songs are grouped into play-count bands (least-played first) and lightly shuffled inside
     * each band, so the radio leans toward tracks the user hasn't already worn out without being
     * perfectly predictable. There's no genre field in the schema, so this uses producer/artist
     * overlap only, not the full producer/artist/genre similarity the original spec described.
     */
    suspend fun buildRadioQueue(seedSong: Song, limit: Int = 20): List<Song> {
        val allSongs = songDao.getAllSongsList().filter { it.id != seedSong.id }
        val sameProducer = if (!seedSong.producer.isNullOrBlank()) {
            allSongs.filter { it.producer.equals(seedSong.producer, ignoreCase = true) }
        } else emptyList()
        val sameArtist = allSongs.filter { it.artist.equals(seedSong.artist, ignoreCase = true) }
        val remaining = allSongs - sameProducer.toSet() - sameArtist.toSet()

        val playCountById = songStatsDao.getMostPlayedStatsSync(Int.MAX_VALUE).associate { it.songId to it.playCount }
        fun List<Song>.underplayedFirst(): List<Song> =
            sortedBy { playCountById[it.id] ?: 0 }
                .chunked(5)
                .flatMap { it.shuffled() }

        val pool = sameProducer.underplayedFirst() + sameArtist.underplayedFirst() + remaining.shuffled()
        return (listOf(seedSong) + pool).distinctBy { it.id }.take(limit)
    }

    /** Songs with a play count of zero, backing the "Unplayed" smart playlist. */
    fun getUnplayedSongs(): Flow<List<Song>> {
        return songDao.getUnplayedSongs()
    }

    /**
     * Retrieves recently added songs in the library.
     */
    fun getRecentlyAddedSongs(limit: Int = 50): Flow<List<Song>> {
        return songDao.getRecentlyAddedSongs(limit)
    }

    /** Creates a new playlist and returns its generated row ID. */
    suspend fun createPlaylist(name: String, description: String? = null, isSmart: Boolean = false): Long {
        val playlist = Playlist(name = name, description = description, isSmart = isSmart)
        return playlistDao.insertPlaylist(playlist)
    }

    /** Updates a playlist's description in place, leaving its other fields untouched. */
    suspend fun updatePlaylistDescription(playlistId: Long, description: String?) {
        playlistDao.updatePlaylistDescription(playlistId, description)
    }

    suspend fun deletePlaylist(playlist: Playlist) {
        // Guarded by isSmart as well as name: a user-created playlist that happens to be named
        // e.g. "Liked Music" (nothing stops them from typing that name) must remain deletable,
        // since it is not the real system playlist - only the actual smart/auto-generated row is
        // protected. Covers every smart playlist, not just Liked Music -- deleting e.g. "Unplayed"
        // used to silently succeed even though it's meant to be just as immutable.
        if (isProtectedSmartPlaylist(playlist)) {
            Log.w(TAG, "Attempted to delete immutable smart playlist '${playlist.name}'. Operation blocked.")
            return
        }
        playlistDao.deletePlaylist(playlist)
    }

    /** Adds a song to a normal (non-smart) playlist by inserting a cross-ref row. */
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        val crossRef = PlaylistSongCrossRef(playlistId = playlistId, songId = songId)
        playlistDao.insertPlaylistSongCrossRef(crossRef)
    }

    /**
     * Removes a song from a normal (non-smart) playlist. No-op for computed smart playlists
     * (see [COMPUTED_SMART_PLAYLIST_NAMES]) since there's no cross-ref row to remove there.
     */
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        playlistDao.removeSongFromPlaylist(playlistId = playlistId, songId = songId)
    }

    /**
     * Returns the row ID of the "Liked Music" smart playlist, creating it first if it doesn't
     * exist yet. Guarded by [smartPlaylistMutex] so two concurrent callers (e.g. a ViewModel
     * collecting [isSongLiked] at the same time app startup calls [ensureSmartPlaylistsExist])
     * can't both see "not found" and each insert a duplicate "Liked Music" row.
     */
    suspend fun getOrCreateLikedMusicPlaylistId(): Long = smartPlaylistMutex.withLock {
        val existing = playlistDao.getPlaylistByName(LIKED_MUSIC_NAME)
        if (existing != null) {
            return@withLock existing.playlistId
        }
        playlistDao.insertPlaylist(
            Playlist(
                name = LIKED_MUSIC_NAME,
                description = "Songs you have liked",
                isSmart = true
            )
        )
    }

    /**
     * Ensures [song] has a real row in the `songs` table, returning its ID. Songs played directly
     * from a live stream (e.g. [com.example.tgmusicai.ui.viewmodel.YouTubeViewModel.playTrack])
     * are transient, in-memory objects with `id == 0L` that are never inserted into Room -- liking
     * or otherwise referencing such a song by ID would silently do nothing (or attach to a
     * nonexistent row). Callers that need to persist a like, playlist membership, etc. for the
     * currently-playing song should route through this first. Deduplicates by `youtubeId` or
     * `mediaUri` so replaying the same stream doesn't create duplicate rows.
     */
    suspend fun ensurePersisted(song: Song): Long {
        if (song.id != 0L) return song.id

        val existing = song.youtubeId?.takeIf { it.isNotBlank() }?.let { songDao.getSongByYoutubeId(it) }
            ?: songDao.getSongByUri(song.mediaUri)
        if (existing != null) return existing.id

        return songDao.insertSong(song.copy(id = 0L))
    }

    /**
     * Flips whether [songId] is in Liked Music and returns the new state (true = now liked,
     * false = now unliked).
     */
    suspend fun toggleLikeSong(songId: Long): Boolean {
        val likedPlaylistId = getOrCreateLikedMusicPlaylistId()
        val isLiked = playlistDao.isSongInPlaylistSync(likedPlaylistId, songId)
        if (isLiked) {
            playlistDao.removeSongFromPlaylist(likedPlaylistId, songId)
            return false
        } else {
            playlistDao.insertPlaylistSongCrossRef(
                PlaylistSongCrossRef(playlistId = likedPlaylistId, songId = songId)
            )
            return true
        }
    }

    /** Adds every song in [songIds] to Liked Music. Already-liked songs are left as-is. */
    suspend fun likeSongs(songIds: List<Long>) {
        val likedPlaylistId = getOrCreateLikedMusicPlaylistId()
        for (songId in songIds) {
            if (!playlistDao.isSongInPlaylistSync(likedPlaylistId, songId)) {
                playlistDao.insertPlaylistSongCrossRef(
                    PlaylistSongCrossRef(playlistId = likedPlaylistId, songId = songId)
                )
            }
        }
    }

    /** Removes every song in [songIds] from Liked Music. Not-liked songs are left as-is. */
    suspend fun unlikeSongs(songIds: List<Long>) {
        val likedPlaylistId = getOrCreateLikedMusicPlaylistId()
        for (songId in songIds) {
            playlistDao.removeSongFromPlaylist(likedPlaylistId, songId)
        }
    }

    /**
     * Observes whether [songId] is in Liked Music. Wrapped in a `flow {}` builder (rather than
     * directly returning a DAO Flow) because resolving the Liked Music playlist ID is itself a
     * suspend call (it may need to create the playlist first) that has to run before the
     * underlying DAO flow can be subscribed to.
     */
    fun isSongLiked(songId: Long): Flow<Boolean> {
        return kotlinx.coroutines.flow.flow {
            val likedPlaylistId = getOrCreateLikedMusicPlaylistId()
            emitAll(playlistDao.isSongInPlaylistFlow(likedPlaylistId, songId))
        }
    }

    /** One-shot (non-observing) check of whether [songId] is currently in Liked Music. */
    suspend fun isSongLikedSync(songId: Long): Boolean {
        val likedPlaylistId = getOrCreateLikedMusicPlaylistId()
        return playlistDao.isSongInPlaylistSync(likedPlaylistId, songId)
    }

    /** Sets a song's pinned state (shown/hidden in the pinned-songs shortcut list). */
    suspend fun togglePinSong(songId: Long, isPinned: Boolean) {
        songDao.updatePinStatus(songId, isPinned)
    }

    /** Sets a playlist's pinned state (shown/hidden in the pinned-playlists shortcut list). */
    suspend fun togglePinPlaylist(playlistId: Long, isPinned: Boolean) {
        playlistDao.updatePinStatus(playlistId, isPinned)
    }

    /** Removes any song_stats/listening_history rows left over from the old play-tracking bug (a play recorded against a bogus/stale id). */
    suspend fun cleanupOrphanedStats() {
        songStatsDao.deleteOrphanedStats()
        listeningHistoryDao?.deleteOrphanedHistory()
    }

    /** Looks up a single song by its row ID, or null if no such song exists. */
    suspend fun getSongById(songId: Long): Song? {
        return songDao.getSongById(songId)
    }

    /**
     * Storage Management: Deletes local file, sets isDownloaded = false, keeps song in database & playlists.
     * Only applies to tracks with a cloud (YouTube) fallback to stream from - deleting the only copy of a
     * purely on-device track would leave it permanently unplayable, so those are left untouched.
     */
    suspend fun removeDownloadKeepInPlaylist(songId: Long) {
        val song = songDao.getSongById(songId) ?: return
        if (song.youtubeId.isNullOrBlank()) {
            Log.w(TAG, "Refusing to remove download for song $songId: no cloud fallback (youtubeId) exists, would leave it unplayable")
            return
        }
        val filePath = when {
            song.mediaUri.startsWith("file:") -> Uri.parse(song.mediaUri).path
            song.mediaUri.startsWith("/") -> song.mediaUri
            else -> null
        }
        if (!filePath.isNullOrBlank()) {
            try {
                val file = File(filePath)
                if (file.exists() && file.isFile) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting local file for song $songId", e)
            }
        }
        val updatedMediaUri = "https://www.youtube.com/watch?v=${song.youtubeId}"
        songDao.updateDownloadStatus(id = songId, isDownloaded = false, mediaUri = updatedMediaUri)
    }

    /**
     * Fully removes each song in [songIds] from the library: deletes its local audio file (if
     * any), removes it from every playlist, deletes its play stats, and deletes the `Song` row
     * itself. Unlike [removeDownloadKeepInPlaylist] this is a real, permanent deletion -- intended
     * for explicit user-confirmed bulk deletion from the Library screen, not for freeing up space
     * on an otherwise-kept cloud track. Best-effort per song: a failure deleting one song's file
     * doesn't stop the rest from being processed.
     */
    suspend fun deleteSongsCompletely(songIds: List<Long>): Int {
        var deletedCount = 0
        for (songId in songIds) {
            val song = songDao.getSongById(songId) ?: continue
            if (song.isDownloaded) {
                val filePath = when {
                    song.mediaUri.startsWith("file:") -> Uri.parse(song.mediaUri).path
                    song.mediaUri.startsWith("/") -> song.mediaUri
                    else -> null
                }
                if (!filePath.isNullOrBlank()) {
                    try {
                        val file = File(filePath)
                        if (file.exists() && file.isFile) {
                            file.delete()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting local file for song $songId during bulk delete", e)
                    }
                }
            }
            playlistDao.deleteCrossRefsForSong(songId)
            songStatsDao.deleteStatsForSong(songId)
            songDao.deleteSong(song)
            deletedCount++
        }
        return deletedCount
    }

    /**
     * Ensures auto-generated Smart Playlists exist in database without duplicates.
     */
    suspend fun ensureSmartPlaylistsExist() = smartPlaylistMutex.withLock {
        for (name in SMART_PLAYLIST_NAMES) {
            val existingList = playlistDao.getPlaylistsByName(name)
            if (existingList.isEmpty()) {
                playlistDao.insertPlaylist(
                    Playlist(
                        name = name,
                        description = if (name == LIKED_MUSIC_NAME) "Songs you have liked" else "Auto-generated playlist",
                        isSmart = true
                    )
                )
            } else if (existingList.size > 1) {
                // Keep the first smart playlist record and safely clean up duplicate rows
                for (i in 1 until existingList.size) {
                    playlistDao.deletePlaylist(existingList[i])
                }
            }
        }
    }

    companion object {
        /** Display names of the app's auto-generated smart playlists; also used as DB row names. */
        const val LIKED_MUSIC_NAME = "Liked Music"
        const val TOP_50_MOST_PLAYED_NAME = "Top 50 Most Played"
        const val RECENTLY_ADDED_NAME = "Recently Added"
        const val UNPLAYED_NAME = "Unplayed"
        const val DOWNLOADS_NAME = "Downloads"
        const val CLOUD_NINE_NAME = "Cloud Nine"

        /** Every auto-generated, immutable smart playlist, in the order they're created. */
        val SMART_PLAYLIST_NAMES = listOf(
            LIKED_MUSIC_NAME, TOP_50_MOST_PLAYED_NAME, RECENTLY_ADDED_NAME, UNPLAYED_NAME,
            DOWNLOADS_NAME, CLOUD_NINE_NAME
        )

        /**
         * The subset of smart playlists computed on the fly from other tables (stats, download
         * status, insertion order) rather than backed by real playlist_song_cross_ref rows --
         * "remove song from playlist" doesn't make sense for these since there's no cross-ref to
         * remove.
         */
        val COMPUTED_SMART_PLAYLIST_NAMES = setOf(
            TOP_50_MOST_PLAYED_NAME, RECENTLY_ADDED_NAME, UNPLAYED_NAME, DOWNLOADS_NAME, CLOUD_NINE_NAME
        )

        /** True for any of the app's auto-generated, immutable smart playlists. */
        fun isProtectedSmartPlaylist(playlist: Playlist): Boolean =
            playlist.isSmart && playlist.name in SMART_PLAYLIST_NAMES
    }

    /** Inserts a new alarm, or replaces an existing one with the same ID, returning its row ID. */
    suspend fun insertOrUpdateAlarm(alarm: Alarm): Long {
        return alarmDao.insertAlarm(alarm)
    }

    /** Deletes an alarm permanently. */
    suspend fun deleteAlarm(alarm: Alarm) {
        alarmDao.deleteAlarm(alarm)
    }

    /** Looks up a single alarm by its row ID, or null if no such alarm exists. */
    suspend fun getAlarmById(alarmId: Long): Alarm? {
        return alarmDao.getAlarmById(alarmId)
    }

    /** Caches fetched/edited lyrics text for a song (see [LyricsRepository.fetchAndSaveLyrics]). */
    suspend fun updateSongLyrics(songId: Long, lyrics: String?) {
        songDao.updateSongLyrics(songId, lyrics)
    }

    /** Stores the local file URI of a downloaded cover art image for a song (see [CoverArtScraper]). */
    suspend fun updateSongArtwork(songId: Long, artworkUri: String?) {
        songDao.updateSongArtwork(songId, artworkUri)
    }

    /** Delegates to [BackupManager] to write a full library backup into [outputStream]. */
    suspend fun exportBackup(outputStream: java.io.OutputStream) {
        backupManager.exportBackup(outputStream)
    }

    /** Delegates to [BackupManager] to restore the library from a backup file stream. */
    suspend fun importBackup(context: Context, inputStream: InputStream): Boolean {
        return backupManager.importBackup(context, inputStream)
    }

    /** Inserts a new song row and returns its generated row ID. */
    suspend fun insertSong(song: Song): Long {
        return songDao.insertSong(song)
    }

    /**
     * One-time cleanup for duplicate [Song] rows that were created before the insertion paths
     * that create cloud songs (search results, playlist sync) all checked for an existing row
     * first. Groups by non-blank [Song.youtubeId] (an exact video match) and, for songs without
     * one, by normalized title+artist (the same "same song" definition already used everywhere
     * else in the app, e.g. [getSongById]/dedup during downloads). For each duplicate group, keeps
     * a downloaded copy if one exists (otherwise the oldest row) as the survivor, repoints
     * playlist membership and merges play stats/pin state/artwork/lyrics onto it, then deletes the
     * other rows. Never deletes any audio file on disk -- only the extra database rows.
     */
    suspend fun deduplicateLibrary(): Int {
        val allSongs = songDao.getAllSongsList()
        val groups = mutableListOf<List<Song>>()
        val consumed = mutableSetOf<Long>()

        val byYoutubeId = allSongs.filter { !it.youtubeId.isNullOrBlank() }.groupBy { it.youtubeId }
        for (group in byYoutubeId.values) {
            if (group.size > 1) {
                groups.add(group)
                consumed.addAll(group.map { it.id })
            }
        }

        val remaining = allSongs.filter { it.id !in consumed }
        // Strip a YouTube auto-generated "- Topic" channel suffix and any "/alias" tail before
        // comparing artists, so e.g. "Jamie Paige - Topic" and "Jamie Paige / JamieP" are
        // recognized as the same artist here too (matches the normalization AiMetadataCleaner now
        // applies at insert time going forward).
        val byTitleArtist = remaining.groupBy {
            it.title.trim().lowercase() to AiMetadataCleaner.normalizeArtistForMatching(it.artist)
        }
        for (group in byTitleArtist.values) {
            if (group.size > 1) {
                groups.add(group)
            }
        }

        var rowsRemoved = 0
        for (group in groups) {
            rowsRemoved += mergeDuplicateSongGroup(group)
        }
        if (rowsRemoved > 0) {
            Log.d(TAG, "deduplicateLibrary: merged $rowsRemoved duplicate song row(s) across ${groups.size} group(s)")
        }

        // Strip a lingering "- Topic"/"/alias" suffix from every song's artist credit, not just
        // ones that happened to collide with another row above -- a song can be the only copy of
        // itself in the library and still have been inserted (before this normalization existed)
        // with the raw auto-generated channel name.
        var artistsCleaned = 0
        for (song in allSongs) {
            val cleanArtist = AiMetadataCleaner.stripTopicSuffix(song.artist).substringBefore("/").trim()
            if (cleanArtist.isNotBlank() && cleanArtist != song.artist) {
                songDao.updateSongArtist(song.id, cleanArtist)
                artistsCleaned++
            }
        }
        if (artistsCleaned > 0) {
            Log.d(TAG, "deduplicateLibrary: stripped Topic/alias suffix from $artistsCleaned artist name(s)")
        }

        return rowsRemoved
    }

    /**
     * Collapses one group of duplicate [Song] rows (as identified by [deduplicateLibrary]) into
     * a single survivor row. Picks a downloaded copy as survivor when one exists (otherwise the
     * oldest row by ID), prefers a non-"Topic" artist credit from any member of the group,
     * repoints every duplicate's playlist memberships onto the survivor, sums play counts and
     * keeps the latest `lastPlayedAt`, backfills artwork/lyrics/pin/youtubeId from a duplicate
     * only if the survivor is missing them, then deletes the duplicate rows. Returns how many
     * rows were removed.
     */
    private suspend fun mergeDuplicateSongGroup(group: List<Song>): Int {
        if (group.size < 2) return 0
        var survivor = group.firstOrNull { it.isDownloaded } ?: group.minByOrNull { it.id }!!
        val duplicates = group.filter { it.id != survivor.id }
        if (duplicates.isEmpty()) return 0

        // Prefer a real channel name over a YouTube auto-generated "- Topic" one for the merged
        // row's artist credit, regardless of which copy happened to become the survivor.
        val nonTopicArtist = group.map { it.artist }.firstOrNull {
            it.isNotBlank() && AiMetadataCleaner.stripTopicSuffix(it) == it
        }
        val normalizedArtist = nonTopicArtist ?: AiMetadataCleaner.stripTopicSuffix(survivor.artist)
        if (normalizedArtist.isNotBlank() && normalizedArtist != survivor.artist) {
            survivor = survivor.copy(artist = normalizedArtist)
        }

        var mergedPlayCount = songStatsDao.getStatsForSong(survivor.id)?.playCount ?: 0
        var mergedLastPlayedAt = songStatsDao.getStatsForSong(survivor.id)?.lastPlayedAt

        for (dup in duplicates) {
            for (playlistId in playlistDao.getPlaylistIdsForSong(dup.id)) {
                playlistDao.insertPlaylistSongCrossRef(PlaylistSongCrossRef(playlistId, survivor.id))
            }
            playlistDao.deleteCrossRefsForSong(dup.id)

            songStatsDao.getStatsForSong(dup.id)?.let { dupStats ->
                mergedPlayCount += dupStats.playCount
                mergedLastPlayedAt = maxOf(mergedLastPlayedAt ?: 0L, dupStats.lastPlayedAt ?: 0L)
                    .takeIf { it > 0L }
                songStatsDao.deleteStatsForSong(dup.id)
            }

            if (survivor.artworkUri.isNullOrBlank() && !dup.artworkUri.isNullOrBlank()) {
                survivor = survivor.copy(artworkUri = dup.artworkUri)
            }
            if (survivor.lyrics.isNullOrBlank() && !dup.lyrics.isNullOrBlank()) {
                survivor = survivor.copy(lyrics = dup.lyrics)
            }
            if (dup.isPinned && !survivor.isPinned) {
                survivor = survivor.copy(isPinned = true)
            }
            if (survivor.youtubeId.isNullOrBlank() && !dup.youtubeId.isNullOrBlank()) {
                survivor = survivor.copy(youtubeId = dup.youtubeId)
            }

            songDao.deleteSong(dup)
        }

        songDao.insertSong(survivor)
        if (mergedPlayCount > 0 || mergedLastPlayedAt != null) {
            songStatsDao.insertOrUpdate(
                SongStats(songId = survivor.id, playCount = mergedPlayCount, lastPlayedAt = mergedLastPlayedAt)
            )
        }
        return duplicates.size
    }
}
