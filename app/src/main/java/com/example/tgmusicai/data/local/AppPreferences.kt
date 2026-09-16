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
 * Typed wrapper around Jetpack DataStore for every simple app setting: onboarding state, AI API
 * key, theme choice, one-shot migration/backfill flags, alarm volume behavior, and equalizer state.
 * Each setting is exposed as a `Flow` for reactive reads and a `suspend fun set...` for writes.
 * Prefer this over touching `context.dataStore` directly so key names stay centralized here.
 */
class AppPreferences(private val context: Context) {

    companion object {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val SELECTED_THEME = stringPreferencesKey("selected_theme")
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
        val ALARM_FORCE_MAX_VOLUME = booleanPreferencesKey("alarm_force_max_volume")
        val ALARM_VOLUME_RAMP_UP = booleanPreferencesKey("alarm_volume_ramp_up")
        val EQUALIZER_ENABLED = booleanPreferencesKey("equalizer_enabled")
        // Comma-separated 5 band levels in millibels, e.g. "0,0,0,0,0".
        val EQUALIZER_BAND_LEVELS = stringPreferencesKey("equalizer_band_levels")
        val EQUALIZER_PRESET_NAME = stringPreferencesKey("equalizer_preset_name")
        val BASS_BOOST_STRENGTH = intPreferencesKey("bass_boost_strength")
        val HAS_BACKFILLED_AI_TAGS_V1 = booleanPreferencesKey("has_backfilled_ai_tags_v1")
    }

    /** True once the user has completed (or skipped) the first-run onboarding flow. */
    val onboardingCompletedFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[ONBOARDING_COMPLETED] ?: false
        }

    /** User-supplied API key for AI-powered features (song tagging / metadata cleaning); null if never set. */
    val aiApiKeyFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[AI_API_KEY]
        }

    /** Selected UI theme identifier; defaults to the YouTube Music-style dark theme. */
    val selectedThemeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[SELECTED_THEME] ?: "YT_DARK"
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

    /** When enabled, an alarm firing forces the device's alarm-stream volume to its maximum instead of just an audible floor. */
    val alarmForceMaxVolumeFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[ALARM_FORCE_MAX_VOLUME] ?: false }

    /** When enabled, an alarm's volume gradually ramps up from quiet to full instead of starting at full volume immediately. */
    val alarmVolumeRampUpFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[ALARM_VOLUME_RAMP_UP] ?: false }

    /** Persists the "force max volume on alarm" setting. */
    suspend fun setAlarmForceMaxVolume(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ALARM_FORCE_MAX_VOLUME] = enabled
        }
    }

    /** Persists the "ramp up alarm volume gradually" setting. */
    suspend fun setAlarmVolumeRampUp(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ALARM_VOLUME_RAMP_UP] = enabled
        }
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

    /** Saves the AI API key, trimmed; a blank key clears the setting entirely instead of storing an empty string. */
    suspend fun setAiApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            if (apiKey.isBlank()) {
                preferences.remove(AI_API_KEY)
            } else {
                preferences[AI_API_KEY] = apiKey.trim()
            }
        }
    }

    /** Persists the selected theme identifier. */
    suspend fun setSelectedTheme(themeName: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_THEME] = themeName
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
}
