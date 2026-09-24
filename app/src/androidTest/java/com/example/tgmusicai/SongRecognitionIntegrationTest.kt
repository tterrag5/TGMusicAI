package com.example.tgmusicai

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tgmusicai.ai.AudioFingerprinter
import com.example.tgmusicai.ai.PcmDecoder
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.local.entity.SongFingerprint
import com.example.tgmusicai.data.repository.SongRecognitionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Exercises song recognition on a real device, end to end, minus the microphone.
 *
 * The unit tests cover the fingerprinting mathematics against synthetic float arrays, and the
 * emulator run covered the UI. Between those sits the part neither reaches: audio actually being
 * decoded by Android's own `MediaExtractor`/`MediaCodec`, landmarks stored in and read back out of
 * a real SQLite database, and a match found through the real DAO -- including the chunking that
 * keeps hash lookups under SQLite's bound-parameter limit. A failure anywhere in there would leave
 * the unit tests green and the feature broken.
 *
 * The microphone genuinely cannot be exercised here: an emulator captures silence, and a real
 * device would need a controlled acoustic environment. That step is the one remaining gap, and it
 * is a thin one -- it produces a `FloatArray`, which is exactly what this test supplies directly.
 */
@RunWith(AndroidJUnit4::class)
class SongRecognitionIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        // In-memory, so the test cannot disturb the real library on the device, but still a real
        // SQLite engine running the real schema, indexes and queries.
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        tempFiles.forEach { runCatching { it.delete() } }
    }

    @Test
    fun anExcerptOfADecodedFileMatchesTheIndexedTrack() = runBlocking {
        val track = writeWav("indexed_track.wav", seconds = 30.0, seed = 11)

        // Decode through the real platform codec, exactly as indexing does in production.
        val decoded = PcmDecoder.decodeToMonoPcm16k(track.absolutePath, maxDurationSec = 240)
        assertNotNull("The platform decoder returned nothing for a valid WAV file", decoded)
        assertTrue("Decoded far less audio than the file contains", decoded!!.size > 16000 * 20)

        val landmarks = AudioFingerprinter.fingerprint(decoded)
        assertTrue("Real decoded audio produced no landmarks", landmarks.isNotEmpty())

        val dao = database.songFingerprintDao()
        dao.insertAll(landmarks.map { SongFingerprint(songId = 1L, hash = it.hash, frameIndex = it.frameIndex) })
        assertEquals(listOf(1L), dao.indexedSongIds())

        // A ten-second excerpt starting partway in, deliberately not on a frame boundary -- a real
        // recording never starts aligned to the analysis grid.
        val start = (16000 * 9.0).toInt() + 137
        val excerpt = decoded.copyOfRange(start, start + 16000 * 10)

        val match = matchThroughDatabase(excerpt)

        assertNotNull("A real decoded excerpt failed to match its own indexed track", match)
        assertEquals(1L, match!!.songId)
        assertEquals(9.0, AudioFingerprinter.framesToSeconds(match.offsetFrames), 1.5)
    }

    @Test
    fun audioFromADifferentTrackDoesNotMatch() = runBlocking {
        // The failure that matters most in the field: confidently naming the wrong song. Verified
        // here against the real index rather than an in-memory map.
        val indexed = writeWav("other_indexed.wav", seconds = 30.0, seed = 21)
        val unrelated = writeWav("unrelated.wav", seconds = 12.0, seed = 987)

        val indexedPcm = PcmDecoder.decodeToMonoPcm16k(indexed.absolutePath, 240)!!
        val dao = database.songFingerprintDao()
        dao.insertAll(
            AudioFingerprinter.fingerprint(indexedPcm)
                .map { SongFingerprint(songId = 5L, hash = it.hash, frameIndex = it.frameIndex) }
        )

        val unrelatedPcm = PcmDecoder.decodeToMonoPcm16k(unrelated.absolutePath, 240)!!

        assertNull(
            "Unrelated audio was matched to an indexed track",
            matchThroughDatabase(unrelatedPcm)
        )
    }

    @Test
    fun theRightTrackWinsAmongSeveralIndexed() = runBlocking {
        val tracks = listOf(31, 32, 33).mapIndexed { index, seed ->
            val id = (index + 1).toLong()
            val pcm = PcmDecoder.decodeToMonoPcm16k(
                writeWav("multi_$seed.wav", seconds = 25.0, seed = seed).absolutePath,
                240
            )!!
            database.songFingerprintDao().insertAll(
                AudioFingerprinter.fingerprint(pcm)
                    .map { SongFingerprint(songId = id, hash = it.hash, frameIndex = it.frameIndex) }
            )
            id to pcm
        }

        // Take the excerpt from the middle track; it has to beat the other two, not merely score.
        val (expectedId, pcm) = tracks[1]
        val start = (16000 * 6.0).toInt()
        val excerpt = pcm.copyOfRange(start, start + 16000 * 8)

        val match = matchThroughDatabase(excerpt)

        assertNotNull(match)
        assertEquals(expectedId, match!!.songId)
    }

    @Test
    fun hashLookupSurvivesMoreHashesThanSqliteWillBindAtOnce() = runBlocking {
        // SQLite refuses a statement with more than 999 bound parameters by default, so the
        // production lookup chunks its hashes. This test drives that same chunk size against the
        // real database and checks every row comes back.
        //
        // The hashes are part real and part synthetic, on purpose. Fingerprinting 30 seconds of
        // this file's synthetic music yields thousands of landmarks but only ~660 *distinct*
        // hashes: the fixture cycles a ten-note scale over two octaves, so its landmark pairs
        // repeat constantly in a way real music's do not. Seeding the table from decoded audio
        // alone would therefore never reach the limit this test exists to cross -- it would prove
        // only that a sub-limit query works. The real landmarks keep the decode-and-fingerprint
        // path covered; the synthetic hashes take the count past the limit.
        val dao = database.songFingerprintDao()
        val pcm = PcmDecoder.decodeToMonoPcm16k(
            writeWav("chunking.wav", seconds = 30.0, seed = 41).absolutePath,
            240
        )!!
        val realRows = AudioFingerprinter.fingerprint(pcm)
            .map { SongFingerprint(songId = 9L, hash = it.hash, frameIndex = it.frameIndex) }
            .distinctBy { it.hash }
        assertTrue("Decoding produced no landmarks at all", realRows.isNotEmpty())

        val target = SongRecognitionManager.SQL_PARAMETER_LIMIT * 3
        val usedHashes = realRows.map { it.hash }.toMutableSet()
        val syntheticRows = mutableListOf<SongFingerprint>()
        var candidate = Int.MIN_VALUE / 2
        while (realRows.size + syntheticRows.size < target) {
            if (usedHashes.add(candidate)) {
                syntheticRows += SongFingerprint(songId = 9L, hash = candidate, frameIndex = syntheticRows.size)
            }
            candidate++
        }

        val allRows = realRows + syntheticRows
        dao.insertAll(allRows)

        val hashes = allRows.map { it.hash }
        assertTrue("Fewer hashes than SQLite's parameter limit; nothing would be chunked", hashes.size > 999)

        val found = hashes
            .chunked(SongRecognitionManager.SQL_PARAMETER_LIMIT)
            .flatMap { dao.findByHashes(it) }

        // Every row, not merely a non-empty result: a chunking bug that dropped the last partial
        // chunk would still return plenty of rows.
        assertEquals("Chunked lookup lost rows", allRows.size, found.size)
    }

    @Test
    fun clearingTheIndexRemovesEverything() = runBlocking {
        val pcm = PcmDecoder.decodeToMonoPcm16k(
            writeWav("clearable.wav", seconds = 12.0, seed = 51).absolutePath,
            240
        )!!
        val dao = database.songFingerprintDao()
        dao.insertAll(
            AudioFingerprinter.fingerprint(pcm)
                .map { SongFingerprint(songId = 3L, hash = it.hash, frameIndex = it.frameIndex) }
        )
        assertTrue(dao.indexedSongIds().isNotEmpty())

        dao.deleteAll()

        // The user is told they can reclaim the space; this is that promise.
        assertTrue("Clearing the index left rows behind", dao.indexedSongIds().isEmpty())
    }

    @Test
    fun reindexingTheSameTrackDoesNotDuplicateRows() = runBlocking {
        // An indexing pass interrupted partway through is re-run from the start, so the composite
        // primary key has to absorb the repeat rather than doubling the table.
        val pcm = PcmDecoder.decodeToMonoPcm16k(
            writeWav("repeat.wav", seconds = 12.0, seed = 61).absolutePath,
            240
        )!!
        val rows = AudioFingerprinter.fingerprint(pcm)
            .map { SongFingerprint(songId = 7L, hash = it.hash, frameIndex = it.frameIndex) }

        val dao = database.songFingerprintDao()
        dao.insertAll(rows)
        val afterFirst = dao.findByHashes(rows.map { it.hash }.distinct().take(900)).size
        dao.insertAll(rows)
        val afterSecond = dao.findByHashes(rows.map { it.hash }.distinct().take(900)).size

        assertEquals("Re-indexing duplicated rows", afterFirst, afterSecond)
    }

    /** Runs a query fingerprint against the real database, the way `SongRecognitionManager` does. */
    private suspend fun matchThroughDatabase(samples: FloatArray): AudioFingerprinter.Match? {
        val landmarks = AudioFingerprinter.fingerprint(samples)
        val occurrences = HashMap<Int, MutableList<Pair<Long, Int>>>()
        for (chunk in landmarks.map { it.hash }.distinct().chunked(900)) {
            for (row in database.songFingerprintDao().findByHashes(chunk)) {
                occurrences.getOrPut(row.hash) { mutableListOf() }.add(row.songId to row.frameIndex)
            }
        }
        return AudioFingerprinter.bestMatch(landmarks, occurrences)
    }

    /**
     * Writes a 16-bit PCM WAV of synthetic music into app storage.
     *
     * A real container rather than a raw float array, so the platform decoder is genuinely
     * involved. WAV specifically because it is decodable everywhere without depending on which
     * codecs a given device ships.
     */
    private fun writeWav(name: String, seconds: Double, seed: Int, sampleRate: Int = 44100): File {
        val samples = syntheticMusic(seconds, seed, sampleRate)
        val file = File(context.cacheDir, name).also { tempFiles.add(it) }
        if (file.exists()) file.delete()

        RandomAccessFile(file, "rw").use { out ->
            val dataBytes = samples.size * 2
            fun writeAscii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
            fun writeIntLe(value: Int) = out.write(
                byteArrayOf(
                    (value and 0xFF).toByte(),
                    ((value shr 8) and 0xFF).toByte(),
                    ((value shr 16) and 0xFF).toByte(),
                    ((value shr 24) and 0xFF).toByte()
                )
            )
            fun writeShortLe(value: Int) = out.write(
                byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
            )

            writeAscii("RIFF"); writeIntLe(36 + dataBytes); writeAscii("WAVE")
            writeAscii("fmt "); writeIntLe(16)
            writeShortLe(1)            // PCM
            writeShortLe(1)            // mono
            writeIntLe(sampleRate)
            writeIntLe(sampleRate * 2) // byte rate
            writeShortLe(2)            // block align
            writeShortLe(16)           // bits per sample
            writeAscii("data"); writeIntLe(dataBytes)

            val pcm = ByteArray(dataBytes)
            for (i in samples.indices) {
                val value = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                pcm[i * 2] = (value and 0xFF).toByte()
                pcm[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            out.write(pcm)
        }
        return file
    }

    /**
     * Chord changes over a beat, the same shape the unit tests use.
     *
     * A pure tone would prove nothing: its spectrogram is constant, so every excerpt of it matches
     * every other excerpt. Recognition depends on the spectrum varying over time.
     */
    private fun syntheticMusic(seconds: Double, seed: Int, sampleRate: Int): FloatArray {
        val random = Random(seed)
        val total = (seconds * sampleRate).toInt()
        val output = FloatArray(total)
        val scale = listOf(220.0, 246.9, 261.6, 293.7, 329.6, 349.2, 392.0, 440.0, 493.9, 523.3)
        val noteLength = (sampleRate * 0.5).toInt()
        var position = 0

        while (position < total) {
            val roots = List(3) { scale[random.nextInt(scale.size)] * (1 shl random.nextInt(2)) }
            val end = minOf(position + noteLength, total)
            for (i in position until end) {
                val t = i.toDouble() / sampleRate
                var value = 0.0
                for (root in roots) {
                    for (harmonic in 1..4) {
                        value += sin(2.0 * PI * root * harmonic * t) / (harmonic * roots.size)
                    }
                }
                if (i % (sampleRate / 2) < 200) value += 0.4 * random.nextDouble(-1.0, 1.0)
                val progress = (i - position).toDouble() / noteLength
                output[i] = (value * 0.5 * (1.0 - progress * 0.6)).toFloat()
            }
            position = end
        }
        return output
    }
}
