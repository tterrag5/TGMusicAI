package com.example.tgmusicai.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Wraps Android's system [Equalizer] and [BassBoost] audio effects, attached to the ExoPlayer's
 * audio session id. Both effects operate on the platform mixer for that session, so they work
 * regardless of which process/object created the ExoPlayer instance -- only the session id needs
 * to match, which is why this can be owned by a ViewModel instead of living inside PlaybackService
 * itself.
 */
class AudioEffectsManager {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var attachedSessionId: Int = 0

    val isAttached: Boolean get() = equalizer != null

    /** (Re)attaches to [sessionId] if not already attached to it. No-op if already attached. */
    fun attach(sessionId: Int) {
        if (sessionId == 0 || (sessionId == attachedSessionId && equalizer != null)) return
        release()
        attachedSessionId = sessionId
        try {
            equalizer = Equalizer(0, sessionId)
            bassBoost = BassBoost(0, sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach audio effects to session $sessionId", e)
            equalizer = null
            bassBoost = null
        }
    }

    fun setEnabled(enabled: Boolean) {
        try {
            equalizer?.enabled = enabled
            bassBoost?.enabled = enabled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set audio effects enabled=$enabled", e)
        }
    }

    val numberOfBands: Short get() = try { equalizer?.numberOfBands ?: 0 } catch (e: Exception) { 0 }

    fun bandLevelRange(): ShortArray = try {
        equalizer?.bandLevelRange ?: shortArrayOf(-1500, 1500)
    } catch (e: Exception) {
        shortArrayOf(-1500, 1500)
    }

    fun centerFreqHz(band: Short): Int = try {
        (equalizer?.getCenterFreq(band) ?: 0) / 1000
    } catch (e: Exception) {
        0
    }

    fun setBandLevel(band: Short, level: Short) {
        try {
            equalizer?.setBandLevel(band, level)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set band $band to level $level", e)
        }
    }

    /** Applies a full set of band levels (one per band, in order) in a single pass. */
    fun applyBandLevels(levels: List<Short>) {
        levels.forEachIndexed { index, level ->
            setBandLevel(index.toShort(), level)
        }
    }

    val presetNames: List<String>
        get() = try {
            val count = equalizer?.numberOfPresets ?: 0
            (0 until count).map { equalizer?.getPresetName(it.toShort()) ?: "" }
        } catch (e: Exception) {
            emptyList()
        }

    fun usePreset(presetIndex: Short) {
        try {
            equalizer?.usePreset(presetIndex)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply preset $presetIndex", e)
        }
    }

    /** Current band levels read back from the live effect, one entry per band. */
    fun currentBandLevels(): List<Short> {
        val bands = numberOfBands
        return (0 until bands).map { try { equalizer?.getBandLevel(it.toShort()) ?: 0 } catch (e: Exception) { 0 } }
    }

    fun setBassBoostStrength(strength: Short) {
        try {
            bassBoost?.setStrength(strength)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set bass boost strength to $strength", e)
        }
    }

    fun release() {
        try {
            equalizer?.release()
            bassBoost?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        }
        equalizer = null
        bassBoost = null
    }

    companion object {
        private const val TAG = "AudioEffectsManager"
    }
}
