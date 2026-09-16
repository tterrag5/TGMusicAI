package com.example.tgmusicai.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.example.tgmusicai.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AlarmWakeLock {
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquire(context: Context) {
        if (wakeLock != null) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TGMusicAI:AlarmWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 minutes timeout
        }
    }

    fun release() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}

/**
 * System [BroadcastReceiver] triggered when a scheduled alarm fires via [AlarmScheduler].
 * Queries the active [com.example.tgmusicai.data.local.entity.Alarm] from database and starts
 * [AlarmPlaybackService], which plays the tone and posts the full-screen-intent notification that
 * launches [AlarmActivity].
 *
 * Playback is started here, from the service, rather than waiting for [AlarmActivity] to launch:
 * a full-screen-intent notification only auto-launches its Activity when the device is locked or
 * the screen is off. With the screen already on and unlocked, Android instead shows a plain
 * heads-up notification and waits for a tap -- if audio only started inside the Activity, that
 * case fired an alarm with a visible notification and no sound at all.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        if (alarmId == -1L) return

        AlarmWakeLock.acquire(context)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val alarm = db.alarmDao().getAlarmById(alarmId)

                if (alarm != null && alarm.isEnabled) {
                    AlarmPlaybackService.start(context, alarmId)

                    // If non-repeating alarm, update DB to disabled state; otherwise reschedule for next occurrence
                    if (alarm.repeatDays.isBlank()) {
                        db.alarmDao().updateAlarm(alarm.copy(isEnabled = false))
                    } else {
                        AlarmScheduler.scheduleAlarm(context, alarm)
                    }
                } else {
                    AlarmWakeLock.release()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
