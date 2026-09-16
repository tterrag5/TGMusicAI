package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.playback.AudioEffectsManager
import com.example.tgmusicai.playback.MediaControllerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the 5-band equalizer + bass boost settings. Owns an [AudioEffectsManager]
 * attached to the current playback session's audio session id -- both effects operate on the
 * platform mixer for that session, so owning them here (rather than inside `PlaybackService`)
 * works fine and keeps the UI layer self-contained.
 */
class EqualizerViewModel(
    private val mediaControllerManager: MediaControllerManager,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val audioEffectsManager = AudioEffectsManager()

    val enabled: StateFlow<Boolean> = appPreferences.equalizerEnabledFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    val presetName: StateFlow<String> = appPreferences.equalizerPresetNameFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), "Custom"
    )
    val bassBoostStrength: StateFlow<Int> = appPreferences.bassBoostStrengthFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )

    private val _bandLevels = MutableStateFlow<List<Short>>(listOf(0, 0, 0, 0, 0))
    val bandLevels: StateFlow<List<Short>> = _bandLevels.asStateFlow()

    private val _isSessionAvailable = MutableStateFlow(false)
    /** False until a playback session exists to attach the effects to (i.e. something has played). */
    val isSessionAvailable: StateFlow<Boolean> = _isSessionAvailable.asStateFlow()

    val bandLevelRange: ShortArray get() = audioEffectsManager.bandLevelRange()
    val presetNames: List<String> get() = audioEffectsManager.presetNames
    fun centerFreqHz(band: Int): Int = audioEffectsManager.centerFreqHz(band.toShort())
    val numberOfBands: Int get() = audioEffectsManager.numberOfBands.toInt()

    init {
        viewModelScope.launch {
            _bandLevels.value = appPreferences.equalizerBandLevelsFlow.first()
        }
    }

    /** Attaches the effects to the current audio session and re-applies saved settings. Call when opening the equalizer UI. */
    fun ensureAttached() {
        val sessionId = mediaControllerManager.getAudioSessionId()
        if (sessionId == 0) {
            _isSessionAvailable.value = false
            return
        }
        audioEffectsManager.attach(sessionId)
        _isSessionAvailable.value = audioEffectsManager.isAttached
        if (!audioEffectsManager.isAttached) return

        audioEffectsManager.setEnabled(enabled.value)
        val savedLevels = _bandLevels.value
        if (savedLevels.size == numberOfBands) {
            audioEffectsManager.applyBandLevels(savedLevels)
        } else {
            // First run on this device/band-count: seed from whatever the effect defaults to.
            _bandLevels.value = audioEffectsManager.currentBandLevels()
        }
        audioEffectsManager.setBassBoostStrength(bassBoostStrength.value.toShort())
    }

    fun setEnabled(isEnabled: Boolean) {
        audioEffectsManager.setEnabled(isEnabled)
        viewModelScope.launch { appPreferences.setEqualizerEnabled(isEnabled) }
    }

    fun setBandLevel(band: Int, level: Short) {
        audioEffectsManager.setBandLevel(band.toShort(), level)
        _bandLevels.value = _bandLevels.value.toMutableList().also {
            if (band < it.size) it[band] = level
        }
        viewModelScope.launch {
            appPreferences.setEqualizerBandLevels(_bandLevels.value)
            appPreferences.setEqualizerPresetName("Custom")
        }
    }

    fun applyPreset(index: Short, name: String) {
        audioEffectsManager.usePreset(index)
        val newLevels = audioEffectsManager.currentBandLevels()
        _bandLevels.value = newLevels
        viewModelScope.launch {
            appPreferences.setEqualizerBandLevels(newLevels)
            appPreferences.setEqualizerPresetName(name)
        }
    }

    fun setBassBoostStrength(strength: Int) {
        audioEffectsManager.setBassBoostStrength(strength.toShort())
        viewModelScope.launch { appPreferences.setBassBoostStrength(strength) }
    }

    override fun onCleared() {
        audioEffectsManager.release()
        super.onCleared()
    }

    class Factory(
        private val mediaControllerManager: MediaControllerManager,
        private val appPreferences: AppPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EqualizerViewModel(mediaControllerManager, appPreferences) as T
        }
    }
}
