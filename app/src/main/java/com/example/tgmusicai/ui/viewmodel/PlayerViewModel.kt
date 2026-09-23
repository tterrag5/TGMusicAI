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
import kotlinx.coroutines.flow.combine
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

    /** Non-null when a song couldn't be resolved to a playable stream -- see [MediaControllerManager.playbackError]. */
    val playbackError: StateFlow<String?> = mediaControllerManager.playbackError

    fun clearPlaybackError() {
        mediaControllerManager.clearPlaybackError()
    }

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

    /**
     * Crossfade: not a true overlapping crossfade (that needs two simultaneous ExoPlayer
     * instances) -- instead the outgoing track fades to silent right as it ends and the incoming
     * one fades in from silent, so there's never a jarring hard cut even though the two never
     * actually play at once. [AppPreferences.crossfadeDurationSecFlow] controls how long each half
     * of that fade is.
     */
    val crossfadeEnabled: StateFlow<Boolean> = appPreferences?.crossfadeEnabledFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        ?: MutableStateFlow(false).asStateFlow()

    val crossfadeDurationSec: StateFlow<Int> = appPreferences?.crossfadeDurationSecFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_CROSSFADE_DURATION_SEC)
        ?: MutableStateFlow(AppPreferences.DEFAULT_CROSSFADE_DURATION_SEC).asStateFlow()

    fun setCrossfadeEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences?.setCrossfadeEnabled(enabled) }
    }

    fun setCrossfadeDurationSec(seconds: Int) {
        viewModelScope.launch { appPreferences?.setCrossfadeDurationSec(seconds) }
    }

    init {
        // Combined volume fade: the sleep timer's fade-to-silent near the countdown's end, and
        // (if crossfade is enabled) the fade-out/fade-in described above. Both effects multiply
        // into a single effective volume instead of each independently calling setVolume, so they
        // can't fight over Player.volume if they ever happen to overlap (e.g. the sleep timer
        // expires right as a track is also fading out).
        var lastAppliedVolume = 1f
        viewModelScope.launch {
            combine(
                remainingSleepTimeMs,
                currentPositionMs,
                durationMs,
                crossfadeEnabled,
                crossfadeDurationSec
            ) { remaining, positionMs, totalMs, fadeEnabled, fadeSec ->
                val sleepFactor = if (remaining != null && remaining <= SLEEP_FADE_WINDOW_MS) {
                    (remaining.toFloat() / SLEEP_FADE_WINDOW_MS).coerceIn(0f, 1f)
                } else {
                    1f
                }
                val fadeMs = fadeSec * 1000L
                val crossfadeFactor = if (fadeEnabled && fadeMs > 0 && totalMs > 0) {
                    val fadeInFactor = (positionMs.toFloat() / fadeMs).coerceIn(0f, 1f)
                    val fadeOutFactor = ((totalMs - positionMs).toFloat() / fadeMs).coerceIn(0f, 1f)
                    minOf(fadeInFactor, fadeOutFactor)
                } else {
                    1f
                }
                sleepFactor * crossfadeFactor
            }.collect { volume ->
                if (kotlin.math.abs(volume - lastAppliedVolume) > 0.001f) {
                    mediaControllerManager.setVolume(volume)
                    lastAppliedVolume = volume
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
     * Whether ExoPlayer auto-trims dead silence at the start/end of tracks. The actual player
     * toggle lives in [com.example.tgmusicai.playback.PlaybackService], which observes this same
     * DataStore flow directly -- this is just the UI-facing read/write surface for the Settings
     * screen switch.
     */
    val skipSilenceEnabled: StateFlow<Boolean> = appPreferences?.skipSilenceEnabledFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        ?: MutableStateFlow(false).asStateFlow()

    fun setSkipSilenceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences?.setSkipSilenceEnabled(enabled)
        }
    }

    /**
     * The active color theme name (e.g. "YT_DARK"), read here (not just threaded through as a
     * plain composable parameter from `MainActivity`) so [com.example.tgmusicai.ui.screens.SettingsScreen]'s
     * theme picker reflects a change immediately. `SettingsScreen` is rendered through a
     * `NavDisplay`/`NavEntry` (androidx.navigation3) which only re-invokes an entry's content on
     * navigation events, not on unrelated ambient state changes -- a `currentTheme: String`
     * parameter threaded through that boundary went stale (the app's actual applied colors updated
     * instantly since `MainActivity`'s `TGMusicAITheme` isn't behind that boundary, but the picker's
     * checkmark didn't move until the screen was re-entered). Collecting the DataStore flow directly
     * inside `SettingsScreen` via this `StateFlow` sidesteps that entirely.
     */
    val selectedTheme: StateFlow<String> = appPreferences?.selectedThemeFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "YT_DARK")
        ?: MutableStateFlow("YT_DARK").asStateFlow()

    fun setSelectedTheme(themeName: String) {
        viewModelScope.launch {
            appPreferences?.setSelectedTheme(themeName)
        }
    }

    /** Light/dark preference name; see `ThemeMode`. */
    val themeMode: StateFlow<String> = appPreferences?.themeModeFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DARK")
        ?: MutableStateFlow("DARK").asStateFlow()

    fun setThemeMode(modeName: String) {
        viewModelScope.launch {
            appPreferences?.setThemeMode(modeName)
        }
    }

    /** Whether Material You wallpaper colors override the selected palette (Android 12+). */
    val dynamicColorEnabled: StateFlow<Boolean> = appPreferences?.dynamicColorFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        ?: MutableStateFlow(false).asStateFlow()

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences?.setDynamicColor(enabled)
        }
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
     * Transcribes the current song's audio via a bundled on-device Whisper-tiny.en model when no
     * lyrics could be found any other way -- see
     * [com.example.tgmusicai.ai.WhisperTranscriptionEngine]. No API key needed.
     *
     * Works for streamed cloud tracks as well as downloaded ones: a cloud song has a `youtubeId`,
     * which is enough for [LyricsRepository.transcribeWithWhisper] to fetch its audio to a
     * temporary file and decode that. Requiring a download first made transcription the one
     * feature a cloud track could not use, which is why the gate is now "no audio reachable at
     * all" rather than "not downloaded".
     */
    fun transcribeLyricsWithAi(song: Song? = currentSong.value) {
        val target = song ?: return
        val repo = lyricsRepository
        if (repo == null) {
            _transcribeError.value = "AI transcription isn't available right now."
            return
        }
        viewModelScope.launch {
            if (!target.isDownloaded && target.youtubeId.isNullOrBlank()) {
                _transcribeError.value = "This song has no audio available to transcribe."
                return@launch
            }
            _isTranscribing.value = true
            try {
                val persistedTarget = ensurePersistedTarget(target)
                val transcribed = repo.transcribeWithWhisper(persistedTarget)
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

    private val _translatedLyrics = MutableStateFlow<List<String>?>(null)
    /** Non-null while an active translation is showing, aligned 1:1 with [parsedLyrics] by index. */
    val translatedLyrics: StateFlow<List<String>?> = _translatedLyrics.asStateFlow()

    private val _isTranslatingLyrics = MutableStateFlow(false)
    val isTranslatingLyrics: StateFlow<Boolean> = _isTranslatingLyrics.asStateFlow()

    private val _translationError = MutableStateFlow<String?>(null)
    val translationError: StateFlow<String?> = _translationError.asStateFlow()

    fun clearTranslationError() {
        _translationError.value = null
    }

    val translationLanguage: StateFlow<String> = appPreferences?.lyricsTranslationLanguageFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_TRANSLATION_LANGUAGE)
        ?: MutableStateFlow(AppPreferences.DEFAULT_TRANSLATION_LANGUAGE).asStateFlow()

    /**
     * Persists [language] as the picked translation target and, if a translation is already
     * showing, immediately re-translates into it -- passing [language] straight into
     * [translateInto] rather than reading it back off [translationLanguage] avoids a race where
     * that StateFlow hasn't picked up the just-persisted DataStore write yet.
     */
    fun setTranslationLanguage(language: String) {
        viewModelScope.launch { appPreferences?.setLyricsTranslationLanguage(language) }
        if (_translatedLyrics.value != null) {
            translateInto(language)
        }
    }

    /**
     * Toggles translated lyrics for the currently showing song, translating [parsedLyrics] into
     * [translationLanguage] via the same AI key used for metadata cleaning and AI transcription.
     * Calling this again while a translation is already showing just turns it back off.
     */
    fun toggleLyricsTranslation() {
        if (_translatedLyrics.value != null) {
            _translatedLyrics.value = null
        } else {
            translateInto(translationLanguage.value)
        }
    }

    private fun translateInto(language: String) {
        val repo = lyricsRepository ?: run {
            _translationError.value = "Lyrics translation isn't available right now."
            return
        }
        val lines = parsedLyrics.value
        if (lines.isEmpty()) return
        viewModelScope.launch {
            _isTranslatingLyrics.value = true
            try {
                // Runs on-device via ML Kit -- no API key, and no network at all once the language
                // model has been downloaded once. The first use of a given language does need a
                // connection to fetch that model, which is what the error below usually means.
                val translated = repo.translateLyrics(lines.map { it.text }, language)
                if (translated != null) {
                    _translatedLyrics.value = translated
                } else {
                    _translationError.value =
                        "Couldn't translate into $language. The language pack may still need to download."
                }
            } finally {
                _isTranslatingLyrics.value = false
            }
        }
    }

    init {
        // A translation is only ever valid for the song it was requested for -- clear it the
        // instant the song changes so a stale translated line never lines up against the wrong
        // song's lyrics/timestamps while the new song's own lyrics are still loading.
        viewModelScope.launch {
            currentSong.collect {
                _translatedLyrics.value = null
                _translationError.value = null
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

    /** Appends [song] to the end of the live "Up Next" queue (e.g. from a swipe-right gesture). */
    fun addToQueue(song: Song) {
        mediaControllerManager.addToQueue(song)
        _statusMessage.value = "Added \"${song.title}\" to queue"
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

    fun removeQueueItems(indices: List<Int>) {
        mediaControllerManager.removeQueueItems(indices)
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
