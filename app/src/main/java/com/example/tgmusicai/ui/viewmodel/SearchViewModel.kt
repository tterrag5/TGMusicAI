package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.youtube.YouTubeAlbumRef
import com.example.tgmusicai.data.youtube.YouTubeArtistRef
import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which slice of the results the Search tab shows (the chips under the search box). */
enum class SearchFilter(val label: String) {
    ALL("All"), SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums"), PLAYLISTS("Playlists"), LIBRARY("Library")
}

/**
 * The Search tab: YouTube Music's own search (songs, artists, albums, playlists) straight from its
 * servers, the same way Discover browses, plus matches already in the library. Has its own query
 * so searching never filters the Library tab.
 */
class SearchViewModel(
    private val repository: MusicRepository,
    private val browser: YouTubeMusicBrowser,
    private val appPreferences: AppPreferences?,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _songs = MutableStateFlow<List<YouTubeSearchResult>>(emptyList())
    val songs: StateFlow<List<YouTubeSearchResult>> = _songs.asStateFlow()

    private val _artists = MutableStateFlow<List<YouTubeArtistRef>>(emptyList())
    val artists: StateFlow<List<YouTubeArtistRef>> = _artists.asStateFlow()

    private val _albums = MutableStateFlow<List<YouTubeAlbumRef>>(emptyList())
    val albums: StateFlow<List<YouTubeAlbumRef>> = _albums.asStateFlow()

    private val _playlists = MutableStateFlow<List<YouTubeAlbumRef>>(emptyList())
    val playlists: StateFlow<List<YouTubeAlbumRef>> = _playlists.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val recentSearches: StateFlow<List<String>> = (appPreferences?.recentSearchesFlow
        ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Library songs matching the query by title, artist or album. */
    val librarySongs: StateFlow<List<Song>> = combine(repository.allSongs, _query) { all, q ->
        val needle = q.trim()
        if (needle.length < 2) emptyList()
        else all.filter {
            it.title.contains(needle, true) || it.artist.contains(needle, true) || it.album.contains(needle, true)
        }.take(LIBRARY_LIMIT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var searchJob: Job? = null

    fun setFilter(filter: SearchFilter) {
        _filter.value = filter
    }

    /**
     * Debounced, and the in-flight search is cancelled on every keystroke, so results never show
     * matches for a prefix the user has already typed past. The four result types are separate
     * requests (YouTube filters a search to one type at a time) and run in parallel.
     */
    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        val q = newQuery.trim()
        if (q.length < 2) {
            clearResults()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _isSearching.value = true
            try {
                val songs = async { runCatching { browser.searchSongs(q) }.getOrDefault(emptyList()) }
                val artists = async { runCatching { browser.searchArtists(q) }.getOrDefault(emptyList()) }
                val albums = async { runCatching { browser.searchAlbums(q) }.getOrDefault(emptyList()) }
                val playlists = async { runCatching { browser.searchPlaylists(q) }.getOrDefault(emptyList()) }
                _songs.value = songs.await()
                _artists.value = artists.await()
                _albums.value = albums.await()
                _playlists.value = playlists.await()
            } finally {
                _isSearching.value = false
            }
        }
    }

    /** Remembers [query] as a recent search (on submit, or when a result is opened). */
    fun commitQuery(query: String = _query.value) {
        val q = query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch { appPreferences?.addRecentSearch(q) }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { appPreferences?.removeRecentSearch(query) }
    }

    fun clear() {
        _query.value = ""
        searchJob?.cancel()
        clearResults()
    }

    private fun clearResults() {
        _songs.value = emptyList()
        _artists.value = emptyList()
        _albums.value = emptyList()
        _playlists.value = emptyList()
        _isSearching.value = false
    }

    class Factory(
        private val repository: MusicRepository,
        private val browser: YouTubeMusicBrowser,
        private val appPreferences: AppPreferences?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(repository, browser, appPreferences) as T
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
        const val LIBRARY_LIMIT = 20
    }
}
