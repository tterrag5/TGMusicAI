package com.example.tgmusicai.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.tgmusicai.data.local.entity.Alarm
import java.util.Calendar

/**
 * Utility object for scheduling, canceling, and snoozing alarms via Android's [AlarmManager].
 *
 * Uses [AlarmManager.setAlarmClock] on API 21+ to ensure exact alarm delivery even during Doze mode,
 * while showing the alarm indicator in the status bar and system clock UI.
 */
object AlarmScheduler {

    /** Action extra filter for alarm intent execution. */
    const val ACTION_TRIGGER_ALARM = "com.example.tgmusicai.ACTION_TRIGGER_ALARM"

    /** Key for passing alarm ID in [Intent] extras. */
    const val EXTRA_ALARM_ID = "com.example.tgmusicai.EXTRA_ALARM_ID"

    /**
     * Schedules or updates a system alarm using [AlarmManager.setAlarmClock] or [AlarmManager.setExactAndAllowWhileIdle].
     *
     * @param context Application or activity context.
     * @param alarm The [Alarm] entity containing schedule parameters.
     */
    fun scheduleAlarm(context: Context, alarm: Alarm) {
        if (!alarm.isEnabled) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // On Android 12+ (API 31+), missing exact-alarm permission surfaces as a SecurityException
        // from setAlarmClock() below, which is caught and falls back to an inexact alarm.
        val triggerTimeMs = calculateNextTriggerTime(alarm)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // System clock UI pending intent launched when tapping quick settings alarm badge
        val showIntent = Intent(context, com.example.tgmusicai.MainActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id.toInt(),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTimeMs, showPendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("AlarmScheduler", "Exact alarm permission not granted, falling back to inexact alarm", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
        }
    }

    /**
     * Cancels an existing scheduled alarm in [AlarmManager].
     *
     * @param context Application context.
     * @param alarm The alarm to cancel.
     */
    fun cancelAlarm(context: Context, alarm: Alarm) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    /**
     * Schedules a temporary snooze alarm for the specified [Alarm.snoozeMinutes] duration from now.
     *
     * @param context Application context.
     * @param alarm The snoozed alarm entity.
     */
    fun scheduleSnooze(context: Context, alarm: Alarm) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val snoozeMs = alarm.snoozeMinutes * 60 * 1000L
        val triggerTimeMs = System.currentTimeMillis() + snoozeMs

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (alarm.id + 100000).toInt(), // Offset ID for snooze request
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("AlarmScheduler", "Exact alarm permission not granted for snooze, falling back to inexact alarm", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMs, pendingIntent)
        }
    }

    /**
     * Calculates the exact future trigger timestamp in milliseconds for an alarm based on time of day
     * and day-of-week repeat options.
     *
     * @param alarm Target alarm to compute.
     * @return Future epoch timestamp in milliseconds.
     */
    fun calculateNextTriggerTime(alarm: Alarm): Long {
        val now = Calendar.getInstance()
        val alarmCal = Calendar.getInstance().apply {
            timeInMillis = alarm.timeInMillis
        }

        val targetHour = alarmCal.get(Calendar.HOUR_OF_DAY)
        val targetMinute = alarmCal.get(Calendar.MINUTE)

        val nextTrigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour)
            set(Calendar.MINUTE, targetMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val repeatDaysList = alarm.repeatDays
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }

        if (repeatDaysList.isEmpty()) {
            // Non-repeating alarm: if time has passed today, trigger tomorrow
            if (nextTrigger.before(now)) {
                nextTrigger.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            // Repeating alarm: find closest matching day of week
            val currentDayOfWeek = nextTrigger.get(Calendar.DAY_OF_WEEK)
            var daysToNext = 0
            while (daysToNext < 7) {
                val candidateDay = ((currentDayOfWeek - 1 + daysToNext) % 7) + 1
                if (repeatDaysList.contains(candidateDay)) {
                    if (daysToNext == 0 && nextTrigger.after(now)) {
                        break
                    } else if (daysToNext > 0) {
                        break
                    }
                }
                daysToNext++
            }
            if (daysToNext > 0) {
                nextTrigger.add(Calendar.DAY_OF_YEAR, daysToNext)
            }
        }
        return nextTrigger.timeInMillis
    }
}
