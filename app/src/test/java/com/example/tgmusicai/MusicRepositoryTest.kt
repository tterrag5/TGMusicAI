package com.example.tgmusicai

import com.example.tgmusicai.data.local.dao.AlarmDao
import com.example.tgmusicai.data.local.dao.PlaylistDao
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.dao.SongStatsDao
import com.example.tgmusicai.data.local.entity.Alarm
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistSongCrossRef
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.local.entity.SongStats
import com.example.tgmusicai.data.repository.MusicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MusicRepository] using Fake DAOs.
 */
class MusicRepositoryTest {

    private lateinit var fakeSongDao: FakeSongDao
    private lateinit var fakePlaylistDao: FakePlaylistDao
    private lateinit var fakeSongStatsDao: FakeSongStatsDao
    private lateinit var fakeAlarmDao: FakeAlarmDao
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        fakeSongDao = FakeSongDao()
        fakePlaylistDao = FakePlaylistDao(fakeSongDao)
        fakeSongStatsDao = FakeSongStatsDao()
        fakeAlarmDao = FakeAlarmDao()
        repository = MusicRepository(fakeSongDao, fakePlaylistDao, fakeSongStatsDao, fakeAlarmDao)
    }

    @Test
    fun testGetAllSongs() = runTest {
        val testSong = Song(id = 1, title = "Test Track", artist = "Test Artist", album = "Test Album", durationMs = 180000, mediaUri = "content://media/1")
        fakeSongDao.songs.add(testSong)

        val songs = repository.allSongs.first()
        assertEquals(1, songs.size)
        assertEquals("Test Track", songs[0].title)
    }

    @Test
    fun testCreatePlaylist() = runTest {
        val id = repository.createPlaylist("My Favorite Songs")
        assertEquals(1L, id)
        assertEquals(1, fakePlaylistDao.createdPlaylists.size)
        assertEquals("My Favorite Songs", fakePlaylistDao.createdPlaylists[0].name)
    }

    @Test
    fun testIncrementPlayCountViaDao() = runTest {
        // MusicRepository.recordSongPlayed/recordSongPlayedByMediaUri were removed once
        // PlaybackService started calling SongStatsDao.incrementPlayCount directly (see
        // FULL_CODE_SUMMARY.md Section 10 -- background listen tracking now lives in the
        // service, not the repository), so this exercises the DAO directly instead.
        fakeSongStatsDao.incrementPlayCount(songId = 10L)
        assertEquals(10L, fakeSongStatsDao.incrementedSongId)
    }

    @Test
    fun testInsertAndGetAlarm() = runTest {
        val alarm = Alarm(id = 1, timeInMillis = System.currentTimeMillis(), label = "Test Alarm")
        repository.insertOrUpdateAlarm(alarm)
        val fetched = repository.getAlarmById(1)
        assertEquals("Test Alarm", fetched?.label)
    }

    @Test
    fun testEnsureSmartPlaylistsExistDeduplication() = runTest {
        // Pre-populate duplicate smart playlists
        fakePlaylistDao.createdPlaylists.add(Playlist(playlistId = 1, name = "Top 50 Most Played", isSmart = true))
        fakePlaylistDao.createdPlaylists.add(Playlist(playlistId = 2, name = "Top 50 Most Played", isSmart = true))
        fakePlaylistDao.createdPlaylists.add(Playlist(playlistId = 3, name = "Recently Added", isSmart = true))

        repository.ensureSmartPlaylistsExist()

        val smartList = repository.smartPlaylists.first()
        assertEquals(6, smartList.size)
        org.junit.Assert.assertTrue(
            smartList.map { it.name }.containsAll(
                listOf("Liked Music", "Top 50 Most Played", "Recently Added", "Unplayed", "Downloads", "Cloud Nine")
            )
        )
    }

    @Test
    fun testSafePlaylistDeletionRemovesCrossRefsWithoutDeletingSongs() = runTest {
        val song = Song(id = 100, title = "Keep Me", artist = "Artist", album = "Album", durationMs = 120000, mediaUri = "uri")
        fakeSongDao.songs.add(song)

        val playlist = Playlist(playlistId = 5, name = "Party Hits", isSmart = false)
        fakePlaylistDao.createdPlaylists.add(playlist)
        repository.addSongToPlaylist(playlistId = 5, songId = 100)

        assertEquals(1, fakePlaylistDao.crossRefs.size)

        repository.deletePlaylist(playlist)

        assertEquals(0, fakePlaylistDao.createdPlaylists.size)
        assertEquals(0, fakePlaylistDao.crossRefs.size)
        // Ensure song is NOT deleted
        assertEquals(1, fakeSongDao.songs.size)
    }

    @Test
    fun movingASongInAPlaylistPersistsTheNewOrder() = runBlocking {
        val playlistId = seedPlaylistWithSongs("Road trip", listOf(1L, 2L, 3L))

        repository.moveSongInPlaylist(playlistId, fromIndex = 0, toIndex = 2)

        assertEquals(
            listOf(2L, 3L, 1L),
            fakePlaylistDao.getOrderedSongsForPlaylistSync(playlistId).map { it.id },
        )
    }

    @Test
    fun movingASongRenumbersEveryRowRatherThanPatchingTwo() = runBlocking {
        // Playlists built before reordering existed hold position 0 on every row. Patching only
        // the two moved rows would leave the rest tied and still arbitrarily ordered, so the whole
        // list has to be renumbered.
        val playlistId = seedPlaylistWithSongs("Legacy", listOf(1L, 2L, 3L), allPositionsZero = true)

        repository.moveSongInPlaylist(playlistId, fromIndex = 2, toIndex = 0)

        val positions = fakePlaylistDao.crossRefs
            .filter { it.playlistId == playlistId }
            .map { it.position }
            .sorted()
        assertEquals(listOf(0, 1, 2), positions)
    }

    @Test
    fun movingASongIgnoresOutOfRangeAndNoOpDrags() = runBlocking {
        val playlistId = seedPlaylistWithSongs("Stable", listOf(1L, 2L, 3L))
        val before = fakePlaylistDao.getOrderedSongsForPlaylistSync(playlistId).map { it.id }

        repository.moveSongInPlaylist(playlistId, fromIndex = 1, toIndex = 1)
        repository.moveSongInPlaylist(playlistId, fromIndex = -1, toIndex = 2)
        repository.moveSongInPlaylist(playlistId, fromIndex = 0, toIndex = 99)

        assertEquals(before, fakePlaylistDao.getOrderedSongsForPlaylistSync(playlistId).map { it.id })
    }

    @Test
    fun movingAPlaylistPersistsTheNewOrder() = runBlocking {
        val first = seedPlaylistWithSongs("First", listOf(1L))
        val second = seedPlaylistWithSongs("Second", listOf(2L))
        val third = seedPlaylistWithSongs("Third", listOf(3L))

        repository.movePlaylist(listOf(first, second, third), fromIndex = 2, toIndex = 0)

        val positionById = fakePlaylistDao.createdPlaylists.associate { it.playlistId to it.position }
        assertEquals(0, positionById[third])
        assertEquals(1, positionById[first])
        assertEquals(2, positionById[second])
    }

    /** Creates a playlist holding [songIds] in order, and returns its id. */
    private suspend fun seedPlaylistWithSongs(
        name: String,
        songIds: List<Long>,
        allPositionsZero: Boolean = false,
    ): Long {
        val playlistId = repository.createPlaylist(name)
        songIds.forEachIndexed { index, songId ->
            if (songId !in fakeSongDao.songs.map { it.id }) {
                fakeSongDao.songs.add(
                    Song(
                        id = songId,
                        title = "Song $songId",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 1000L,
                        mediaUri = "file:///$songId.mp3",
                    )
                )
            }
            fakePlaylistDao.insertCrossRefRaw(
                PlaylistSongCrossRef(
                    playlistId = playlistId,
                    songId = songId,
                    position = if (allPositionsZero) 0 else index,
                )
            )
        }
        return playlistId
    }
}

