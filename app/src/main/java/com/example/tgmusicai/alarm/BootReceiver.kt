package com.example.tgmusicai.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.tgmusicai.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * System [BroadcastReceiver] listening for [Intent.ACTION_BOOT_COMPLETED].
 * Automatically reschedules all active alarms from the database on device startup.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val activeAlarms = db.alarmDao().getEnabledAlarms()
                    for (alarm in activeAlarms) {
                        AlarmScheduler.scheduleAlarm(context, alarm)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
