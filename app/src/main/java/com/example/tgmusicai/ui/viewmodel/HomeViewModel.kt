package com.example.tgmusicai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.network.NetworkObserver
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.repository.SongWithStats
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * ViewModel for the Speed Dial Home Tab.
 * Manages pinned items, recently played, top tracks, smart playlists, offline state, and backup/restore.
 */
class HomeViewModel(
    private val repository: MusicRepository,
    private val networkObserver: NetworkObserver
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkObserver.isOnline

    private val _isDownloadedOnly = MutableStateFlow(false)
    val isDownloadedOnly: StateFlow<Boolean> = _isDownloadedOnly.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    private val _isBackupLoading = MutableStateFlow(false)
    val isBackupLoading: StateFlow<Boolean> = _isBackupLoading.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureSmartPlaylistsExist()
        }
    }

    // Pinned Songs filtered by isDownloadedOnly if enabled
    val pinnedSongs: StateFlow<List<Song>> = combine(
        repository.pinnedSongs,
        _isDownloadedOnly
    ) { songs, downloadedOnly ->
        if (downloadedOnly) songs.filter { it.isDownloaded } else songs
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pinned Playlists
    val pinnedPlaylists: StateFlow<List<Playlist>> = repository.pinnedPlaylists
        .map { list -> list.distinctBy { it.playlistId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Smart Playlists
    val smartPlaylists: StateFlow<List<Playlist>> = repository.smartPlaylists
        .map { list -> list.filter { it.isSmart }.distinctBy { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Top-song artwork for every playlist shown on Home (pinned real playlists AND smart
    // playlists), keyed by playlistId. Reuses MusicRepository's single shared definition of "the
    // songs in this playlist" (real cross-ref join or the matching computed query for a smart
    // playlist) instead of re-deriving it here, so pinned playlists get real cover art the same
    // way the Playlists tab and PlaylistDetailScreen already do.
    val playlistTopArtwork: StateFlow<Map<Long, String?>> = repository.allPlaylistsWithSongs
        .map { list -> list.associate { it.playlist.playlistId to it.songs.firstOrNull()?.artworkUri } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Listen Again (Recently Played)
    val listenAgainSongs: StateFlow<List<SongWithStats>> = combine(
        repository.getRecentlyPlayedSongs(15),
        _isDownloadedOnly
    ) { list, downloadedOnly ->
        if (downloadedOnly) list.filter { it.song.isDownloaded } else list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Most Played Tracks
    val mostPlayedSongs: StateFlow<List<SongWithStats>> = combine(
        repository.getMostPlayedSongsWithStats(20),
        _isDownloadedOnly
    ) { list, downloadedOnly ->
        if (downloadedOnly) list.filter { it.song.isDownloaded } else list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setDownloadedOnly(enabled: Boolean) {
        _isDownloadedOnly.value = enabled
    }

    fun togglePinSong(song: Song) {
        viewModelScope.launch {
            repository.togglePinSong(song.id, !song.isPinned)
        }
    }

    fun togglePinPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            repository.togglePinPlaylist(playlist.playlistId, !playlist.isPinned)
        }
    }

    fun removeDownload(songId: Long) {
        viewModelScope.launch {
            repository.removeDownloadKeepInPlaylist(songId)
        }
    }

    fun exportBackup(context: Context) {
        viewModelScope.launch {
            _isBackupLoading.value = true
            try {
                val file = repository.exportBackup(context)
                _backupStatus.value = "Backup saved: ${file.name}"
            } catch (e: Exception) {
                _backupStatus.value = "Backup failed: ${e.message}"
            } finally {
                _isBackupLoading.value = false
            }
        }
    }

    fun importBackup(context: Context, inputStream: InputStream) {
        viewModelScope.launch {
            _isBackupLoading.value = true
            try {
                val success = repository.importBackup(context, inputStream)
                if (success) {
                    _backupStatus.value = "Backup restored successfully!"
                } else {
                    _backupStatus.value = "Failed to restore backup zip."
                }
            } catch (e: Exception) {
                _backupStatus.value = "Restore failed: ${e.message}"
            } finally {
                _isBackupLoading.value = false
            }
        }
    }

    fun clearBackupStatus() {
        _backupStatus.value = null
    }

    class Factory(
        private val repository: MusicRepository,
        private val networkObserver: NetworkObserver
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, networkObserver) as T
        }
    }
}
