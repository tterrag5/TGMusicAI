package com.example.tgmusicai.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.tgmusicai.ai.AudioFingerprinter
import com.example.tgmusicai.ai.PcmDecoder
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.dao.SongFingerprintDao
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.local.entity.SongFingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Recognises a song playing nearby by listening to it, matching against an acoustic index built
 * from the user's own library.
 *
 * What this is and is not, stated plainly because the distinction matters to whether it is worth
 * switching on: it identifies tracks **already in this library**, not arbitrary music. Identifying
 * an unknown song against the world's catalogue requires a commercial fingerprinting service, all
 * of which need a paid API key, and this app deliberately ships without service credentials of any
 * kind. What it does cover is the case where a track you own is playing somewhere and you want to
 * know which one -- and unlike a cloud service, it works with no network and tells nobody what you
 * are listening to.
 *
 * Indexing is opt-in. Measured on-device, the index runs to about 120 rows per second of audio --
 * roughly 25,000 rows and 1-2 MB for a typical song, and several hundred megabytes for a large
 * library. That is a real amount of storage to spend on a feature not everyone wants, so nothing
 * is built until asked and the whole thing can be dropped again.
 *
 * Failures are contained the way the rest of the on-device analysis is: one undecodable track is
 * skipped, and recognition returning "not sure" is a normal outcome rather than an error.
 */
