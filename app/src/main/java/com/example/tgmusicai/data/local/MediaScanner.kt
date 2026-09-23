package com.example.tgmusicai.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.entity.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * Reads the device's local music library via [MediaStore] and mirrors it into the Room `songs`
 * table so the rest of the app only ever has to query Room, never MediaStore directly.
 * Stateless singleton -- holds no data itself, just performs the one-shot scan.
 */
object MediaScanner {

    private const val TAG = "MediaScanner"

    /**
     * Scans for local music files using MediaStore (which encompasses files in Music dir and others),
     * passes raw titles through [AiMetadataCleaner], and inserts or updates them in Room database.
     */
    suspend fun scanMediaStore(context: Context, songDao: SongDao) = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val title = it.getString(titleColumn) ?: "Unknown Title"
                val artist = it.getString(artistColumn) ?: "Unknown Artist"
                val album = it.getString(albumColumn) ?: "Unknown Album"
                val duration = it.getLong(durationColumn)
                val path = it.getString(dataColumn)

                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())

                // MediaStore sometimes reports 0 duration for certain files/codecs; fall back to
                // decoding the file's own metadata header via MediaMetadataRetriever for a real value.
                var finalDuration = duration
                if (finalDuration <= 0 && path != null) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(path)
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        finalDuration = durationStr?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting duration for $path", e)
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {}
                    }
                }

                try {
                    // Media scans run repeatedly (every app start, plus after each cloud download),
                    // so check whether this file is already known BEFORE doing any cleaning work.
                    // The check used to come after, which meant every song in the library was
                    // re-cleaned on every scan and the result thrown away for all but new files.
                    val existing = songDao.getSongByUri(uri.toString())
                    if (existing == null) {
                        // Raw MediaStore titles are often messy ("Artist - Song (Official Audio)");
                        // run them through the cleaner to strip noise and split out
                        // artist/producer when possible.
                        val cleaned = AiMetadataCleaner.clean(
                            rawTitle = title,
                            rawArtist = if (artist != "Unknown Artist") artist else null
                        )

                        songDao.insertSong(
                            Song(
                                title = cleaned.cleanTitle,
                                artist = cleaned.artist ?: artist,
                                album = album,
                                durationMs = finalDuration,
                                mediaUri = uri.toString(),
                                producer = cleaned.producer
                            )
                        )
                        Log.d(TAG, "Added song: $title")
                    }
                    // Song already known: intentionally a no-op. Re-cleaning and overwriting here
                    // would clobber any user edits (pinned, lyrics, artwork) made since.
                } catch (e: Exception) {
                    Log.e(TAG, "Error inserting song $title", e)
                }
            }
        }
    }
}
