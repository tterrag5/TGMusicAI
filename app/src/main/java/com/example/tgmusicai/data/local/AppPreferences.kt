package com.example.tgmusicai.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

import androidx.datastore.preferences.core.stringPreferencesKey

/** Jetpack DataStore instance backing all app settings, persisted to a "settings" preferences file. */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Typed wrapper around Jetpack DataStore for every simple app setting: onboarding state, theme
 * choice, one-shot migration/backfill flags, view-mode choices, and equalizer state.
 * Each setting is exposed as a `Flow` for reactive reads and a `suspend fun set...` for writes.
 * Prefer this over touching `context.dataStore` directly so key names stay centralized here.
 */
class AppPreferences(private val context: Context) {

    companion object {
        const val DEFAULT_CROSSFADE_DURATION_SEC = 3
        const val DEFAULT_TRANSLATION_LANGUAGE = "Spanish"
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val SELECTED_THEME = stringPreferencesKey("selected_theme")
        // Light/dark selection and Material You opt-in, kept separate from SELECTED_THEME so
        // switching palette doesn't reset the user's light/dark choice or vice versa.
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        // Grid-vs-list choice for the Library and Playlists screens. Persisted because it used to
        // live only in a ViewModel StateFlow and silently reverted on every app restart.
        val LIBRARY_GRID_VIEW = booleanPreferencesKey("library_grid_view")
        val PLAYLISTS_GRID_VIEW = booleanPreferencesKey("playlists_grid_view")
        // Restrict the library to tracks that are actually on the device. Persisted because it
        // used to be a ViewModel-only flag that reset on every launch.
        val DOWNLOADED_ONLY = booleanPreferencesKey("downloaded_only")
        // HAS_DEDUPLICATED_LIBRARY_V1..V6: one-shot "ran already" flags for successive library
        // deduplication passes. Each new dedup algorithm/bugfix gets its own V-numbered flag rather
        // than reusing one, so fixing a bad dedup pass can re-run cleanup on every device without
        // needing a fresh app install.
        val HAS_DEDUPLICATED_LIBRARY_V1 = booleanPreferencesKey("has_deduplicated_library_v1")
        val HAS_DEDUPLICATED_LIBRARY_V2 = booleanPreferencesKey("has_deduplicated_library_v2")
        val HAS_DEDUPLICATED_LIBRARY_V3 = booleanPreferencesKey("has_deduplicated_library_v3")
        val HAS_DEDUPLICATED_LIBRARY_V4 = booleanPreferencesKey("has_deduplicated_library_v4")
        val HAS_DEDUPLICATED_LIBRARY_V5 = booleanPreferencesKey("has_deduplicated_library_v5")
        val HAS_DEDUPLICATED_LIBRARY_V6 = booleanPreferencesKey("has_deduplicated_library_v6")
        val EQUALIZER_ENABLED = booleanPreferencesKey("equalizer_enabled")
        // Comma-separated 5 band levels in millibels, e.g. "0,0,0,0,0".
        val EQUALIZER_BAND_LEVELS = stringPreferencesKey("equalizer_band_levels")
        val EQUALIZER_PRESET_NAME = stringPreferencesKey("equalizer_preset_name")
        val BASS_BOOST_STRENGTH = intPreferencesKey("bass_boost_strength")
        val HAS_BACKFILLED_AI_TAGS_V1 = booleanPreferencesKey("has_backfilled_ai_tags_v1")
        // YouTube Music InnerTube session, captured from the WebView sign-in flow (see
        // InnerTubeCookieManager) -- replaces the old OAuth access token, which was never
        // persisted since Play Services re-issued one silently on every launch.
        val YOUTUBE_MUSIC_COOKIE_HEADER = stringPreferencesKey("youtube_music_cookie_header")
        val YOUTUBE_MUSIC_SAPISID = stringPreferencesKey("youtube_music_sapisid")
        val SKIP_SILENCE_ENABLED = booleanPreferencesKey("skip_silence_enabled")
        val CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
        val CROSSFADE_DURATION_SEC = intPreferencesKey("crossfade_duration_sec")
        val LYRICS_TRANSLATION_LANGUAGE = stringPreferencesKey("lyrics_translation_language")
    }

