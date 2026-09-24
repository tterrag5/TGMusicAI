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
import com.example.tgmusicai.data.repository.LibraryTag
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.repository.MusicTagIndex
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

/**
 * How many tag chips to offer at once. The whole list can run to hundreds on an analysed library,
 * which is a wall of chips rather than a filter; the most-used ones plus whatever the search box
 * matches is what a person actually reaches for.
 */
private const val TAG_FILTER_CHIP_LIMIT = 12

/**
 * The ways the Library screen can show itself.
 *
 * Discover and Playlists are views here rather than destinations of their own. Browsing YouTube
 * Music is looking through the library's cloud half -- the same thing the search bar already does
 * when it lists "From YouTube" results -- and a playlist is a way of looking at the same songs.
 * Both used to sit elsewhere in the navigation (Discover in the drawer, Playlists in the bottom
 * bar), which split one idea across three places.
 */
enum class LibraryView { SONGS, PLAYLISTS, DISCOVER }

class LibraryViewModel(
    private val repository: MusicRepository,
    private val lyricsRepository: LyricsRepository? = null,
    private val coverArtScraper: CoverArtScraper? = null,
    private val aiFeatureManager: AiFeatureManager? = null,
    private val appPreferences: AppPreferences? = null,
    private val youtubeExtractor: YouTubeExtractor? = null,
    private val tagEditorManager: TagEditorManager? = null,
    private val musicBrowser: YouTubeMusicBrowser? = null,
    private val aiSongTagsDao: com.example.tgmusicai.data.local.dao.AiSongTagsDao? = null
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

    // --- Tag filtering ---

    /**
     * Which of the library's views is showing. Deliberately not persisted: it is a way of looking
     * for something right now, not a standing preference like grid-vs-list.
     */
    private val _libraryView = MutableStateFlow(LibraryView.SONGS)
    val libraryView: StateFlow<LibraryView> = _libraryView.asStateFlow()

    /** Tags the song list is currently narrowed to. Empty means no tag filter. */
    private val _selectedTagFilters = MutableStateFlow<Set<String>>(emptySet())
    val selectedTagFilters: StateFlow<Set<String>> = _selectedTagFilters.asStateFlow()

    /**
     * Every tag in the library: what the on-device models heard in the audio and read in the
     * lyrics, plus the genres the files themselves declare. Rebuilt whenever the library or the
     * analysis table changes, so a newly scanned or newly analysed track becomes filterable with
     * no refresh button.
     */
    val libraryTags: StateFlow<List<LibraryTag>> = combine(
        repository.allSongs,
        aiSongTagsDao?.observeAll() ?: MutableStateFlow(emptyList())
    ) { songs, aiTags ->
        MusicTagIndex.build(songs, aiTags)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * The tag chips to offer above the song list: the most-used tags, narrowed by whatever is
     * typed in the search box, with anything already selected kept in view so a filter can always
     * be switched back off.
     */
    val visibleTags: StateFlow<List<LibraryTag>> = combine(
        libraryTags,
        _searchQuery,
        _selectedTagFilters
    ) { tags, query, selected ->
        val matching = MusicTagIndex.matching(tags, query).take(TAG_FILTER_CHIP_LIMIT)
        val selectedTags = tags.filter { it.name in selected }
        (selectedTags + matching).distinctBy { it.name }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /** Switches views. */
    fun setLibraryView(view: LibraryView) {
        _libraryView.value = view
    }

    /** Adds or removes one tag from the filter. */
    fun toggleTagFilter(tag: String) {
        _selectedTagFilters.update { current ->
            if (tag in current) current - tag else current + tag
        }
    }

    /** Drops every tag filter, showing the whole library again. */
    fun clearTagFilters() {
        _selectedTagFilters.value = emptySet()
    }

    // Filtered song list based on search query and the downloaded-only toggle.
    val filteredSongs: StateFlow<List<Song>> = combine(
        repository.allSongs,
        _searchQuery,
        downloadedOnly,
        _selectedTagFilters,
        libraryTags
    ) { songsList, query, localOnly, tagFilters, tags ->
        val downloaded = if (localOnly) songsList.filter { it.isDownloaded } else songsList
        // Selected chips narrow rather than widen: each one added means "and this too", which is
        // what makes combining two tags useful instead of producing a longer list than either.
        val base = if (tagFilters.isEmpty()) {
            downloaded
        } else {
            val required = tagFilters.map { name ->
                tags.firstOrNull { it.name.equals(name, ignoreCase = true) }?.songIds.orEmpty()
            }
            downloaded.filter { song -> required.all { song.id in it } }
        }
        if (query.isBlank()) {
            base
        } else {
            // Tags count as something to search by, so typing "jazz" finds the tracks tagged jazz
            // as well as the ones with it in a title -- the chips are the deliberate version of
            // the same idea, this is the one that works without knowing a tag exists.
            val taggedIds = MusicTagIndex.songIdsMatching(tags, query)
            base.filter { song ->
                song.id in taggedIds ||
                song.title.contains(query, ignoreCase = true) ||
                song.artist.contains(query, ignoreCase = true) ||
                song.album.contains(query, ignoreCase = true) ||
                (song.genre != null && song.genre.contains(query, ignoreCase = true)) ||
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
        private val musicBrowser: YouTubeMusicBrowser? = null,
        private val aiSongTagsDao: com.example.tgmusicai.data.local.dao.AiSongTagsDao? = null
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
                musicBrowser = musicBrowser,
                aiSongTagsDao = aiSongTagsDao
            ) as T
        }
    }
}