private class FakeSongDao : SongDao {
    val songs = mutableListOf<Song>()

    override suspend fun insertSongs(songs: List<Song>) {
        this.songs.addAll(songs)
    }

    override suspend fun insertSong(song: Song): Long {
        songs.add(song)
        return song.id
    }

    override fun getAllSongs(): Flow<List<Song>> = flowOf(songs)

    override suspend fun getAllSongsList(): List<Song> = songs

    override fun getDownloadedSongs(): Flow<List<Song>> = flowOf(songs.filter { it.isDownloaded })

    override suspend fun getDownloadedSongsSync(): List<Song> = songs.filter { it.isDownloaded }

    override fun getNotDownloadedSongs(): Flow<List<Song>> = flowOf(songs.filter { !it.isDownloaded })

    override suspend fun getRandomDownloadedSongs(limit: Int): List<Song> = songs.filter { it.isDownloaded }.take(limit)
    override suspend fun getSongsMissingArtwork(): List<Song> = songs.filter { it.artworkUri.isNullOrBlank() }

    override fun getPinnedSongs(): Flow<List<Song>> = flowOf(songs.filter { it.isPinned })

    override suspend fun getSongById(songId: Long): Song? = songs.find { it.id == songId }

    override suspend fun getSongByUri(uri: String): Song? = songs.find { it.mediaUri == uri }

