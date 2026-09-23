package com.example.tgmusicai.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.local.entity.Alarm
import com.example.tgmusicai.data.local.entity.AlarmToneType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground [Service] that actually plays the alarm tone, started directly by [AlarmReceiver] the
 * instant an alarm fires -- independent of whether Android also auto-launches the full-screen
 * [AlarmActivity] UI.
 *
 * That independence matters because a full-screen-intent notification only auto-launches its
 * Activity when the device is locked or the screen is off; with the screen already on and
 * unlocked, Android instead shows it as a plain heads-up notification and waits for a tap. Audio
 * playback used to live entirely inside `AlarmActivity.onCreate()`, so in that screen-on case the
 * alarm fired, a notification appeared, and nothing audible ever happened unless the user
 * happened to tap it open -- matching a real report of "alarm sent a notification but made no
 * noise". Running playback here instead means the alarm is always audible the instant it fires,
 * regardless of screen state; `AlarmActivity`, whenever it does become visible, just attaches to
 * this already-playing service for its title display and Dismiss/Snooze controls.
 */
class AlarmPlaybackService : Service() {

    private var player: ExoPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var loadJob: Job? = null
    private var rampJob: Job? = null
    private var rampUpEnabled: Boolean = false
    private var currentAlarm: Alarm? = null
    private var currentAlarmId: Long = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L) ?: -1L
        currentAlarmId = alarmId
        instance = this

        startForeground(NOTIFICATION_ID, buildNotification(alarmId, label = "Alarm"))
        // The wake lock only needed to bridge the gap between the receiver firing and this
        // foreground service actually starting -- from here on the foreground service itself
        // (plus the actively-playing ExoPlayer) keeps the process alive.
        AlarmWakeLock.release()

        preparePlayer()

        loadJob?.cancel()
        loadJob = serviceScope.launch {
            // Volume behavior is per-alarm (Alarm.forceMaxVolume/volumeRampUp), not a global
            // setting -- so the Alarm row has to be loaded before either can be applied. There's
            // no alarm to look up in the -1L fallback-ringtone case, so both default off there.
            val alarm = if (alarmId != -1L) {
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(applicationContext).alarmDao().getAlarmById(alarmId)
                }
            } else {
                null
            }
            currentAlarm = alarm

            ensureAudibleAlarmVolume(alarm?.forceMaxVolume ?: false)
            rampUpEnabled = alarm?.volumeRampUp ?: false
            player?.volume = if (rampUpEnabled) RAMP_START_VOLUME else 1f

            if (alarm == null) {
                playFallbackRingtone()
                return@launch
            }
            updateNotification(alarmId, alarm.label.ifBlank { "Alarm" })
            prepareAndPlayTone(alarm)
        }

        return START_NOT_STICKY
    }

    /**
     * Ensures the device's `STREAM_ALARM` volume is audible: forced straight to max if
     * [forceMax] is enabled (like Android's own Clock app setting), otherwise just raised to a
     * ~60% floor if it's currently lower. `USAGE_ALARM` audio correctly bypasses silent/DND
     * mode, but the alarm stream has its own independent volume slider that's easy to leave (or
     * accidentally set) near zero without anyone noticing, which would otherwise make a
     * correctly-firing, correctly-playing alarm still silent.
     */
    private fun ensureAudibleAlarmVolume(forceMax: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxAlarmVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (forceMax) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVolume, 0)
            return
        }
        val minAudibleVolume = (maxAlarmVolume * 0.6f).toInt().coerceAtLeast(1)
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) < minAudibleVolume) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, minAudibleVolume, 0)
        }
    }

    private fun preparePlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_ALARM)
                    .build(),
                // An alarm must never be ducked or paused because some other app holds audio
                // focus (e.g. an active call) - let it play unconditionally.
                false
            )
            .build().apply {
                volume = 1f
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e(TAG, "ExoPlayer playback error for custom alarm tone", error)
                        playFallbackRingtone()
                    }
                })
            }
    }

    private suspend fun prepareAndPlayTone(alarm: Alarm) {
        val db = AppDatabase.getDatabase(applicationContext)
        val mediaItems = mutableListOf<MediaItem>()
        var resolvedTitle = "Alarm Tone"

        when (alarm.toneType) {
            AlarmToneType.SONG -> {
                val song = db.songDao().getSongByUri(alarm.toneUriOrId)
                    ?: alarm.toneUriOrId.toLongOrNull()?.let { db.songDao().getSongById(it) }
                if (song != null) {
                    mediaItems.add(MediaItem.fromUri(song.mediaUri))
                    resolvedTitle = "${song.title} - ${song.artist}"
                }
            }
            AlarmToneType.PLAYLIST -> {
                val playlistId = alarm.toneUriOrId.toLongOrNull()
                if (playlistId != null) {
                    val playlistWithSongs = db.playlistDao().getPlaylistWithSongsSync(playlistId)
                    if (playlistWithSongs != null && playlistWithSongs.songs.isNotEmpty()) {
                        resolvedTitle = "Playlist: ${playlistWithSongs.playlist.name}"
                        playlistWithSongs.songs.shuffled().forEach { song ->
                            mediaItems.add(MediaItem.fromUri(song.mediaUri))
                        }
                    }
                }
            }
            AlarmToneType.RANDOM_LIKED -> {
                val likedPlaylist = db.playlistDao().getPlaylistByName(
                    com.example.tgmusicai.data.repository.MusicRepository.LIKED_MUSIC_NAME
                )
                val likedSongs = likedPlaylist?.let { db.playlistDao().getPlaylistWithSongsSync(it.playlistId)?.songs }
                    ?: emptyList()

                val candidateSongs = likedSongs.ifEmpty {
                    val mostPlayed = db.songStatsDao().getMostPlayedStatsSync(20)
                    val songs = db.songDao().getAllSongsList()
                    songs.filter { song -> mostPlayed.any { it.songId == song.id } }.ifEmpty { songs }
                }

                if (candidateSongs.isNotEmpty()) {
                    val randomSong = candidateSongs.random()
                    mediaItems.add(MediaItem.fromUri(randomSong.mediaUri))
                    resolvedTitle = "Random Song: ${randomSong.title}"
                }
            }
        }

        _toneTitle.value = resolvedTitle
        if (mediaItems.isNotEmpty()) {
            player?.run {
                setMediaItems(mediaItems)
                repeatMode = Player.REPEAT_MODE_ALL
                prepare()
                play()
            }
            startVolumeRampIfEnabled()
        } else {
            playFallbackRingtone()
        }
    }

    private fun playFallbackRingtone() {
        val ringtoneUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        _toneTitle.value = "Alarm Sound"
        player?.run {
            setMediaItem(MediaItem.fromUri(ringtoneUri))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            play()
        }
        startVolumeRampIfEnabled()
    }

    /**
     * Gradually raises the player's volume from [RAMP_START_VOLUME] to full over [RAMP_DURATION_MS],
     * like Android Clock's "gradually increase volume" setting, instead of hitting full volume the
     * instant the alarm starts. No-op if the ramp-up preference isn't enabled.
     */
    private fun startVolumeRampIfEnabled() {
        if (!rampUpEnabled) return
        rampJob?.cancel()
        rampJob = serviceScope.launch {
            val steps = (RAMP_DURATION_MS / RAMP_STEP_MS).toInt().coerceAtLeast(1)
            for (step in 1..steps) {
                delay(RAMP_STEP_MS)
                val fraction = step.toFloat() / steps
                player?.volume = RAMP_START_VOLUME + (1f - RAMP_START_VOLUME) * fraction
            }
        }
    }

    private fun buildNotification(alarmId: Long, label: String): android.app.Notification {
        val activityIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            alarmId.toInt(),
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Used to display alarms"
                    }
                )
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("Tap to open alarm")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(alarmId: Long, label: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(alarmId, label))
    }

    /** Stops playback, cancels the notification, and stops this service. Called from [AlarmActivity]'s Dismiss/Snooze handlers. */
    private fun stopAndFinish() {
        loadJob?.cancel()
        rampJob?.cancel()
        player?.stop()
        player?.release()
        player = null
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        rampJob?.cancel()
        player?.release()
        player = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmPlaybackService"
        private const val CHANNEL_ID = "alarm_channel"
        private const val NOTIFICATION_ID = 424242
        private const val RAMP_START_VOLUME = 0.08f
        private const val RAMP_DURATION_MS = 60_000L
        private const val RAMP_STEP_MS = 1_000L

        private var instance: AlarmPlaybackService? = null

        private val _toneTitle = MutableStateFlow("Alarm Sound")
        /** Resolved display title for whatever is currently ringing (song/playlist/ringtone name). */
        val toneTitle: StateFlow<String> = _toneTitle.asStateFlow()

        /** The alarm currently ringing, if this service is running for one. Read-only snapshot for UI display. */
        fun currentAlarm(): Alarm? = instance?.currentAlarm

        fun start(context: Context, alarmId: Long) {
            val intent = Intent(context, AlarmPlaybackService::class.java)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            context.startForegroundService(intent)
        }

        /** Stops whatever alarm is currently ringing, if any. Safe to call even if nothing is playing. */
        fun stopRinging() {
            instance?.stopAndFinish()
        }
    }
}
