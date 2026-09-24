package com.example.tgmusicai.data.repository

import android.content.Context
import android.util.Log
import com.example.tgmusicai.data.local.AudioTagIo
import com.example.tgmusicai.data.local.LocalAudioFile
import com.example.tgmusicai.data.local.dao.SongDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Reads the genre out of each already-known track's file, so the Library's tag browser works for a
 * library that predates the column rather than only for newly scanned tracks.
 *
 * Newly scanned tracks get their genre at scan time and never reach this. Reading one is a tag
 * parse rather than a decode, so it is far cheaper than the loudness pass, but it is still file
 * I/O per track: it works in batches, yields between them, and stops on cancellation instead of
 * running to completion regardless.
 */
class GenreBackfill(
    context: Context,
    private val songDao: SongDao
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Reads and stores the genre for up to [batchSize] tracks, returning how many it got through.
     * Returns 0 only when there is nothing left to do, which is how the caller knows to stop --
     * counting *found* genres instead would end the pass at the first batch of untagged files.
     */
    suspend fun runBatch(batchSize: Int = DEFAULT_BATCH_SIZE): Int = withContext(scope.coroutineContext) {
        val pending = try {
            songDao.getSongsMissingGenre(batchSize)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not read the genre backlog", e)
            return@withContext 0
        }
        if (pending.isEmpty()) return@withContext 0

        var processed = 0
        for (song in pending) {
            if (!currentCoroutineContext().isActive) break
            val genre = try {
                LocalAudioFile.resolve(appContext, song.mediaUri)
                    ?.let { AudioTagIo.readTags(it)?.genre }
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            } catch (e: Throwable) {
                Log.w(TAG, "Could not read a genre for song ${song.id}", e)
                null
            }
            try {
                // A track with no genre tag is marked with a placeholder rather than left null.
                // Leaving it null would put it back in the next batch, forever, and every pass
                // would re-open a file that has nothing to give. The tag index drops the
                // placeholder, so an untagged track simply carries no genre tag.
                songDao.updateGenre(song.id, genre ?: NO_GENRE)
                processed++
            } catch (e: Throwable) {
                Log.w(TAG, "Could not store the genre for song ${song.id}", e)
            }
        }
        processed
    }

    /** Works through the whole backlog, batch by batch. */
    suspend fun runToCompletion() {
        while (currentCoroutineContext().isActive) {
            val processed = try {
                runBatch()
            } catch (e: Throwable) {
                Log.e(TAG, "Genre backfill batch failed; stopping", e)
                break
            }
            if (processed == 0) break
        }
    }

    companion object {
        private const val TAG = "GenreBackfill"

        /**
         * Smaller than the folder backfill this replaces: that one did a content-provider lookup
         * per track, this one opens and parses the file itself.
         */
        private const val DEFAULT_BATCH_SIZE = 50

        /**
         * Marks a track whose file carries no genre, so it is not re-read on every pass. Blank
         * rather than a word, because the tag index treats it as no tag at all and nothing should
         * ever display it.
         */
        const val NO_GENRE = ""
    }
}