    /** True once the user has completed (or skipped) the first-run onboarding flow. */
    val onboardingCompletedFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[ONBOARDING_COMPLETED] ?: false
        }

    /** Selected UI theme identifier; defaults to the YouTube Music-style dark theme. */
    val selectedThemeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[SELECTED_THEME] ?: "YT_DARK"
        }

    /**
     * Light/dark preference name (see `ThemeMode`). Defaults to DARK rather than SYSTEM so
     * existing installs -- which only ever had dark palettes -- don't suddenly flip to light.
     */
    val themeModeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[THEME_MODE] ?: "DARK"
        }

    /** Whether to derive colors from the device wallpaper (Material You, Android 12+). */
    val dynamicColorFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[DYNAMIC_COLOR] ?: false
        }

    /** Library grid vs list; defaults to grid, which shows far more of a music library at once. */
    val libraryGridViewFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[LIBRARY_GRID_VIEW] ?: true
        }

    /** Playlists grid vs list; defaults to grid to match the Library. */
    val playlistsGridViewFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PLAYLISTS_GRID_VIEW] ?: true
        }

    /** When true, hide cloud-only tracks and don't search YouTube. */
    val downloadedOnlyFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[DOWNLOADED_ONLY] ?: false
        }

    // hasDeduplicatedLibraryV1..V6Flow: read by app startup logic to decide whether the
    // corresponding one-shot dedup pass still needs to run on this device.
    val hasDeduplicatedLibraryV1Flow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V1] ?: false
        }

    val hasDeduplicatedLibraryV2Flow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V2] ?: false
        }

    val hasDeduplicatedLibraryV3Flow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V3] ?: false
        }

    val hasDeduplicatedLibraryV4Flow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V4] ?: false
        }

    val hasDeduplicatedLibraryV5Flow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V5] ?: false
        }

    val hasDeduplicatedLibraryV6Flow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V6] ?: false
        }

    /** Whether the parametric equalizer is currently applied to playback. */
    val equalizerEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[EQUALIZER_ENABLED] ?: false }

    /** 5 band levels in millibels, e.g. [0, 0, 0, 0, 0] when flat/unset. */
    val equalizerBandLevelsFlow: Flow<List<Short>> = context.dataStore.data
        .map { preferences ->
            preferences[EQUALIZER_BAND_LEVELS]
                ?.split(",")
                ?.mapNotNull { it.trim().toShortOrNull() }
                ?.takeIf { it.size == 5 }
                ?: listOf(0, 0, 0, 0, 0)
        }

    /** Name of the active equalizer preset (e.g. "Custom", "Bass Boost", "Flat"). */
    val equalizerPresetNameFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[EQUALIZER_PRESET_NAME] ?: "Custom" }

    /** Bass boost strength as a percentage-like value understood by [com.example.tgmusicai.playback.AudioEffectsManager]. */
    val bassBoostStrengthFlow: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[BASS_BOOST_STRENGTH] ?: 0 }

    /** Toggles the equalizer on/off. */
    suspend fun setEqualizerEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[EQUALIZER_ENABLED] = enabled }
    }

    /** Persists the 5 equalizer band levels, serialized as a comma-separated string since DataStore has no native list-of-Short type. */
    suspend fun setEqualizerBandLevels(levels: List<Short>) {
        context.dataStore.edit { preferences ->
            preferences[EQUALIZER_BAND_LEVELS] = levels.joinToString(",")
        }
    }

    /** Persists the active equalizer preset name. */
    suspend fun setEqualizerPresetName(name: String) {
        context.dataStore.edit { preferences -> preferences[EQUALIZER_PRESET_NAME] = name }
    }

    /** Persists bass boost strength. */
    suspend fun setBassBoostStrength(strength: Int) {
        context.dataStore.edit { preferences -> preferences[BASS_BOOST_STRENGTH] = strength }
    }

    /**
     * Whether ExoPlayer should auto-trim dead silence at the start/end of tracks (common on
     * YouTube-sourced audio). Backed by ExoPlayer's own [androidx.media3.exoplayer.ExoPlayer.setSkipSilenceEnabled].
     */
    val skipSilenceEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SKIP_SILENCE_ENABLED] ?: false }

    /** Toggles skip-silence on/off. */
    suspend fun setSkipSilenceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[SKIP_SILENCE_ENABLED] = enabled }
    }

    /**
     * Whether consecutive tracks fade out/in into each other instead of cutting directly from one
     * to the next. Implemented as a sequential fade-to-silent-then-fade-in (not a true overlapping
     * crossfade, which would need two simultaneous ExoPlayer instances) -- see
     * [com.example.tgmusicai.ui.viewmodel.PlayerViewModel]'s combined volume-fade logic.
     */
    val crossfadeEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[CROSSFADE_ENABLED] ?: false }

    /** Fade duration in seconds, applied at both the end of the outgoing track and the start of the incoming one. */
    val crossfadeDurationSecFlow: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[CROSSFADE_DURATION_SEC] ?: DEFAULT_CROSSFADE_DURATION_SEC }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[CROSSFADE_ENABLED] = enabled }
    }

    suspend fun setCrossfadeDurationSec(seconds: Int) {
        context.dataStore.edit { preferences -> preferences[CROSSFADE_DURATION_SEC] = seconds }
    }

    /** Last-picked lyrics translation target language, remembered across songs/sessions. */
    val lyricsTranslationLanguageFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[LYRICS_TRANSLATION_LANGUAGE] ?: DEFAULT_TRANSLATION_LANGUAGE }

    suspend fun setLyricsTranslationLanguage(language: String) {
        context.dataStore.edit { preferences -> preferences[LYRICS_TRANSLATION_LANGUAGE] = language }
    }

    /** True once the one-time AI-tag backfill (tagging pre-existing songs added before the AI feature existed) has run. */
    val hasBackfilledAiTagsV1Flow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[HAS_BACKFILLED_AI_TAGS_V1] ?: false }

    /** Marks the AI-tag backfill as complete so it never re-runs on this device. */
    suspend fun setHasBackfilledAiTagsV1(done: Boolean) {
        context.dataStore.edit { preferences -> preferences[HAS_BACKFILLED_AI_TAGS_V1] = done }
    }

    /** Marks onboarding as completed/skipped so it won't show again. */
    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETED] = completed
        }
    }

    /** Persists the selected theme identifier. */
    suspend fun setSelectedTheme(themeName: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_THEME] = themeName
        }
    }

    /** Persists the light/dark preference (a `ThemeMode` name). */
    suspend fun setThemeMode(modeName: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE] = modeName
        }
    }

    /** Persists the Material You dynamic-color opt-in. */
    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = enabled
        }
    }

    /** Persists the Library's grid-vs-list choice. */
    suspend fun setLibraryGridView(gridView: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LIBRARY_GRID_VIEW] = gridView
        }
    }

    /** Persists the downloaded-only library filter. */
    suspend fun setDownloadedOnly(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DOWNLOADED_ONLY] = enabled
        }
    }

    /** Persists the Playlists screen's grid-vs-list choice. */
    suspend fun setPlaylistsGridView(gridView: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PLAYLISTS_GRID_VIEW] = gridView
        }
    }

    // setHasDeduplicatedLibraryV1..V6: mark the corresponding dedup pass as done so it's skipped on future launches.
    suspend fun setHasDeduplicatedLibraryV1(done: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V1] = done
        }
    }

    suspend fun setHasDeduplicatedLibraryV2(done: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V2] = done
        }
    }

    suspend fun setHasDeduplicatedLibraryV3(done: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V3] = done
        }
    }

    suspend fun setHasDeduplicatedLibraryV4(done: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V4] = done
        }
    }

    suspend fun setHasDeduplicatedLibraryV5(done: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V5] = done
        }
    }

    suspend fun setHasDeduplicatedLibraryV6(done: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAS_DEDUPLICATED_LIBRARY_V6] = done
        }
    }

    /** Full `Cookie` header string for authenticated music.youtube.com InnerTube calls; null if never signed in. */
    val youtubeMusicCookieHeaderFlow: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[YOUTUBE_MUSIC_COOKIE_HEADER] }

    /** The session's SAPISID (or `__Secure-3PAPISID`) value used to compute a `SAPISIDHASH` per request. */
    val youtubeMusicSapisidFlow: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[YOUTUBE_MUSIC_SAPISID] }

    /** Persists a freshly-captured YouTube Music web session. */
    suspend fun setYouTubeMusicSession(cookieHeader: String, sapisid: String) {
        context.dataStore.edit { preferences ->
            preferences[YOUTUBE_MUSIC_COOKIE_HEADER] = cookieHeader
            preferences[YOUTUBE_MUSIC_SAPISID] = sapisid
        }
    }

    /** Forgets the persisted YouTube Music web session (sign-out). */
    suspend fun clearYouTubeMusicSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(YOUTUBE_MUSIC_COOKIE_HEADER)
            preferences.remove(YOUTUBE_MUSIC_SAPISID)
        }
    }
}
