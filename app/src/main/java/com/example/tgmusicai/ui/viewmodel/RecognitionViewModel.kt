package com.example.tgmusicai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.data.repository.SongRecognitionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives listening for a song and building the index it matches against.
 *
 * Both are long-running and cancellable, and both are things the user can walk away from, so each
 * has its own job and its own visible state rather than a single shared "busy" flag.
 */
class RecognitionViewModel(
    private val manager: SongRecognitionManager
) : ViewModel() {

    /** Null while idle; set to the outcome once an attempt finishes. */
    private val _result = MutableStateFlow<SongRecognitionManager.Recognition?>(null)
    val result: StateFlow<SongRecognitionManager.Recognition?> = _result.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    /** How many tracks are in the index, so Settings can show progress as it is built. */
    val indexedSongCount: StateFlow<Int> = manager.indexedSongCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var listenJob: Job? = null
    private var indexJob: Job? = null

    /** Records a few seconds of audio and tries to identify it. */
    fun listen() {
        listenJob?.cancel()
        _result.value = null
        listenJob = viewModelScope.launch {
            _isListening.value = true
            try {
                _result.value = manager.recognize()
            } finally {
                _isListening.value = false
            }
        }
    }

    /** Abandons an attempt in progress; the microphone is released as the job unwinds. */
    fun cancelListening() {
        listenJob?.cancel()
        listenJob = null
        _isListening.value = false
    }

    fun clearResult() {
        _result.value = null
    }

    /**
     * Builds the index, batch by batch, until nothing is left. Runs until it finishes or is
     * cancelled, so the user can start it and leave the screen.
     */
    fun buildIndex() {
        if (_isIndexing.value) return
        indexJob = viewModelScope.launch {
            _isIndexing.value = true
            try {
                while (true) {
                    val indexed = manager.indexBatch()
                    if (indexed == 0) break
                }
            } finally {
                _isIndexing.value = false
            }
        }
    }

    fun cancelIndexing() {
        indexJob?.cancel()
        indexJob = null
        _isIndexing.value = false
    }

    /** Drops the index. Stops any build in progress first, so it cannot re-add rows afterwards. */
    fun clearIndex() {
        cancelIndexing()
        viewModelScope.launch { manager.clearIndex() }
    }

    class Factory(private val manager: SongRecognitionManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RecognitionViewModel(manager) as T
    }
}
