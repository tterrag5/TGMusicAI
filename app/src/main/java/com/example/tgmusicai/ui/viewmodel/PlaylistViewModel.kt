package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import com.example.tgmusicai.data.repository.MusicRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing playlists list, smart playlists dynamic resolution, playlist creation/deletion, and selection.
 */
class PlaylistViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    private val _selectedPlaylistId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            repository.ensureSmartPlaylistsExist()
        }
    }

    val playlists: StateFlow<List<Playlist>> = repository.allPlaylists
        .map { list ->
            list.distinctBy { if (it.isSmart) "smart_${it.name}" else "playlist_${it.playlistId}" }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val playlistsWithSongs: StateFlow<List<PlaylistWithSongs>> = repository.allPlaylistsWithSongs
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedPlaylistWithSongs: StateFlow<PlaylistWithSongs?> = _selectedPlaylistId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                repository.getPlaylistWithSongs(id).flatMapLatest { pws ->
                    if (pws == null) flowOf(null) else repository.playlistWithSongsFlow(pws.playlist)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun openCreatePlaylistDialog() {
        _showCreateDialog.value = true
    }

    fun closeCreatePlaylistDialog() {
        _showCreateDialog.value = false
    }

    fun createPlaylist(name: String, description: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createPlaylist(name = name, description = description)
            closeCreatePlaylistDialog()
        }
    }

    fun updatePlaylistDescription(playlistId: Long, description: String?) {
        viewModelScope.launch {
            repository.updatePlaylistDescription(playlistId, description)
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist)
        }
    }

    fun togglePinPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            repository.togglePinPlaylist(playlist.playlistId, !playlist.isPinned)
        }
    }

    fun selectPlaylist(playlistId: Long) {
        _selectedPlaylistId.value = playlistId
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId = playlistId, songId = songId)
        }
    }

    /** Permanently deletes [songId] from the whole library (used for computed/read-only smart playlists like "Unplayed" that have no real cross-ref to remove). */
    fun deleteSongCompletely(songId: Long) {
        viewModelScope.launch {
            repository.deleteSongsCompletely(listOf(songId))
        }
    }

    fun likeAllSongs(songIds: List<Long>) {
        viewModelScope.launch {
            repository.likeSongs(songIds)
        }
    }

    fun unlikeAllSongs(songIds: List<Long>) {
        viewModelScope.launch {
            repository.unlikeSongs(songIds)
        }
    }

    // Multi-select within a playlist detail view, for bulk liking/unliking a subset of songs.
    private val _selectedSongIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSongIds: StateFlow<Set<Long>> = _selectedSongIds.asStateFlow()

    fun startSelection(songId: Long) {
        _selectedSongIds.value = setOf(songId)
    }

    fun toggleSongSelected(songId: Long) {
        _selectedSongIds.update { current ->
            if (songId in current) current - songId else current + songId
        }
    }

    fun clearSelection() {
        _selectedSongIds.value = emptySet()
    }

    fun likeSelectedSongs() {
        viewModelScope.launch {
            repository.likeSongs(_selectedSongIds.value.toList())
            clearSelection()
        }
    }

    fun unlikeSelectedSongs() {
        viewModelScope.launch {
            repository.unlikeSongs(_selectedSongIds.value.toList())
            clearSelection()
        }
    }

    class Factory(private val repository: MusicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlaylistViewModel(repository) as T
        }
    }
}
