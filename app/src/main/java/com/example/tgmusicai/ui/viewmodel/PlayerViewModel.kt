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
import com.example.tgmusicai.data.scrobble.ListenBrainzScrobbler
import com.example.tgmusicai.playback.MediaControllerManager
import com.example.tgmusicai.playback.SleepTimerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
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

    /**
     * What the player is showing: the track actually playing, or the one the user just asked for
     * while its stream is still being resolved. Without the second case, tapping a cloud track
     * opened Now Playing on the previous song for the length of a network round trip.
     */
    val currentSong: StateFlow<Song?> = combine(
        mediaControllerManager.currentSong,
        mediaControllerManager.pendingSong
    ) { playing, pending ->
        pending ?: playing
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isPlaying: StateFlow<Boolean> = mediaControllerManager.isPlaying
    // While a track is still being resolved, the player is still reporting the *previous* track's
    // position and length. Showing those under the new title is the same confusion the pending
    // song fixes, one line further down the screen: the progress bar reads as though the track had
    // already been playing for a minute.
    val currentPositionMs: StateFlow<Long> = combine(
        mediaControllerManager.currentPositionMs,
        mediaControllerManager.pendingSong
    ) { position, pending ->
        if (pending != null) 0L else position
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val durationMs: StateFlow<Long> = combine(
        mediaControllerManager.durationMs,
        mediaControllerManager.pendingSong
    ) { duration, pending ->
        // The search result knows how long the track is, so the bar can be the right length
        // before a single byte of it has been fetched.
        pending?.durationMs?.takeIf { it > 0L } ?: duration
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
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

    /**
     * The showing song's lyrics, parsed, broken into one line per phrase, then padded with music
     * markers across every long wordless stretch.
     *
     * Splitting runs before the markers on purpose: a split line's phrases reach further into the
     * gap that follows it, so a drawn-out delivery no longer looks like a stretch of pure music to
     * [LyricsRepository.withInstrumentalMarkers]. The track duration feeds both -- it bounds the
     * last line's span and gives the outro its markers.
     */
    val parsedLyrics: StateFlow<List<LyricLine>> = combine(_lyrics, durationMs) { raw, duration ->
        val parsed = LyricsRepository.parseLyrics(raw)
        LyricsRepository.withInstrumentalMarkers(
            LyricsRepository.splitDenseLines(parsed, duration),
            duration,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Songs whose automatic lyrics lookup has already come back empty in this session.
     *
     * A found set of lyrics is cached on the song row, but a *miss* was recorded nowhere, so every
     * play of a track LrcLib has never heard of re-ran the whole chain -- an LrcLib request, a tag
     * parse, and a YouTube caption fetch -- for a result already known to be nothing. Keyed by
     * video id where there is one, so a cloud track is not re-attempted when its stream URL
     * changes between plays.
     *
     * Deliberately only for this session, and deliberately not consulted by the manual "get
     * lyrics" action: a track missing from LrcLib today may be there next week, and the user
     * asking for it directly is exactly when to look again.
     */
    private val emptyLyricsLookups = mutableSetOf<String>()

    private fun lyricsLookupKey(song: Song): String =
        song.youtubeId?.takeIf { it.isNotBlank() } ?: song.mediaUri

    init {
        viewModelScope.launch {
            // Keyed rather than raw, so the same track does not re-trigger this when its row is
            // rewritten -- a cloud track is announced before resolution and again once its real
            // stream URL is known, which is the same song twice.
            currentSong
                .distinctUntilChangedBy { it?.let(::lyricsLookupKey) }
                .collect { song ->
                    if (song != null) {
                        val clean = sanitizeLyrics(song.lyrics)
                        _lyrics.value = clean
                        if (clean == null && lyricsRepository != null &&
                            lyricsLookupKey(song) !in emptyLyricsLookups
                        ) {
                            fetchLyrics(song, isAutomatic = true)
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
     * Whether playback evens out the loudness difference between tracks. Like skip-silence, the
     * effect itself lives in [com.example.tgmusicai.playback.PlaybackService], which observes the
     * same DataStore flow; this is only the Settings screen's read/write surface.
     */
    val volumeNormalizationEnabled: StateFlow<Boolean> = appPreferences?.volumeNormalizationEnabledFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
        ?: MutableStateFlow(true).asStateFlow()

    fun setVolumeNormalizationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences?.setVolumeNormalizationEnabled(enabled)
        }
    }

    /**
     * Whether playback skips the crowd-sourced non-music segments of a YouTube track. Off by
     * default; see [AppPreferences.sponsorBlockEnabledFlow] for why it is opt-in.
     */
    val sponsorBlockEnabled: StateFlow<Boolean> = appPreferences?.sponsorBlockEnabledFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        ?: MutableStateFlow(false).asStateFlow()

    /** Which kinds of segment to skip. */
    val sponsorBlockCategories: StateFlow<Set<String>> = appPreferences?.sponsorBlockCategoriesFlow
        ?.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_SPONSORBLOCK_CATEGORIES
        )
        ?: MutableStateFlow(AppPreferences.DEFAULT_SPONSORBLOCK_CATEGORIES).asStateFlow()

    fun setSponsorBlockEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences?.setSponsorBlockEnabled(enabled) }
    }

    /** Adds or removes one category, leaving the rest of the selection alone. */
    fun toggleSponsorBlockCategory(category: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = sponsorBlockCategories.value
            val updated = if (enabled) current + category else current - category
            appPreferences?.setSponsorBlockCategories(updated)
        }
    }

    // --- Scrobbling ---

    /** Whether finished tracks are submitted to the user's ListenBrainz listening history. */
    val scrobblingEnabled: StateFlow<Boolean> = appPreferences?.scrobblingEnabledFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        ?: MutableStateFlow(false).asStateFlow()

    /** The account the stored token resolved to, or null when no working token is stored. */
    val listenBrainzUsername: StateFlow<String?> = appPreferences?.listenBrainzUsernameFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        ?: MutableStateFlow<String?>(null).asStateFlow()

    val listenBrainzServer: StateFlow<String> = appPreferences?.listenBrainzServerFlow
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.DEFAULT_LISTENBRAINZ_SERVER)
        ?: MutableStateFlow(AppPreferences.DEFAULT_LISTENBRAINZ_SERVER).asStateFlow()

    private val _scrobbleConnectionStatus = MutableStateFlow<String?>(null)

    /** Result of the last connection attempt, shown once and then cleared by the screen. */
    val scrobbleConnectionStatus: StateFlow<String?> = _scrobbleConnectionStatus.asStateFlow()

    private val _isConnectingScrobbler = MutableStateFlow(false)
    val isConnectingScrobbler: StateFlow<Boolean> = _isConnectingScrobbler.asStateFlow()

    fun clearScrobbleConnectionStatus() {
        _scrobbleConnectionStatus.value = null
    }

    fun setScrobblingEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences?.setScrobblingEnabled(enabled) }
    }

    /**
     * Verifies [token] against the server before storing it, and only turns scrobbling on if it
     * works.
     *
     * Checking first matters because the alternative fails invisibly: a mistyped token stores
     * cleanly, and the user then finds out weeks later that nothing was ever submitted.
     */
    fun connectListenBrainz(token: String, server: String) {
        val preferences = appPreferences ?: return
        viewModelScope.launch {
            _isConnectingScrobbler.value = true
            try {
                val resolvedServer = server.ifBlank { AppPreferences.DEFAULT_LISTENBRAINZ_SERVER }
                val username = ListenBrainzScrobbler().validateToken(token.trim(), resolvedServer)
                if (username == null) {
                    _scrobbleConnectionStatus.value =
                        "That token didn't work. Check it against your ListenBrainz profile page."
                    return@launch
                }
                preferences.setListenBrainzCredentials(token.trim(), resolvedServer, username)
                preferences.setScrobblingEnabled(true)
                _scrobbleConnectionStatus.value = "Connected as $username."
            } finally {
                _isConnectingScrobbler.value = false
            }
        }
    }

    fun disconnectListenBrainz() {
        viewModelScope.launch {
            appPreferences?.clearListenBrainzCredentials()
            _scrobbleConnectionStatus.value = "Disconnected."
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

    /**
     * Looks up lyrics for [song]. [isAutomatic] marks the lookup that happens on its own when a
     * track starts, as opposed to the user asking: a miss on an automatic lookup is remembered so
     * the same fruitless chain of requests is not repeated on every play.
     */
    fun fetchLyrics(
        song: Song? = currentSong.value,
        forceFetch: Boolean = false,
        isAutomatic: Boolean = false
    ) {
        val target = song ?: return
        if (lyricsRepository == null) return

        viewModelScope.launch {
            _isScraping.value = true
            val persistedTarget = ensurePersistedTarget(target)
            val fetched = lyricsRepository.fetchAndSaveLyrics(persistedTarget, forceFetch = forceFetch)
            val clean = sanitizeLyrics(fetched)
            if (isAutomatic && clean == null) {
                emptyLyricsLookups += lyricsLookupKey(target)
            }
            _lyrics.value = clean
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

    fun playSong(song: Song, queue: List<Song> = listOf(song), fromPlaylistId: Long? = null) {
        // Deferred until playback actually starts (see MediaControllerManager.playSong's
        // onStarted callback) -- expanding immediately used to show the full-screen player with
        // the *previous* song's cover/title still in it for however long the new song took to
        // resolve, which looked like the app had glitched into the wrong screen.
        mediaControllerManager.playSong(song, queue, fromPlaylistId, onStarted = { expandNowPlaying() })
    }

    fun playQueue(queue: List<Song>, startIndex: Int = 0, fromPlaylistId: Long? = null) {
        mediaControllerManager.playQueue(queue, startIndex, fromPlaylistId, onStarted = { expandNowPlaying() })
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
