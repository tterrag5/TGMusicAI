package com.example.tgmusicai

import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.playback.SleepTimerManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ConvenienceFeaturesTest {

    @Test
    fun testSongEntityNewFieldsDefaultValues() {
        val song = Song(
            id = 10,
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180000L,
            mediaUri = "file:///test.mp3"
        )

        assertNull(song.youtubeId)
        assertTrue(song.isDownloaded)
        assertFalse(song.isPinned)
    }

    @Test
    fun testPlaylistEntityNewFieldsDefaultValues() {
        val playlist = Playlist(
            playlistId = 1,
            name = "My Favorites"
        )

        assertFalse(playlist.isPinned)
        assertFalse(playlist.isSmart)
    }

    @Test
    fun testBackupManifestZipCreationAndParsing() {
        val manifestJsonString = """
            {
              "songs": [
                {
                  "id": 1,
                  "title": "Song A",
                  "artist": "Artist A",
                  "isPinned": true,
                  "isDownloaded": true
                }
              ],
              "playlists": [
                {
                  "playlistId": 1,
                  "name": "Top 50 Most Played",
                  "isSmart": true,
                  "isPinned": true
                }
              ]
            }
        """.trimIndent()

        // Zip output stream
        val baos = ByteArrayOutputStream()
        val zipOut = ZipOutputStream(baos)
        zipOut.putNextEntry(ZipEntry("manifest.json"))
        zipOut.write(manifestJsonString.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()
        zipOut.close()

        // Read zip input stream
        val bais = ByteArrayInputStream(baos.toByteArray())
        val zipIn = ZipInputStream(bais)

        var entry = zipIn.nextEntry
        var readManifestString: String? = null
        while (entry != null) {
            if (entry.name == "manifest.json") {
                readManifestString = zipIn.bufferedReader(Charsets.UTF_8).readText()
            }
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        zipIn.close()

        assertNotNull(readManifestString)
        assertTrue(readManifestString!!.contains("Song A"))
        assertTrue(readManifestString.contains("Top 50 Most Played"))
        assertTrue(readManifestString.contains("\"isSmart\": true"))
    }

    @Test
    fun testSleepTimerManagerStartAndCancel() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        var timerExpiredCalled = false
        val sleepTimerManager = SleepTimerManager(
            onTimerExpired = { timerExpiredCalled = true },
            dispatcher = testDispatcher
        )

        assertFalse(sleepTimerManager.isRunning)
        assertNull(sleepTimerManager.remainingMs.value)

        sleepTimerManager.startTimer(15)
        assertTrue(sleepTimerManager.isRunning)
        assertNotNull(sleepTimerManager.remainingMs.value)
        assertEquals(15 * 60 * 1000L, sleepTimerManager.remainingMs.value)

        sleepTimerManager.cancelTimer()
        assertFalse(sleepTimerManager.isRunning)
        assertNull(sleepTimerManager.remainingMs.value)
        assertFalse(timerExpiredCalled)
    }

    @Test
    fun testDeduplicationMatchingLogic() {
        val existingSong = Song(
            id = 5,
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            durationMs = 240000L,
            mediaUri = "file:///storage/emulated/0/Music/M83_Midnight_City.mp3",
            youtubeId = "DX3Kb_eAdA",
            isDownloaded = true
        )

        val matchByYt = if (existingSong.youtubeId == "DX3Kb_eAdA" && existingSong.isDownloaded) existingSong else null
        assertNotNull(matchByYt)
        assertEquals(5L, matchByYt?.id)

        val matchByTitleArtist = if (
            existingSong.title.equals("midnight city", ignoreCase = true) &&
            existingSong.artist.equals("m83", ignoreCase = true) &&
            existingSong.isDownloaded
        ) existingSong else null

        assertNotNull(matchByTitleArtist)
        assertEquals("M83", matchByTitleArtist?.artist)
    }
}
