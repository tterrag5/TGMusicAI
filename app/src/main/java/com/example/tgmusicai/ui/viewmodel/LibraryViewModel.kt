package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.ai.AiFeatureManager
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.CoverArtScraper
import com.example.tgmusicai.data.repository.LyricsRepository
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.local.AudioTagIo
import com.example.tgmusicai.data.repository.TagEditorManager
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.example.tgmusicai.data.youtube.YouTubeAlbumRef
import com.example.tgmusicai.data.youtube.YouTubeArtistRef
import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import com.example.tgmusicai.data.repository.MusicFolderTree
import com.example.tgmusicai.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel responsible for managing state and actions on the Library Screen.
 * Collects songs from the database, filters them, handles playlist addition,
 * and triggers cover art & lyrics scraping for selected tracks.
 */
private const val CLOUD_ENTITY_RESULT_LIMIT = 5

class LibraryViewModel(
    private val repository: MusicRepository,
    private val lyricsRepository: LyricsRepository? = null,
    private val coverArtScraper: CoverArtScraper? = null,
    private val aiFeatureManager: AiFeatureManager? = null,
    private val appPreferences: AppPreferences? = null,
    private val youtubeExtractor: YouTubeExtractor? = null,
    private val tagEditorManager: TagEditorManager? = null,
    private val musicBrowser: YouTubeMusicBrowser? = null
) : ViewModel() {

    // User search query for filtering songs
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // The song whose tags are open for editing, or null when the editor is closed.
    private val _songForTagEdit = MutableStateFlow<Song?>(null)
    val songForTagEdit: StateFlow<Song?> = _songForTagEdit.asStateFlow()

    /**
     * A consent request the system wants shown before a shared-storage file may be rewritten.
     * The screen launches it and, once granted, re-submits the same edit.
     */
    private val _tagWriteConsentRequest = MutableStateFlow<android.app.PendingIntent?>(null)
    val tagWriteConsentRequest: StateFlow<android.app.PendingIntent?> = _tagWriteConsentRequest.asStateFlow()

    // Held so the edit can be retried verbatim after consent is granted, rather than making the
    // user retype everything they had just entered.
    private var pendingTagEdit: Pair<Song, AudioTagIo.EditableTags>? = null

    /** True when this track has a local file whose tags can be edited at all. */
    fun canEditTags(song: Song): Boolean = tagEditorManager != null && song.isDownloaded

    fun openTagEditor(song: Song) {
        _songForTagEdit.value = song
    }

    fun closeTagEditor() {
        _songForTagEdit.value = null
    }

    /** Reads the tags currently in the file, for pre-filling the editor. */
    suspend fun loadFileTags(song: Song): AudioTagIo.EditableTags? = tagEditorManager?.readTags(song)

    /**
     * Writes edited tags into the file and the library, surfacing whatever happened as a status
     * message. A permission request is not a failure -- it is stored so the screen can prompt and
     * then call [retryPendingTagEdit].
     */
    fun saveTags(song: Song, tags: AudioTagIo.EditableTags) {
        val manager = tagEditorManager ?: return
        _songForTagEdit.value = null
        viewModelScope.launch {
            when (val result = manager.writeTags(song, tags)) {
                is TagEditorManager.Result.Success ->
                    _statusMessage.value = "Tags saved to the file."
                is TagEditorManager.Result.NeedsPermission -> {
                    pendingTagEdit = song to tags
                    _tagWriteConsentRequest.value = result.request
                }
                is TagEditorManager.Result.NotALocalFile ->
                    _statusMessage.value = "This track has no local file to edit."
                is TagEditorManager.Result.Failed ->
                    _statusMessage.value = result.reason
            }
        }
    }

    /** Re-runs the edit the user already made, now that the system has granted the write. */
    fun retryPendingTagEdit() {
        val (song, tags) = pendingTagEdit ?: return
        pendingTagEdit = null
        _tagWriteConsentRequest.value = null
        saveTags(song, tags)
    }

    /** Drops a pending edit the user declined to grant permission for. */
    fun cancelPendingTagEdit() {
        pendingTagEdit = null
        _tagWriteConsentRequest.value = null
        _statusMessage.value = "Tags weren't changed."
    }

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

    /**
     * When on, the library hides cloud-only tracks and never searches YouTube -- everything shown
     * is playable without a network. Replaces the toggle that used to live on Home and only
     * filtered three of that screen's sections; it belongs next to the library it filters.
     */
    val downloadedOnly: StateFlow<Boolean> = appPreferences?.downloadedOnlyFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        ?: MutableStateFlow(false).asStateFlow()

    fun setDownloadedOnly(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences?.setDownloadedOnly(enabled)
        }
    }

    // Filtered song list based on search query and the downloaded-only toggle.
    val filteredSongs: StateFlow<List<Song>> = combine(
        repository.allSongs,
        _searchQuery,
        downloadedOnly
    ) { songsList, query, localOnly ->
        val base = if (localOnly) songsList.filter { it.isDownloaded } else songsList
        if (query.isBlank()) {
            base
        } else {
            base.filter { song ->
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

    // --- Cloud (YouTube) search, folded in from the old separate Explore tab ---

    private val _cloudResults = MutableStateFlow<List<YouTubeSearchResult>>(emptyList())
    val cloudResults: StateFlow<List<YouTubeSearchResult>> = _cloudResults.asStateFlow()

    private val _isSearchingCloud = MutableStateFlow(false)
    val isSearchingCloud: StateFlow<Boolean> = _isSearchingCloud.asStateFlow()

    private var cloudSearchJob: Job? = null

    /**
     * Artists and albums matching the query, shown above the individual tracks.
     *
     * A separate lookup from the track search: that one goes through NewPipeExtractor, which
     * returns videos and has no notion of a YouTube Music artist or album page. Searching for an
     * artist and being offered only a scattering of their songs is the gap this closes.
     */
    private val _cloudArtists = MutableStateFlow<List<YouTubeArtistRef>>(emptyList())
    val cloudArtists: StateFlow<List<YouTubeArtistRef>> = _cloudArtists.asStateFlow()

    private val _cloudAlbums = MutableStateFlow<List<YouTubeAlbumRef>>(emptyList())
    val cloudAlbums: StateFlow<List<YouTubeAlbumRef>> = _cloudAlbums.asStateFlow()

    /**
     * Debounced so typing doesn't fire a network request per keystroke, and skipped entirely in
     * downloaded-only mode. Local filtering above is synchronous and unaffected by this.
     */
    private fun scheduleCloudSearch(query: String) {
        cloudSearchJob?.cancel()
        if (youtubeExtractor == null || query.length < 2 || downloadedOnly.value) {
            _cloudResults.value = emptyList()
            _cloudArtists.value = emptyList()
            _cloudAlbums.value = emptyList()
            _isSearchingCloud.value = false
            return
        }
        cloudSearchJob = viewModelScope.launch {
            delay(500)
            _isSearchingCloud.value = true
            try {
                _cloudResults.value = youtubeExtractor.search(query)
            } catch (e: Exception) {
                _cloudResults.value = emptyList()
            } finally {
                _isSearchingCloud.value = false
            }
            // Deliberately after the track search rather than alongside it: tracks are what the
            // user is usually after, and making them wait on two round trips to see any result at
            // all would be a worse search for the sake of a less common case.
            if (musicBrowser != null) {
                _cloudArtists.value = musicBrowser.searchArtists(query).take(CLOUD_ENTITY_RESULT_LIMIT)
                _cloudAlbums.value = musicBrowser.searchAlbums(query).take(CLOUD_ENTITY_RESULT_LIMIT)
            }
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
        scheduleCloudSearch(newQuery)
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

    /** Toggles [song]'s liked state (e.g. from a swipe-left gesture on its row). */
    fun toggleLikeSong(song: Song) {
        viewModelScope.launch {
            val nowLiked = repository.toggleLikeSong(song.id)
            _statusMessage.value = if (nowLiked) "Liked \"${song.title}\"" else "Removed \"${song.title}\" from Liked Music"
        }
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
    // Persisted rather than in-memory: this used to reset to list view on every app restart.
    // Grid is the default -- it shows several times more of a library per screen.
    val isGridView: StateFlow<Boolean> = appPreferences?.libraryGridViewFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        ?: MutableStateFlow(true).asStateFlow()

    fun toggleGridView() {
        viewModelScope.launch {
            appPreferences?.setLibraryGridView(!isGridView.value)
        }
    }

    // --- Folder browsing ---

    /**
     * Whether the library is showing folders rather than a flat song list. Deliberately not
     * persisted: it is a way of looking for something right now, not a standing preference like
     * grid-vs-list, and reopening the app into a half-navigated folder tree is disorienting.
     */
    private val _folderBrowsingEnabled = MutableStateFlow(false)
    val folderBrowsingEnabled: StateFlow<Boolean> = _folderBrowsingEnabled.asStateFlow()

    /** Path of the folder currently open, or null while at the top of the tree. */
    private val _currentFolderPath = MutableStateFlow<String?>(null)

    /**
     * The whole folder tree, rebuilt whenever the library changes. Cheap enough to rebuild wholesale
     * -- it is a grouping pass over rows already in memory -- and doing so means a newly scanned
     * track appears in its folder without any invalidation logic.
     */
    private val folderTree: StateFlow<MusicFolderTree.FolderNode> = repository.allSongs
        .map { MusicFolderTree.build(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MusicFolderTree.FolderNode("", "", emptyList(), emptyList())
        )

    /**
     * The folder being shown. Resolved against the freshly built tree on every change rather than
     * held as an object, so a rescan that replaces the tree cannot strand the browser on a node
     * that no longer exists.
     */
    val currentFolder: StateFlow<MusicFolderTree.FolderNode> =
        combine(folderTree, _currentFolderPath) { tree, path ->
            if (path == null) tree else MusicFolderTree.findNode(tree, path) ?: tree
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MusicFolderTree.FolderNode("", "", emptyList(), emptyList())
        )

    /** True when there is a parent folder to go back to. */
    val canNavigateUp: StateFlow<Boolean> = combine(folderTree, _currentFolderPath) { tree, path ->
        path != null && path != tree.path
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setFolderBrowsingEnabled(enabled: Boolean) {
        _folderBrowsingEnabled.value = enabled
        if (!enabled) _currentFolderPath.value = null
    }

    fun openFolder(path: String) {
        _currentFolderPath.value = path
    }

    /** Steps up one directory, stopping at the top of the tree rather than walking off it. */
    fun navigateUpFolder() {
        val current = _currentFolderPath.value ?: return
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        _currentFolderPath.value = parent.takeIf { it.isNotEmpty() && it != current }
    }

    /** Every track in the open folder and everything below it, in the order they are displayed. */
    fun songsInFolderRecursively(node: MusicFolderTree.FolderNode): List<Song> =
        node.songs + node.subfolders.flatMap { songsInFolderRecursively(it) }

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
        private val aiFeatureManager: AiFeatureManager? = null,
        private val appPreferences: AppPreferences? = null,
        private val youtubeExtractor: YouTubeExtractor? = null,
        private val tagEditorManager: TagEditorManager? = null,
        private val musicBrowser: YouTubeMusicBrowser? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(
                repository = repository,
                lyricsRepository = lyricsRepository,
                coverArtScraper = coverArtScraper,
                aiFeatureManager = aiFeatureManager,
                appPreferences = appPreferences,
                youtubeExtractor = youtubeExtractor,
                tagEditorManager = tagEditorManager,
                musicBrowser = musicBrowser
            ) as T
        }
    }
}
