package com.example.tgmusicai.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the sleep timer for music playback.
 * Counts down remaining duration and invokes [onTimerExpired] callback when finished.
 */
class SleepTimerManager(
    private val onTimerExpired: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var timerJob: Job? = null

    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    /**
     * Starts sleep timer for specified duration in minutes.
     */
    fun startTimer(minutes: Int) {
        cancelTimer()
        val durationMs = minutes * 60 * 1000L
        _remainingMs.value = durationMs

        timerJob = scope.launch {
            var left = durationMs
            while (left > 0) {
                delay(1000L)
                left -= 1000L
                _remainingMs.value = left.coerceAtLeast(0L)
            }
            _remainingMs.value = null
            onTimerExpired()
        }
    }

    /**
     * Cancels the currently active sleep timer.
     */
    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _remainingMs.value = null
    }

    val isRunning: Boolean
        get() = _remainingMs.value != null
}
