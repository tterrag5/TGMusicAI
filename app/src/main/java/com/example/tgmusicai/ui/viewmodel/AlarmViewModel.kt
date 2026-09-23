package com.example.tgmusicai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tgmusicai.alarm.AlarmScheduler
import com.example.tgmusicai.data.local.entity.Alarm
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.MusicRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel managing alarm management state and operations.
 */
class AlarmViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    /**
     * Active stream of all user alarms.
     */
    val alarms: StateFlow<List<Alarm>> = repository.allAlarms.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Stream of local songs available for alarm tones.
     */
    val allSongs: StateFlow<List<Song>> = repository.allSongs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Stream of playlists available for alarm tones.
     */
    val allPlaylists: StateFlow<List<Playlist>> = repository.allPlaylists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Saves or updates an alarm in Room DB and schedules it with system AlarmManager.
     */
    fun saveAlarm(context: Context, alarm: Alarm) {
        viewModelScope.launch {
            val savedId = repository.insertOrUpdateAlarm(alarm)
            val updatedAlarm = if (alarm.id == 0L) alarm.copy(id = savedId) else alarm
            if (updatedAlarm.isEnabled) {
                AlarmScheduler.scheduleAlarm(context, updatedAlarm)
            } else {
                AlarmScheduler.cancelAlarm(context, updatedAlarm)
            }
        }
    }

    /**
     * Toggles an alarm's active state and updates system schedule.
     */
    fun toggleAlarm(context: Context, alarm: Alarm) {
        viewModelScope.launch {
            val updatedAlarm = alarm.copy(isEnabled = !alarm.isEnabled)
            repository.insertOrUpdateAlarm(updatedAlarm)
            if (updatedAlarm.isEnabled) {
                AlarmScheduler.scheduleAlarm(context, updatedAlarm)
            } else {
                AlarmScheduler.cancelAlarm(context, updatedAlarm)
            }
        }
    }

    /**
     * Deletes an alarm from database and cancels system schedule.
     */
    fun deleteAlarm(context: Context, alarm: Alarm) {
        viewModelScope.launch {
            AlarmScheduler.cancelAlarm(context, alarm)
            repository.deleteAlarm(alarm)
        }
    }

    /**
     * Factory for constructing [AlarmViewModel] with dependencies.
     */
    class Factory(
        private val repository: MusicRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AlarmViewModel(repository) as T
        }
    }
}
