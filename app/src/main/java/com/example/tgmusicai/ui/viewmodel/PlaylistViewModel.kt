package com.example.tgmusicai.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.local.PlaylistImportExportManager
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import com.example.tgmusicai.data.local.entity.Song
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
    private val repository: MusicRepository,
    private val importExportManager: PlaylistImportExportManager? = null,
    private val appPreferences: AppPreferences? = null
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

    // --- Playlists screen: grid/list mode and playlist-level multi-select ---

    /** Grid vs list on the Playlists screen, persisted so it survives a restart. */
    val isGridView: StateFlow<Boolean> = appPreferences?.playlistsGridViewFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        ?: MutableStateFlow(true).asStateFlow()

    fun toggleGridView() {
        viewModelScope.launch {
            appPreferences?.setPlaylistsGridView(!isGridView.value)
        }
    }

    // Deliberately separate from [_selectedSongIds] below: that one selects songs *inside* a
    // playlist for PlaylistDetailScreen, this one selects whole playlists on the Playlists grid.
    // Sharing one field would make a selection on either screen leak into the other.
    private val _selectedPlaylistIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedPlaylistIds: StateFlow<Set<Long>> = _selectedPlaylistIds.asStateFlow()

    fun startPlaylistSelection(playlistId: Long) {
        _selectedPlaylistIds.value = setOf(playlistId)
    }

    fun togglePlaylistSelected(playlistId: Long) {
        _selectedPlaylistIds.update { current ->
            if (playlistId in current) current - playlistId else current + playlistId
        }
    }

    fun clearPlaylistSelection() {
        _selectedPlaylistIds.value = emptySet()
    }

    /**
     * Deletes every selected playlist. The repository already refuses to delete the protected
     * smart playlists, and the UI additionally prevents selecting them, so the number the user is
     * shown always matches the number actually removed.
     */
    fun deleteSelectedPlaylists() {
        viewModelScope.launch {
            val ids = _selectedPlaylistIds.value
            playlists.value
                .filter { it.playlistId in ids && !MusicRepository.isProtectedSmartPlaylist(it) }
                .forEach { repository.deletePlaylist(it) }
            clearPlaylistSelection()
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

    // --- Playlist import/export (CSV) ---

    private val _exportStatusMessage = MutableStateFlow<String?>(null)
    val exportStatusMessage: StateFlow<String?> = _exportStatusMessage.asStateFlow()

    private val _importProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val importProgress: StateFlow<Pair<Int, Int>?> = _importProgress.asStateFlow()

    private val _importResultMessage = MutableStateFlow<String?>(null)
    val importResultMessage: StateFlow<String?> = _importResultMessage.asStateFlow()

    fun clearExportStatus() {
        _exportStatusMessage.value = null
    }

    fun clearImportResult() {
        _importResultMessage.value = null
    }

    /** The file name to pre-fill in the system save dialog for a playlist CSV export. */
    fun suggestedExportFileName(playlistName: String): String =
        importExportManager?.suggestedFileName(playlistName) ?: "Playlist.csv"

    /**
     * Exports [songs] (a playlist's tracks) as CSV into [destination] -- a document URI the user
     * chose via the system file picker -- importable by this app or any spreadsheet tool.
     */
    fun exportPlaylist(context: Context, destination: Uri, playlistName: String, songs: List<Song>) {
        val manager = importExportManager ?: return
        viewModelScope.launch {
            _exportStatusMessage.value = try {
                val wrote = context.contentResolver.openOutputStream(destination)?.use { out ->
                    manager.exportPlaylistToStream(out, playlistName, songs)
                    true
                } ?: false
                if (wrote) "Playlist exported" else "Export failed: couldn't write to that location"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    /**
     * Imports [csvText] (e.g. a Spotify playlist exported via a tool like Exportify, or this
     * app's own export) as a new playlist named [playlistName]. Rows already in the library are
     * matched directly; everything else falls back to a YouTube search, so this can take a while
     * for a long playlist -- [importProgress] reports `(rowsProcessed, totalRows)` as it works.
     */
    fun importPlaylistFromCsv(context: Context, csvText: String, playlistName: String) {
        val manager = importExportManager ?: return
        if (playlistName.isBlank()) return
        viewModelScope.launch {
            _importProgress.value = 0 to 0
            try {
                val result = manager.importPlaylistFromCsv(csvText, playlistName) { processed, total ->
                    _importProgress.value = processed to total
                }
                _importResultMessage.value = buildString {
                    append("Imported ${result.totalImported} track(s) into \"$playlistName\"")
                    if (result.matchedViaYoutubeSearch > 0) append(" (${result.matchedViaYoutubeSearch} matched via YouTube search)")
                    if (result.notFound > 0) append(", ${result.notFound} not found")
                }
            } catch (e: Exception) {
                _importResultMessage.value = "Import failed: ${e.message}"
            } finally {
                _importProgress.value = null
            }
        }
    }

    class Factory(
        private val repository: MusicRepository,
        private val importExportManager: PlaylistImportExportManager? = null,
        private val appPreferences: AppPreferences? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlaylistViewModel(repository, importExportManager, appPreferences) as T
        }
    }

    /**
     * Moves a song within a playlist and persists the new order.
     *
     * Dragging fires this once per single-step swap, so the write is one renumbering transaction
     * per row crossed rather than one per pixel.
     */
    fun moveSongInPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            repository.moveSongInPlaylist(playlistId, fromIndex, toIndex)
        }
    }

    /**
     * Moves a playlist within the list the user is looking at and persists the new order.
     *
     * [visiblePlaylistIds] is passed in rather than re-read here so the drag means what it looked
     * like it meant, even if the visible list is filtered.
     */
    fun movePlaylist(visiblePlaylistIds: List<Long>, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            repository.movePlaylist(visiblePlaylistIds, fromIndex, toIndex)
        }
    }
}
