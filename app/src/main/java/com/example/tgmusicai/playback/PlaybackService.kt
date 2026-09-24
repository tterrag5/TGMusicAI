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
import androidx.media3.common.util.UnstableApi
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
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import com.example.tgmusicai.cast.CastAvailability
import com.example.tgmusicai.cast.CastMediaItemConverter
import com.example.tgmusicai.cast.LocalMediaHttpServer
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.local.entity.ListeningHistory
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.CloudRecommendationSource
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.repository.RecommendationEngine
import com.example.tgmusicai.data.scrobble.ListenBrainzScrobbler
import com.example.tgmusicai.data.sponsorblock.SponsorBlockManager
import com.example.tgmusicai.data.youtube.InnerTubeCookieManager
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background media playback service extending [MediaLibraryService] for Android Auto support.
 * Exposes a hierarchical, browsable media tree (Root -> automotive categories -> Songs) for head
 * units and media browsers, plus car-screen custom actions (Like / Repeat), Google Assistant
 * voice search, and safe-driving audio focus handling.
 *
 * Marked [UnstableApi] because it builds on Media3 APIs Google has not frozen -- the renderers
 * factory and audio sink that per-track volume normalization is inserted into, the Cast players,
 * and the caching data source. Declaring that here rather than opting in at each call site is the
 * documented way to acknowledge it, and keeps the acknowledgement in one place where a future
 * Media3 upgrade will look for it.
 */
