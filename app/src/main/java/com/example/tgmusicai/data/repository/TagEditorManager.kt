package com.example.tgmusicai.data.repository

import android.app.PendingIntent
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.tgmusicai.data.local.AudioTagIo
import com.example.tgmusicai.data.local.LocalAudioFile
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.entity.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies a metadata edit to a local track: rewrites the file's own tags, updates the library row,
 * and tells MediaStore the file changed.
 *
 * All three steps matter. Writing only the database leaves the file wrong, so the edit vanishes on
 * any re-scan and does not follow the file anywhere else. Writing only the file leaves the app
 * showing stale metadata until a scan happens to notice. Skipping the MediaStore notification
 * leaves every *other* app on the device showing the old tags.
 */
class TagEditorManager(
    private val context: Context,
    private val songDao: SongDao
) {
    private val appContext = context.applicationContext

    /** What happened to an edit, so the UI can say something specific rather than "failed". */
    sealed interface Result {
        /** The file and the library row were both updated. */
        data object Success : Result

        /**
         * The file is in shared storage and the system needs the user to approve the write. The
         * caller must launch [request] and retry once it is granted.
         */
        data class NeedsPermission(val request: PendingIntent) : Result

        /** The track has no local file to edit -- a cloud-only entry. */
        data object NotALocalFile : Result

        /** The file exists but could not be rewritten, e.g. an unsupported container. */
        data class Failed(val reason: String) : Result
    }

    /** Reads the tags currently stored in [song]'s file, or null if it has none this app can parse. */
    suspend fun readTags(song: Song): AudioTagIo.EditableTags? = withContext(Dispatchers.IO) {
        val file = LocalAudioFile.resolve(appContext, song.mediaUri) ?: return@withContext null
        AudioTagIo.readTags(file)
    }

    /**
     * Writes [tags] into [song]'s file and mirrors the result into the library.
     *
     * At `targetSdk 37` the app has no blanket write access to shared storage, so a file that came
     * from MediaStore needs per-file consent. Rather than failing, this returns the
     * [Result.NeedsPermission] intent the system wants shown; once the user approves it, calling
     * this again succeeds. Files the app downloaded itself live in its own storage and skip that
     * round trip entirely.
     */
    suspend fun writeTags(song: Song, tags: AudioTagIo.EditableTags): Result =
        withContext(Dispatchers.IO) {
            val file = LocalAudioFile.resolve(appContext, song.mediaUri)
                ?: return@withContext Result.NotALocalFile

            if (!LocalAudioFile.isWritable(appContext, song.mediaUri)) {
                requestWriteConsent(song.mediaUri)?.let { return@withContext Result.NeedsPermission(it) }
            }

            val wrote = AudioTagIo.writeTags(file, tags)
            if (!wrote) {
                return@withContext Result.Failed("This file's format can't be rewritten.")
            }

            try {
                songDao.updateEditedMetadata(
                    id = song.id,
                    title = tags.title?.takeIf { it.isNotBlank() } ?: song.title,
                    artist = tags.artist?.takeIf { it.isNotBlank() } ?: song.artist,
                    album = tags.album?.takeIf { it.isNotBlank() } ?: song.album,
                    // The editor has no producer field -- it is this app's own notion, derived by
                    // AiMetadataCleaner rather than read from a tag -- so a manual edit leaves it
                    // as it was instead of clearing it.
                    producer = song.producer
                )
                // Genre is stored on the song as well as in the file, because the tag browser
                // groups the whole library by it. Writing only the file would leave the browser
                // showing the old genre until a rescan.
                songDao.updateGenre(song.id, tags.genre?.takeIf { it.isNotBlank() } ?: song.genre)
            } catch (e: Throwable) {
                Log.e(TAG, "Wrote tags to ${file.name} but could not update the library row", e)
                return@withContext Result.Failed("Saved to the file, but the library didn't update.")
            }

            notifyMediaStore(file.absolutePath)
            Result.Success
        }

    /**
     * Asks the system for permission to modify one MediaStore-owned file.
     *
     * Only exists from Android 11 onward. Below that, the legacy storage permission the manifest
     * already declares covers the write, so there is nothing to ask for.
     */
    private fun requestWriteConsent(mediaUri: String): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!mediaUri.startsWith("content://")) return null
        return try {
            MediaStore.createWriteRequest(
                appContext.contentResolver,
                listOf(Uri.parse(mediaUri))
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Could not build a write-consent request for $mediaUri", e)
            null
        }
    }

    /** Re-indexes the rewritten file so every other app on the device sees the new tags too. */
    private fun notifyMediaStore(path: String) {
        try {
            MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not ask MediaStore to re-scan $path", e)
        }
    }

    private companion object {
        const val TAG = "TagEditorManager"
    }
}
