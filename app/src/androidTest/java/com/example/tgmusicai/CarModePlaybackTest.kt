package com.example.tgmusicai

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.playback.PlaybackService
import com.example.tgmusicai.playback.SongMediaExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android Auto's side of the app with the app itself closed: a bare [MediaBrowser] (no app-UI
 * connection hint, exactly what the car is) browsing, playing a song from a folder, playing a
 * cloud track that only the service can resolve, and resuming after the queue is gone.
 * The cloud case needs network access to YouTube.
 */
@RunWith(AndroidJUnit4::class)
class CarModePlaybackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var browser: MediaBrowser
    private val artist = "CarModeTestArtist"

    @Before
    fun setUp() = runBlocking {
        val dao = AppDatabase.getDatabase(context).songDao()
        for (n in 1..3) {
            val file = File(context.cacheDir, "car_mode_$n.wav").apply { writeBytes(silenceWav(10)) }
            dao.insertSong(
                Song(
                    id = 0, title = "Car Mode $n", artist = artist, album = "Car Mode",
                    durationMs = 10_000, mediaUri = Uri.fromFile(file).toString(), isDownloaded = true
                )
            )
        }
        browser = withContext(Dispatchers.Main) {
            MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .buildAsync().await()
        }
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) {
            browser.stop()
            browser.clearMediaItems()
            browser.release()
        }
    }

    @Test
    fun rootShowsTheAppsSections() = runBlocking {
        val titles = withContext(Dispatchers.Main) {
            browser.getChildren(PlaybackService.ROOT_ID, 0, 50, null).await().value.orEmpty()
                .map { it.mediaMetadata.title.toString() }
        }
        Log.i(TAG, "Root: $titles")
        assertTrue(titles.containsAll(listOf("Recommended for you", "Discover", "Playlists", "Liked Music")))
    }

    @Test
    fun aSongTappedInAFolderQueuesTheFolder() = runBlocking {
        withContext(Dispatchers.Main) {
            val songs = browser.getChildren("artist_$artist", 0, 50, null).await().value.orEmpty()
            assertTrue(songs.size >= 3)
            assertTrue(songs.all { it.mediaId.contains(PlaybackService.FOLDER_SEPARATOR) })
            browser.setMediaItem(songs[1])
            browser.prepare()
            browser.play()
        }
        val ok = withTimeoutOrNull(10_000) {
            while (withContext(Dispatchers.Main) { browser.mediaItemCount } < 3) delay(200)
            true
        } ?: false
        val (count, index) = withContext(Dispatchers.Main) { browser.mediaItemCount to browser.currentMediaItemIndex }
        Log.i(TAG, "Folder queue: count=$count index=$index")
        assertTrue("Folder was not queued (count=$count)", ok)
        assertEquals(1, index)
    }

    @Test
    fun aCloudTrackPlaysWithTheAppClosed() = runBlocking {
        val videoId = "4IJI6soiQhI"
        val item = MediaItem.Builder()
            .setMediaId("https://www.youtube.com/watch?v=$videoId")
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle("Karma Police").setArtist("Radiohead").setIsPlayable(true)
                    .setExtras(SongMediaExtras.fromSong(Song(id = 0, title = "Karma Police", artist = "Radiohead", album = "", durationMs = 0, mediaUri = "https://www.youtube.com/watch?v=$videoId", youtubeId = videoId)))
                    .build()
            )
            .build()
        withContext(Dispatchers.Main) {
            browser.setMediaItem(item)
            browser.prepare()
            browser.play()
        }
        val playing = withTimeoutOrNull(60_000) {
            while (!withContext(Dispatchers.Main) { browser.isPlaying }) delay(500)
            true
        } ?: false
        val mediaId = withContext(Dispatchers.Main) { browser.currentMediaItem?.mediaId }
        Log.i(TAG, "Cloud playback: playing=$playing mediaId=${mediaId?.take(60)}")
        assertTrue("Cloud track never played with only the service resolving it", playing)
        assertTrue(mediaId?.contains("googlevideo") == true)
    }

    @Test
    fun playWithNothingLoadedResumesTheLastQueue() = runBlocking {
        val played = withContext(Dispatchers.Main) {
            val songs = browser.getChildren("artist_$artist", 0, 50, null).await().value.orEmpty()
            browser.setMediaItem(songs.last())
            browser.prepare()
            browser.play()
            songs.last().mediaMetadata.title.toString()
        }
        withTimeoutOrNull(10_000) { while (!withContext(Dispatchers.Main) { browser.isPlaying }) delay(200) }
        withContext(Dispatchers.Main) {
            browser.pause()
            browser.clearMediaItems()
        }
        delay(500)
        withContext(Dispatchers.Main) { browser.play() }
        val resumed = withTimeoutOrNull(10_000) {
            while (withContext(Dispatchers.Main) { browser.mediaItemCount } == 0) delay(200)
            true
        } ?: false
        val title = withContext(Dispatchers.Main) { browser.currentMediaItem?.mediaMetadata?.title?.toString() }
        Log.i(TAG, "Resumed: $resumed at $title")
        assertTrue("Play with an empty queue did not resume", resumed)
        assertEquals(played, title)
    }

    private fun silenceWav(seconds: Int): ByteArray {
        val rate = 8_000
        val dataSize = rate * 2 * seconds
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray()); write(le32(36 + dataSize)); write("WAVE".toByteArray())
            write("fmt ".toByteArray()); write(le32(16)); write(le16(1)); write(le16(1))
            write(le32(rate)); write(le32(rate * 2)); write(le16(2)); write(le16(16))
            write("data".toByteArray()); write(le32(dataSize)); write(ByteArray(dataSize))
        }.toByteArray()
    }

    private companion object {
        const val TAG = "CarModePlaybackTest"
    }
}