@UnstableApi
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
    private val scrobbler by lazy { ListenBrainzScrobbler() }

    /**
     * Supplies the cloud half of autoplay: tracks the library does not hold, picked from the
     * artists the user actually plays. Built lazily because a device that never reaches the end of
     * a queue never needs it, and because it must not add work to service start-up.
     */
    private val cloudRecommendations by lazy {
        CloudRecommendationSource(
            browser = YouTubeMusicBrowser(InnerTubeCookieManager(AppPreferences(applicationContext))),
            songDao = database.songDao(),
            recommendationEngine = RecommendationEngine(
                songDao = database.songDao(),
                songStatsDao = database.songStatsDao(),
                aiSongTagsDao = database.aiSongTagsDao(),
                listeningHistoryDao = database.listeningHistoryDao()
            )
        )
    }

    // Cast, when the device supports it. Both stay null on a device without Play Services, which
    // leaves the session running on the plain ExoPlayer.
    private var castPlayer: CastPlayer? = null
    private var mediaServer: LocalMediaHttpServer? = null

    /**
     * Scrobbling settings, mirrored into fields for the same reason the others are: they are read
     * on the playback ticker and on media item transitions, which cannot suspend on DataStore.
     */
    @Volatile
    private var scrobblingEnabled: Boolean = false

    @Volatile
    private var listenBrainzToken: String? = null

    @Volatile
    private var listenBrainzServer: String = AppPreferences.DEFAULT_LISTENBRAINZ_SERVER

    /**
     * SponsorBlock settings, mirrored into fields for the same reason
     * [volumeNormalizationEnabled] is: they are consulted on a media item transition and on the
     * playback ticker, neither of which can afford to suspend on a DataStore read.
     */
    /**
     * The app-wide "Downloaded only" preference. When it is on the user has said they want nothing
     * fetched from the network, so autoplay stays entirely local.
     */
    @Volatile
    private var downloadedOnly: Boolean = false

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

        // "Downloaded only" decides whether autoplay may reach for cloud recommendations at all.
        // Mirrored into a field for the same reason the others are: autoplay runs on a media item
        // transition, which cannot suspend on a DataStore read.
        serviceScope.launch(Dispatchers.Main) {
            AppPreferences(applicationContext).downloadedOnlyFlow.collect { enabled ->
                downloadedOnly = enabled
            }
        }

        // Scrobbling settings. A token that is removed takes effect immediately rather than at the
        // next track, so turning it off really does stop submissions.
        serviceScope.launch(Dispatchers.Main) {
            val preferences = AppPreferences(applicationContext)
            combine(
                preferences.scrobblingEnabledFlow,
                preferences.listenBrainzTokenFlow,
                preferences.listenBrainzServerFlow
            ) { enabled, token, server -> Triple(enabled, token, server) }
                .collect { (enabled, token, server) ->
                    scrobblingEnabled = enabled
                    listenBrainzToken = token
                    listenBrainzServer = server
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
        //
        // The session is given the Cast-aware wrapper rather than the ExoPlayer directly, where
        // Cast is usable. That wrapper forwards to whichever of the two is active and moves the
        // queue and position across when a Cast session starts or ends, so everything above it --
        // the session, Android Auto, the UI, the widget -- keeps talking to one player and needs
        // to know nothing about casting.
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            buildCastAwarePlayerOrNull() ?: player,
            CustomMediaLibrarySessionCallback()
        )
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

        /** Random on-device tracks one autoplay queue extension appends. */
        private const val AUTOPLAY_LOCAL_COUNT = 3

        /**
         * Recommended cloud tracks one autoplay queue extension appends. Deliberately fewer than
         * the local half: each one costs a stream resolution, and a queue extension that keeps the
         * user in music they own reads as their library continuing rather than as the app wandering
         * off into YouTube.
         */
        private const val AUTOPLAY_CLOUD_COUNT = 2

        /**
         * How many recommendations to consider per track actually wanted. Some fraction of any
         * batch will not resolve, and over-fetching from an already-cached list costs nothing.
         */
        private const val RECOMMENDATION_OVERFETCH = 4

        /**
         * Ceiling on the whole recommendation fetch-and-resolve pass. It runs when the last track
         * in the queue *starts*, so there are minutes of slack -- but the pass must still end on
         * its own rather than hold a coroutine open on a network that never answers.
         */
        private const val RECOMMENDATION_RESOLVE_TIMEOUT_MS = 30_000L

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
     * Builds the player that hands playback to a Cast device when one is connected, or null if
     * Cast cannot be used here.
     *
     * Cast rides on Google Play Services, which a meaningful share of devices do not have --
     * de-Googled ROMs, some OEM builds, emulators without Play. On those, initialising it throws
     * rather than returning nothing, so a failure here has to leave the service running on the
     * plain ExoPlayer instead of taking playback down with it.
     *
     * The local HTTP server exists because a Cast device fetches media by URL and cannot see this
     * phone's storage. Without it only cloud tracks could be cast, which for a library that is
     * mostly local files would make the feature close to useless.
     */
    private fun buildCastAwarePlayerOrNull(): Player? {
        if (CastAvailability.castContext(this) == null) {
            android.util.Log.d("PlaybackService", "Cast is unavailable on this device")
            return null
        }

        return try {
            val server = LocalMediaHttpServer(this).also { mediaServer = it }
            if (!server.start()) {
                android.util.Log.w("PlaybackService", "Local media server did not start; casting local files will not work")
            }

            val remotePlayer = RemoteCastPlayer.Builder(this)
                .setMediaItemConverter(CastMediaItemConverter(server))
                .build()

            CastPlayer.Builder(this)
                .setLocalPlayer(player)
                .setRemotePlayer(remotePlayer)
                .build()
                .also { castPlayer = it }
        } catch (e: Throwable) {
            android.util.Log.w("PlaybackService", "Could not set up Cast; staying on local playback", e)
            mediaServer?.close()
            mediaServer = null
            null
        }
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
     * Appends a few songs to the live queue as soon as playback reaches the current last item,
     * provided repeat is off -- so the queue never actually runs out, the new songs are visible
     * and skippable in "Up Next" (they are really appended to the player's timeline, not swapped
     * in reactively after a stop), and nothing audibly interrupts playback.
     *
     * The songs are a mix: recommendations from YouTube Music chosen around what the user
     * actually plays, plus random tracks already on the device. Both halves are requested, and
     * whatever comes back is interleaved so the run does not read as a block of cloud followed by
     * a block of local. The local half is what makes this work on a plane -- a failed or empty
     * recommendation fetch must never leave the queue un-extended, which is why local tracks are
     * asked for outright rather than only after the network has been given its chance.
     */
    private fun maybeExtendQueueForAutoplay(exoPlayer: ExoPlayer) {
        if (exoPlayer.repeatMode != Player.REPEAT_MODE_OFF) return
        if (exoPlayer.hasNextMediaItem()) return
        val currentIndex = exoPlayer.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex == lastAutoExtendedAtIndex) return
        lastAutoExtendedAtIndex = currentIndex

        val existingIds = (0 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it).mediaId }.toSet()
        serviceScope.launch {
            val local = database.songDao().getRandomDownloadedSongs(AUTOPLAY_LOCAL_COUNT)
                .filter { it.mediaUri !in existingIds }
            val cloud = resolvedRecommendations(AUTOPLAY_CLOUD_COUNT, existingIds)
            val candidates = interleave(cloud, local)
            if (candidates.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val items = candidates.map { song -> autoplayMediaItem(song) }
                exoPlayer.addMediaItems(items)
                android.util.Log.d(
                    "PlaybackService",
                    "Auto-extended queue with ${items.size} song(s): ${cloud.size} recommended, ${local.size} local"
                )
            }
        }
    }

    /**
     * Picks a song (different from the one that just ended, if possible) and starts playing it,
     * so the queue ending with repeat off doesn't just stop. Prefers a recommendation and falls
     * back to a random downloaded track, on the same reasoning as [maybeExtendQueueForAutoplay].
     */
    private fun playRandomSongAsAutoplay(exoPlayer: ExoPlayer) {
        val lastMediaId = exoPlayer.currentMediaItem?.mediaId
        serviceScope.launch {
            val excluded = setOfNotNull(lastMediaId)
            val candidates = database.songDao().getRandomDownloadedSongs(3)
            val next = resolvedRecommendations(1, excluded).firstOrNull()
                ?: candidates.firstOrNull { it.mediaUri !in excluded }
                ?: candidates.firstOrNull()
            if (next == null) return@launch
            withContext(Dispatchers.Main) {
                exoPlayer.setMediaItem(autoplayMediaItem(next))
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
    }

    /** The queue entry for one autoplay pick, local or cloud -- they differ only in their URI. */
    private fun autoplayMediaItem(song: Song): MediaItem = MediaItem.Builder()
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

    /**
     * Up to [limit] recommended cloud tracks, each already **resolved to a playable stream** and
     * persisted as a song row.
     *
     * Resolving here rather than appending a watch URL is the whole point of this function. An
     * unresolved cloud item fails the instant ExoPlayer tries to play it, and a queue of them is
     * exactly the cascade that the guard in `onPlayerError` exists to contain -- autoplay adding
     * its own would be self-inflicted. Anything that cannot be resolved is dropped.
     *
     * Returns nothing when "Downloaded only" is set, when the device is offline, or when the whole
     * attempt outruns [RECOMMENDATION_RESOLVE_TIMEOUT_MS]. The caller treats an empty list as "use
     * local tracks", which is what keeps autoplay working with no network.
     */
    private suspend fun resolvedRecommendations(limit: Int, excludedMediaIds: Set<String>): List<Song> {
        if (limit <= 0 || downloadedOnly) return emptyList()
        return try {
            withTimeoutOrNull(RECOMMENDATION_RESOLVE_TIMEOUT_MS) {
                val tracks = cloudRecommendations.recommendedTracks(
                    limit = limit * RECOMMENDATION_OVERFETCH
                )
                val resolved = mutableListOf<Song>()
                // Shuffled so a queue that runs dry three times in one evening does not append the
                // same few tracks each time; the cached recommendation list barely changes.
                for (track in tracks.shuffled()) {
                    if (resolved.size >= limit) break
                    val song = resolveRecommendation(track) ?: continue
                    if (song.mediaUri in excludedMediaIds) continue
                    if (resolved.any { it.mediaUri == song.mediaUri }) continue
                    resolved += song
                }
                resolved
            }.orEmpty()
        } catch (e: Throwable) {
            android.util.Log.w("PlaybackService", "Could not resolve autoplay recommendations", e)
            emptyList()
        }
    }

    /**
     * Turns one recommendation into a playable, persisted [Song], or null when its stream cannot
     * be resolved.
     *
     * The row is persisted (deduped by video id) before it is queued for the same reason
     * `YouTubeViewModel.playTrack` persists one: a transient id=0 song can never be attributed to
     * a play-count row, so autoplayed tracks would silently never appear in Stats.
     */
    private suspend fun resolveRecommendation(track: YouTubeSearchResult): Song? = try {
        val stream = youtubeExtractor.extractAudioStream(track.videoId)
        if (stream == null || stream.url.isBlank()) {
            null
        } else {
            val cleaned = com.example.tgmusicai.data.local.AiMetadataCleaner.cleanOffline(
                track.title,
                track.uploader
            )
            val song = Song(
                id = 0,
                title = cleaned.cleanTitle,
                artist = cleaned.artist ?: track.uploader,
                album = "YouTube Cloud",
                durationMs = track.durationSeconds * 1000L,
                mediaUri = stream.url,
                producer = cleaned.producer,
                youtubeId = track.videoId,
                artworkUri = track.thumbnailUri,
                isDownloaded = false
            )
            song.copy(id = repository.ensurePersisted(song))
        }
    } catch (e: Throwable) {
        android.util.Log.w("PlaybackService", "Could not resolve recommendation ${track.videoId}", e)
        null
    }

    /**
     * Alternates between two lists, starting with [first], and appends whatever is left over when
     * one runs out -- so a queue extension mixes recommendations and local tracks instead of
     * playing one group and then the other.
     */
    private fun <T> interleave(first: List<T>, second: List<T>): List<T> {
        val mixed = mutableListOf<T>()
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.let { mixed += it }
            second.getOrNull(i)?.let { mixed += it }
        }
        return mixed
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

    // Scrobbling state for the track currently playing, reset on every transition so a listen can
    // never be attributed to the wrong track.
    private var scrobbleSubmittedForCurrentItem = false
    private var currentItemStartedAtEpochSeconds = 0L

    /**
     * Submits the track that is playing to the user's listening history once it has played far
     * enough to count.
     *
     * The threshold -- half the track, or four minutes -- is the one every scrobbler has used
     * since the practice existed, so history recorded here looks like history recorded anywhere
     * else. Deliberately separate from the in-app play counter above, which uses a shorter
     * threshold suited to a personal library rather than to a shared record.
     */
    private fun maybeScrobble(positionMs: Long, durationMs: Long) {
        if (!scrobblingEnabled || scrobbleSubmittedForCurrentItem) return
        if (durationMs in 1 until ListenBrainzScrobbler.MIN_TRACK_LENGTH_MS) return
        if (positionMs < ListenBrainzScrobbler.scrobbleThresholdMs(durationMs)) return

        val token = listenBrainzToken ?: return
        val item = player.currentMediaItem ?: return
        val listen = buildListen(item, durationMs) ?: return
        scrobbleSubmittedForCurrentItem = true

        serviceScope.launch {
            when (val result = scrobbler.submitListen(token, listenBrainzServer, listen)) {
                is ListenBrainzScrobbler.Result.Success ->
                    android.util.Log.d("PlaybackService", "Scrobbled ${listen.title}")
                is ListenBrainzScrobbler.Result.InvalidToken ->
                    // Retrying cannot help, and the user has to fix it in Settings, so stop trying
                    // for this session rather than failing once per track for the rest of it.
                    android.util.Log.w("PlaybackService", "ListenBrainz rejected the token; scrobbling is paused")
                is ListenBrainzScrobbler.Result.Transient ->
                    android.util.Log.d("PlaybackService", "Scrobble failed (${result.reason})")
            }
        }
    }

    /** Sends the "now playing" indicator, which is not stored as history and is fine to lose. */
    private fun submitNowPlaying(mediaItem: MediaItem?) {
        if (!scrobblingEnabled || mediaItem == null) return
        val token = listenBrainzToken ?: return
        val listen = buildListen(mediaItem, 0L) ?: return
        serviceScope.launch { scrobbler.submitNowPlaying(token, listenBrainzServer, listen) }
    }

    private fun buildListen(item: MediaItem, durationMs: Long): ListenBrainzScrobbler.Listen? {
        val metadata = item.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return ListenBrainzScrobbler.Listen(
            title = title,
            artist = artist,
            album = metadata.albumTitle?.toString(),
            durationMs = durationMs.coerceAtLeast(0L),
            startedAtEpochSeconds = currentItemStartedAtEpochSeconds
        )
    }

    private fun resetListenTracking(mediaItem: MediaItem?) {
        flushPendingListenTime()
        scrobbleSubmittedForCurrentItem = false
        currentItemStartedAtEpochSeconds = System.currentTimeMillis() / 1000
        submitNowPlaying(mediaItem)
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
                maybeScrobble(pos, dur)
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
        // Released before the session, since the session's player is the Cast wrapper around it.
        // Closing the media server drops every registration with it, so nothing stays reachable on
        // the network once playback is over.
        castPlayer?.release()
        castPlayer = null
        mediaServer?.close()
        mediaServer = null
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
