package com.example.tgmusicai.data.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.room.withTransaction
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
 * Exports a database manifest (JSON) plus local audio files into a zip written to a caller-supplied
 * stream, and restores them back into the Room database and local storage without re-downloading.
 */
class BackupManager(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val songStatsDao: SongStatsDao,
    private val alarmDao: AlarmDao,
    // Optional so the lightweight MusicRepository instances built inside the playback service and
    // download manager (which never import) don't have to carry a database reference. When present,
    // the whole import runs in one transaction so a failure part-way can't leave a half-restored library.
    private val database: AppDatabase? = null
) {
    private val TAG = "BackupManager"

    companion object {
        /**
         * Bumped whenever the manifest's shape changes incompatibly. [importBackup] refuses a
         * backup whose version is higher than this, rather than importing it with the unknown
         * fields silently defaulted.
         */
        const val BACKUP_SCHEMA_VERSION = 1
    }

    /**
     * Writes a complete .tgmusic backup into [outputStream].
     *
     * Format: a `manifest.json` (schemaVersion plus songs/playlists/crossRefs/stats/alarms as JSON
     * arrays) and an `audio/<fileName>` entry for every locally-downloaded song, so restoring on a
     * fresh device doesn't require re-downloading anything from YouTube.
     *
     * Takes a stream rather than choosing its own path. The previous version wrote straight into
     * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` with a raw `File`, which
     * cannot work on any supported Android version: the app declares no WRITE_EXTERNAL_STORAGE and
     * targets an API level long past scoped storage, so every export failed with EACCES. Callers now
     * hand in a stream from the system file picker, which needs no storage permission at all and
     * lets the user choose where the backup lands.
     *
     * The caller owns [outputStream] and is responsible for closing it.
     */
    suspend fun exportBackup(outputStream: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val zipOut = ZipOutputStream(BufferedOutputStream(outputStream))

        try {
            val songs = songDao.getAllSongsList()
            val playlists = playlistDao.getAllPlaylistsList()
            val alarms = alarmDao.getAllAlarmsList()

            val manifestJson = JSONObject()
            // Lets importBackup reject a backup written by a newer app version instead of silently
            // dropping fields it doesn't know how to read.
            manifestJson.put("schemaVersion", BACKUP_SCHEMA_VERSION)

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
                // A content:// URI is an id in THIS device's MediaStore and means nothing on
                // another one. Flagging it lets the import re-match the track locally instead of
                // restoring a row that looks playable but isn't.
                songObj.put("isLocalMediaStore", song.mediaUri.startsWith("content:"))

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
            // Int.MAX_VALUE rather than a 1000-row cap: the cap silently truncated the play history
            // of any library larger than that, and totalListenTimeMs was omitted entirely, so a
            // restore wiped the Stats screen's headline metric.
            val allStats = songStatsDao.getMostPlayedStatsSync(Int.MAX_VALUE)
            for (st in allStats) {
                val stObj = JSONObject()
                stObj.put("songId", st.songId)
                stObj.put("playCount", st.playCount)
                stObj.put("lastPlayedAt", st.lastPlayedAt ?: 0L)
                stObj.put("totalListenTimeMs", st.totalListenTimeMs)
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
                aObj.put("forceMaxVolume", al.forceMaxVolume)
                aObj.put("volumeRampUp", al.volumeRampUp)
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
    }

    /**
     * Imports a .tgmusic backup zip, restoring audio files and Room DB entries.
     *
     * Rows are inserted with fresh auto-generated ids and the manifest's ids are used only to
     * re-point cross-refs and stats at the new rows. The previous version reused the backup's
     * primary keys against REPLACE-on-conflict inserts, so restoring onto a device that already had
     * a library silently overwrote whichever unrelated local rows happened to share an id.
     *
     * Every field read uses `opt*` with a default so a backup from an older app version still
     * imports; a backup from a *newer* schema version is rejected outright rather than imported
     * with unknown fields quietly defaulted. Returns false (never throws) on any failure.
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

            // A backup written by a newer build may use fields this version can't interpret.
            // Importing it anyway would appear to succeed while silently defaulting them.
            val manifestVersion = manifest.optInt("schemaVersion", 1)
            if (manifestVersion > BACKUP_SCHEMA_VERSION) {
                Log.e(TAG, "Backup schema v$manifestVersion is newer than supported v$BACKUP_SCHEMA_VERSION")
                return@withContext false
            }

            // Every row is inserted with a fresh auto-generated id and the old ids are remembered
            // here, so cross-refs and stats can be repointed at the new rows. Reusing the backup's
            // primary keys (as this used to) meant a REPLACE could overwrite an unrelated local
            // song or playlist that simply happened to occupy the same row id.
            val songIdMap = mutableMapOf<Long, Long>()
            val playlistIdMap = mutableMapOf<Long, Long>()

            // All the Room writes below go through this. With a database available they run as one
            // transaction, so a failure part-way rolls the whole import back instead of leaving a
            // partially-restored library behind.
            suspend fun applyManifest(body: suspend () -> Unit) {
                if (database != null) database.withTransaction { body() } else body()
            }

            applyManifest {
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

                    val originalId = sObj.optLong("id", 0L)
                    val bundledAudio = fileName.isNotBlank() &&
                        File(musicDir, fileName).exists()
                    val isLocalMediaStore = sObj.optBoolean(
                        "isLocalMediaStore",
                        sObj.optString("mediaUri").startsWith("content:")
                    )

                    // A MediaStore-backed song with no audio bundled in the zip can't be restored
                    // as-is: its content:// id belongs to the device the backup came from. If this
                    // device already scanned the same track, reuse that row so playlists and stats
                    // still line up; otherwise fall through and import it as a non-playable entry
                    // that the next media scan can supersede.
                    if (isLocalMediaStore && !bundledAudio) {
                        val localMatch = songDao.getSongByTitleAndArtist(
                            sObj.optString("title", "Unknown"),
                            sObj.optString("artist", "Unknown")
                        )
                        if (localMatch != null) {
                            if (originalId != 0L) songIdMap[originalId] = localMatch.id
                            continue
                        }
                    }

                    val song = Song(
                        id = 0L,
                        title = sObj.optString("title", "Unknown"),
                        artist = sObj.optString("artist", "Unknown"),
                        album = sObj.optString("album", "Unknown"),
                        durationMs = sObj.optLong("durationMs", 0L),
                        mediaUri = localUri,
                        producer = sObj.optString("producer").ifEmpty { null },
                        lyrics = sObj.optString("lyrics").ifEmpty { null },
                        artworkUri = sObj.optString("artworkUri").ifEmpty { null },
                        youtubeId = sObj.optString("youtubeId").ifEmpty { null },
                        // Never claim a track is downloaded when its audio wasn't in the zip --
                        // that made restored rows pass the "downloaded only" filter and then fail
                        // to play.
                        isDownloaded = isDownloaded && (bundledAudio || !isLocalMediaStore),
                        isPinned = sObj.optBoolean("isPinned", false)
                    )
                    val newId = songDao.insertSong(song)
                    if (originalId != 0L) songIdMap[originalId] = newId
                }

                // Playlists
                val playlistsArray = manifest.optJSONArray("playlists") ?: JSONArray()
                for (i in 0 until playlistsArray.length()) {
                    val pObj = playlistsArray.getJSONObject(i)
                    val originalPlaylistId = pObj.optLong("playlistId", 0L)
                    val playlist = Playlist(
                        playlistId = 0L,
                        name = pObj.optString("name", "Playlist"),
                        description = pObj.optString("description").ifEmpty { null },
                        createdAt = pObj.optLong("createdAt", System.currentTimeMillis()),
                        isPinned = pObj.optBoolean("isPinned", false),
                        isSmart = pObj.optBoolean("isSmart", false)
                    )
                    val newPlaylistId = playlistDao.insertPlaylist(playlist)
                    if (originalPlaylistId != 0L) playlistIdMap[originalPlaylistId] = newPlaylistId
                }

                // CrossRefs
                val crossRefsArray = manifest.optJSONArray("crossRefs") ?: JSONArray()
                for (i in 0 until crossRefsArray.length()) {
                    val cObj = crossRefsArray.getJSONObject(i)
                    // Skip links whose playlist or song didn't make it into the import rather than
                    // writing a cross-ref pointing at a row that doesn't exist.
                    val mappedPlaylistId = playlistIdMap[cObj.optLong("playlistId")] ?: continue
                    val mappedSongId = songIdMap[cObj.optLong("songId")] ?: continue
                    playlistDao.insertPlaylistSongCrossRef(
                        PlaylistSongCrossRef(playlistId = mappedPlaylistId, songId = mappedSongId)
                    )
                }

                // Stats
                val statsArray = manifest.optJSONArray("stats") ?: JSONArray()
                for (i in 0 until statsArray.length()) {
                    val stObj = statsArray.getJSONObject(i)
                    val mappedSongId = songIdMap[stObj.optLong("songId")] ?: continue
                    songStatsDao.insertOrUpdate(
                        SongStats(
                            songId = mappedSongId,
                            playCount = stObj.optInt("playCount", 0),
                            lastPlayedAt = if (stObj.optLong("lastPlayedAt", 0L) > 0) {
                                stObj.optLong("lastPlayedAt")
                            } else {
                                null
                            },
                            // Older backups predate this field; 0 is the entity default anyway.
                            totalListenTimeMs = stObj.optLong("totalListenTimeMs", 0L)
                        )
                    )
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
                        id = 0L,
                        timeInMillis = aObj.optLong("timeInMillis", System.currentTimeMillis()),
                        isEnabled = aObj.optBoolean("isEnabled", true),
                        repeatDays = aObj.optString("repeatDays", ""),
                        toneType = toneType,
                        toneUriOrId = aObj.optString("toneUriOrId", ""),
                        snoozeMinutes = aObj.optInt("snoozeMinutes", 10),
                        label = aObj.optString("label", "Alarm"),
                        forceMaxVolume = aObj.optBoolean("forceMaxVolume", false),
                        volumeRampUp = aObj.optBoolean("volumeRampUp", false)
                    )
                    alarmDao.insertAlarm(alarm)
                }
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
