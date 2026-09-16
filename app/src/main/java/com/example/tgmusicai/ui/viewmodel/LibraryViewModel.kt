package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.ai.AiFeatureManager
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.CoverArtScraper
import com.example.tgmusicai.data.repository.LyricsRepository
import com.example.tgmusicai.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel responsible for managing state and actions on the Library Screen.
 * Collects songs from the database, filters them, handles playlist addition,
 * and triggers cover art & lyrics scraping for selected tracks.
 */
class LibraryViewModel(
    private val repository: MusicRepository,
    private val lyricsRepository: LyricsRepository? = null,
    private val coverArtScraper: CoverArtScraper? = null,
    private val aiFeatureManager: AiFeatureManager? = null
) : ViewModel() {

    // User search query for filtering songs
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Currently selected song for the "Add to Playlist" dialog
    private val _songForAddToPlaylist = MutableStateFlow<Song?>(null)
    val songForAddToPlaylist: StateFlow<Song?> = _songForAddToPlaylist.asStateFlow()

    // Scraping status message for UI feedback Toast/Snackbar
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // All playlists flow for the "Add to Playlist" dialog
    val playlists: StateFlow<List<Playlist>> = repository.allPlaylists
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Filtered song list based on search query
    val filteredSongs: StateFlow<List<Song>> = combine(
        repository.allSongs,
        _searchQuery
    ) { songsList, query ->
        if (query.isBlank()) {
            songsList
        } else {
            songsList.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                song.artist.contains(query, ignoreCase = true) ||
                song.album.contains(query, ignoreCase = true) ||
                (song.producer != null && song.producer.contains(query, ignoreCase = true))
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun onSearchQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
    }

    fun openAddToPlaylistDialog(song: Song) {
        _songForAddToPlaylist.value = song
    }

    fun closeAddToPlaylistDialog() {
        _songForAddToPlaylist.value = null
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repository.addSongToPlaylist(playlistId = playlistId, songId = songId)
            closeAddToPlaylistDialog()
        }
    }

    fun createPlaylistAndAddSong(playlistName: String, songId: Long) {
        viewModelScope.launch {
            if (playlistName.isNotBlank()) {
                val newPlaylistId = repository.createPlaylist(name = playlistName)
                repository.addSongToPlaylist(playlistId = newPlaylistId, songId = songId)
                closeAddToPlaylistDialog()
            }
        }
    }

    /**
     * Scrapes high-res cover art and lyrics for the specified [song] and updates Room DB.
     */
    fun scrapeArtworkAndLyrics(song: Song) {
        viewModelScope.launch {
            _statusMessage.value = "Scraping cover art & lyrics for \"${song.title}\"..."
            lyricsRepository?.fetchAndSaveLyrics(song, forceFetch = true)
            coverArtScraper?.scrapeAndSaveArtwork(song)
            _statusMessage.value = "Updated cover art & lyrics for \"${song.title}\""
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /**
     * Best-effort, fully optional: derives audio tags and a lyrics embedding for [song] via the
     * on-device AI container ([AiFeatureManager]) and caches them. A no-op if the manager wasn't
     * supplied (e.g. AI deps unavailable on this build) or if analysis fails for any reason --
     * this can never affect the rest of Library or playback.
     */
    fun analyzeSongWithAi(song: Song) {
        val manager = aiFeatureManager ?: return
        viewModelScope.launch {
            _statusMessage.value = "Analyzing \"${song.title}\"..."
            val outcome = manager.analyzeSong(song)
            _statusMessage.value = if (outcome.tags.isNotEmpty()) {
                "Tags: ${outcome.tags.joinToString(", ")}"
            } else {
                "No AI tags found for \"${song.title}\""
            }
        }
    }

    private fun selectedSongObjects(): List<Song> {
        val ids = _selectedSongIds.value
        return filteredSongs.value.filter { it.id in ids }
    }

    /** Bulk version of [scrapeArtworkAndLyrics] for every currently-selected song. */
    fun bulkScrapeArtworkAndLyrics() {
        val targets = selectedSongObjects()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _statusMessage.value = "Fetching cover art & lyrics for ${targets.size} song(s)..."
            for (song in targets) {
                lyricsRepository?.fetchAndSaveLyrics(song, forceFetch = true)
                coverArtScraper?.scrapeAndSaveArtwork(song)
            }
            _statusMessage.value = "Updated cover art & lyrics for ${targets.size} song(s)"
            clearSelection()
        }
    }

    /** Bulk version of [analyzeSongWithAi] for every currently-selected song. A no-op if the AI container isn't available. */
    fun bulkAnalyzeWithAi() {
        val manager = aiFeatureManager ?: return
        val targets = selectedSongObjects()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _statusMessage.value = "Analyzing ${targets.size} song(s)..."
            for (song in targets) {
                manager.analyzeSong(song)
            }
            _statusMessage.value = "Finished analyzing ${targets.size} song(s)"
            clearSelection()
        }
    }

    // Grid vs. list layout toggle for the song list.
    private val _isGridView = MutableStateFlow(false)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    fun toggleGridView() {
        _isGridView.value = !_isGridView.value
    }

    // Multi-select: a non-empty set means selection mode is active. Long-pressing a song starts
    // it; tapping any song while active toggles that song instead of playing it.
    private val _selectedSongIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSongIds: StateFlow<Set<Long>> = _selectedSongIds.asStateFlow()

    private val _showBulkAddToPlaylistDialog = MutableStateFlow(false)
    val showBulkAddToPlaylistDialog: StateFlow<Boolean> = _showBulkAddToPlaylistDialog.asStateFlow()

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

    fun openBulkAddToPlaylistDialog() {
        _showBulkAddToPlaylistDialog.value = true
    }

    fun closeBulkAddToPlaylistDialog() {
        _showBulkAddToPlaylistDialog.value = false
    }

    fun addSelectedSongsToPlaylist(playlistId: Long) {
        viewModelScope.launch {
            val ids = _selectedSongIds.value
            for (id in ids) {
                repository.addSongToPlaylist(playlistId = playlistId, songId = id)
            }
            _statusMessage.value = "Added ${ids.size} song(s) to playlist"
            closeBulkAddToPlaylistDialog()
            clearSelection()
        }
    }

    fun createPlaylistAndAddSelectedSongs(playlistName: String) {
        viewModelScope.launch {
            if (playlistName.isBlank()) return@launch
            val ids = _selectedSongIds.value
            val newPlaylistId = repository.createPlaylist(name = playlistName)
            for (id in ids) {
                repository.addSongToPlaylist(playlistId = newPlaylistId, songId = id)
            }
            closeBulkAddToPlaylistDialog()
            clearSelection()
        }
    }

    /**
     * Permanently deletes every selected song (local file, if any, plus its database row,
     * playlist memberships, and stats). Caller is expected to have already confirmed this with
     * the user -- there's no undo.
     */
    fun deleteSelectedSongs() {
        viewModelScope.launch {
            val ids = _selectedSongIds.value.toList()
            val count = repository.deleteSongsCompletely(ids)
            _statusMessage.value = "Deleted $count song(s) from your library"
            clearSelection()
        }
    }

    /**
     * Factory for constructing [LibraryViewModel] with dependencies.
     */
    class Factory(
        private val repository: MusicRepository,
        private val lyricsRepository: LyricsRepository? = null,
        private val coverArtScraper: CoverArtScraper? = null,
        private val aiFeatureManager: AiFeatureManager? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(
                repository = repository,
                lyricsRepository = lyricsRepository,
                coverArtScraper = coverArtScraper,
                aiFeatureManager = aiFeatureManager
            ) as T
        }
    }
}
