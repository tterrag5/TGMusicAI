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
import com.example.tgmusicai.ui.theme.AppTheme
import com.example.tgmusicai.ui.theme.NordicSlateAccent
import com.example.tgmusicai.ui.theme.NordicSlateBackground
import com.example.tgmusicai.ui.theme.PastelMidnightAccent
import com.example.tgmusicai.ui.theme.PastelMidnightBackground
import com.example.tgmusicai.ui.theme.WarmAmberAccent
import com.example.tgmusicai.ui.theme.WarmAmberBackground
import com.example.tgmusicai.ui.theme.YTDarkAccent
import com.example.tgmusicai.ui.theme.YTDarkBackground
import com.example.tgmusicai.ui.theme.getSoftColorScheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying Multiple Soft Themes, Liked Music Immutability,
 * Thumbs Up Toggle, and Top Song Artwork resolution.
 */
class ThemesAndLikedMusicTest {

    private lateinit var fakeSongDao: FakeTestSongDao
    private lateinit var fakePlaylistDao: FakeTestPlaylistDao
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        fakeSongDao = FakeTestSongDao()
        fakePlaylistDao = FakeTestPlaylistDao(fakeSongDao)
        repository = MusicRepository(
            songDao = fakeSongDao,
            playlistDao = fakePlaylistDao,
            songStatsDao = FakeTestSongStatsDao(),
            alarmDao = FakeTestAlarmDao()
        )
    }

    @Test
    fun testSoftThemeOptionResolutionAndColors() {
        val ytDarkTheme = AppTheme.fromName("YT_DARK")
        assertEquals(AppTheme.YT_DARK, ytDarkTheme)

        val pastelTheme = AppTheme.fromName("PASTEL_MIDNIGHT")
        assertEquals(AppTheme.PASTEL_MIDNIGHT, pastelTheme)

        val warmTheme = AppTheme.fromName("WARM_AMBER")
        assertEquals(AppTheme.WARM_AMBER, warmTheme)

        val nordicTheme = AppTheme.fromName("NORDIC_SLATE")
        assertEquals(AppTheme.NORDIC_SLATE, nordicTheme)

        // Fallback test
        assertEquals(AppTheme.YT_DARK, AppTheme.fromName("UNKNOWN_THEME"))

        val ytColorScheme = getSoftColorScheme("YT_DARK")
        assertEquals(YTDarkBackground, ytColorScheme.background)
        assertEquals(YTDarkAccent, ytColorScheme.primary)

        val pastelColorScheme = getSoftColorScheme("PASTEL_MIDNIGHT")
        assertEquals(PastelMidnightBackground, pastelColorScheme.background)
        assertEquals(PastelMidnightAccent, pastelColorScheme.primary)

        val warmColorScheme = getSoftColorScheme("WARM_AMBER")
        assertEquals(WarmAmberBackground, warmColorScheme.background)
        assertEquals(WarmAmberAccent, warmColorScheme.primary)

        val nordicColorScheme = getSoftColorScheme("NORDIC_SLATE")
        assertEquals(NordicSlateBackground, nordicColorScheme.background)
        assertEquals(NordicSlateAccent, nordicColorScheme.primary)
    }

    @Test
    fun testLikedMusicPlaylistImmutability() = runTest {
        repository.ensureSmartPlaylistsExist()

        val playlists = repository.allPlaylists.first()
        val likedPlaylist = playlists.find { it.name == "Liked Music" }

        assertNotNull(likedPlaylist)
        assertTrue(likedPlaylist!!.isSmart)

        // Attempt deletion of immutable Liked Music playlist
        repository.deletePlaylist(likedPlaylist)

        val playlistsAfterDelete = repository.allPlaylists.first()
        val likedPlaylistAfterDelete = playlistsAfterDelete.find { it.name == "Liked Music" }

        // Must still exist
        assertNotNull(likedPlaylistAfterDelete)
    }

    @Test
    fun testAllSmartPlaylistsAreImmutable() = runTest {
        repository.ensureSmartPlaylistsExist()

        for (name in com.example.tgmusicai.data.repository.MusicRepository.SMART_PLAYLIST_NAMES) {
            val playlist = repository.allPlaylists.first().find { it.name == name }
            assertNotNull("Expected smart playlist '$name' to exist", playlist)

            repository.deletePlaylist(playlist!!)

            val stillExists = repository.allPlaylists.first().any { it.name == name }
            assertTrue("Smart playlist '$name' should not be deletable", stillExists)
        }
    }

    @Test
    fun testThumbsUpToggleLikingAndUnlikingSong() = runTest {
        val songId = 42L
        repository.ensureSmartPlaylistsExist()

        // Initially unliked
        assertFalse(repository.isSongLikedSync(songId))

        // First toggle: Adds song to Liked Music
        val isLikedFirstToggle = repository.toggleLikeSong(songId)
        assertTrue(isLikedFirstToggle)
        assertTrue(repository.isSongLikedSync(songId))

        // Second toggle: Removes song from Liked Music
        val isLikedSecondToggle = repository.toggleLikeSong(songId)
        assertFalse(isLikedSecondToggle)
        assertFalse(repository.isSongLikedSync(songId))
    }

    @Test
    fun testTopSongCoverArtResolution() = runTest {
        val song1 = Song(
            id = 101,
            title = "First Song",
            artist = "Artist A",
            album = "Album A",
            durationMs = 200000,
            mediaUri = "content://101",
            artworkUri = "https://example.com/artwork1.jpg"
        )
        val song2 = Song(
            id = 102,
            title = "Second Song",
            artist = "Artist B",
            album = "Album B",
            durationMs = 210000,
            mediaUri = "content://102",
            artworkUri = "https://example.com/artwork2.jpg"
        )

        fakeSongDao.songs.addAll(listOf(song1, song2))

        val customPlaylistId = repository.createPlaylist(name = "Summer Vibe", description = "Warm summer tracks")
        repository.addSongToPlaylist(customPlaylistId, song1.id)
        repository.addSongToPlaylist(customPlaylistId, song2.id)

        val pws = fakePlaylistDao.getPlaylistWithSongsSync(customPlaylistId)
        assertNotNull(pws)
        assertEquals("Summer Vibe", pws!!.playlist.name)
        assertEquals("Warm summer tracks", pws.playlist.description)

        // Top song artwork should match first song's artwork
        val topSongArtwork = pws.songs.firstOrNull()?.artworkUri
        assertEquals("https://example.com/artwork1.jpg", topSongArtwork)
    }
}

