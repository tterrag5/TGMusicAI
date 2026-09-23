package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.google.YouTubePlaylistSyncManager
import com.example.tgmusicai.data.local.dao.PlaylistDao
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.youtube.GoogleYouTubePlaylist
import com.example.tgmusicai.data.youtube.InnerTubeCookieManager
import com.example.tgmusicai.data.youtube.LIKED_MUSIC_PLAYLIST_ID
import com.example.tgmusicai.data.youtube.YouTubeInnerTubeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages the YouTube Music web session and importing/syncing YouTube Music playlists (including
 * Liked Music) as local playlists. See [YouTubePlaylistSyncManager] for the actual import/sync
 * logic and [InnerTubeCookieManager] for how the session itself is authenticated.
 */
class GoogleSyncViewModel(
    val cookieManager: InnerTubeCookieManager,
    private val syncManager: YouTubePlaylistSyncManager,
    private val innerTubeClient: YouTubeInnerTubeClient,
    private val playlistDao: PlaylistDao
) : ViewModel() {

    val syncedPlaylists: StateFlow<List<Playlist>> = playlistDao.getYoutubeSyncedPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _availablePlaylists = MutableStateFlow<List<GoogleYouTubePlaylist>>(emptyList())
    val availablePlaylists: StateFlow<List<GoogleYouTubePlaylist>> = _availablePlaylists.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _importingPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val importingPlaylistIds: StateFlow<Set<String>> = _importingPlaylistIds.asStateFlow()

    init {
        // Restores sign-in state from a session captured in a previous app run, without
        // reopening the WebView -- InnerTubeCookieManager persists it across launches.
        viewModelScope.launch {
            if (cookieManager.hasValidSession()) {
                _isSignedIn.value = true
                loadPlaylists()
            }
        }
    }

    /** Called once [com.example.tgmusicai.ui.components.YouTubeLoginDialog] captures a usable session. */
    fun onSignedIn() {
        viewModelScope.launch {
            _isSignedIn.value = true
            loadPlaylists()
        }
    }

    /** Forgets the YouTube Music session and clears the loaded playlist picker (does not touch already-imported local playlists). */
    fun signOut() {
        viewModelScope.launch {
            cookieManager.clearSession()
            _isSignedIn.value = false
            _availablePlaylists.value = emptyList()
        }
    }

    private suspend fun loadPlaylists() {
        _isLoading.value = true
        _errorMessage.value = null
        _availablePlaylists.value = innerTubeClient.fetchMyPlaylists()
        _isLoading.value = false
    }

    fun importLikedMusic(mergeIntoLikedMusic: Boolean = false) {
        viewModelScope.launch {
            _importingPlaylistIds.value = _importingPlaylistIds.value + LIKED_MUSIC_PLAYLIST_ID
            try {
                syncManager.importLikedMusic(mergeIntoLikedMusic)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to import Liked Music: ${e.message}"
            } finally {
                _importingPlaylistIds.value = _importingPlaylistIds.value - LIKED_MUSIC_PLAYLIST_ID
            }
        }
    }

    fun importPlaylist(playlist: GoogleYouTubePlaylist) {
        viewModelScope.launch {
            _importingPlaylistIds.value = _importingPlaylistIds.value + playlist.playlistId
            try {
                syncManager.importPlaylist(playlist)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to import '${playlist.title}': ${e.message}"
            } finally {
                _importingPlaylistIds.value = _importingPlaylistIds.value - playlist.playlistId
            }
        }
    }

    /**
     * Re-syncs every already-imported YouTube playlist against its current source content.
     * A no-op if the user has never imported anything, or isn't currently signed in -- safe to
     * call unconditionally on every app startup.
     */
    fun refreshAllSynced() {
        viewModelScope.launch {
            if (playlistDao.getYoutubeSyncedPlaylistsList().isEmpty()) return@launch
            if (!cookieManager.hasValidSession()) return@launch

            _isLoading.value = true
            try {
                syncManager.syncAll()
            } catch (e: Exception) {
                _errorMessage.value = "Sync failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    class Factory(
        private val cookieManager: InnerTubeCookieManager,
        private val syncManager: YouTubePlaylistSyncManager,
        private val innerTubeClient: YouTubeInnerTubeClient,
        private val playlistDao: PlaylistDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GoogleSyncViewModel(cookieManager, syncManager, innerTubeClient, playlistDao) as T
        }
    }
}
