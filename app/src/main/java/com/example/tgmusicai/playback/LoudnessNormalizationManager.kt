package com.example.tgmusicai.playback

import android.content.Context
import android.util.Log
import com.example.tgmusicai.ai.LoudnessAnalyzer
import com.example.tgmusicai.data.local.AudioTagIo
import com.example.tgmusicai.data.local.LocalAudioFile
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.entity.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Determines how loud each track in the library is, so [ReplayGainAudioProcessor] has a per-track
 * gain to apply.
 *
 * Two sources, tried in that order:
 *
 * 1. The file's own ReplayGain tag, if some other tool already wrote one. Reading it is
 *    near-instant and its measurement was made from the full-rate audio, so it beats anything
 *    measured here.
 * 2. An on-device measurement ([LoudnessAnalyzer]), for the large majority of files that carry no
 *    such tag -- everything ripped, downloaded, or exported by software that does not tag loudness.
 *
 * Measuring decodes two minutes of audio per track, so the backfill works in bounded batches and
 * stops the moment its scope is cancelled rather than running the library to completion in one
 * pass. Like the AI engines, every failure is contained to the track that caused it: a file that
 * cannot be decoded simply keeps a null gain and plays unmodified.
 */
class LoudnessNormalizationManager(
    context: Context,
    private val songDao: SongDao
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How many local tracks still have no loudness value, for the Settings progress readout. */
    val pendingCount: Flow<Int> = songDao.countSongsMissingReplayGain()

    /**
     * Determines and stores [song]'s loudness if it does not already have one. Returns the gain in
     * dB that was stored, or null if the track was skipped or could not be measured.
     */
    suspend fun analyzeIfNeeded(song: Song): Float? = withContext(scope.coroutineContext) {
        if (song.replayGainDb != null) return@withContext null
        val file = LocalAudioFile.resolve(appContext, song.mediaUri) ?: return@withContext null

        try {
            val tagged = AudioTagIo.readReplayGain(file)
            if (tagged?.trackGainDb != null) {
                songDao.updateReplayGain(song.id, tagged.trackGainDb, tagged.trackPeak)
                Log.d(TAG, "Song ${song.id} took its loudness from an existing tag: ${tagged.trackGainDb} dB")
                return@withContext tagged.trackGainDb
            }

            val measured = LoudnessAnalyzer.analyzeFile(file.absolutePath) ?: return@withContext null
            songDao.updateReplayGain(song.id, measured.gainDb, measured.peak)
            Log.d(
                TAG,
                "Song ${song.id} measured at ${measured.integratedLufs} LUFS, gain ${measured.gainDb} dB"
            )
            measured.gainDb
        } catch (e: Throwable) {
            Log.e(TAG, "Loudness analysis failed for song ${song.id}; it will play unmodified", e)
            null
        }
    }

    /**
     * Measures up to [batchSize] untouched tracks, returning how many gained a value. Call it
     * repeatedly to work through a library; it picks up where the last call left off because each
     * measured track stops matching the "missing" query.
     */
    suspend fun runBackfillBatch(batchSize: Int = DEFAULT_BATCH_SIZE): Int =
        withContext(scope.coroutineContext) {
            val pending = try {
                songDao.getSongsMissingReplayGain(batchSize)
            } catch (e: Throwable) {
                Log.e(TAG, "Could not read the loudness backlog", e)
                return@withContext 0
            }

            var analyzed = 0
            for (song in pending) {
                if (!currentCoroutineContext().isActive) break
                if (analyzeIfNeeded(song) != null) analyzed++
            }
            analyzed
        }

    companion object {
        private const val TAG = "LoudnessNormalization"

        /**
         * Tracks per backfill pass. Small enough that a pass finishes in well under a minute on a
         * mid-range device, which keeps the work interruptible and off the critical path of
         * anything the user is doing.
         */
        const val DEFAULT_BATCH_SIZE = 25
    }
}
