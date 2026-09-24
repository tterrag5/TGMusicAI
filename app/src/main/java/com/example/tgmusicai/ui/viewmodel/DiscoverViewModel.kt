package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.youtube.YouTubeAlbumPage
import com.example.tgmusicai.data.youtube.YouTubeAlbumRef
import com.example.tgmusicai.data.youtube.YouTubeArtistPage
import com.example.tgmusicai.data.youtube.YouTubeHomeShelf
import com.example.tgmusicai.data.youtube.YouTubeMoodCategory
import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the YouTube Music discovery screens: the mood and genre browser, the charts, and the
 * artist and album pages reached from them or from search.
 *
 * Everything it exposes is fetched from an undocumented internal API that can change shape without
 * warning, so each piece of state carries its own loading flag and an empty result is a normal
 * outcome rather than an error. The screens show "nothing here" instead of a failure, because a
 * failure the user can do nothing about is not worth interrupting them with.
 */
class DiscoverViewModel(
    private val browser: YouTubeMusicBrowser
) : ViewModel() {

    private val _moods = MutableStateFlow<List<YouTubeMoodCategory>>(emptyList())
    val moods: StateFlow<List<YouTubeMoodCategory>> = _moods.asStateFlow()

    private val _homeShelves = MutableStateFlow<List<YouTubeHomeShelf>>(emptyList())
    val homeShelves: StateFlow<List<YouTubeHomeShelf>> = _homeShelves.asStateFlow()

    private val _charts = MutableStateFlow<List<YouTubeSearchResult>>(emptyList())
    val charts: StateFlow<List<YouTubeSearchResult>> = _charts.asStateFlow()

    private val _selectedMood = MutableStateFlow<YouTubeMoodCategory?>(null)
    val selectedMood: StateFlow<YouTubeMoodCategory?> = _selectedMood.asStateFlow()

    private val _moodPlaylists = MutableStateFlow<List<YouTubeAlbumRef>>(emptyList())
    val moodPlaylists: StateFlow<List<YouTubeAlbumRef>> = _moodPlaylists.asStateFlow()

    private val _isLoadingDiscover = MutableStateFlow(false)
    val isLoadingDiscover: StateFlow<Boolean> = _isLoadingDiscover.asStateFlow()

    private val _artist = MutableStateFlow<YouTubeArtistPage?>(null)
    val artist: StateFlow<YouTubeArtistPage?> = _artist.asStateFlow()

    private val _album = MutableStateFlow<YouTubeAlbumPage?>(null)
    val album: StateFlow<YouTubeAlbumPage?> = _album.asStateFlow()

    private val _isLoadingDetail = MutableStateFlow(false)
    val isLoadingDetail: StateFlow<Boolean> = _isLoadingDetail.asStateFlow()

    // One job per kind of load, cancelled before starting the next. Opening two artist pages in
    // quick succession would otherwise let the slower response land last and overwrite the page
    // the user is actually looking at.
    private var discoverJob: Job? = null
    private var moodJob: Job? = null
    private var detailJob: Job? = null

    /** Loads the mood categories and the chart. Safe to call on every visit; it refreshes. */
    fun loadDiscover() {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            _isLoadingDiscover.value = true
            try {
                // The home feed first, and shown as soon as it lands: it is the one source that
                // reliably returns something, so waiting on the other two before drawing anything
                // would leave the screen blank for no reason.
                _homeShelves.value = browser.fetchHomeFeed()
                _moods.value = browser.fetchMoodCategories()
                _charts.value = browser.fetchTopChart()
            } finally {
                _isLoadingDiscover.value = false
            }
        }
    }

    /** Opens one mood or genre, or closes the open one when [category] is null. */
    fun selectMood(category: YouTubeMoodCategory?) {
        moodJob?.cancel()
        _selectedMood.value = category
        _moodPlaylists.value = emptyList()
        if (category == null) return
        moodJob = viewModelScope.launch {
            _moodPlaylists.value = browser.fetchMoodPlaylists(category)
        }
    }

    /** Loads an artist page, clearing any album page so the two cannot render at once. */
    fun loadArtist(browseId: String) {
        detailJob?.cancel()
        _artist.value = null
        _album.value = null
        detailJob = viewModelScope.launch {
            _isLoadingDetail.value = true
            try {
                _artist.value = browser.fetchArtist(browseId)
            } finally {
                _isLoadingDetail.value = false
            }
        }
    }

    /** Loads an album page, clearing any artist page for the same reason. */
    fun loadAlbum(browseId: String) {
        detailJob?.cancel()
        _artist.value = null
        _album.value = null
        detailJob = viewModelScope.launch {
            _isLoadingDetail.value = true
            try {
                _album.value = browser.fetchAlbum(browseId)
            } finally {
                _isLoadingDetail.value = false
            }
        }
    }

    class Factory(private val browser: YouTubeMusicBrowser) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DiscoverViewModel(browser) as T
    }
}