private class FakeTestSongDao : SongDao {
    val songs = mutableListOf<Song>()

    override suspend fun insertSongs(songs: List<Song>) { this.songs.addAll(songs) }
    override suspend fun insertSong(song: Song): Long { songs.add(song); return song.id }
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
    override suspend fun deleteSong(song: Song) { songs.remove(song) }
    override suspend fun updateSongLyrics(id: Long, lyrics: String?) {}
    override suspend fun updateSongArtwork(id: Long, artworkUri: String?) {}
    override suspend fun updateSongArtist(id: Long, artist: String) {
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) songs[index] = songs[index].copy(artist = artist)
    }
    override suspend fun updatePinStatus(id: Long, isPinned: Boolean) {}
    override suspend fun updateDownloadStatus(id: Long, isDownloaded: Boolean, mediaUri: String) {}
    override suspend fun updateReplayGain(id: Long, gainDb: Float?, peak: Float?) {
        val index = songs.indexOfFirst { it.id == id }
        if (index >= 0) songs[index] = songs[index].copy(replayGainDb = gainDb, replayPeak = peak)
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
            songs[index] = songs[index].copy(title = title, artist = artist, album = album, producer = producer)
        }
    }
}

private class FakeTestPlaylistDao(private val songDao: FakeTestSongDao) : PlaylistDao {
    val createdPlaylists = mutableListOf<Playlist>()
    val crossRefs = mutableListOf<PlaylistSongCrossRef>()
    private var idCounter = 1L

    override suspend fun insertPlaylist(playlist: Playlist): Long {
        val id = if (playlist.playlistId == 0L) idCounter++ else playlist.playlistId
        val entry = playlist.copy(playlistId = id)
        createdPlaylists.add(entry)
        return id
    }

    override suspend fun insertPlaylistSongCrossRef(crossRef: PlaylistSongCrossRef) {
        crossRefs.add(crossRef)
    }

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

    override suspend fun updatePinStatus(playlistId: Long, isPinned: Boolean) {}

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

}

private class FakeTestSongStatsDao : SongStatsDao {
    override suspend fun insertOrUpdate(stats: SongStats) {}
    override suspend fun getStatsForSong(songId: Long): SongStats? = null
    override fun getMostPlayedStats(limit: Int): Flow<List<SongStats>> = flowOf(emptyList())
    override fun getRecentlyPlayedStats(limit: Int): Flow<List<SongStats>> = flowOf(emptyList())
    override suspend fun getRecentlyPlayedStatsSync(limit: Int): List<SongStats> = emptyList()
    override suspend fun getMostPlayedStatsSync(limit: Int): List<SongStats> = emptyList()
    override suspend fun deleteStatsForSong(songId: Long) {}
    override suspend fun deleteOrphanedStats() {}
    override suspend fun incrementPlayCount(songId: Long, currentTime: Long) {}
    override fun getTotalPlayCount(): Flow<Int> = flowOf(0)
    override fun getTotalListenTimeMs(): Flow<Long> = flowOf(0L)
}

private class FakeTestAlarmDao : AlarmDao {
    override fun getAllAlarms(): Flow<List<Alarm>> = flowOf(emptyList())
    override suspend fun getAllAlarmsList(): List<Alarm> = emptyList()
    override suspend fun getEnabledAlarms(): List<Alarm> = emptyList()
    override suspend fun getAlarmById(alarmId: Long): Alarm? = null
    override suspend fun insertAlarm(alarm: Alarm): Long = alarm.id
    override suspend fun updateAlarm(alarm: Alarm) {}
    override suspend fun deleteAlarm(alarm: Alarm) {}
    override suspend fun deleteAlarmById(alarmId: Long) {}
}
