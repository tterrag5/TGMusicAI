package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.CoverArtScraper
import com.example.tgmusicai.data.repository.LyricLine
import com.example.tgmusicai.data.repository.LyricsRepository
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.playback.MediaControllerManager
import com.example.tgmusicai.playback.SleepTimerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel connecting UI components (Mini Player and Now Playing screen) to [MediaControllerManager].
 * Delegates player commands directly to the manager, manages Sleep Timer, and orchestrates lyrics & cover art scraping.
 */
class PlayerViewModel(
    private val mediaControllerManager: MediaControllerManager,
    private val lyricsRepository: LyricsRepository? = null,
    private val coverArtScraper: CoverArtScraper? = null,
    private val repository: MusicRepository? = null,
    private val appPreferences: AppPreferences? = null
) : ViewModel() {

    private companion object {
        /** Sleep-timer fade-out window: volume ramps from full to silent over the last 30s. */
        const val SLEEP_FADE_WINDOW_MS = 30_000L
    }

    val currentSong: StateFlow<Song?> = mediaControllerManager.currentSong
    val isPlaying: StateFlow<Boolean> = mediaControllerManager.isPlaying
    val currentPositionMs: StateFlow<Long> = mediaControllerManager.currentPositionMs
    val durationMs: StateFlow<Long> = mediaControllerManager.durationMs
    val playlist: StateFlow<List<Song>> = mediaControllerManager.playlist
    val shuffleMode: StateFlow<Boolean> = mediaControllerManager.shuffleMode
    val repeatMode: StateFlow<Int> = mediaControllerManager.repeatMode
    val isResolving: StateFlow<Boolean> = mediaControllerManager.isResolving
    val playbackSpeed: StateFlow<Float> = mediaControllerManager.playbackSpeed

    fun setPlaybackSpeed(speed: Float) {
        mediaControllerManager.setPlaybackSpeed(speed)
    }

    // Whether the full-screen Now Playing view should be shown. Selecting a song anywhere in the
    // app opens it by default (matches the "should be more accessible" request); the user can
    // still collapse it back to the mini player.
    private val _isNowPlayingExpanded = MutableStateFlow(false)
    val isNowPlayingExpanded: StateFlow<Boolean> = _isNowPlayingExpanded.asStateFlow()

    fun expandNowPlaying() {
        _isNowPlayingExpanded.value = true
    }

    fun collapseNowPlaying() {
        _isNowPlayingExpanded.value = false
    }

    val sleepTimerManager = SleepTimerManager(
        onTimerExpired = {
            mediaControllerManager.pause()
            // Restore full volume immediately so the next playback session isn't silently quiet.
            mediaControllerManager.setVolume(1f)
        }
    )
    val remainingSleepTimeMs: StateFlow<Long?> = sleepTimerManager.remainingMs

    init {
        // Gentle fade-out: once the sleep timer enters its last SLEEP_FADE_WINDOW_MS, linearly
        // ramp the player's volume down to 0 instead of playback cutting off at full volume.
        // Restores to full volume as soon as the timer is cancelled or expires (see above).
        viewModelScope.launch {
            remainingSleepTimeMs.collect { remaining ->
                if (remaining != null && remaining <= SLEEP_FADE_WINDOW_MS) {
                    mediaControllerManager.setVolume(remaining.toFloat() / SLEEP_FADE_WINDOW_MS)
                } else if (remaining == null) {
                    mediaControllerManager.setVolume(1f)
                }
            }
        }
    }

    private val _lyrics = MutableStateFlow<String?>(null)
    val lyrics: StateFlow<String?> = _lyrics.asStateFlow()

    /**
     * Defensive guard against the literal 4-character string "null" ever reaching the parser or
     * UI as if it were real lyrics content -- the root cause (an `org.json` quirk where
     * `optString` returns that literal text for an explicit JSON `null` value) is already fixed
     * at the source in `LyricsRepository`, but this is cheap belt-and-suspenders protection
     * against any other source (a stale pre-fix DB row, a future fetch tier) doing the same thing.
     */
    private fun sanitizeLyrics(raw: String?): String? =
        if (!raw.isNullOrBlank() && raw.trim() != "null") raw else null

    private val _isScraping = MutableStateFlow(false)
    val isScraping: StateFlow<Boolean> = _isScraping.asStateFlow()

    private val _isCurrentSongLiked = MutableStateFlow(false)
    val isCurrentSongLiked: StateFlow<Boolean> = _isCurrentSongLiked.asStateFlow()

    val parsedLyrics: StateFlow<List<LyricLine>> = _lyrics.map { raw ->
        LyricsRepository.parseLyrics(raw)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            currentSong.collect { song ->
                if (song != null) {
                    val clean = sanitizeLyrics(song.lyrics)
                    _lyrics.value = clean
                    if (clean == null && lyricsRepository != null) {
                        fetchLyrics(song)
                    }
                    if (repository != null) {
                        _isCurrentSongLiked.value = repository.isSongLikedSync(song.id)
                    }
                } else {
                    _lyrics.value = null
                    _isCurrentSongLiked.value = false
                }
            }
        }
    }

    fun toggleLikeCurrentSong() {
        val song = currentSong.value ?: return
        if (repository == null) return
        viewModelScope.launch {
            // A song streamed directly (not downloaded or added to a playlist first) is a
            // transient object with id == 0L that was never saved to the database -- liking it
            // needs a real row to attach to, or the like silently has nowhere to go.
            val songId = repository.ensurePersisted(song)
            val newLikedState = repository.toggleLikeSong(songId)
            _isCurrentSongLiked.value = newLikedState
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerManager.startTimer(minutes)
    }

    fun cancelSleepTimer() {
        sleepTimerManager.cancelTimer()
    }

    /**
     * A song streamed directly (not downloaded or added to a playlist first) is a transient
     * `id == 0L` object never inserted into Room -- saving fetched lyrics against that id would
     * silently affect zero rows, so the fetch would appear to work (the UI shows the lyrics right
     * away from the in-memory result) but nothing persists: replay the same song later and it's
     * gone. Persisting first (deduped by youtubeId/mediaUri) gives the save somewhere real to land.
     */
    private suspend fun ensurePersistedTarget(song: Song): Song {
        if (song.id != 0L || repository == null) return song
        val persistedId = repository.ensurePersisted(song)
        return song.copy(id = persistedId)
    }

    fun fetchLyrics(song: Song? = currentSong.value, forceFetch: Boolean = false) {
        val target = song ?: return
        if (lyricsRepository == null) return

        viewModelScope.launch {
            _isScraping.value = true
            val persistedTarget = ensurePersistedTarget(target)
            val fetched = lyricsRepository.fetchAndSaveLyrics(persistedTarget, forceFetch = forceFetch)
            _lyrics.value = sanitizeLyrics(fetched)
            _isScraping.value = false
        }
    }

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _transcribeError = MutableStateFlow<String?>(null)
    val transcribeError: StateFlow<String?> = _transcribeError.asStateFlow()

    fun clearTranscribeError() {
        _transcribeError.value = null
    }

    /**
     * Transcribes the current song's downloaded audio via OpenAI Whisper when no lyrics could be
     * found any other way, reusing the same user-configured AI API key that [com.example.tgmusicai.data.local.AiMetadataCleaner]
     * already uses for optional online metadata cleaning -- no separate key-management UI needed.
     * A no-op (with a clear status message) if there's no key, the key isn't OpenAI-format, or
     * the song isn't downloaded yet, since Whisper needs the actual local audio file.
     */
    fun transcribeLyricsWithAi(song: Song? = currentSong.value) {
        val target = song ?: return
        val repo = lyricsRepository
        if (repo == null) {
            _transcribeError.value = "AI transcription isn't available right now."
            return
        }
        viewModelScope.launch {
            val apiKey = appPreferences?.aiApiKeyFlow?.first()
            if (apiKey.isNullOrBlank() || !apiKey.startsWith("sk-")) {
                _transcribeError.value = "Add an OpenAI API key in Settings to use AI Transcribe Lyrics."
                return@launch
            }
            if (!target.isDownloaded) {
                _transcribeError.value = "Download this song first to transcribe its lyrics with AI."
                return@launch
            }
            _isTranscribing.value = true
            try {
                val persistedTarget = ensurePersistedTarget(target)
                val transcribed = repo.transcribeWithWhisper(persistedTarget, apiKey)
                if (transcribed != null) {
                    repository?.updateSongLyrics(persistedTarget.id, transcribed)
                    _lyrics.value = sanitizeLyrics(transcribed)
                } else {
                    _transcribeError.value = "AI transcription didn't return any lyrics for this song."
                }
            } finally {
                _isTranscribing.value = false
            }
        }
    }

    /** Playlists available for the Now Playing screen's "Save to Playlist" action. */
    val playlists: StateFlow<List<Playlist>> = (repository?.allPlaylists ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Adds the current song to [playlistId], persisting it first if it's a transient (id == 0L)
     * streamed song that was never saved to Room -- same reasoning as [ensurePersistedTarget].
     */
    fun addCurrentSongToPlaylist(playlistId: Long) {
        val song = currentSong.value ?: return
        val repo = repository ?: return
        viewModelScope.launch {
            val persistedTarget = ensurePersistedTarget(song)
            repo.addSongToPlaylist(playlistId = playlistId, songId = persistedTarget.id)
            _statusMessage.value = "Added \"${persistedTarget.title}\" to playlist"
        }
    }

    /** Saves the entire current playback queue as a brand new playlist named [name]. */
    fun saveQueueAsPlaylist(name: String) {
        val repo = repository ?: return
        val songs = playlist.value
        if (songs.isEmpty() || name.isBlank()) return
        viewModelScope.launch {
            val newPlaylistId = repo.createPlaylist(name = name)
            songs.forEach { song ->
                val persisted = ensurePersistedTarget(song)
                repo.addSongToPlaylist(playlistId = newPlaylistId, songId = persisted.id)
            }
            _statusMessage.value = "Saved queue as \"$name\""
        }
    }

    fun createPlaylistAndAddCurrentSong(playlistName: String) {
        val song = currentSong.value ?: return
        val repo = repository ?: return
        if (playlistName.isBlank()) return
        viewModelScope.launch {
            val persistedTarget = ensurePersistedTarget(song)
            val newPlaylistId = repo.createPlaylist(name = playlistName)
            repo.addSongToPlaylist(playlistId = newPlaylistId, songId = persistedTarget.id)
            _statusMessage.value = "Added \"${persistedTarget.title}\" to \"$playlistName\""
        }
    }

    fun scrapeArtworkAndLyrics(song: Song? = currentSong.value) {
        val target = song ?: return
        viewModelScope.launch {
            _isScraping.value = true
            val persistedTarget = ensurePersistedTarget(target)
            lyricsRepository?.fetchAndSaveLyrics(persistedTarget, forceFetch = true)?.let {
                _lyrics.value = sanitizeLyrics(it)
            }
            coverArtScraper?.scrapeAndSaveArtwork(persistedTarget)
            _isScraping.value = false
        }
    }

    fun playSong(song: Song, queue: List<Song> = listOf(song)) {
        // Deferred until playback actually starts (see MediaControllerManager.playSong's
        // onStarted callback) -- expanding immediately used to show the full-screen player with
        // the *previous* song's cover/title still in it for however long the new song took to
        // resolve, which looked like the app had glitched into the wrong screen.
        mediaControllerManager.playSong(song, queue, onStarted = { expandNowPlaying() })
    }

    fun playQueue(queue: List<Song>, startIndex: Int = 0) {
        mediaControllerManager.playQueue(queue, startIndex, onStarted = { expandNowPlaying() })
    }

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /** "Start Radio": builds and plays a queue of songs related to [song] (see [MusicRepository.buildRadioQueue]). */
    fun startRadio(song: Song) {
        val repo = repository ?: return
        viewModelScope.launch {
            val queue = repo.buildRadioQueue(song)
            if (queue.isNotEmpty()) {
                playQueue(queue, startIndex = 0)
                _statusMessage.value = "Starting radio based on \"${song.title}\""
            }
        }
    }

    fun togglePlayPause() {
        mediaControllerManager.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        mediaControllerManager.seekTo(positionMs)
    }

    fun skipToNext() {
        mediaControllerManager.skipToNext()
    }

    fun skipToPrevious() {
        mediaControllerManager.skipToPrevious()
    }

    fun skipToIndex(index: Int) {
        mediaControllerManager.skipToIndex(index)
    }

    fun moveQueueItem(from: Int, to: Int) {
        mediaControllerManager.moveQueueItem(from, to)
    }

    private var lastRestartOrPreviousClickAtMs = 0L

    /**
     * One click restarts the current song from the beginning. Clicking again within 2 seconds
     * of the last click, or clicking within the first second of the song, instead jumps to the
     * previous track -- matches the classic "tapping repeatedly means you want the previous
     * song, not to keep restarting this one" media-player convention.
     */
    fun restartOrPrevious() {
        val now = System.currentTimeMillis()
        val isRapidRepeatClick = now - lastRestartOrPreviousClickAtMs < 2000L
        val isNearSongStart = currentPositionMs.value < 1000L
        lastRestartOrPreviousClickAtMs = now

        if (isRapidRepeatClick || isNearSongStart) {
            mediaControllerManager.skipToPrevious()
        } else {
            mediaControllerManager.seekTo(0L)
        }
    }

    fun toggleShuffle() {
        mediaControllerManager.toggleShuffle()
    }

    fun toggleRepeat() {
        mediaControllerManager.toggleRepeat()
    }

    override fun onCleared() {
        sleepTimerManager.cancelTimer()
        super.onCleared()
    }

    class Factory(
        private val mediaControllerManager: MediaControllerManager,
        private val lyricsRepository: LyricsRepository? = null,
        private val coverArtScraper: CoverArtScraper? = null,
        private val repository: MusicRepository? = null,
        private val appPreferences: AppPreferences? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(
                mediaControllerManager = mediaControllerManager,
                lyricsRepository = lyricsRepository,
                coverArtScraper = coverArtScraper,
                repository = repository,
                appPreferences = appPreferences
            ) as T
        }
    }
}
