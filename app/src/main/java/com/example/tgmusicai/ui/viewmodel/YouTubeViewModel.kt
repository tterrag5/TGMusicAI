package com.example.tgmusicai.ui.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.ai.AiFeatureManager
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.local.entity.PlaylistSongCrossRef
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.CoverArtScraper
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.youtube.CloudDownloadManager
import com.example.tgmusicai.data.youtube.DownloadProgressState
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import com.example.tgmusicai.playback.MediaControllerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel managing YouTube cloud search, stream extraction, playback, cloud playlist additions, and download tasks.
 */
class YouTubeViewModel(application: Application) : AndroidViewModel(application) {

    private val LOG_TAG = "TGMusicCloud"

    private val db = AppDatabase.getDatabase(application)
    val youtubeExtractor = YouTubeExtractor()
    private val aiFeatureManager: AiFeatureManager by lazy {
        AiFeatureManager(application, db.aiSongTagsDao())
    }
    val cloudDownloadManager = CloudDownloadManager(
        context = application,
        songDao = db.songDao(),
        youtubeExtractor = youtubeExtractor,
        pendingDownloadDao = db.pendingDownloadDao(),
        aiFeatureManager = aiFeatureManager
    )

    init {
        cloudDownloadManager.resumePendingDownloads()
    }
    private val musicRepository: MusicRepository by lazy {
        MusicRepository(
            songDao = db.songDao(),
            playlistDao = db.playlistDao(),
            songStatsDao = db.songStatsDao(),
            alarmDao = db.alarmDao()
        )
    }
    private val coverArtScraper: CoverArtScraper by lazy {
        CoverArtScraper(
            context = application,
            musicRepository = musicRepository,
            youtubeExtractor = youtubeExtractor
        )
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<YouTubeSearchResult>>(emptyList())
    val searchResults: StateFlow<List<YouTubeSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private val _extractingVideoId = MutableStateFlow<String?>(null)
    val extractingVideoId: StateFlow<String?> = _extractingVideoId.asStateFlow()

    val downloadMap: StateFlow<Map<String, DownloadProgressState>> = cloudDownloadManager.downloadMap

    private var searchJob: Job? = null

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(500)
                performSearch(query)
            }
        } else if (query.isEmpty()) {
            _searchResults.value = emptyList()
            _searchError.value = null
        }
    }

    fun performSearch(query: String = _searchQuery.value) {
        if (query.isBlank()) return

        viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null
            Log.d(LOG_TAG, "Performing search for query: '$query'")
            try {
                val results = youtubeExtractor.search(query)
                _searchResults.value = results
                Log.d(LOG_TAG, "Search for '$query' completed with ${results.size} result(s)")
                if (results.isEmpty()) {
                    _searchError.value = "No results found for '$query'"
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Search error for '$query'", e)
                _searchError.value = "Search failed for '$query': ${e.message}"
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun playTrack(item: YouTubeSearchResult, mediaControllerManager: MediaControllerManager) {
        viewModelScope.launch {
            _extractingVideoId.value = item.videoId
            Log.d(LOG_TAG, "playTrack requested for video ID: ${item.videoId}, title: '${item.title}'")
            try {
                val audioStream = youtubeExtractor.extractAudioStream(item.videoId)
                if (audioStream != null && audioStream.url.isNotBlank()) {
                    Log.d(LOG_TAG, "Stream resolved for video ID '${item.videoId}': URL=${audioStream.url}, format=${audioStream.format}, bitrate=${audioStream.bitrate}")
                    val cleaned = com.example.tgmusicai.data.local.AiMetadataCleaner.cleanOffline(item.title, item.uploader)
                    val streamSong = Song(
                        id = 0,
                        title = cleaned.cleanTitle,
                        artist = cleaned.artist ?: item.uploader,
                        album = "YouTube Cloud",
                        durationMs = item.durationSeconds * 1000L,
                        mediaUri = audioStream.url,
                        producer = cleaned.producer,
                        youtubeId = item.videoId,
                        isDownloaded = false
                    )
                    // Persist a real row for this stream (deduped by youtubeId) before playing --
                    // otherwise this Song stays a transient id=0 object forever and its play can
                    // never be attributed to a song_stats row, silently disappearing from Stats.
                    val persistedId = musicRepository.ensurePersisted(streamSong)
                    mediaControllerManager.playSong(streamSong.copy(id = persistedId))
                } else {
                    val failureMsg = "Failed to resolve audio stream for video ID '${item.videoId}': No working stream endpoints found"
                    Log.e(LOG_TAG, failureMsg)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            getApplication(),
                            failureMsg,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                val failureMsg = "Playback error for video ID '${item.videoId}': ${e.message}"
                Log.e(LOG_TAG, failureMsg, e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        getApplication(),
                        failureMsg,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                _extractingVideoId.value = null
            }
        }
    }

    fun downloadTrack(item: YouTubeSearchResult) {
        Log.d(LOG_TAG, "downloadTrack requested for video ID: ${item.videoId}, title: '${item.title}'")
        cloudDownloadManager.downloadTrack(
            videoId = item.videoId,
            title = item.title,
            uploader = item.uploader,
            durationSeconds = item.durationSeconds,
            thumbnailUri = item.thumbnailUri
        )
    }

    fun addCloudTrackToPlaylist(item: YouTubeSearchResult, playlistId: Long) {
        Log.d(LOG_TAG, "addCloudTrackToPlaylist requested for video ID: ${item.videoId}, playlist ID: $playlistId")
        viewModelScope.launch(Dispatchers.IO) {
            val cleaned = com.example.tgmusicai.data.local.AiMetadataCleaner.cleanOffline(item.title, item.uploader)

            // Reuse the existing Song row (by youtubeId, then title+artist) instead of always
            // inserting a new one -- this is the same track appearing again from search results,
            // or being added to a second playlist, or re-added after already being in the
            // library; without this check every one of those cases created a brand new duplicate
            // row instead of just adding a cross-ref to the existing song.
            val existing = db.songDao().getSongByYoutubeId(item.videoId)
                ?: db.songDao().findByTitleAndNormalizedArtist(cleaned.cleanTitle, cleaned.artist ?: item.uploader)

            val songId = if (existing != null) {
                existing.id
            } else {
                val cloudSong = Song(
                    title = cleaned.cleanTitle,
                    artist = cleaned.artist ?: item.uploader,
                    album = "YouTube Cloud",
                    durationMs = item.durationSeconds * 1000L,
                    mediaUri = "https://www.youtube.com/watch?v=${item.videoId}",
                    producer = cleaned.producer,
                    youtubeId = item.videoId,
                    isDownloaded = false
                )
                db.songDao().insertSong(cloudSong)
            }

            db.playlistDao().insertPlaylistSongCrossRef(
                PlaylistSongCrossRef(playlistId, songId)
            )

            if (existing == null || existing.artworkUri.isNullOrBlank()) {
                try {
                    val savedSong = db.songDao().getSongById(songId)
                    if (savedSong != null) {
                        coverArtScraper.scrapeAndSaveArtwork(savedSong)
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Cover art scraping failed for cloud track $songId", e)
                }
            }
        }
    }
}
