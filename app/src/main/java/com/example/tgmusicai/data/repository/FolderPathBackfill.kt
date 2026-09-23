package com.example.tgmusicai.data.repository

import android.content.Context
import android.util.Log
import com.example.tgmusicai.data.local.LocalAudioFile
import com.example.tgmusicai.data.local.dao.SongDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Fills in the folder each already-known track lives in, so the folder browser works for a library
 * that predates the column.
 *
 * New tracks get their folder at scan or download time and never reach this. This exists only for
 * rows added before the folder browser did, which for an established library is most of them.
 *
 * Resolving one track is a single content-provider lookup, so this is far cheaper than the
 * loudness pass and can work in much larger batches. It still yields between batches and stops on
 * cancellation rather than running to completion regardless.
 */
class FolderPathBackfill(
    context: Context,
    private val songDao: SongDao
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Resolves and stores the folder for up to [batchSize] tracks, returning how many were
     * updated. Returns 0 when there is nothing left to do, which is how the caller knows to stop.
     */
    suspend fun runBatch(batchSize: Int = DEFAULT_BATCH_SIZE): Int = withContext(scope.coroutineContext) {
        val pending = try {
            songDao.getSongsMissingFolderPath(batchSize)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not read the folder backlog", e)
            return@withContext 0
        }
        if (pending.isEmpty()) return@withContext 0

        var updated = 0
        for (song in pending) {
            if (!currentCoroutineContext().isActive) break
            val folder = try {
                LocalAudioFile.resolve(appContext, song.mediaUri)?.parent
            } catch (e: Throwable) {
                Log.w(TAG, "Could not resolve a folder for song ${song.id}", e)
                null
            }
            try {
                // A track whose file cannot be found is marked with a placeholder rather than left
                // null. Leaving it null would put it back in the next batch, forever, and every
                // pass would re-query the provider for a file that is not there.
                songDao.updateFolderPath(song.id, folder ?: UNKNOWN_FOLDER)
                if (folder != null) updated++
            } catch (e: Throwable) {
                Log.w(TAG, "Could not store the folder for song ${song.id}", e)
            }
        }
        updated
    }

    /** Works through the whole backlog, batch by batch. */
    suspend fun runToCompletion() {
        while (currentCoroutineContext().isActive) {
            val updated = try {
                runBatch()
            } catch (e: Throwable) {
                Log.e(TAG, "Folder backfill batch failed; stopping", e)
                break
            }
            if (updated == 0) break
        }
    }

    companion object {
        private const val TAG = "FolderPathBackfill"
        private const val DEFAULT_BATCH_SIZE = 200

        /**
         * Marks a track whose file could not be located, so it is not retried on every pass. The
         * folder browser filters it out, matching what the user sees today: a track with no
         * reachable file has no folder to show it in.
         */
        const val UNKNOWN_FOLDER = ""
    }
}
