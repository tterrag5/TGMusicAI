package com.example.tgmusicai.playback

import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.example.tgmusicai.R
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.local.entity.ListeningHistory
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.sponsorblock.SponsorBlockManager
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.example.tgmusicai.widget.NowPlayingWidgetState
import com.example.tgmusicai.widget.refreshNowPlayingWidgets
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Background media playback service extending [MediaLibraryService] for Android Auto support.
 * Exposes a hierarchical, browsable media tree (Root -> automotive categories -> Songs) for head
 * units and media browsers, plus car-screen custom actions (Like / Repeat), Google Assistant
 * voice search, and safe-driving audio focus handling.
 */
class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: AppDatabase
    private lateinit var repository: MusicRepository
    private val youtubeExtractor: YouTubeExtractor by lazy { YouTubeExtractor() }
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val replayGainProcessor = ReplayGainAudioProcessor()
    private lateinit var loudnessNormalization: LoudnessNormalizationManager
    private val sponsorBlock by lazy { SponsorBlockManager() }

    /**
     * SponsorBlock settings, mirrored into fields for the same reason
     * [volumeNormalizationEnabled] is: they are consulted on a media item transition and on the
     * playback ticker, neither of which can afford to suspend on a DataStore read.
     */
    @Volatile
    private var sponsorBlockEnabled: Boolean = false

    @Volatile
    private var sponsorBlockCategories: Set<String> = AppPreferences.DEFAULT_SPONSORBLOCK_CATEGORIES

    /**
     * Whether per-track volume normalization is on. Mirrored into a field because it is read on
     * every media item transition, where suspending to consult DataStore would delay the gain
     * being applied until after the track had already started at the wrong volume.
     */
    @Volatile
    private var volumeNormalizationEnabled: Boolean = true

    /**
     * (Re)attaches the fixed-gain loudness booster to [audioSessionId], releasing any previous
     * instance.
     *
     * This applies the same boost to every track, so it makes the whole library louder without
     * making any two tracks match -- which is why it is used only when per-track normalization is
     * switched off. When normalization is on, [ReplayGainAudioProcessor] is doing the real work and
     * stacking a blanket boost on top of it would push the tracks it just brought into line back
     * toward clipping.
     */
    private fun attachLoudnessEnhancer(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        loudnessEnhancer?.release()
        loudnessEnhancer = if (volumeNormalizationEnabled) {
            null
        } else {
            try {
                LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(LOUDNESS_TARGET_GAIN_MILLIBELS)
                    enabled = true
                }
            } catch (e: Exception) {
                android.util.Log.w("PlaybackService", "Failed to attach LoudnessEnhancer to session $audioSessionId", e)
                null
            }
        }
    }

    /**
     * Applies the volume scale for [mediaItem], so it plays at the same perceived loudness as the
     * track before it.
     *
     * The gain travels in the MediaItem's own extras where one is available, which is the common
     * case and costs no database read on the transition. A queue item built before this feature
     * existed, or one appended by another controller, carries no such value and is looked up by
     * song id instead -- asynchronously, so a slow read delays the correction rather than the
     * track. A track with no known loudness plays unmodified.
     */
    private fun applyReplayGain(mediaItem: MediaItem?) {
        if (!volumeNormalizationEnabled || mediaItem == null) {
            replayGainProcessor.clearTrackScale()
            return
        }

        val extras = mediaItem.mediaMetadata.extras
        val embeddedGain = SongMediaExtras.replayGainDb(extras)
        if (embeddedGain != null) {
            replayGainProcessor.setTrackScale(
                ReplayGainAudioProcessor.gainToScale(embeddedGain, SongMediaExtras.replayPeak(extras))
            )
            return
        }

        replayGainProcessor.clearTrackScale()
        val songId = SongMediaExtras.songId(extras) ?: return
        serviceScope.launch {
            val song = try {
                database.songDao().getSongById(songId)
            } catch (e: Throwable) {
                android.util.Log.w("PlaybackService", "Could not load loudness for song $songId", e)
                null
            } ?: return@launch
            // The queue may have moved on while this read was in flight; applying a stale gain
            // would be worse than applying none.
            val stillCurrent = withContext(Dispatchers.Main) {
                player.currentMediaItem?.mediaId == mediaItem.mediaId
            }
            if (!stillCurrent) return@launch
            val gain = song.replayGainDb
            if (gain != null) {
                replayGainProcessor.setTrackScale(
                    ReplayGainAudioProcessor.gainToScale(gain, song.replayPeak)
                )
            } else {
                // Not measured yet. Measure it now so the next play of this track is normalized;
                // correcting it mid-play would be an audible jump in volume.
                loudnessNormalization.analyzeIfNeeded(song)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getDatabase(this)
        repository = MusicRepository(
            songDao = database.songDao(),
            playlistDao = database.playlistDao(),
            songStatsDao = database.songStatsDao(),
            alarmDao = database.alarmDao(),
            listeningHistoryDao = database.listeningHistoryDao()
        )

        // Zero-wasted-data stream caching: every HTTP(S) byte range ExoPlayer reads (YouTube
        // audio streams in particular) is written through to a shared 500MB LRU disk cache, so
        // re-listening to a recently played stream replays entirely from disk -- 0MB of data and
        // works offline -- instead of re-downloading it from the network every time.
        // Only http(s):// (YouTube streams) goes through the disk cache; local file:// / content://
        // tracks (downloaded/scanned songs) bypass it entirely -- see SchemeAwareCacheDataSource.
        val cacheDataSourceFactory = SchemeAwareCacheDataSourceFactory(this, AudioCacheManager.getCache(this))

        // Initialize ExoPlayer with Safe Driving Audio Focus: automatic focus handling ducks
        // music for GPS voice prompts (AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK), pauses for phone calls
        // (AUDIOFOCUS_LOSS_TRANSIENT) and resumes on AUDIOFOCUS_GAIN, and pauses when a
        // Bluetooth/aux device disconnects mid-playback (setHandleAudioBecomingNoisy).
        loudnessNormalization = LoudnessNormalizationManager(this, database.songDao())

        // Per-track volume normalization has to sit inside the audio pipeline, because it scales
        // the samples themselves. The processor is inserted ahead of ExoPlayer's own chain rather
        // than replacing it: passing the default silence-skipping and speed-adjustment processors
        // through keeps `skipSilenceEnabled` and playback speed working, both of which silently
        // stop responding if the chain is rebuilt without them.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            arrayOf(replayGainProcessor),
                            SilenceSkippingAudioProcessor(),
                            SonicAudioProcessor()
                        )
                    )
                    .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // handle audio focus automatically
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                // Loudness Normalization: attaches a LoudnessEnhancer to whatever audio session
                // id ExoPlayer is currently using, balancing playback volume across quiet local
                // FLACs and loud YouTube streams. The session id can change (e.g. across
                // player/renderer resets), so this re-attaches on every change rather than once.
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                        attachLoudnessEnhancer(audioSessionId)
                    }
                })
                var consecutivePlaybackErrors = 0
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("PlaybackService", "Playback error encountered: ${error.message}", error)
                        consecutivePlaybackErrors++
                        // Cascade guard: a queue of not-yet-resolved cloud tracks (e.g. "Cloud
                        // Nine", or any freshly-loaded queue whose background resolution hasn't
                        // caught up yet -- see MediaControllerManager.resolveRemainingInBackground)
                        // has every remaining item's mediaUri still pointing at a raw YouTube watch
                        // URL, which fails instantly when ExoPlayer tries to play it as audio.
                        // Unconditionally skipping-on-error used to chain through the entire rest
                        // of the queue in a fraction of a second, looking like the app was trying
                        // to play everything at once. Give up after a few in a row instead of
                        // fighting the resolver -- it'll patch working URLs back in shortly.
                        if (consecutivePlaybackErrors <= MAX_CONSECUTIVE_PLAYBACK_ERRORS && hasNextMediaItem()) {
                            seekToNextMediaItem()
                            prepare()
                            play()
                        } else {
                            android.util.Log.w("PlaybackService", "Stopping auto-skip after $consecutivePlaybackErrors consecutive playback error(s)")
                            pause()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // A real, successful start (not another instant re-failure) -- reset the
                        // cascade guard so a later, unrelated run of errors isn't cut short by an
                        // error count left over from an earlier unrelated skip sequence.
                        if (isPlaying) consecutivePlaybackErrors = 0
                        if (isPlaying) startTelemetryTicker(this@apply) else stopTelemetryTicker()
                        publishWidgetState(currentMediaItem, isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // Last-resort safety net: normally maybeExtendQueueForAutoplay (triggered
                        // from onMediaItemTransition, below) already appends more songs the moment
                        // the last track in the queue starts playing, well before it ends. This
                        // only fires if that never got the chance to (e.g. no downloaded songs
                        // existed yet at that point).
                        if (playbackState == Player.STATE_ENDED &&
                            repeatMode == Player.REPEAT_MODE_OFF &&
                            !hasNextMediaItem()
                        ) {
                            playRandomSongAsAutoplay(this@apply)
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        // Proactively extend the queue as soon as playback reaches what is
                        // currently the last item, so there's always a "next" track queued up
                        // well before the current one ends -- keeps music going seamlessly
                        // (repeat off) AND makes the auto-added songs show up in "Up Next" and be
                        // skippable, instead of only ever appearing reactively after a stop.
                        maybeExtendQueueForAutoplay(this@apply)
                        resetListenTracking(mediaItem)
                        applyReplayGain(mediaItem)
                        loadSponsorSegments(mediaItem)
                        publishWidgetState(mediaItem, isPlaying)
                        updateCustomLayout()
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        updateCustomLayout()
                    }
                })
            }

        // Skip Silence: auto-trims dead silence at the start/end of tracks (common on
        // YouTube-sourced audio, which often has a beat of silence baked into the upload) via
        // ExoPlayer's own built-in silence-skipping audio processor. Observed continuously (not
        // just read once) so flipping the setting screen toggle takes effect immediately.
        serviceScope.launch(Dispatchers.Main) {
            AppPreferences(applicationContext).skipSilenceEnabledFlow.collect { enabled ->
                player.skipSilenceEnabled = enabled
            }
        }

        // Volume normalization, observed rather than read once so the Settings toggle takes effect
        // on the track already playing. Turning it off also restores the blanket loudness boost it
        // replaces, and turning it on tears that boost back down.
        serviceScope.launch(Dispatchers.Main) {
            AppPreferences(applicationContext).volumeNormalizationEnabledFlow.collect { enabled ->
                volumeNormalizationEnabled = enabled
                applyReplayGain(player.currentMediaItem)
                attachLoudnessEnhancer(player.audioSessionId)
            }
        }

        // SponsorBlock settings. Switching the feature on mid-track fetches segments for what is
        // already playing rather than waiting for the next one.
        serviceScope.launch(Dispatchers.Main) {
            val preferences = AppPreferences(applicationContext)
            preferences.sponsorBlockEnabledFlow
                .combine(preferences.sponsorBlockCategoriesFlow) { enabled, categories -> enabled to categories }
                .collect { (enabled, categories) ->
                    sponsorBlockEnabled = enabled
                    sponsorBlockCategories = categories
                    loadSponsorSegments(player.currentMediaItem)
                }
        }

        // Work through the library's unmeasured tracks in the background, so normalization applies
        // to songs the user has not played yet rather than only correcting each track from its
        // second play onward.
        serviceScope.launch {
            while (isActive) {
                val analyzed = loudnessNormalization.runBackfillBatch()
                if (analyzed == 0) break
                // A pause between batches keeps a large library's measurement pass from competing
                // with playback for CPU on a low-end device.
                delay(LOUDNESS_BACKFILL_PAUSE_MS)
            }
        }

        // Tapping the media notification opens the app straight to the Now Playing screen,
        // instead of doing nothing / opening to whatever screen was last shown.
        val openNowPlayingIntent = Intent(this, com.example.tgmusicai.MainActivity::class.java).apply {
            action = ACTION_OPEN_NOW_PLAYING
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            openNowPlayingIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Build the MediaLibrarySession with custom callback for Android Auto browsing & media resolution
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, CustomMediaLibrarySessionCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        // Show the initial Like/Repeat button state on the car screen before anything plays.
        updateCustomLayout()
    }

    companion object {
        const val ACTION_OPEN_NOW_PLAYING = "com.example.tgmusicai.action.OPEN_NOW_PLAYING"
        // +500 millibels (+5dB) target gain -- audible loudness normalization without pushing
        // already-loud YouTube streams into clipping/distortion.
        private const val LOUDNESS_TARGET_GAIN_MILLIBELS = 500
        private const val MAX_CONSECUTIVE_PLAYBACK_ERRORS = 2
        private const val LISTEN_TIME_FLUSH_INTERVAL_MS = 10_000L
        /** Idle gap between loudness-measurement batches, so the pass never competes with playback. */
        private const val LOUDNESS_BACKFILL_PAUSE_MS = 5_000L
        /**
         * A segment ending within this much of the track's end is treated as running to the end,
         * so skipping it advances to the next track instead of seeking to a position that would
         * immediately end playback anyway.
         */
        private const val SPONSOR_END_GUARD_MS = 1_000L
        private const val YOUTUBE_FALLBACK_SEARCH_CANDIDATES = 6
        private const val YOUTUBE_FALLBACK_RESOLVE_TARGET = 3

        // Automotive media tree category IDs (children of ROOT_ID, surfaced via onGetChildren).
        const val ROOT_ID = "root"
        const val CATEGORY_DOWNLOADED = "category_downloaded"
        const val CATEGORY_LIKED = "category_liked"
        const val CATEGORY_RECENT = "category_recent"
        const val CATEGORY_PLAYLISTS = "category_playlists"
        const val CATEGORY_MOST_PLAYED = "category_most_played"
        const val CATEGORY_PRODUCERS = "category_producers"
        const val CATEGORY_ALL_SONGS = "category_all_songs"

        // Custom car-screen action IDs, handled in onCustomCommand.
        const val ACTION_TOGGLE_LIKE = "com.example.tgmusicai.ACTION_TOGGLE_LIKE"
        const val ACTION_TOGGLE_REPEAT_MODE = "com.example.tgmusicai.ACTION_TOGGLE_REPEAT_MODE"

        // Content style extra keys/values Android Auto reads to choose Grid vs List rendering.
        const val EXTRAS_KEY_CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE"
        const val EXTRAS_KEY_CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE"
        const val EXTRAS_VALUE_CONTENT_STYLE_GRID = 1
        const val EXTRAS_VALUE_CONTENT_STYLE_LIST = 2

        // External processes that read MediaItem artwork over IPC and need a per-URI read grant
        // for content:// URIs minted from local file:// cover art (see resolveCarArtworkUri).
        private val CAR_ART_CONSUMER_PACKAGES = listOf(
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.googlequicksearchbox" // Google app / Assistant / Gemini
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    /**
     * Resolves a [Song.artworkUri] into a URI Android Auto's Gearhead process and the
     * Assistant/Gemini app can actually open. Scraped local covers (see [CoverArtScraper]) are
     * stored as `file://` paths into this app's private external storage, which those other
     * processes cannot read over IPC -- handing that URI straight to a MediaItem silently fails
     * to load art on the car screen. Remote/http(s) URIs (YouTube thumbnails, etc.) are returned
     * unchanged since the receiving app fetches those itself.
     */
    private fun resolveCarArtworkUri(rawUri: String?): Uri? {
        if (rawUri.isNullOrBlank()) return null
        if (!rawUri.startsWith("file:")) return Uri.parse(rawUri)
        return try {
            val file = Uri.parse(rawUri).path?.let { java.io.File(it) } ?: return null
            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            for (pkg in CAR_ART_CONSUMER_PACKAGES) {
                grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            contentUri
        } catch (e: Exception) {
            android.util.Log.w("PlaybackService", "Failed to resolve car-safe artwork URI for $rawUri", e)
            null
        }
    }

    // Guards maybeExtendQueueForAutoplay against re-triggering for the same "now playing the
    // last item" position on every transition callback for that same item.
    private var lastAutoExtendedAtIndex = -1

    /**
     * Appends a few random already-downloaded songs to the live queue as soon as playback
     * reaches the current last item, provided repeat is off -- so the queue never actually runs
     * out (repeat off), the new songs are visible and skippable in "Up Next" (since they're
     * really appended to the player's timeline, not swapped in reactively after a stop), and nothing
     * audibly interrupts playback.
     */
    private fun maybeExtendQueueForAutoplay(exoPlayer: ExoPlayer) {
        if (exoPlayer.repeatMode != Player.REPEAT_MODE_OFF) return
        if (exoPlayer.hasNextMediaItem()) return
        val currentIndex = exoPlayer.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex == lastAutoExtendedAtIndex) return
        lastAutoExtendedAtIndex = currentIndex

        val existingIds = (0 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it).mediaId }.toSet()
        serviceScope.launch {
            val candidates = database.songDao().getRandomDownloadedSongs(5)
                .filter { it.mediaUri !in existingIds }
            if (candidates.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val items = candidates.map { song ->
                    MediaItem.Builder()
                        .setMediaId(song.mediaUri)
                        .setUri(Uri.parse(song.mediaUri))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.title)
                                .setArtist(song.artist)
                                .setAlbumTitle(song.album)
                                .setArtworkUri(resolveCarArtworkUri(song.artworkUri))
                                .setIsPlayable(true)
                                .setExtras(SongMediaExtras.fromSong(song))
                                .build()
                        )
                        .build()
                }
                exoPlayer.addMediaItems(items)
                android.util.Log.d("PlaybackService", "Auto-extended queue with ${items.size} random song(s)")
            }
        }
    }

    /**
     * Picks a random already-downloaded song (different from the one that just ended, if
     * possible) and starts playing it, so the queue ending with repeat off doesn't just stop.
     */
    private fun playRandomSongAsAutoplay(exoPlayer: ExoPlayer) {
        val lastMediaId = exoPlayer.currentMediaItem?.mediaId
        serviceScope.launch {
            val candidates = database.songDao().getRandomDownloadedSongs(3)
            val next = candidates.firstOrNull { it.mediaUri != lastMediaId } ?: candidates.firstOrNull()
            if (next == null) return@launch
            withContext(Dispatchers.Main) {
                val item = MediaItem.Builder()
                    .setMediaId(next.mediaUri)
                    .setUri(Uri.parse(next.mediaUri))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(next.title)
                            .setArtist(next.artist)
                            .setAlbumTitle(next.album)
                            .setArtworkUri(resolveCarArtworkUri(next.artworkUri))
                            .setIsPlayable(true)
                            .setExtras(SongMediaExtras.fromSong(next))
                            .build()
                    )
                    .build()
                exoPlayer.setMediaItem(item)
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
    }

    // Play-tracking & listen-time telemetry -- deliberately owned by this Service (not the
    // UI-side MediaControllerManager) because MainActivity.onDestroy() releases and cancels
    // MediaControllerManager's CoroutineScope, silently killing any telemetry tracking that lived
    // there for every song played after the Activity is gone even though this Service (and its
    // ExoPlayer) keeps playing music in the background the whole time. Owning it here means it
    // runs for as long as the Service does, independent of Activity lifecycle -- fixing the "plays
    // during background listening are never counted" bug.
    private var trackedMediaId: String? = null
    private var trackedSongId: Long? = null
    private var trackedYoutubeId: String? = null
    private var accumulatedListenMs: Long = 0L
    private var pendingFlushMs: Long = 0L
    private var playRecordedForCurrentItem: Boolean = false
    private var lastTickPositionMs: Long = 0L
    private var telemetryTickerJob: Job? = null

    private fun resetListenTracking(mediaItem: MediaItem?) {
        flushPendingListenTime()
        trackedMediaId = mediaItem?.mediaId
        val extras = mediaItem?.mediaMetadata?.extras
        trackedSongId = SongMediaExtras.songId(extras)
        trackedYoutubeId = SongMediaExtras.youtubeId(extras)
        accumulatedListenMs = 0L
        pendingFlushMs = 0L
        playRecordedForCurrentItem = false
        lastTickPositionMs = 0L
    }

    private fun startTelemetryTicker(exoPlayer: ExoPlayer) {
        telemetryTickerJob?.cancel()
        telemetryTickerJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.let { if (it > 0) it else 0L }
                maybeSkipSponsorSegment(pos)
                val delta = (pos - lastTickPositionMs).coerceIn(0L, 2000L)
                lastTickPositionMs = pos
                if (delta > 0) {
                    accumulatedListenMs += delta
                    pendingFlushMs += delta
                    if (!playRecordedForCurrentItem) {
                        val threshold = if (dur > 0) minOf(30_000L, dur / 2) else 30_000L
                        if (accumulatedListenMs >= threshold) {
                            playRecordedForCurrentItem = true
                            recordPlay()
                        }
                    }
                    if (pendingFlushMs >= LISTEN_TIME_FLUSH_INTERVAL_MS) {
                        flushPendingListenTime()
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopTelemetryTicker() {
        telemetryTickerJob?.cancel()
        telemetryTickerJob = null
        flushPendingListenTime()
    }

    /** Resolves the tracked song's real DB id, preferring the id embedded in the MediaItem's extras. */
    private suspend fun resolveTrackedSongId(): Long? {
        trackedSongId?.let { return it }
        val mediaId = trackedMediaId
        val byUri = mediaId?.let { database.songDao().getSongByUri(it)?.id }
        if (byUri != null) return byUri
        return trackedYoutubeId?.takeIf { it.isNotBlank() }?.let { database.songDao().getSongByYoutubeId(it)?.id }
    }

    private fun recordPlay() {
        if (trackedMediaId == null) return
        serviceScope.launch(Dispatchers.IO) {
            val songId = resolveTrackedSongId() ?: return@launch
            trackedSongId = songId
            database.songStatsDao().incrementPlayCount(songId)
        }
    }

    private fun flushPendingListenTime() {
        val ms = pendingFlushMs
        if (ms <= 0L || trackedMediaId == null) {
            pendingFlushMs = 0L
            return
        }
        pendingFlushMs = 0L
        serviceScope.launch(Dispatchers.IO) {
            val songId = resolveTrackedSongId() ?: return@launch
            trackedSongId = songId
            database.songStatsDao().addListenTime(songId, ms)
            database.listeningHistoryDao().insert(ListeningHistory(songId = songId, durationMs = ms))
        }
    }

    // SponsorBlock state for the track currently playing. Both are replaced wholesale on every
    // media item transition rather than accumulated, so segments from a previous track can never
    // be applied to this one.
    private var sponsorSegments: List<SponsorBlockManager.Segment> = emptyList()
    private val skippedSegmentIds = mutableSetOf<String>()
    private var sponsorFetchJob: Job? = null

    /**
     * Loads the non-music segments for [mediaItem], if it is a YouTube track and the feature is on.
     *
     * Only cloud tracks have segments: the database is keyed by YouTube video id, and a local file
     * has none. Fetching is fire-and-forget -- playback starts immediately and segments apply from
     * whenever they arrive, which is within a second or so and long before most of them matter.
     */
    private fun loadSponsorSegments(mediaItem: MediaItem?) {
        sponsorFetchJob?.cancel()
        sponsorSegments = emptyList()
        skippedSegmentIds.clear()

        if (!sponsorBlockEnabled) return
        val videoId = SongMediaExtras.youtubeId(mediaItem?.mediaMetadata?.extras)
            ?.takeIf { it.isNotBlank() } ?: return

        sponsorFetchJob = serviceScope.launch {
            val segments = sponsorBlock.fetchSegments(videoId, sponsorBlockCategories)
            val stillCurrent = withContext(Dispatchers.Main) {
                player.currentMediaItem?.mediaId == mediaItem?.mediaId
            }
            if (stillCurrent) {
                sponsorSegments = segments
                if (segments.isNotEmpty()) {
                    android.util.Log.d("PlaybackService", "SponsorBlock: ${segments.size} segment(s) for $videoId")
                }
            }
        }
    }

    /**
     * Seeks past any segment [positionMs] has entered.
     *
     * Called from the existing telemetry ticker rather than a second timer of its own, so segment
     * checking costs nothing beyond a list scan on a tick that already happens.
     *
     * A segment is skipped at most once per track. Without that, seeking back into a skipped
     * section to hear it deliberately would be undone instantly, leaving the user unable to reach
     * part of their own audio.
     */
    private fun maybeSkipSponsorSegment(positionMs: Long) {
        if (sponsorSegments.isEmpty()) return
        val segment = sponsorSegments.firstOrNull { candidate ->
            positionMs >= candidate.startMs &&
                positionMs < candidate.endMs &&
                candidate.id !in skippedSegmentIds
        } ?: return

        skippedSegmentIds.add(segment.id)
        serviceScope.launch(Dispatchers.Main) {
            val duration = player.duration
            // Landing past the end of the track would end playback rather than skip within it.
            if (duration > 0 && segment.endMs >= duration - SPONSOR_END_GUARD_MS) {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                return@launch
            }
            player.seekTo(segment.endMs)
            android.util.Log.d(
                "PlaybackService",
                "SponsorBlock: skipped ${segment.displayName} (${segment.startMs}-${segment.endMs}ms)"
            )
        }
    }

    /**
     * Mirrors what is playing into the snapshot the home-screen widget renders from, then asks
     * every placed widget to redraw.
     *
     * The widget lives in the launcher's process and cannot see the player, so this push is the
     * only thing that keeps it current. Failures are swallowed: a widget that shows a stale track
     * is a far smaller problem than one that takes playback down with it.
     */
    private fun publishWidgetState(mediaItem: MediaItem?, isPlaying: Boolean) {
        serviceScope.launch {
            try {
                val metadata = mediaItem?.mediaMetadata
                NowPlayingWidgetState.write(
                    context = applicationContext,
                    title = metadata?.title?.toString(),
                    artist = metadata?.artist?.toString(),
                    // The raw stored artwork URI, not the FileProvider one minted for Android Auto:
                    // the widget reads it inside this app's own process, where a plain path works
                    // and a per-consumer content:// grant would only get in the way.
                    artworkUri = SongMediaExtras.artworkUri(metadata?.extras)
                        ?: metadata?.artworkUri?.toString(),
                    isPlaying = isPlaying
                )
                refreshNowPlayingWidgets(applicationContext)
            } catch (e: Throwable) {
                android.util.Log.w("PlaybackService", "Could not update the home-screen widget", e)
            }
        }
    }

    /**
     * Rebuilds and pushes the car-screen custom action row (Like toggle + 3-state Repeat toggle)
     * to reflect the currently-playing song's liked status and the player's current repeat mode.
     * Called on every media item transition, repeat mode change, custom-command handling, and
     * once at startup so a freshly-connected head unit sees the correct initial state.
     */
    private fun updateCustomLayout() {
        val session = mediaLibrarySession ?: return
        serviceScope.launch {
            val repeatMode = withContext(Dispatchers.Main) { player.repeatMode }
            val isLiked = withContext(Dispatchers.IO) {
                resolveTrackedSongId()?.let { repository.isSongLikedSync(it) } ?: false
            }

            val likeButton = CommandButton.Builder(
                if (isLiked) CommandButton.ICON_THUMB_UP_FILLED else CommandButton.ICON_THUMB_UP_UNFILLED
            )
                .setDisplayName(if (isLiked) "Unlike" else "Like")
                .setIconResId(if (isLiked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up_outline)
                .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
                .build()

            val (repeatIcon, repeatIconRes, repeatTitle) = when (repeatMode) {
                Player.REPEAT_MODE_ALL -> Triple(CommandButton.ICON_REPEAT_ALL, R.drawable.ic_repeat_all, "Repeat: All")
                Player.REPEAT_MODE_ONE -> Triple(CommandButton.ICON_REPEAT_ONE, R.drawable.ic_repeat_one, "Repeat: 1")
                else -> Triple(CommandButton.ICON_REPEAT_OFF, R.drawable.ic_repeat_off, "Repeat: Off")
            }
            val repeatButton = CommandButton.Builder(repeatIcon)
                .setDisplayName(repeatTitle)
                .setIconResId(repeatIconRes)
                .setSessionCommand(SessionCommand(ACTION_TOGGLE_REPEAT_MODE, Bundle.EMPTY))
                .build()

            withContext(Dispatchers.Main) {
                session.setCustomLayout(ImmutableList.of(likeButton, repeatButton))
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            // Stop service if not actively playing
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopTelemetryTicker()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class CustomMediaLibrarySessionCallback : MediaLibrarySession.Callback {

        /**
         * Grants every connecting controller (including Android Auto and Google Assistant) the
         * custom session commands used by the car-screen Like/Repeat buttons -- without this,
         * onCustomCommand is never invoked and the buttons render disabled.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // super.onConnect() (the deprecated sync overload we're forced to override for the
            // custom session commands below) returns a sentinel EMPTY/EMPTY result, not a usable
            // default -- unlike the newer onConnectAsync, it does NOT grant trusted controllers
            // (including our own app's MediaControllerManager) any player commands at all, which
            // silently blocked every play()/prepare()/setMediaItems() call from the UI. Build the
            // real trust-aware default explicitly instead.
            val defaultResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
            val availableSessionCommands = defaultResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_TOGGLE_REPEAT_MODE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(availableSessionCommands, defaultResult.availablePlayerCommands)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("TGMusic")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                val items = when {
                    parentId == ROOT_ID -> buildRootCategories()
                    parentId == CATEGORY_DOWNLOADED -> fetchDownloadedSongs()
                    parentId == CATEGORY_LIKED -> fetchLikedSongs()
                    parentId == CATEGORY_RECENT -> fetchRecentlyPlayedSongs()
                    parentId == CATEGORY_PLAYLISTS -> fetchPlaylists()
                    parentId == CATEGORY_MOST_PLAYED -> fetchTopPlayedSongs()
                    parentId == CATEGORY_PRODUCERS -> fetchProducersAndArtists()
                    parentId == CATEGORY_ALL_SONGS -> fetchAllSongs()
                    parentId.startsWith("playlist_") -> fetchSongsForPlaylist(parentId.removePrefix("playlist_"))
                    parentId.startsWith("artist_") -> fetchSongsForArtist(parentId.removePrefix("artist_"))
                    parentId.startsWith("producer_") -> fetchSongsForProducer(parentId.removePrefix("producer_"))
                    else -> emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return serviceScope.future {
                val song = database.songDao().getSongByUri(mediaId)
                if (song != null) {
                    LibraryResult.ofItem(song.toMediaItem(), null)
                } else {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            return serviceScope.future {
                val resolvedItems = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    val mediaId = item.mediaId
                    val searchQuery = item.requestMetadata.searchQuery
                    if (mediaId.isNotEmpty()) {
                        val song = database.songDao().getSongByUri(mediaId)
                        if (song != null) {
                            resolvedItems.add(song.toMediaItem())
                        } else {
                            val resolvedItem = item.buildUpon()
                                .setUri(Uri.parse(mediaId))
                                .build()
                            resolvedItems.add(resolvedItem)
                        }
                    } else if (!searchQuery.isNullOrBlank()) {
                        // "Add X to queue on TGMusic" -- Assistant/Gemini's legacy addQueueItem
                        // call arrives here as a mediaId-less item carrying the raw query, same
                        // shape onSetMediaItems handles for "play X on TGMusic".
                        resolvedItems.addAll(searchSongsAndPlaylists(searchQuery).take(1))
                    } else {
                        resolvedItems.add(item)
                    }
                }
                resolvedItems
            }
        }

        /**
         * Handles Google Assistant "play from search" voice commands (e.g. "Hey Google, play
         * Drake on TGMusic"). Android Auto/Assistant deliver these as a legacy
         * `playFromSearch(query, extras)` call, which Media3 forwards here as a single MediaItem
         * carrying the raw query in [MediaItem.RequestMetadata.searchQuery] rather than through
         * [onSearch] (which only powers the browsable search-results UI, not autoplay).
         * Resolving and returning the matching items here makes Media3 automatically call
         * setMediaItems()/prepare()/play() on the resolved queue.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val searchQuery = mediaItems.firstOrNull()?.requestMetadata?.searchQuery
            if (!searchQuery.isNullOrBlank()) {
                return serviceScope.future {
                    val results = searchSongsAndPlaylists(searchQuery)
                    MediaSession.MediaItemsWithStartPosition(results, 0, C.TIME_UNSET)
                }
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        /**
         * Powers the browsable "search" affordance (e.g. a head unit's search box): resolves
         * [query] and notifies the session so a follow-up [onGetSearchResult] call can page
         * through the results.
         */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            return serviceScope.future {
                val results = searchSongsAndPlaylists(query)
                session.notifySearchResultChanged(browser, query, results.size, params)
                LibraryResult.ofVoid(params)
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                val results = searchSongsAndPlaylists(query)
                LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_LIKE -> {
                    val currentItem = player.currentMediaItem
                    if (currentItem != null) {
                        serviceScope.launch {
                            val songId = resolveTrackedSongId() ?: run {
                                val metadata = currentItem.mediaMetadata
                                val song = SongMediaExtras.toSong(
                                    mediaUri = currentItem.mediaId,
                                    title = metadata.title?.toString() ?: "Unknown",
                                    artist = metadata.artist?.toString() ?: "Unknown",
                                    album = metadata.albumTitle?.toString() ?: "",
                                    extras = metadata.extras
                                )
                                repository.ensurePersisted(song)
                            }
                            trackedSongId = songId
                            repository.toggleLikeSong(songId)
                            updateCustomLayout()
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                ACTION_TOGGLE_REPEAT_MODE -> {
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    updateCustomLayout()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        // --- Helper methods for automotive category generation ---

        private fun folderItem(id: String, title: String, subtitle: String? = null, gridStyle: Boolean = false): MediaItem {
            val style = if (gridStyle) EXTRAS_VALUE_CONTENT_STYLE_GRID else EXTRAS_VALUE_CONTENT_STYLE_LIST
            val extras = Bundle().apply {
                putInt(EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
                putInt(EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
            }
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .also { builder -> subtitle?.let { builder.setSubtitle(it) } }
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .setExtras(extras)
                        .build()
                )
                .build()
        }

        /**
         * Root category list surfaced under [ROOT_ID]. Grid-style categories (Liked, Recent,
         * Playlists, Producers & Artists) show their children as rich album-art tiles; List-style
         * categories (Downloaded, Most Played, All Songs) show dense, scannable text rows.
         */
        private fun buildRootCategories(): List<MediaItem> = listOf(
            folderItem(CATEGORY_DOWNLOADED, "Downloaded Only", gridStyle = false),
            folderItem(CATEGORY_LIKED, "Liked Music", gridStyle = true),
            folderItem(CATEGORY_RECENT, "Listen Again", gridStyle = true),
            folderItem(CATEGORY_PLAYLISTS, "Playlists", gridStyle = true),
            folderItem(CATEGORY_MOST_PLAYED, "Top 50 Most Played", gridStyle = false),
            folderItem(CATEGORY_PRODUCERS, "Producers & Artists", gridStyle = true),
            folderItem(CATEGORY_ALL_SONGS, "All Songs", gridStyle = false)
        )

        private suspend fun fetchDownloadedSongs(): List<MediaItem> = withContext(Dispatchers.IO) {
            database.songDao().getDownloadedSongsSync().map { it.toMediaItem() }
        }

        private suspend fun fetchLikedSongs(): List<MediaItem> = withContext(Dispatchers.IO) {
            val likedPlaylistId = repository.getOrCreateLikedMusicPlaylistId()
            database.playlistDao().getPlaylistWithSongsSync(likedPlaylistId)?.songs.orEmpty().map { it.toMediaItem() }
        }

        private suspend fun fetchRecentlyPlayedSongs(): List<MediaItem> = withContext(Dispatchers.IO) {
            database.songStatsDao().getRecentlyPlayedStatsSync(20)
                .mapNotNull { stats -> database.songDao().getSongById(stats.songId) }
                .map { it.toMediaItem() }
        }

        private suspend fun fetchPlaylists(): List<MediaItem> = withContext(Dispatchers.IO) {
            database.playlistDao().getAllPlaylistsList()
                .filter { !it.isSmart }
                .map { playlist ->
                    val songCount = database.playlistDao().getSongIdsInPlaylist(playlist.playlistId).size
                    folderItem(
                        id = "playlist_${playlist.playlistId}",
                        title = playlist.name,
                        subtitle = "$songCount Songs",
                        gridStyle = false
                    )
                }
        }

        private suspend fun fetchTopPlayedSongs(): List<MediaItem> = withContext(Dispatchers.IO) {
            database.songStatsDao().getMostPlayedStatsSync(50)
                .mapNotNull { stats -> database.songDao().getSongById(stats.songId) }
                .map { it.toMediaItem() }
        }

        private suspend fun fetchProducersAndArtists(): List<MediaItem> = withContext(Dispatchers.IO) {
            val allSongs = database.songDao().getAllSongsList()
            val artistItems = allSongs.map { it.artist }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .map { artist -> folderItem(id = "artist_$artist", title = artist, gridStyle = false) }
            val producerItems = allSongs.mapNotNull { it.producer?.takeIf { p -> p.isNotBlank() } }
                .distinct()
                .sorted()
                .map { producer -> folderItem(id = "producer_$producer", title = "$producer (Producer)", gridStyle = false) }
            artistItems + producerItems
        }

        private suspend fun fetchAllSongs(): List<MediaItem> = withContext(Dispatchers.IO) {
            database.songDao().getAllSongsList().map { it.toMediaItem() }
        }

        private suspend fun fetchSongsForPlaylist(playlistId: String): List<MediaItem> = withContext(Dispatchers.IO) {
            database.playlistDao().getPlaylistWithSongsSync(playlistId.toLongOrNull() ?: 0L)?.songs.orEmpty().map { it.toMediaItem() }
        }

        private suspend fun fetchSongsForArtist(artist: String): List<MediaItem> = withContext(Dispatchers.IO) {
            database.songDao().getAllSongsList().filter { it.artist == artist }.map { it.toMediaItem() }
        }

        private suspend fun fetchSongsForProducer(producer: String): List<MediaItem> = withContext(Dispatchers.IO) {
            database.songDao().getAllSongsList().filter { it.producer == producer }.map { it.toMediaItem() }
        }

        /**
         * Resolves a driver's voice/text query against the local library first, falling back to
         * a live YouTube InnerTube search when nothing local matches -- see Part 3 of
         * ANDROID_AUTO_UI_AND_FEATURES_SPEC.md for the full query-to-resolution matrix.
         */
        private suspend fun searchSongsAndPlaylists(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()

            if (Regex("(?i)\\bliked\\b|\\bfavorites?\\b").containsMatchIn(trimmed)) {
                val likedPlaylistId = repository.getOrCreateLikedMusicPlaylistId()
                val likedSongs = database.playlistDao().getPlaylistWithSongsSync(likedPlaylistId)?.songs.orEmpty()
                if (likedSongs.isNotEmpty()) return@withContext likedSongs.map { it.toMediaItem() }
            }

            val playlistMatch = database.playlistDao().getAllPlaylistsList()
                .firstOrNull { it.name.contains(trimmed, ignoreCase = true) }
            if (playlistMatch != null) {
                val songs = database.playlistDao().getPlaylistWithSongsSync(playlistMatch.playlistId)?.songs.orEmpty()
                if (songs.isNotEmpty()) return@withContext songs.map { it.toMediaItem() }
            }

            val allSongs = database.songDao().getAllSongsList()

            val exactTitle = allSongs.filter { it.title.equals(trimmed, ignoreCase = true) }
            if (exactTitle.isNotEmpty()) return@withContext exactTitle.map { it.toMediaItem() }

            val artistMatches = allSongs.filter { it.artist.contains(trimmed, ignoreCase = true) }
            if (artistMatches.isNotEmpty()) return@withContext artistMatches.map { it.toMediaItem() }

            val fuzzyTitleMatches = allSongs.filter { it.title.contains(trimmed, ignoreCase = true) }
            if (fuzzyTitleMatches.isNotEmpty()) return@withContext fuzzyTitleMatches.map { it.toMediaItem() }

            // No local matches -- fall back to a live YouTube search and resolve real audio
            // streams up front, since (unlike the UI-side MediaControllerManager queue) this
            // service has no later "resolve ahead" pass for items it hands straight to ExoPlayer.
            // Stream extraction is a multi-second network round trip per candidate; Gemini/Assistant
            // abandons a playFromSearch voice command as failed if this call doesn't return within
            // its own short timeout, so this stops as soon as enough playable results are found
            // instead of always extracting every one of up to 10 candidates first.
            try {
                val resolved = mutableListOf<MediaItem>()
                for (result in youtubeExtractor.search(trimmed).take(YOUTUBE_FALLBACK_SEARCH_CANDIDATES)) {
                    if (resolved.size >= YOUTUBE_FALLBACK_RESOLVE_TARGET) break
                    val stream = youtubeExtractor.extractAudioStream(result.videoId) ?: continue
                    resolved.add(
                        MediaItem.Builder()
                            .setMediaId("yt:${result.videoId}")
                            .setUri(Uri.parse(stream.url))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(result.title)
                                    .setArtist(result.uploader)
                                    .setArtworkUri(Uri.parse(result.thumbnailUri))
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .build()
                            )
                            .build()
                    )
                }
                resolved
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "YouTube fallback search failed for '$trimmed'", e)
                emptyList()
            }
        }

        private fun Song.toMediaItem(): MediaItem {
            return MediaItem.Builder()
                .setMediaId(mediaUri)
                .setUri(Uri.parse(mediaUri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setArtworkUri(resolveCarArtworkUri(artworkUri))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(SongMediaExtras.fromSong(this))
                        .build()
                )
                .build()
        }
    }
}
