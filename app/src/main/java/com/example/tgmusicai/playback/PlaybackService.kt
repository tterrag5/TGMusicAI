package com.example.tgmusicai.playback

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.example.tgmusicai.R
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.local.entity.ListeningHistory
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

        // Initialize ExoPlayer with Safe Driving Audio Focus: automatic focus handling ducks
        // music for GPS voice prompts (AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK), pauses for phone calls
        // (AUDIOFOCUS_LOSS_TRANSIENT) and resumes on AUDIOFOCUS_GAIN, and pauses when a
        // Bluetooth/aux device disconnects mid-playback (setHandleAudioBecomingNoisy).
        player = ExoPlayer.Builder(this)
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
                        updateCustomLayout()
                    }

                    override fun onRepeatModeChanged(repeatMode: Int) {
                        updateCustomLayout()
                    }
                })
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
        private const val MAX_CONSECUTIVE_PLAYBACK_ERRORS = 2
        private const val LISTEN_TIME_FLUSH_INTERVAL_MS = 10_000L

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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
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
            val defaultResult = super.onConnect(session, controller)
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
            try {
                youtubeExtractor.search(trimmed).take(10).mapNotNull { result ->
                    val stream = youtubeExtractor.extractAudioStream(result.videoId) ?: return@mapNotNull null
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
                }
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
                        .setArtworkUri(artworkUri?.let { Uri.parse(it) })
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
