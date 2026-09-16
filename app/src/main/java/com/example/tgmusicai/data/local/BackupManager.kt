package com.example.tgmusicai.data.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.example.tgmusicai.data.local.dao.AlarmDao
import com.example.tgmusicai.data.local.dao.PlaylistDao
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.dao.SongStatsDao
import com.example.tgmusicai.data.local.entity.Alarm
import com.example.tgmusicai.data.local.entity.AlarmToneType
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistSongCrossRef
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.local.entity.SongStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Handles portable backup (.tgmusic zip) and restore operations for TGMusicAI.
 * Exports database manifest (JSON) and local audio files into a zip package,
 * and restores them back into Room database and local storage without re-downloading.
 */
class BackupManager(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val songStatsDao: SongStatsDao,
    private val alarmDao: AlarmDao
) {
    private val TAG = "BackupManager"

    /**
     * Exports all database records and local downloaded audio files into a .tgmusic zip file.
     * Format: a `manifest.json` (songs/playlists/crossRefs/stats/alarms as JSON arrays) plus an
     * `audio/<fileName>` entry for every locally-downloaded song, so restoring on a fresh device
     * doesn't require re-downloading anything from YouTube. There is no manifest schema version
     * field -- [importBackup] assumes the shape produced here, so changing a key name below must
     * be paired with a matching change in [importBackup]'s `optString`/`optLong` reads.
     */
    suspend fun exportBackup(context: Context): File = withContext(Dispatchers.IO) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir

        if (!downloadsDir.exists()) downloadsDir.mkdirs()

        val backupFile = File(downloadsDir, "TGMusic_Backup_${System.currentTimeMillis()}.tgmusic")
        val zipOut = ZipOutputStream(BufferedOutputStream(FileOutputStream(backupFile)))

        try {
            val songs = songDao.getAllSongsList()
            val playlists = playlistDao.getAllPlaylistsList()
            val alarms = alarmDao.getAllAlarmsList()

            val manifestJson = JSONObject()

            // Songs array
            val songsArray = JSONArray()
            val musicFilesToZip = mutableListOf<Pair<String, File>>()

            for (song in songs) {
                val songObj = JSONObject()
                songObj.put("id", song.id)
                songObj.put("title", song.title)
                songObj.put("artist", song.artist)
                songObj.put("album", song.album)
                songObj.put("durationMs", song.durationMs)
                songObj.put("mediaUri", song.mediaUri)
                songObj.put("producer", song.producer ?: "")
                songObj.put("lyrics", song.lyrics ?: "")
                songObj.put("artworkUri", song.artworkUri ?: "")
                songObj.put("youtubeId", song.youtubeId ?: "")
                songObj.put("isDownloaded", song.isDownloaded)
                songObj.put("isPinned", song.isPinned)

                if (song.isDownloaded && song.mediaUri.startsWith("file:")) {
                    try {
                        val file = File(Uri.parse(song.mediaUri).path ?: "")
                        if (file.exists() && file.isFile) {
                            val fileName = file.name
                            songObj.put("fileName", fileName)
                            musicFilesToZip.add("audio/$fileName" to file)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve file for backup: ${song.mediaUri}", e)
                    }
                }
                songsArray.put(songObj)
            }
            manifestJson.put("songs", songsArray)

            // Playlists & Playlist CrossRefs
            val playlistsArray = JSONArray()
            val crossRefsArray = JSONArray()

            for (playlist in playlists) {
                val pObj = JSONObject()
                pObj.put("playlistId", playlist.playlistId)
                pObj.put("name", playlist.name)
                pObj.put("description", playlist.description ?: "")
                pObj.put("createdAt", playlist.createdAt)
                pObj.put("isPinned", playlist.isPinned)
                pObj.put("isSmart", playlist.isSmart)
                playlistsArray.put(pObj)

                val withSongs = playlistDao.getPlaylistWithSongsSync(playlist.playlistId)
                withSongs?.songs?.forEach { s ->
                    val cObj = JSONObject()
                    cObj.put("playlistId", playlist.playlistId)
                    cObj.put("songId", s.id)
                    crossRefsArray.put(cObj)
                }
            }
            manifestJson.put("playlists", playlistsArray)
            manifestJson.put("crossRefs", crossRefsArray)

            // Stats array
            val statsArray = JSONArray()
            val mostPlayed = songStatsDao.getMostPlayedStatsSync(1000)
            for (st in mostPlayed) {
                val stObj = JSONObject()
                stObj.put("songId", st.songId)
                stObj.put("playCount", st.playCount)
                stObj.put("lastPlayedAt", st.lastPlayedAt ?: 0L)
                statsArray.put(stObj)
            }
            manifestJson.put("stats", statsArray)

            // Alarms array
            val alarmsArray = JSONArray()
            for (al in alarms) {
                val aObj = JSONObject()
                aObj.put("id", al.id)
                aObj.put("timeInMillis", al.timeInMillis)
                aObj.put("repeatDays", al.repeatDays)
                aObj.put("toneType", al.toneType.name)
                aObj.put("toneUriOrId", al.toneUriOrId)
                aObj.put("isEnabled", al.isEnabled)
                aObj.put("snoozeMinutes", al.snoozeMinutes)
                aObj.put("label", al.label)
                alarmsArray.put(aObj)
            }
            manifestJson.put("alarms", alarmsArray)

            // Write manifest.json
            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write(manifestJson.toString(2).toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // Write audio files
            val buffer = ByteArray(8192)
            for ((entryPath, audioFile) in musicFilesToZip) {
                zipOut.putNextEntry(ZipEntry(entryPath))
                BufferedInputStream(FileInputStream(audioFile)).use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        zipOut.write(buffer, 0, read)
                    }
                }
                zipOut.closeEntry()
            }

        } finally {
            zipOut.flush()
            zipOut.close()
        }

        backupFile
    }

    /**
     * Imports a .tgmusic backup zip from input stream / file, restoring audio files and Room DB entries.
     * All Room writes use the primary keys stored in the manifest (`insertSong`/`insertPlaylist`/etc.
     * are REPLACE-on-conflict), so restoring onto a device that already has data with matching IDs
     * overwrites those rows rather than erroring. Every field read uses `opt*` with a default so a
     * backup from an older app version (missing newer keys) still imports instead of throwing.
     * Returns false (never throws) on any failure so callers can show a simple error message.
     */
    suspend fun importBackup(context: Context, inputStream: InputStream): Boolean = withContext(Dispatchers.IO) {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "Music")
        if (!musicDir.exists()) musicDir.mkdirs()

        val zipIn = ZipInputStream(BufferedInputStream(inputStream))
        var manifestString: String? = null
        val buffer = ByteArray(8192)

        try {
            var entry = zipIn.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (entryName == "manifest.json") {
                    val baos = java.io.ByteArrayOutputStream()
                    val entryBuffer = ByteArray(8192)
                    var r: Int
                    while (zipIn.read(entryBuffer).also { r = it } != -1) {
                        baos.write(entryBuffer, 0, r)
                    }
                    manifestString = baos.toString("UTF-8")
                } else if (entryName.startsWith("audio/")) {
                    val fileName = entryName.removePrefix("audio/")
                    if (fileName.isNotBlank()) {
                        val outFile = File(musicDir, fileName)
                        // Security check: Zip Slip path traversal vulnerability prevention.
                        // Must compare against the canonical path PLUS a trailing separator, otherwise
                        // a sibling directory whose name has musicDir's name as a prefix (e.g. "Music-evil"
                        // next to "Music") would incorrectly pass String.startsWith().
                        val musicDirBoundary = musicDir.canonicalPath + File.separator
                        if (!outFile.canonicalPath.startsWith(musicDirBoundary)) {
                            Log.e(TAG, "Zip Slip vulnerability detected for entry: $entryName")
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                            continue
                        }
                        BufferedOutputStream(FileOutputStream(outFile)).use { fos ->
                            var read: Int
                            while (zipIn.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                            }
                            fos.flush()
                        }
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }

            if (manifestString == null) {
                Log.e(TAG, "Import failed: manifest.json not found in backup zip")
                return@withContext false
            }

            val manifest = JSONObject(manifestString)

            // Songs
            val songsArray = manifest.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until songsArray.length()) {
                val sObj = songsArray.getJSONObject(i)
                val isDownloaded = sObj.optBoolean("isDownloaded", true)
                val fileName = sObj.optString("fileName", "")

                val localUri = if (isDownloaded && fileName.isNotBlank()) {
                    val extractedFile = File(musicDir, fileName)
                    if (extractedFile.exists()) Uri.fromFile(extractedFile).toString() else sObj.optString("mediaUri")
                } else {
                    sObj.optString("mediaUri")
                }

                val song = Song(
                    id = sObj.optLong("id", 0L),
                    title = sObj.optString("title", "Unknown"),
                    artist = sObj.optString("artist", "Unknown"),
                    album = sObj.optString("album", "Unknown"),
                    durationMs = sObj.optLong("durationMs", 0L),
                    mediaUri = localUri,
                    producer = sObj.optString("producer").ifEmpty { null },
                    lyrics = sObj.optString("lyrics").ifEmpty { null },
                    artworkUri = sObj.optString("artworkUri").ifEmpty { null },
                    youtubeId = sObj.optString("youtubeId").ifEmpty { null },
                    isDownloaded = isDownloaded,
                    isPinned = sObj.optBoolean("isPinned", false)
                )
                songDao.insertSong(song)
            }

            // Playlists
            val playlistsArray = manifest.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until playlistsArray.length()) {
                val pObj = playlistsArray.getJSONObject(i)
                val playlist = Playlist(
                    playlistId = pObj.optLong("playlistId", 0L),
                    name = pObj.optString("name", "Playlist"),
                    description = pObj.optString("description").ifEmpty { null },
                    createdAt = pObj.optLong("createdAt", System.currentTimeMillis()),
                    isPinned = pObj.optBoolean("isPinned", false),
                    isSmart = pObj.optBoolean("isSmart", false)
                )
                playlistDao.insertPlaylist(playlist)
            }

            // CrossRefs
            val crossRefsArray = manifest.optJSONArray("crossRefs") ?: JSONArray()
            for (i in 0 until crossRefsArray.length()) {
                val cObj = crossRefsArray.getJSONObject(i)
                val crossRef = PlaylistSongCrossRef(
                    playlistId = cObj.optLong("playlistId"),
                    songId = cObj.optLong("songId")
                )
                playlistDao.insertPlaylistSongCrossRef(crossRef)
            }

            // Stats
            val statsArray = manifest.optJSONArray("stats") ?: JSONArray()
            for (i in 0 until statsArray.length()) {
                val stObj = statsArray.getJSONObject(i)
                val stats = SongStats(
                    songId = stObj.optLong("songId"),
                    playCount = stObj.optInt("playCount", 0),
                    lastPlayedAt = if (stObj.optLong("lastPlayedAt", 0L) > 0) stObj.optLong("lastPlayedAt") else null
                )
                songStatsDao.insertOrUpdate(stats)
            }

            // Alarms
            val alarmsArray = manifest.optJSONArray("alarms") ?: JSONArray()
            for (i in 0 until alarmsArray.length()) {
                val aObj = alarmsArray.getJSONObject(i)
                val toneTypeStr = aObj.optString("toneType", "SONG")
                val toneType = try {
                    AlarmToneType.valueOf(toneTypeStr)
                } catch (_: Exception) {
                    AlarmToneType.RANDOM_LIKED
                }
                val alarm = Alarm(
                    id = aObj.optLong("id", 0L),
                    timeInMillis = aObj.optLong("timeInMillis", System.currentTimeMillis()),
                    isEnabled = aObj.optBoolean("isEnabled", true),
                    repeatDays = aObj.optString("repeatDays", ""),
                    toneType = toneType,
                    toneUriOrId = aObj.optString("toneUriOrId", ""),
                    snoozeMinutes = aObj.optInt("snoozeMinutes", 10),
                    label = aObj.optString("label", "Alarm")
                )
                alarmDao.insertAlarm(alarm)
            }

            Log.d(TAG, "Backup successfully imported!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing backup zip", e)
            false
        } finally {
            try { zipIn.close() } catch (_: Exception) {}
        }
    }
}
