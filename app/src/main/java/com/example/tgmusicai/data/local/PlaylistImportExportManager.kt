package com.example.tgmusicai.data.local

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of [PlaylistImportExportManager.importPlaylistFromCsv]. */
data class PlaylistImportResult(
    val playlistId: Long,
    val matchedInLibrary: Int,
    val matchedViaYoutubeSearch: Int,
    val notFound: Int
) {
    val totalImported: Int get() = matchedInLibrary + matchedViaYoutubeSearch
}

/**
 * Imports/exports a playlist as a plain CSV file (`Title,Artist,Album`) -- the same shape tools
 * like Exportify produce from a Spotify playlist, so a Spotify export can be dropped in directly.
 * There's no such thing as a universal cross-service track ID, so import works the same way real
 * playlist-transfer tools (TuneMyMusic, SongShift) do: match each row against the local library
 * by title/artist first, and fall back to a YouTube search for anything not already known,
 * adding the top result as a cloud (not-yet-downloaded) track -- the same convention
 * [com.example.tgmusicai.ui.viewmodel.YouTubeViewModel.addCloudTrackToPlaylist] already uses.
 */
class PlaylistImportExportManager(
    private val songDao: SongDao,
    private val musicRepository: MusicRepository,
    private val youtubeExtractor: YouTubeExtractor = YouTubeExtractor()
) {
    private val TAG = "PlaylistImportExport"

    // --- Export ---

    fun buildCsv(songs: List<Song>): String = buildString {
        append("Title,Artist,Album\n")
        for (song in songs) {
            append(csvField(song.title)).append(',')
                .append(csvField(song.artist)).append(',')
                .append(csvField(song.album)).append('\n')
        }
    }

    private fun csvField(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (value.any { it == ',' || it == '"' || it == '\n' }) "\"$escaped\"" else escaped
    }

    /** A filesystem-safe base name for this playlist's exported CSV, without the extension. */
    fun suggestedFileName(playlistName: String): String {
        val safeName = playlistName.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifBlank { "Playlist" }
        return "${safeName}_${System.currentTimeMillis()}.csv"
    }

    /**
     * Writes [songs] as CSV into [outputStream].
     *
     * Takes a stream for the same reason [com.example.tgmusicai.data.local.BackupManager] does: the
     * previous version wrote directly into the public Downloads directory, which throws EACCES on
     * every supported Android version because the app holds no storage permission. The caller
     * supplies a stream from the system file picker instead, and owns closing it.
     */
    suspend fun exportPlaylistToStream(
        outputStream: java.io.OutputStream,
        playlistName: String,
        songs: List<Song>
    ) = withContext(Dispatchers.IO) {
        outputStream.write(buildCsv(songs).toByteArray(Charsets.UTF_8))
        outputStream.flush()
        Log.d(TAG, "Exported playlist '$playlistName' (${songs.size} track(s))")
    }

    // --- Import ---

    private companion object {
        val TITLE_HEADERS = setOf("title", "track name", "name", "song", "song name")
        val ARTIST_HEADERS = setOf("artist name(s)", "artist", "artists", "artist name")
        val ALBUM_HEADERS = setOf("album name", "album")
    }

    /**
     * Parses [csvText] and imports every row as a track into a new playlist named
     * [playlistName]. [onProgress] is called as `(rowsProcessed, totalRows)` so the caller can
     * show a progress indicator -- this can be slow since a row with no local match falls back to
     * a live YouTube search, one row at a time.
     */
    suspend fun importPlaylistFromCsv(
        csvText: String,
        playlistName: String,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ): PlaylistImportResult = withContext(Dispatchers.IO) {
        val lines = csvText.lines().filter { it.isNotBlank() }
        val playlistId = musicRepository.createPlaylist(playlistName, description = "Imported playlist")
        if (lines.isEmpty()) {
            return@withContext PlaylistImportResult(playlistId, 0, 0, 0)
        }

        val headerCells = parseCsvLine(lines.first()).map { it.trim().lowercase() }
        val titleIdx = headerCells.indexOfFirst { it in TITLE_HEADERS }
        val artistIdx = headerCells.indexOfFirst { it in ARTIST_HEADERS }
        val hasHeader = titleIdx >= 0
        val dataLines = if (hasHeader) lines.drop(1) else lines
        // No recognized header -- best-effort assume "Title,Artist,..." column order rather than
        // refusing to import a plain, header-less CSV someone hand-wrote.
        val resolvedTitleIdx = if (titleIdx >= 0) titleIdx else 0
        val resolvedArtistIdx = if (artistIdx >= 0) artistIdx else 1

        var matchedLocal = 0
        var matchedViaSearch = 0
        var notFound = 0

        dataLines.forEachIndexed { index, line ->
            onProgress(index + 1, dataLines.size)
            val cells = parseCsvLine(line)
            val title = cells.getOrNull(resolvedTitleIdx)?.trim().orEmpty()
            val artist = cells.getOrNull(resolvedArtistIdx)?.trim().orEmpty()
            if (title.isBlank()) return@forEachIndexed

            val existing = songDao.findByTitleAndNormalizedArtist(title, artist)
            if (existing != null) {
                musicRepository.addSongToPlaylist(playlistId, existing.id)
                matchedLocal++
                return@forEachIndexed
            }

            val query = if (artist.isNotBlank()) "$title $artist" else title
            val topResult = try {
                youtubeExtractor.search(query).firstOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "Search failed while importing row '$title' / '$artist'", e)
                null
            }
            if (topResult == null) {
                notFound++
                return@forEachIndexed
            }

            val cleaned = AiMetadataCleaner.cleanOffline(topResult.title, topResult.uploader)
            val resolvedArtist = cleaned.artist ?: topResult.uploader
            val songId = songDao.getSongByYoutubeId(topResult.videoId)?.id
                ?: songDao.findByTitleAndNormalizedArtist(cleaned.cleanTitle, resolvedArtist)?.id
                ?: songDao.insertSong(
                    Song(
                        title = cleaned.cleanTitle,
                        artist = resolvedArtist,
                        album = "YouTube Cloud",
                        durationMs = topResult.durationSeconds * 1000L,
                        mediaUri = "https://www.youtube.com/watch?v=${topResult.videoId}",
                        producer = cleaned.producer,
                        youtubeId = topResult.videoId,
                        isDownloaded = false
                    )
                )
            musicRepository.addSongToPlaylist(playlistId, songId)
            matchedViaSearch++
        }

        Log.d(TAG, "Imported '$playlistName': $matchedLocal local, $matchedViaSearch via search, $notFound not found")
        PlaylistImportResult(playlistId, matchedLocal, matchedViaSearch, notFound)
    }

    /** Minimal CSV line parser handling quoted fields (with embedded commas/escaped quotes) -- no external CSV dependency in this project. */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }
}
