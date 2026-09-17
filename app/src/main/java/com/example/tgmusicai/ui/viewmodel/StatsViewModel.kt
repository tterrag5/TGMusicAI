package com.example.tgmusicai.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.ArtistPlayStats
import com.example.tgmusicai.data.repository.DailyListeningData
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.repository.ProducerStat
import com.example.tgmusicai.data.repository.SongWithStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class SongStorageInfo(val song: Song, val sizeBytes: Long)
data class PlaylistStorageInfo(val playlist: Playlist, val sizeBytes: Long, val songCount: Int)
data class StorageOverview(
    val totalBytes: Long,
    val biggestSongs: List<SongStorageInfo>,
    val playlists: List<PlaylistStorageInfo>
)

/**
 * ViewModel for managing and formatting listening statistics displayed on the Stats Screen.
 */
class StatsViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    // Top played songs with their play counts and timestamps
    val mostPlayedSongs: StateFlow<List<SongWithStats>> = repository.getMostPlayedSongsWithStats(limit = 10)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Whole-library total, not just the sum of whatever top-N list is currently loaded.
    val totalPlayCount: StateFlow<Int> = repository.getTotalPlayCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val topArtists: StateFlow<List<ArtistPlayStats>> = repository.getTopArtistsByPlayCount(limit = 5)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val topProducers: StateFlow<List<ProducerStat>> = repository.getTopProducers(limit = 5)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val totalListenTimeMs: StateFlow<Long> = repository.getTotalListenTimeMs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0L
        )

    private val _weeklyListeningTrend = MutableStateFlow<List<DailyListeningData>>(emptyList())
    val weeklyListeningTrend: StateFlow<List<DailyListeningData>> = _weeklyListeningTrend.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _weeklyListeningTrend.value = repository.getWeeklyListeningTrend()
        }
    }

    private val _storageOverview = MutableStateFlow<StorageOverview?>(null)
    val storageOverview: StateFlow<StorageOverview?> = _storageOverview.asStateFlow()

    private val _isLoadingStorage = MutableStateFlow(false)
    val isLoadingStorage: StateFlow<Boolean> = _isLoadingStorage.asStateFlow()

    /**
     * Computes on-disk storage usage: total bytes used by downloaded songs, the biggest
     * individual songs, and a per-playlist breakdown. Involves a filesystem stat() call per
     * downloaded song, so it's computed on demand (when the Storage tab is opened) rather than
     * kept continuously live.
     */
    fun refreshStorageOverview() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingStorage.value = true
            try {
                val allSongs = repository.allSongs.first()
                val sizeByUri = allSongs.associate { it.mediaUri to songFileSizeBytes(it) }
                val totalBytes = sizeByUri.values.sum()

                val biggestSongs = allSongs
                    .map { SongStorageInfo(it, sizeByUri[it.mediaUri] ?: 0L) }
                    .filter { it.sizeBytes > 0L }
                    .sortedByDescending { it.sizeBytes }
                    .take(20)

                val playlistsWithSongs = repository.allPlaylistsWithSongs.first()
                val playlistInfos = playlistsWithSongs
                    .map { pws ->
                        val bytes = pws.songs.sumOf { sizeByUri[it.mediaUri] ?: 0L }
                        PlaylistStorageInfo(pws.playlist, bytes, pws.songs.size)
                    }
                    .sortedByDescending { it.sizeBytes }

                _storageOverview.value = StorageOverview(totalBytes, biggestSongs, playlistInfos)
            } finally {
                _isLoadingStorage.value = false
            }
        }
    }

    /**
     * Deletes a downloaded song's local file to free space, keeping it in the library and every
     * playlist so it can still stream from YouTube. Refuses (in [MusicRepository]) if the song
     * has no cloud fallback. Refreshes the storage overview afterward so freed space shows up.
     */
    fun removeDownload(songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeDownloadKeepInPlaylist(songId)
            refreshStorageOverview()
        }
    }

    private fun songFileSizeBytes(song: Song): Long {
        if (!song.isDownloaded) return 0L
        val path = when {
            song.mediaUri.startsWith("file:") -> Uri.parse(song.mediaUri).path
            song.mediaUri.startsWith("/") -> song.mediaUri
            else -> null
        } ?: return 0L
        return try {
            val file = File(path)
            if (file.exists() && file.isFile) file.length() else 0L
        } catch (e: Exception) {
            0L
        }
    }

    class Factory(private val repository: MusicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StatsViewModel(repository) as T
        }
    }
}
