package com.example.tgmusicai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tgmusicai.data.local.AudioTagIo
import com.example.tgmusicai.data.local.LocalAudioFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the tag reader and writer on a real device.
 *
 * jaudiotagger is a desktop Java library. Upstream reaches for `java.awt` and `java.nio.file`
 * classes Android does not ship, which is why the Adonai fork is used instead -- but "it compiles
 * and the unit tests pass on the JVM" says nothing about whether it works on Android, because the
 * unit tests run on the desktop JVM where those classes exist. A `NoClassDefFoundError` here would
 * be invisible until a user tried to edit a tag.
 *
 * So this deliberately does the real thing: writes into a real file in app storage and reads it
 * back through the real library.
 */
@RunWith(AndroidJUnit4::class)
class AudioTagIoInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File

    @Before
    fun setUp() {
        // A minimal but structurally valid MP3: a silent frame repeated enough times that
        // jaudiotagger accepts it as an audio file it can attach a tag to.
        file = File(context.cacheDir, "taggable.mp3")
        if (file.exists()) file.delete()
        file.writeBytes(silentMp3(frames = 200))
    }

    @After
    fun tearDown() {
        runCatching { file.delete() }
    }

    @Test
    fun tagsWrittenOnDeviceCanBeReadBack() {
        val written = AudioTagIo.writeTags(
            file,
            AudioTagIo.EditableTags(
                title = "Edited Title",
                artist = "Edited Artist",
                album = "Edited Album",
                albumArtist = "Edited Album Artist",
                genre = "Ambient",
                year = "2021",
                trackNumber = "4"
            )
        )
        assertTrue("Writing tags failed on device", written)

        val read = AudioTagIo.readTags(file)
        assertNotNull("Tags could not be read back after writing", read)
        assertEquals("Edited Title", read!!.title)
        assertEquals("Edited Artist", read.artist)
        assertEquals("Edited Album", read.album)
        assertEquals("Ambient", read.genre)
        assertEquals("2021", read.year)
        assertEquals("4", read.trackNumber)
    }

    @Test
    fun aPartialEditLeavesUntouchedFieldsAlone() {
        // The editor sends null for fields it is not changing. If those cleared the tag instead,
        // opening the dialog and saving would quietly strip metadata the user never looked at.
        AudioTagIo.writeTags(
            file,
            AudioTagIo.EditableTags(title = "Original", artist = "Original Artist", album = "Original Album")
        )

        AudioTagIo.writeTags(file, AudioTagIo.EditableTags(title = "New Title Only"))

        val read = AudioTagIo.readTags(file)!!
        assertEquals("New Title Only", read.title)
        assertEquals("Original Artist", read.artist)
        assertEquals("Original Album", read.album)
    }

    @Test
    fun anEmptyValueClearsThatFieldRatherThanWritingBlankText() {
        AudioTagIo.writeTags(file, AudioTagIo.EditableTags(title = "Has Title", genre = "Rock"))
        AudioTagIo.writeTags(file, AudioTagIo.EditableTags(genre = ""))

        val read = AudioTagIo.readTags(file)!!
        assertEquals("Has Title", read.title)
        assertNull("Clearing a field left a blank value behind", read.genre)
    }

    @Test
    fun readingReplayGainFromAFileWithoutItReturnsNull() {
        // The overwhelmingly common case, and the one that sends a track down the on-device
        // measurement path. It must report "absent", not throw or invent a value.
        AudioTagIo.writeTags(file, AudioTagIo.EditableTags(title = "No Gain Here"))
        assertNull(AudioTagIo.readReplayGain(file))
    }

    @Test
    fun aFileThatIsNotAudioIsRejectedWithoutThrowing() {
        // Tag parsing runs over arbitrary user files, a share of which are malformed. One bad file
        // must cost only its own metadata.
        val junk = File(context.cacheDir, "not_audio.mp3").apply {
            writeBytes(ByteArray(4096) { it.toByte() })
        }
        try {
            assertNull(AudioTagIo.readTags(junk))
            assertNull(AudioTagIo.readReplayGain(junk))
            assertFalse(AudioTagIo.writeTags(junk, AudioTagIo.EditableTags(title = "Nope")))
        } finally {
            junk.delete()
        }
    }

    @Test
    fun localAudioFileResolvesAFilePathAndReportsWritability() {
        // The other half of editing: finding the file at all. App-private storage is writable
        // without the system consent flow, which is what makes downloaded tracks editable directly.
        val resolved = LocalAudioFile.resolve(context, "file://${file.absolutePath}")
        assertNotNull("A file:// URI in app storage did not resolve", resolved)
        assertEquals(file.absolutePath, resolved!!.absolutePath)
        assertTrue(LocalAudioFile.isWritable(context, "file://${file.absolutePath}"))

        assertNull("A non-existent path resolved to something", LocalAudioFile.resolve(context, "file:///nope/missing.mp3"))
        assertNull("A cloud watch URL resolved to a file", LocalAudioFile.resolve(context, "https://youtube.com/watch?v=x"))
    }

    /**
     * Builds an MP3 of silent MPEG-1 Layer III frames.
     *
     * Synthesised rather than bundled so the test carries no binary fixture. Each frame is a valid
     * 4-byte header (MPEG-1 Layer III, 128 kbps, 44.1 kHz, mono) followed by zeroed payload, which
     * is enough structure for a tag library to parse and rewrite.
     */
    private fun silentMp3(frames: Int): ByteArray {
        val frameLength = 417 // 144 * 128000 / 44100, the standard frame size at this bitrate
        val output = ByteArray(frameLength * frames)
        for (frame in 0 until frames) {
            val base = frame * frameLength
            output[base] = 0xFF.toByte()      // sync
            output[base + 1] = 0xFB.toByte()  // MPEG-1, Layer III, no CRC
            output[base + 2] = 0x90.toByte()  // 128 kbps, 44.1 kHz
            output[base + 3] = 0xC0.toByte()  // mono
        }
        return output
    }
}