    override suspend fun getSongByYoutubeId(youtubeId: String): Song? = songs.find { it.youtubeId == youtubeId }

    override suspend fun getSongByTitleAndArtist(title: String, artist: String): Song? = songs.find {
        it.title.equals(title, ignoreCase = true) && it.artist.equals(artist, ignoreCase = true)
    }

    override suspend fun getSongsByTitle(title: String): List<Song> = songs.filter {
        it.title.equals(title, ignoreCase = true)
    }

    override fun getRecentlyAddedSongs(limit: Int): Flow<List<Song>> = flowOf(songs.take(limit))

    override fun getUnplayedSongs(): Flow<List<Song>> = flowOf(songs)

    override suspend fun deleteSong(song: Song) {
        songs.remove(song)
    }

    override suspend fun updateSongLyrics(id: Long, lyrics: String?) {
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) {
            songs[index] = songs[index].copy(lyrics = lyrics)
        }
    }

    override suspend fun updateSongArtwork(id: Long, artworkUri: String?) {
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) {
            songs[index] = songs[index].copy(artworkUri = artworkUri)
        }
    }

    override suspend fun updatePinStatus(id: Long, isPinned: Boolean) {
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) {
            songs[index] = songs[index].copy(isPinned = isPinned)
        }
    }

    override suspend fun updateDownloadStatus(id: Long, isDownloaded: Boolean, mediaUri: String) {
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) {
            songs[index] = songs[index].copy(isDownloaded = isDownloaded, mediaUri = mediaUri)
        }
    }

    override suspend fun updateSongArtist(id: Long, artist: String) {
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) {
            songs[index] = songs[index].copy(artist = artist)
        }
    }

    override suspend fun updateReplayGain(id: Long, gainDb: Float?, peak: Float?) {
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) {
            songs[index] = songs[index].copy(replayGainDb = gainDb, replayPeak = peak)
        }
    }

    override suspend fun getSongsMissingReplayGain(limit: Int): List<Song> =
        songs.filter { it.replayGainDb == null && it.isDownloaded }.take(limit)

    override fun countSongsMissingReplayGain(): Flow<Int> =
        flowOf(songs.count { it.replayGainDb == null && it.isDownloaded })

    override suspend fun updateEditedMetadata(
        id: Long,
        title: String,
        artist: String,
        album: String,
        producer: String?
    ) {
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) {
            songs[index] = songs[index].copy(
                title = title,
                artist = artist,
                album = album,
                producer = producer
            )
        }
    }
}

private class FakePlaylistDao(private val songDao: FakeSongDao) : PlaylistDao {
    val createdPlaylists = mutableListOf<Playlist>()
    val crossRefs = mutableListOf<PlaylistSongCrossRef>()

    override suspend fun insertPlaylist(playlist: Playlist): Long {
        // Store the row under the id that is handed back, the way Room does. Keeping the
        // incoming playlistId of 0 made every later lookup by id miss.
        // Next id past the highest in use, not size + 1: after a delete those differ, and the
        // size-based guess would collide with a row that is still there and overwrite it.
        val assignedId = if (playlist.playlistId != 0L) {
            playlist.playlistId
        } else {
            (createdPlaylists.maxOfOrNull { it.playlistId } ?: 0L) + 1L
        }
        createdPlaylists.removeAll { it.playlistId == assignedId }
        createdPlaylists.add(playlist.copy(playlistId = assignedId))
        return assignedId
    }

    override suspend fun insertPlaylistSongCrossRef(crossRef: PlaylistSongCrossRef) {
        crossRefs.add(crossRef)
    }


