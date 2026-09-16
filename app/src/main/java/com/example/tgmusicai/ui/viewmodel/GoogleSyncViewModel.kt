package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.google.GoogleAuthManager
import com.example.tgmusicai.data.google.GoogleYouTubePlaylist
import com.example.tgmusicai.data.google.YouTubeDataApiClient
import com.example.tgmusicai.data.google.YouTubePlaylistSyncManager
import com.example.tgmusicai.data.local.dao.PlaylistDao
import com.example.tgmusicai.data.local.entity.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages Google sign-in and importing/syncing YouTube playlists (including Liked Videos) as
 * local playlists. See [YouTubePlaylistSyncManager] for the actual import/sync logic.
 */
class GoogleSyncViewModel(
    private val authManager: GoogleAuthManager,
    private val syncManager: YouTubePlaylistSyncManager,
    private val apiClient: YouTubeDataApiClient,
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

    private var cachedAccessToken: String? = null

    /**
     * Signs in (prompting for consent only if not already granted) and loads the account's
     * available YouTube playlists for the user to choose from.
     */
    fun signInAndLoadPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val token = authManager.getAccessToken()
            if (token == null) {
                _errorMessage.value = "Google sign-in failed or was cancelled."
                _isLoading.value = false
                return@launch
            }
            cachedAccessToken = token
            _isSignedIn.value = true
            _availablePlaylists.value = apiClient.fetchMyPlaylists(token)
            _isLoading.value = false
        }
    }

    fun importLikedVideos(mergeIntoLikedMusic: Boolean = false) {
        val token = cachedAccessToken ?: return
        viewModelScope.launch {
            _importingPlaylistIds.value = _importingPlaylistIds.value + "LL"
            try {
                syncManager.importLikedVideos(token, mergeIntoLikedMusic)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to import Liked Videos: ${e.message}"
            } finally {
                _importingPlaylistIds.value = _importingPlaylistIds.value - "LL"
            }
        }
    }

    fun importPlaylist(playlist: GoogleYouTubePlaylist) {
        val token = cachedAccessToken ?: return
        viewModelScope.launch {
            _importingPlaylistIds.value = _importingPlaylistIds.value + playlist.playlistId
            try {
                syncManager.importPlaylist(token, playlist)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to import '${playlist.title}': ${e.message}"
            } finally {
                _importingPlaylistIds.value = _importingPlaylistIds.value - playlist.playlistId
            }
        }
    }

    /**
     * Re-syncs every already-imported YouTube playlist against its current source content.
     * A no-op (does NOT prompt for Google sign-in) if the user has never imported anything --
     * safe to call unconditionally on every app startup, since it should never surprise a user
     * who has never touched this feature with a consent screen.
     */
    fun refreshAllSynced() {
        viewModelScope.launch {
            if (playlistDao.getYoutubeSyncedPlaylistsList().isEmpty()) return@launch

            _isLoading.value = true
            val token = cachedAccessToken ?: authManager.getAccessToken()
            if (token == null) {
                _isLoading.value = false
                return@launch
            }
            cachedAccessToken = token
            try {
                syncManager.syncAll(token)
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
        private val authManager: GoogleAuthManager,
        private val syncManager: YouTubePlaylistSyncManager,
        private val apiClient: YouTubeDataApiClient,
        private val playlistDao: PlaylistDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GoogleSyncViewModel(authManager, syncManager, apiClient, playlistDao) as T
        }
    }
}
