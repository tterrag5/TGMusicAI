package com.example.tgmusicai.data.local

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Resolves a [com.example.tgmusicai.data.local.entity.Song.mediaUri] to a real file on disk.
 *
 * Songs reach the library by two different routes and carry different URI shapes as a result:
 * a cloud download is written into app-private storage and stored as a `file://` path, while a
 * device track found by [MediaScanner] is stored as the `content://` MediaStore URI for its row.
 * Anything that has to open the audio bytes as a file rather than a stream -- reading or writing
 * ID3/Vorbis tags, measuring loudness -- needs the underlying path either way.
 *
 * `MediaStore.Audio.Media.DATA` is deprecated and on some devices returns a path the app cannot
 * open, so every result is checked for real readability before being returned. Callers get null
 * rather than a path that will fail later.
 */
object LocalAudioFile {

    private const val TAG = "LocalAudioFile"

    /** The readable file behind [mediaUri], or null for a cloud-only track or an unreadable path. */
    fun resolve(context: Context, mediaUri: String): File? {
        val path = resolvePath(context, mediaUri) ?: return null
        val file = File(path)
        return if (file.isFile && file.canRead()) file else null
    }

    /** True when [mediaUri] points at a file this app may rewrite in place without user consent. */
    fun isWritable(context: Context, mediaUri: String): Boolean {
        val file = resolve(context, mediaUri) ?: return false
        if (!file.canWrite()) return false
        // App-private directories are always writable; a shared-storage file may report canWrite()
        // while the platform still rejects the write at scoped-storage level, so a MediaStore-backed
        // track is treated as needing consent regardless of what the file bit says.
        return !mediaUri.startsWith("content://")
    }

    private fun resolvePath(context: Context, mediaUri: String): String? = when {
        mediaUri.startsWith("file://") -> Uri.parse(mediaUri).path
        mediaUri.startsWith("/") -> mediaUri
        mediaUri.startsWith("content://") -> queryMediaStorePath(context, Uri.parse(mediaUri))
        else -> null
    }

    private fun queryMediaStorePath(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (column >= 0) cursor.getString(column) else null
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve a file path for $uri", e)
        null
    }
}