    override suspend fun insertCrossRefRaw(crossRef: PlaylistSongCrossRef) {
        crossRefs.removeAll { it.playlistId == crossRef.playlistId && it.songId == crossRef.songId }
        crossRefs.add(crossRef)
    }

    override suspend fun getSongPositionInPlaylist(playlistId: Long, songId: Long): Int? =
        crossRefs.find { it.playlistId == playlistId && it.songId == songId }?.position

    override suspend fun getMaxPositionInPlaylist(playlistId: Long): Int? =
        crossRefs.filter { it.playlistId == playlistId }.maxOfOrNull { it.position }

    override suspend fun updateSongPosition(playlistId: Long, songId: Long, position: Int) {
        val index = crossRefs.indexOfFirst { it.playlistId == playlistId && it.songId == songId }
        if (index >= 0) crossRefs[index] = crossRefs[index].copy(position = position)
    }

    override suspend fun updatePlaylistPosition(playlistId: Long, position: Int) {
        val index = createdPlaylists.indexOfFirst { it.playlistId == playlistId }
        if (index >= 0) createdPlaylists[index] = createdPlaylists[index].copy(position = position)
    }

    // Mirrors the real ordered join: position first, row id as the stable tie-break.
    private fun orderedSongs(playlistId: Long): List<Song> {
        val ordered = crossRefs.filter { it.playlistId == playlistId }
            .sortedWith(compareBy({ it.position }, { it.songId }))
        return ordered.mapNotNull { ref -> songDao.songs.find { it.id == ref.songId } }
    }

    override fun getOrderedSongsForPlaylist(playlistId: Long): Flow<List<Song>> =
        flowOf(orderedSongs(playlistId))

    override suspend fun getOrderedSongsForPlaylistSync(playlistId: Long): List<Song> =
        orderedSongs(playlistId)

    override fun getAllPlaylists(): Flow<List<Playlist>> = flow { emit(createdPlaylists.toList()) }

    override suspend fun getAllPlaylistsList(): List<Playlist> = createdPlaylists.toList()

    override fun getPinnedPlaylists(): Flow<List<Playlist>> = flow { emit(createdPlaylists.filter { it.isPinned }) }

    override fun getSmartPlaylists(): Flow<List<Playlist>> = flow { emit(createdPlaylists.filter { it.isSmart }) }

    override suspend fun getPlaylistByName(name: String): Playlist? = createdPlaylists.find { it.name == name }

    override suspend fun getPlaylistsByName(name: String): List<Playlist> = createdPlaylists.filter { it.name == name }

    override suspend fun getPlaylistById(playlistId: Long): Playlist? = createdPlaylists.find { it.playlistId == playlistId }

    override fun getPlaylistWithSongs(playlistId: Long): Flow<PlaylistWithSongs?> {
        val playlist = createdPlaylists.find { it.playlistId == playlistId } ?: return flowOf(null)
        val songIds = crossRefs.filter { it.playlistId == playlistId }.map { it.songId }
        val playlistSongs = songDao.songs.filter { songIds.contains(it.id) }
        return flowOf(PlaylistWithSongs(playlist, playlistSongs))
    }

    override suspend fun getPlaylistWithSongsSync(playlistId: Long): PlaylistWithSongs? {
        val playlist = createdPlaylists.find { it.playlistId == playlistId } ?: return null
        val songIds = crossRefs.filter { it.playlistId == playlistId }.map { it.songId }
        val playlistSongs = songDao.songs.filter { songIds.contains(it.id) }
        return PlaylistWithSongs(playlist, playlistSongs)
    }

    override suspend fun deleteCrossRefsForPlaylist(playlistId: Long) {
        crossRefs.removeAll { it.playlistId == playlistId }
    }

    override suspend fun deletePlaylistEntity(playlist: Playlist) {
        createdPlaylists.remove(playlist)
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        deleteCrossRefsForPlaylist(playlist.playlistId)
        deletePlaylistEntity(playlist)
    }