class SongRecognitionManager(
    context: Context,
    private val songDao: SongDao,
    private val fingerprintDao: SongFingerprintDao
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How many tracks currently have a fingerprint, for the progress readout in Settings. */
    val indexedSongCount: Flow<Int> = fingerprintDao.indexedSongCount()

    /** What a recognition attempt produced. */
    sealed interface Recognition {
        /** A track matched, at [positionSeconds] into it, with [confidence] aligned hashes behind it. */
        data class Found(val song: Song, val positionSeconds: Double, val confidence: Int) : Recognition

        /** Nothing in the index matched. The ordinary outcome for music the user does not own. */
        data object NoMatch : Recognition

        /** The index is empty, so nothing could have matched. Distinguished so the UI can say so. */
        data object NotIndexed : Recognition

        /** The microphone could not be used -- permission denied, or another app holds it. */
        data class CannotListen(val reason: String) : Recognition
    }

    /**
     * Records [seconds] of audio and tries to identify it.
     *
     * Requires [Manifest.permission.RECORD_AUDIO]; the caller is expected to have requested it,
     * and this reports the refusal rather than assuming.
     */
    suspend fun recognize(seconds: Int = DEFAULT_LISTEN_SECONDS): Recognition =
        withContext(scope.coroutineContext) {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext Recognition.CannotListen("Microphone permission is needed to listen.")
            }

            if (fingerprintDao.indexedSongIds().isEmpty()) {
                return@withContext Recognition.NotIndexed
            }

            val samples = recordSamples(seconds)
                ?: return@withContext Recognition.CannotListen("Couldn't record from the microphone.")

            val landmarks = AudioFingerprinter.fingerprint(samples)
            if (landmarks.isEmpty()) return@withContext Recognition.NoMatch

            val hashes = landmarks.map { it.hash }.distinct()
            // Chunked because SQLite caps how many parameters one statement may bind, and a few
            // seconds of audio produces far more hashes than that limit.
            val occurrences = HashMap<Int, MutableList<Pair<Long, Int>>>()
            for (chunk in hashes.chunked(SQL_PARAMETER_LIMIT)) {
                if (!currentCoroutineContext().isActive) return@withContext Recognition.NoMatch
                for (row in fingerprintDao.findByHashes(chunk)) {
                    occurrences.getOrPut(row.hash) { mutableListOf() }.add(row.songId to row.frameIndex)
                }
            }

            val match = AudioFingerprinter.bestMatch(landmarks, occurrences)
                ?: return@withContext Recognition.NoMatch
            val song = songDao.getSongById(match.songId) ?: return@withContext Recognition.NoMatch

            Recognition.Found(
                song = song,
                positionSeconds = AudioFingerprinter.framesToSeconds(match.offsetFrames).coerceAtLeast(0.0),
                confidence = match.alignedHashes
            )
        }

    /**
     * Fingerprints up to [batchSize] tracks that are not indexed yet, returning how many were
     * added. Call repeatedly to work through a library.
     */
    suspend fun indexBatch(batchSize: Int = DEFAULT_INDEX_BATCH): Int = withContext(scope.coroutineContext) {
        val alreadyIndexed = try {
            fingerprintDao.indexedSongIds().toSet()
        } catch (e: Throwable) {
            Log.e(TAG, "Could not read the fingerprint index", e)
            return@withContext 0
        }

        val pending = try {
            songDao.getDownloadedSongsSync().filter { it.id !in alreadyIndexed }.take(batchSize)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not list tracks to index", e)
            return@withContext 0
        }

        var indexed = 0
        for (song in pending) {
            if (!currentCoroutineContext().isActive) break
            if (indexSong(song)) indexed++
        }
        indexed
    }

    /** Fingerprints one track. Returns false if it could not be decoded, which is not fatal. */
    suspend fun indexSong(song: Song): Boolean = withContext(scope.coroutineContext) {
        val path = com.example.tgmusicai.data.local.LocalAudioFile.resolve(appContext, song.mediaUri)
            ?.absolutePath
            ?: return@withContext false

        try {
            val samples = PcmDecoder.decodeToMonoPcm16k(path, INDEX_SECONDS_PER_TRACK)
                ?: return@withContext false
            val landmarks = AudioFingerprinter.fingerprint(samples)
            if (landmarks.isEmpty()) return@withContext false

            fingerprintDao.insertAll(
                landmarks.map { SongFingerprint(song.id, it.hash, it.frameIndex) }
            )
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not fingerprint song ${song.id}", e)
            false
        }
    }

    /** Drops the whole index, reclaiming the space it takes. */
    suspend fun clearIndex() = withContext(scope.coroutineContext) {
        try {
            fingerprintDao.deleteAll()
        } catch (e: Throwable) {
            Log.e(TAG, "Could not clear the fingerprint index", e)
        }
    }

    /**
     * Captures [seconds] of mono audio from the microphone as float samples in `[-1, 1]`.
     *
     * [MediaRecorder.AudioSource.UNPROCESSED] where the device offers it, falling back to the
     * default: the usual voice-tuned sources apply noise suppression and automatic gain, both of
     * which are designed to strip out exactly the steady background music this needs to hear.
     */
    @SuppressLint("MissingPermission") // Checked by the caller in recognize(), which is the only entry point.
    private fun recordSamples(seconds: Int): FloatArray? {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioFingerprinter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null

        val bufferSize = maxOf(minBuffer, AudioFingerprinter.SAMPLE_RATE * 2)
        var recorder: AudioRecord? = null
        return try {
            recorder = buildRecorder(bufferSize) ?: return null
            recorder.startRecording()

            val total = AudioFingerprinter.SAMPLE_RATE * seconds
            val output = FloatArray(total)
            val chunk = ShortArray(bufferSize / 2)
            var written = 0
            while (written < total) {
                val read = recorder.read(chunk, 0, minOf(chunk.size, total - written))
                if (read <= 0) break
                for (i in 0 until read) {
                    output[written + i] = chunk[i] / 32768f
                }
                written += read
            }
            if (written == 0) null else output.copyOf(written)
        } catch (e: Throwable) {
            Log.w(TAG, "Recording failed", e)
            null
        } finally {
            try {
                recorder?.stop()
            } catch (_: Throwable) {
            }
            try {
                recorder?.release()
            } catch (_: Throwable) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildRecorder(bufferSize: Int): AudioRecord? {
        for (source in AUDIO_SOURCES) {
            try {
                val recorder = AudioRecord(
                    source,
                    AudioFingerprinter.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
                recorder.release()
            } catch (e: Throwable) {
                Log.d(TAG, "Audio source $source unavailable", e)
            }
        }
        return null
    }

    companion object {
        private const val TAG = "SongRecognition"

        /**
         * Listening window. Long enough that a few seconds of it land on a distinctive passage
         * rather than a drum fill, short enough that the user is not left holding the phone up.
         */
        const val DEFAULT_LISTEN_SECONDS = 8

        /** Tracks per indexing pass, keeping the work interruptible. */
        const val DEFAULT_INDEX_BATCH = 10

        /**
         * How much of each track is fingerprinted.
         *
         * The whole track would be ideal and is what makes the index large. Four minutes covers
         * essentially every song in full while bounding what a long mix or a live set can cost.
         */
        const val INDEX_SECONDS_PER_TRACK = 240

        /**
         * Hashes per lookup query. SQLite refuses a statement with more than 999 bound parameters
         * by default, and a recognition attempt produces several times that.
         *
         * Not private so the instrumented test can chunk by the same number the production lookup
         * uses. A test that hardcoded its own would keep passing if this one were raised past the
         * limit it exists to respect.
         */
        internal const val SQL_PARAMETER_LIMIT = 900

        /**
         * Tried in order. UNPROCESSED bypasses the noise suppression and automatic gain control
         * the voice-oriented sources apply, which treat steady background music as noise to remove
         * -- the opposite of what is wanted here. Not every device implements it, hence the
         * fallback.
         */
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
    }
}
