# Alarm Triggering Issue Fix Report

This document summarizes the architectural changes implemented to ensure the alarm accurately triggers and turns on the screen from sleep/lock modes on Android.

## Architectural Changes Made

### 1. **Wakelock Management (`AlarmWakeLock`)**
   - **Why:** When `AlarmManager` wakes up the device to broadcast an intent to `AlarmReceiver`, the system guarantees a short Wakelock only for the duration of the `onReceive` method. Once `onReceive` finishes (or when moving to an async block/coroutine), the device may go back to sleep before the activity actually launches or loads UI.
   - **Fix:** Implemented a standalone `AlarmWakeLock` object in `AlarmReceiver.kt`. It acquires a `PARTIAL_WAKE_LOCK` for up to 10 minutes at the start of `onReceive()`. 
   - **Handoff:** The lock is explicitly released when `AlarmActivity` calls `onCreate()`, smoothly handing over the responsibility to keep the screen active to the `WindowManager` flags within the Activity itself.

### 2. **Full-Screen Intent Notification (`AlarmReceiver.kt`)**
   - **Why:** On newer Android versions (Android 10+), launching an Activity directly from the background using `startActivity()` is strictly restricted and will silently fail if the app isn't already in the foreground.
   - **Fix:** Used a High-Priority `NotificationChannel` and `NotificationCompat.Builder` targeting `CATEGORY_ALARM`. 
   - We wrapped the `AlarmActivity` Intent in a `PendingIntent` and attached it via `.setFullScreenIntent(pendingIntent, true)`. 
   - This explicitly signals the OS to launch our `AlarmActivity` bypassing background restrictions.

### 3. **Permissions**
   - **Added/Verified `USE_FULL_SCREEN_INTENT`:** Required for `setFullScreenIntent()` to operate successfully.
   - **Added/Verified `WAKE_LOCK`:** Required to use `PowerManager.WakeLock`.

### 4. **Activity Window Setup (`AlarmActivity.kt`)**
   - **Notification Cleanup:** Now dynamically clears the alarm notification ID from the status bar on `onCreate` (since the notification did its job of launching the Activity).
   - **Lock Screen Overlays:** The Activity actively sets `setShowWhenLocked(true)` and `setTurnScreenOn(true)` natively for modern APIs (and fallback flags for legacy), combined with EdgeToEdge support to draw properly over the keyguard.

---
**Summary for Claude:**
You can safely assume that the standard Android 10+ background activity launch restrictions have been resolved via Full-Screen Intents, and that CPU sleep races between `AlarmReceiver` and `AlarmActivity` have been mitigated via a manual `PowerManager.WakeLock` handoff.