    override suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        crossRefs.removeAll { it.playlistId == playlistId && it.songId == songId }
    }

    override suspend fun updatePinStatus(playlistId: Long, isPinned: Boolean) {
        val index = createdPlaylists.indexOfFirst { it.playlistId == playlistId }
        if (index >= 0) {
            createdPlaylists[index] = createdPlaylists[index].copy(isPinned = isPinned)
        }
    }

    override suspend fun updatePlaylistDescription(playlistId: Long, description: String?) {
        val index = createdPlaylists.indexOfFirst { it.playlistId == playlistId }
        if (index >= 0) {
            createdPlaylists[index] = createdPlaylists[index].copy(description = description)
        }
    }

    override fun isSongInPlaylistFlow(playlistId: Long, songId: Long): Flow<Boolean> =
        flowOf(crossRefs.any { it.playlistId == playlistId && it.songId == songId })

    override suspend fun isSongInPlaylistSync(playlistId: Long, songId: Long): Boolean =
        crossRefs.any { it.playlistId == playlistId && it.songId == songId }

    override suspend fun getPlaylistByYoutubeId(youtubePlaylistId: String): Playlist? =
        createdPlaylists.find { it.youtubePlaylistId == youtubePlaylistId }

    override fun getYoutubeSyncedPlaylists(): Flow<List<Playlist>> =
        flow { emit(createdPlaylists.filter { it.youtubePlaylistId != null }) }

    override suspend fun getYoutubeSyncedPlaylistsList(): List<Playlist> =
        createdPlaylists.filter { it.youtubePlaylistId != null }

    override suspend fun updateLastSyncedAt(playlistId: Long, timestamp: Long) {
        val index = createdPlaylists.indexOfFirst { it.playlistId == playlistId }
        if (index >= 0) {
            createdPlaylists[index] = createdPlaylists[index].copy(lastSyncedAt = timestamp)
        }
    }

    override suspend fun getSongIdsInPlaylist(playlistId: Long): List<Long> =
        crossRefs.filter { it.playlistId == playlistId }.map { it.songId }

    override suspend fun getPlaylistIdsForSong(songId: Long): List<Long> =
        crossRefs.filter { it.songId == songId }.map { it.playlistId }

    override suspend fun deleteCrossRefsForSong(songId: Long) {
        crossRefs.removeAll { it.songId == songId }
    }
}

private class FakeSongStatsDao : SongStatsDao {
    var incrementedSongId: Long? = null
    val stats = mutableMapOf<Long, SongStats>()

    override suspend fun insertOrUpdate(stats: SongStats) {
        this.stats[stats.songId] = stats
    }

    override suspend fun getStatsForSong(songId: Long): SongStats? = stats[songId]

    override fun getMostPlayedStats(limit: Int): Flow<List<SongStats>> = flowOf(emptyList())

    override fun getRecentlyPlayedStats(limit: Int): Flow<List<SongStats>> = flowOf(emptyList())

    override suspend fun getRecentlyPlayedStatsSync(limit: Int): List<SongStats> = emptyList()

    override suspend fun getMostPlayedStatsSync(limit: Int): List<SongStats> = emptyList()

    override suspend fun deleteStatsForSong(songId: Long) {
        stats.remove(songId)
    }

    override suspend fun deleteOrphanedStats() {}

    override suspend fun incrementPlayCount(songId: Long, currentTime: Long) {
        incrementedSongId = songId
    }

    override fun getTotalPlayCount(): Flow<Int> = flowOf(stats.values.sumOf { it.playCount })

    override fun getTotalListenTimeMs(): Flow<Long> = flowOf(stats.values.sumOf { it.totalListenTimeMs })
}

private class FakeAlarmDao : AlarmDao {
    val alarms = mutableListOf<Alarm>()

    override fun getAllAlarms(): Flow<List<Alarm>> = flowOf(alarms)

    override suspend fun getAllAlarmsList(): List<Alarm> = alarms

    override suspend fun getEnabledAlarms(): List<Alarm> = alarms.filter { it.isEnabled }

    override suspend fun getAlarmById(alarmId: Long): Alarm? = alarms.find { it.id == alarmId }

    override suspend fun insertAlarm(alarm: Alarm): Long {
        alarms.removeAll { it.id == alarm.id }
        alarms.add(alarm)
        return alarm.id
    }

    override suspend fun updateAlarm(alarm: Alarm) {
        alarms.removeAll { it.id == alarm.id }
        alarms.add(alarm)
    }

    override suspend fun deleteAlarm(alarm: Alarm) {
        alarms.remove(alarm)
    }

    override suspend fun deleteAlarmById(alarmId: Long) {
        alarms.removeAll { it.id == alarmId }
    }
}
