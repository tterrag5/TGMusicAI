package com.example.tgmusicai.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Singleton or application-scoped manager that binds to [PlaybackService] via a [MediaController].
 * It exposes reactive [StateFlow] properties for the currently playing song, playback state,
 * position, duration, queue, and playback control functions.
 *
 * Opts in to Media3's unstable API surface, which [PlaybackService] is marked with: binding to it
 * from here counts as using it. The annotation is an acknowledgement rather than a suppression --
 * a Media3 upgrade may change what it points at.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaControllerManager(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Pulls the playing track and the one after it fully onto disk, so a signed stream URL
     * expiring or throttling mid-song cannot kill playback partway through. Best-effort only --
     * see [StreamPrefetcher].
     */
    private val prefetcher = StreamPrefetcher(context, scope)

    private var controller: MediaController? = null

    // Reactive StateFlows for UI binding
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playlist = MutableStateFlow<List<Song>>(emptyList())
    val playlist: StateFlow<List<Song>> = _playlist.asStateFlow()

    private val _shuffleMode = MutableStateFlow(false)
    val shuffleMode: StateFlow<Boolean> = _shuffleMode.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    // True while a cloud song's stream URL is being resolved before playback starts -- the UI
    // should show a loading indicator during this window, since ExoPlayer's own STATE_BUFFERING
    // doesn't cover it (playback hasn't been handed to the player yet at this point).
    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    private val _pendingSong = MutableStateFlow<Song?>(null)

    /**
     * The track the user just asked for, while its stream is still being resolved.
     *
     * A cloud track takes seconds to turn into a playable URL, and until [playInternal] runs the
     * player still reports the *previous* track. Tapping a cloud song therefore opened Now Playing
     * on whatever was already playing and sat there -- it looked like the tap had been ignored, or
     * like the app had opened the wrong song. Showing the requested track straight away, with the
     * resolving indicator the mini player already has, makes the wait legible instead of wrong.
     */
    val pendingSong: StateFlow<Song?> = _pendingSong.asStateFlow()

    /**
     * Declares what is about to play, before anything is resolved. Called by the cloud play path
     * so the UI can switch to the tapped track immediately; cleared once playback starts or the
     * attempt fails.
     */
    fun setPendingSong(song: Song?) {
        _pendingSong.value = song
    }

    // Set when a cloud song can't be turned into a playable stream URL at all (every Piped and
    // Invidious endpoint failed). Without this the failure was completely silent: the unresolved
    // watch URL was still handed to ExoPlayer, which errored internally, so onMediaItemTransition
    // never fired and the Now Playing screen just sat on "No Song Selected" with no explanation.
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    private var positionTickerJob: Job? = null
    private var currentSongList: List<Song> = emptyList()

    // Lazily created only if an unresolved cloud song is actually encountered during playback,
    // so most sessions (playing local files or already-resolved streams) never pay for it.
    private val youtubeExtractor: YouTubeExtractor by lazy { YouTubeExtractor() }

    init {
        initializeController()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        
        controllerFuture.addListener(
            {
                try {
                    val controllerInstance = controllerFuture.get()
                    controller = controllerInstance
                    setupPlayerListener(controllerInstance)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun setupPlayerListener(player: MediaController) {
        // Sync initial state
        _isPlaying.value = player.isPlaying
        _shuffleMode.value = player.shuffleModeEnabled
        _repeatMode.value = player.repeatMode
        updateCurrentMediaItem(player.currentMediaItem)

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startPositionTicker()
                } else {
                    stopPositionTicker()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Reset immediately rather than waiting for the next 500ms position-ticker tick --
                // otherwise _currentPositionMs briefly still holds the outgoing track's last
                // position while _durationMs (set below) already reflects the new track, which
                // would misreport progress into the new song for up to 500ms (and, since crossfade
                // reads both flows together, briefly fade the new track's volume to 0).
                _currentPositionMs.value = 0L
                // Queued tracks stack after the current one; once that one is over, the count of
                // them means nothing and the next "add to queue" starts a fresh run.
                pendingQueueInsertions = 0
                updateCurrentMediaItem(mediaItem)
                // One coalesced pass rather than a resolve and a prefetch per transition: holding
                // "next" fires this dozens of times a second, and acting on each firing is what
                // made the app fetch everything it was skipped past.
                scheduleWindowRefresh()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = controller?.duration?.coerceAtLeast(0L) ?: 0L
                    // A track that actually played is allowed a fresh retry next time it fails.
                    reresolveAttempts.clear()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                recoverFromPlaybackError(error)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffleMode.value = shuffleModeEnabled
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatMode.value = repeatMode
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                // Keeps the exposed queue (and therefore the "Up Next" sheet) in sync whenever the
                // player's real timeline changes for a reason this class didn't itself initiate --
                // e.g. PlaybackService appending random autoplay songs directly to the ExoPlayer
                // queue when the current queue is about to run out. Without this, those songs
                // would play fine but never show up as visible, skippable "Up Next" entries.
                refreshQueueFromPlayer()
            }
        })
    }

    /**
     * Rebuilds [currentSongList]/[_playlist] from the player's actual current timeline. Reuses
     * the existing [Song] for each item -- first checking the same index (the common, cheap
     * case), then searching the whole previous list by media URI (covers a reorder moving a song
     * to a different index) -- so a real, DB-backed [Song] is never silently downgraded to a
     * lossy `id = 0` reconstruction just because a background operation (progressive queue
     * resolution, a drag reorder) briefly has it at an unexpected index relative to the last
     * snapshot. That downgrade was a real bug: once a song's tracked id becomes null, the
     * real-listen play-tracking reset check (`foundSong?.id != trackedSongId`) becomes `null !=
     * null`, which is always false, so tracking silently and permanently stops recording plays
     * for the rest of the session -- matching a report that only the first song of a session ever
     * got counted, not anything played afterward via a playlist queue. Only falls back to a fresh
     * reconstruction from [MediaItem] metadata for genuinely new items (e.g. an autoplay
     * extension appended by [PlaybackService]) that were never in [currentSongList] at all.
     */
    private fun refreshQueueFromPlayer() {
        val player = controller ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        val previous = currentSongList
        val updated = (0 until count).map { index ->
            val item = player.getMediaItemAt(index)
            previous.getOrNull(index)?.takeIf { it.mediaUri == item.mediaId }
                ?: previous.find { it.mediaUri == item.mediaId }
                ?: run {
                    val metadata = item.mediaMetadata
                    SongMediaExtras.toSong(
                        mediaUri = item.mediaId,
                        title = metadata.title?.toString() ?: "Unknown Title",
                        artist = metadata.artist?.toString() ?: "Unknown Artist",
                        album = metadata.albumTitle?.toString() ?: "Unknown Album",
                        extras = metadata.extras
                    )
                }
        }
        currentSongList = updated
        _playlist.value = updated
    }

    // Real-listen play/listen-time tracking used to live here, but this manager's CoroutineScope
    // is cancelled by MediaControllerManager.release() in MainActivity.onDestroy() -- silently
    // stopping all telemetry the instant the Activity dies, even though PlaybackService (and its
    // ExoPlayer) keeps playing music in the background for as long as the process lives. That
    // tracking now lives in PlaybackService itself, keyed off the real DB song id embedded in
    // each MediaItem's extras (see SongMediaExtras); this class only mirrors position/duration
    // for UI display.
    private fun updateCurrentMediaItem(mediaItem: MediaItem?) {
        if (mediaItem == null) {
            _currentSong.value = null
            return
        }

        val mediaId = mediaItem.mediaId
        // Find song from our active queue or database
        val foundSong = currentSongList.find { it.mediaUri == mediaId }
        val resolvedSong = foundSong ?: run {
            // Reconstruct from MediaMetadata (including any embedded SongMediaExtras) rather than
            // a bare id=0/no-artwork placeholder, so a song this manager didn't already know about
            // (e.g. an autoplay extension appended service-side) still shows real artwork/id.
            val metadata = mediaItem.mediaMetadata
            SongMediaExtras.toSong(
                mediaUri = mediaId,
                title = metadata.title?.toString() ?: "Unknown Title",
                artist = metadata.artist?.toString() ?: "Unknown Artist",
                album = metadata.albumTitle?.toString() ?: "Unknown Album",
                extras = metadata.extras
            ).let { if (it.durationMs > 0L) it else it.copy(durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L) }
        }
        _currentSong.value = resolvedSong
        _durationMs.value = controller?.duration?.coerceAtLeast(0L) ?: 0L
    }

    private fun startPositionTicker() {
        stopPositionTicker()
        positionTickerJob = scope.launch {
            while (isActive) {
                val pos = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                _currentPositionMs.value = pos
                val dur = controller?.duration?.coerceAtLeast(0L) ?: 0L
                if (dur > 0) {
                    _durationMs.value = dur
                }
                // 100ms rather than 500ms: at half-second granularity the synced-lyrics view held
                // a line and then jumped to the next one, instead of tracking playback the way
                // YouTube Music does. MediaController.currentPosition is extrapolated locally
                // between session updates, so polling it more often costs no extra IPC.
                delay(POSITION_TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    /**
     * Plays a single song, optionally setting the playback queue to a given list of songs.
     * Cloud songs whose [Song.mediaUri] is an unresolved YouTube watch URL (e.g. added to a
     * playlist from search without downloading) are re-resolved to a direct stream URL first.
     */
    fun playSong(song: Song, queue: List<Song> = listOf(song), onStarted: () -> Unit = {}) {
        val activeQueue = if (queue.isEmpty()) listOf(song) else queue
        val startId = song.mediaUri

        // If the requested queue is exactly what's already loaded into the player (e.g. tapping
        // a different track while browsing the same Library/Playlist list), just jump to that
        // index instead of re-resolving and re-marshaling the whole queue -- for a large library
        // that queue can be hundreds of MediaItems, and rebuilding + re-sending all of them across
        // the Binder IPC boundary to PlaybackService on every single tap is the dominant cost of
        // "starting a song" feeling slow. Falls through to the full path whenever the queue
        // actually differs.
        if (queueMatchesCurrent(activeQueue)) {
            val player = controller
            val songIndex = currentSongList.indexOfFirst { it.mediaUri == startId }
                .let { if (it >= 0) it else 0 }
            if (player != null) {
                player.seekTo(songIndex, 0L)
                player.play()
                // Nothing had to be resolved, so any track announced as pending is now playing.
                _pendingSong.value = null
                onStarted()
                return
            }
        }

        val songIndex = activeQueue.indexOfFirst { it.mediaUri == startId }.let { if (it >= 0) it else 0 }
        playQueueProgressively(activeQueue, songIndex, onStarted)
    }

    /**
     * True when [queue] has exactly the same tracks, in the same order, as the queue already
     * loaded into the player -- letting [playSong] skip a full requeue and just seek instead.
     */
    private fun queueMatchesCurrent(queue: List<Song>): Boolean {
        if (queue.size != currentSongList.size) return false
        for (i in queue.indices) {
            if (queue[i].mediaUri != currentSongList[i].mediaUri) return false
        }
        return true
    }

    /**
     * Plays an entire queue of songs starting from a specific index. Cloud songs whose
     * [Song.mediaUri] is an unresolved YouTube watch URL are re-resolved to a direct stream URL first.
     */
    fun playQueue(queue: List<Song>, startIndex: Int = 0, onStarted: () -> Unit = {}) {
        if (queue.isEmpty()) return
        playQueueProgressively(queue, startIndex.coerceIn(0, queue.lastIndex), onStarted)
    }

    // Bumped every time playInternal loads a new queue, so a background resolution job from an
    // older playQueue/playSong call can tell its queue is stale and stop patching it (e.g. the
    // user tapped a different playlist before the first one finished resolving in the background).
    private var queueGeneration = 0

    /**
     * Starts playback as soon as the **starting** song is resolved, instead of waiting for the
     * whole queue -- for a playlist of undownloaded cloud tracks, waiting on every song's network
     * round-trip before playing anything made "Play All" look broken (nothing happened until a
     * wait roughly as long as the whole playlist would take to play). The rest of the queue is
     * resolved concurrently in the background and patched into the live player queue via
     * [Player.replaceMediaItem] as each one finishes, well before the user would naturally reach
     * it -- by the time playback advances there, it's already a direct playable URL.
     */
    private fun playQueueProgressively(queue: List<Song>, startIndex: Int, onStarted: () -> Unit) {
        // Pause the previously playing track immediately rather than letting it keep playing
        // (confusingly, under the old song's title/art) while the start song resolves.
        controller?.pause()
        scope.launch {
            _isResolving.value = true
            _playbackError.value = null
            _pendingSong.value = queue[startIndex]
            val startSong = resolveSongForPlayback(queue[startIndex])
            _isResolving.value = false

            // Handing an unresolved watch URL to ExoPlayer can only ever produce a silent internal
            // error, so report it to the user instead of pretending playback started.
            if (isUnresolvedCloudUri(startSong.mediaUri)) {
                android.util.Log.w(
                    "TGMusicCloud",
                    "Giving up on \"${startSong.title}\": no Piped/Invidious endpoint returned a playable stream"
                )
                // Kept short: a Toast truncates, so the actionable part has to come first.
                _playbackError.value =
                    "Can't stream \"${startSong.title}\" right now. Download it to play offline."
                _pendingSong.value = null
                onStarted()
                return@launch
            }

            val initialQueue = queue.toMutableList().also { it[startIndex] = startSong }
            playInternal(initialQueue, startIndex)
            _pendingSong.value = null
            onStarted()
            // The rest of the queue is not resolved here. Only the window around the start
            // track is, and it moves with playback.
            scheduleWindowRefresh(immediate = true)
        }
    }

    private fun playInternal(queue: List<Song>, startIndex: Int) {
        val player = controller ?: return
        if (queue.isEmpty()) return
        queueGeneration++
        currentSongList = queue
        _playlist.value = queue

        val mediaItems = queue.map { buildMediaItem(it) }
        val validIndex = startIndex.coerceIn(0, queue.lastIndex)

        player.setMediaItems(mediaItems, validIndex, 0L)
        player.prepare()
        player.play()
        scheduleWindowRefresh(immediate = true)
    }

    /** Cloud tracks re-resolved after a playback failure, so one bad URL cannot loop forever. */
    private val reresolveAttempts = mutableSetOf<String>()

    /**
     * Recovers from a playback failure by resolving the track again, once.
     *
     * A resolved YouTube URL is signed and expires, and YouTube throttles repeated fetches from
     * one address, so a URL that worked a moment ago can start answering HTTP 403 while the
     * resolver still considers it fresh. Before this, that ended playback outright with no
     * recovery. Re-resolving is the correct response, and is what makes a stale URL a hiccup
     * rather than a dead queue.
     *
     * Only cloud tracks are retried, and each only once between successful plays -- a local file
     * that fails to open will not start working because it was asked for twice, and retrying
     * forever would spin.
     */
    private fun recoverFromPlaybackError(error: PlaybackException) {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        val song = currentSongList.getOrNull(index) ?: return
        val videoId = song.youtubeId

        if (videoId.isNullOrBlank()) {
            android.util.Log.w("TGMusicCloud", "Playback failed for a local track: ${error.errorCodeName}")
            return
        }
        if (!reresolveAttempts.add(videoId)) {
            android.util.Log.w(
                "TGMusicCloud",
                "Giving up on \"${song.title}\": still failing after re-resolving (${error.errorCodeName})"
            )
            _playbackError.value = "Can't stream \"${song.title}\" right now. Download it to play offline."
            return
        }

        android.util.Log.w(
            "TGMusicCloud",
            "Playback failed for \"${song.title}\" (${error.errorCodeName}); re-resolving its stream"
        )

        val generation = queueGeneration
        scope.launch {
            // Let the skipping settle first. Skipping fast through cloud tracks whose streams the
            // resolver has not reached yet produces one error per track passed, and re-resolving
            // each of them spent the resolver's permits on songs the user was already past. If the
            // player has moved on by the time this fires, the failure no longer matters.
            delay(TRANSITION_SETTLE_MS)
            if (queueGeneration != generation) return@launch
            val stillHere = controller?.let { currentSongList.getOrNull(it.currentMediaItemIndex) }
            if (stillHere?.youtubeId != videoId) return@launch

            // The cached URL is the one that just failed, so it has to go before re-resolving.
            withContext(Dispatchers.IO) { youtubeExtractor.invalidateCachedStream(videoId) }
            val resolved = resolveSongForPlayback(song.copy(mediaUri = "https://www.youtube.com/watch?v=$videoId"))

            if (queueGeneration != generation) return@launch
            if (isUnresolvedCloudUri(resolved.mediaUri)) {
                _playbackError.value =
                    "Can't stream \"${song.title}\" right now. Download it to play offline."
                return@launch
            }

            val activePlayer = controller ?: return@launch
            currentSongList = currentSongList.toMutableList().also {
                if (index < it.size) it[index] = resolved
            }
            _playlist.value = currentSongList
            activePlayer.replaceMediaItem(index, buildMediaItem(resolved))
            activePlayer.prepare()
            activePlayer.play()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The playback window: two tracks back, the playing track, two tracks ahead. Only those five
    // are resolved and only those five are held in the audio cache.
    //
    // Deliberately a window and not the whole queue. Resolving every track of a cloud queue up
    // front fanned out one coroutine per item, and because they acquired their permits roughly in
    // launch order the resolver worked forwards from index 0 -- so skipping to track 20 left it
    // still grinding through 1..19, fetching every song that had just been skipped past before it
    // reached the one actually being waited on. On a long queue that also meant hundreds of live
    // coroutines and as many half-finished cache writes, which is what turned a run of fast skips
    // into lag and then into a dead queue.
    // ---------------------------------------------------------------------------------------------

    /**
     * Resolutions currently in flight, keyed by [windowKey] -- stable across resolution, which the
     * media URI is not, since resolving is precisely what changes it. Keeping the jobs lets anything
     * that leaves the window be cancelled rather than run to completion for a track the user has
     * already gone past.
     */
    private val resolveJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Pending window pass, so a burst of transitions collapses into one. */
    private var windowJob: Job? = null

    /**
     * Identifies a track across resolution. A cloud track keeps its video id while its URI is
     * rewritten from a watch URL to a signed stream URL; a local track never resolves at all, so
     * its URI is already stable.
     */
    private fun windowKey(song: Song): String = song.youtubeId?.takeIf { it.isNotBlank() } ?: song.mediaUri

    /**
     * Queue positions the window covers, ordered by how soon each is needed: the playing track
     * first, then ahead, then behind. The resolver has only a couple of permits, so the order is
     * what decides which track gets a permit first -- the one being waited on, always.
     */
    private fun windowIndices(current: Int, size: Int): List<Int> =
        listOf(0, 1, 2, -1, -2)
            .map { current + it }
            .filter { it in 0 until size }
            .distinct()

    /**
     * Asks for a window pass. Coalesced by default: a user holding down "next" produces a
     * transition per track, and acting on each one is how the app ended up fetching everything it
     * was skipped past. One pass, after the skipping settles, is both cheaper and more correct --
     * it acts on where the user actually landed. [immediate] is for a freshly loaded queue, where
     * there is nothing to settle and the first track is wanted now.
     */
    private fun scheduleWindowRefresh(immediate: Boolean = false) {
        windowJob?.cancel()
        windowJob = scope.launch {
            if (!immediate) delay(TRANSITION_SETTLE_MS)
            refreshPlaybackWindow()
        }
    }

    /**
     * Brings the window up to date: cancels resolutions and prefetches for tracks now outside it,
     * and starts them, in parallel, for the tracks inside it that still need them.
     */
    private fun refreshPlaybackWindow() {
        val player = controller ?: return
        if (currentSongList.isEmpty()) return
        val indices = windowIndices(player.currentMediaItemIndex, currentSongList.size)
        val window = indices.mapNotNull { currentSongList.getOrNull(it) }

        // Anything that has fallen out of the window is dropped: the work is wasted now, and it is
        // competing for the resolver's permits with the track being waited on.
        val keep = window.map(::windowKey).toSet()
        resolveJobs.keys.filterNot { it in keep }.forEach { resolveJobs.remove(it)?.cancel() }

        // Cache the window, and only the window. An unresolved watch URL is skipped -- there is
        // nothing to cache until it has a real stream, and the resolution below will ask again.
        val cacheable = window.map { it.mediaUri }.filterNot { isUnresolvedCloudUri(it) }
        prefetcher.retainOnly(cacheable)
        cacheable.forEach(prefetcher::prefetch)

        for (index in indices) {
            val song = currentSongList.getOrNull(index) ?: continue
            if (!isUnresolvedCloudUri(song.mediaUri)) continue
            val key = windowKey(song)
            if (resolveJobs.containsKey(key)) continue
            resolveJobs[key] = launchWindowResolve(song, key)
        }
    }

    /**
     * Resolves one windowed track and patches it into the live queue. Located by [windowKey] at
     * patch time rather than by the index it started at, because the queue can be reordered or
     * added to while a resolution is in the air.
     */
    private fun launchWindowResolve(song: Song, key: String): Job {
        val generation = queueGeneration
        return scope.launch(Dispatchers.IO) {
            try {
                val resolved = resolveSemaphore.withPermit { resolveSongForPlayback(song) }
                if (resolved.mediaUri == song.mediaUri) return@launch
                withContext(Dispatchers.Main) {
                    if (queueGeneration != generation) return@withContext
                    val livePlayer = controller ?: return@withContext
                    val at = currentSongList.indexOfFirst { windowKey(it) == key }
                    if (at < 0 || at >= livePlayer.mediaItemCount) return@withContext
                    currentSongList = currentSongList.toMutableList().also { it[at] = resolved }
                    _playlist.value = currentSongList
                    livePlayer.replaceMediaItem(at, buildMediaItem(resolved))
                    prefetcher.prefetch(resolved.mediaUri)
                    // The player may be sitting on this very item, stalled because the watch URL it
                    // was handed could never play. Now that there is a real stream, start it.
                    if (at == livePlayer.currentMediaItemIndex) {
                        livePlayer.prepare()
                        livePlayer.play()
                    }
                }
            } finally {
                resolveJobs.remove(key)
            }
        }
    }

    // Caps how many cloud-stream resolutions run at once. Firing a dozen concurrent requests at
    // the same shared Piped/Invidious mirror the instant "Play All" is tapped on an all-cloud
    // playlist (e.g. Cloud Nine) looks a lot like hostile traffic to that mirror and was observed
    // live to trigger a wall of 401/403 responses across every candidate at once -- a self-inflicted
    // rate-limit, not a real outage. A window of five tracks is small enough that three at a time
    // still fills it promptly while staying well short of that burst.
    private val resolveSemaphore = Semaphore(3)

    /**
     * Re-resolves [song] if it's an unresolved cloud song (a raw YouTube watch URL, not yet
     * downloaded) to a direct playable stream URL. Best-effort: returns the song unchanged on any
     * failure (ExoPlayer will surface a playback error for it via [PlaybackService]'s error
     * listener rather than silently dropping it from the queue).
     */
    private suspend fun resolveSongForPlayback(song: Song): Song = withContext(Dispatchers.IO) {
        if (isUnresolvedCloudUri(song.mediaUri) && !song.youtubeId.isNullOrBlank()) {
            try {
                val stream = youtubeExtractor.extractAudioStream(song.youtubeId)
                if (stream != null && stream.url.isNotBlank()) {
                    song.copy(mediaUri = stream.url)
                } else {
                    song
                }
            } catch (e: Exception) {
                song
            }
        } else {
            song
        }
    }

    private fun isUnresolvedCloudUri(uri: String): Boolean {
        return uri.startsWith("https://www.youtube.com/watch?v=") || uri.startsWith("https://youtu.be/")
    }

    /**
     * Toggles playback between play and pause.
     */
    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0, 0L)
            }
            player.play()
        }
    }

    /**
     * Directly pauses playback.
     */
    fun pause() {
        controller?.pause()
    }

    /** Sets the player's output volume (0f-1f). Used for the sleep timer's fade-out. */
    fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * Appends [song] to the end of the live "Up Next" queue without interrupting current
     * playback. If nothing was queued yet, prepares the player so the newly-added item is ready
     * to play, without forcing playback to start.
     */
    fun addToQueue(song: Song) {
        val player = controller ?: return
        val wasEmpty = player.mediaItemCount == 0

        // Directly after the track playing, not at the end of the queue. "Add to queue" means
        // "play this next"; appending it behind an hour of already-queued music is the one thing
        // it obviously must not do. Queuing several in a row keeps their order, because each new
        // one lands after the previous addition rather than displacing it.
        val insertAt = if (wasEmpty) {
            0
        } else {
            (player.currentMediaItemIndex + 1 + pendingQueueInsertions).coerceAtMost(player.mediaItemCount)
        }
        pendingQueueInsertions++

        player.addMediaItem(insertAt, buildMediaItem(song))
        // The queue sheet reads currentSongList, which the player timeline alone does not update:
        // without this the song really was queued but never appeared in the list, which looked
        // exactly like the queue ignoring it.
        currentSongList = currentSongList.toMutableList().also {
            it.add(insertAt.coerceAtMost(it.size), song)
        }
        _playlist.value = currentSongList
        if (wasEmpty) player.prepare()

        // A cloud track is queued as an unresolved watch URL, which fails the instant playback
        // reaches it. Resolve it in the background and swap the real stream in, the same way a
        // freshly loaded queue resolves everything after its first track.
        if (isUnresolvedCloudUri(song.mediaUri)) {
            // Resolved through the window resolver, which locates the track by key at patch time
            // (the user can skip, reorder or queue something else meanwhile) and drops the work if
            // the track falls outside the window before it lands.
            val key = windowKey(song)
            if (!resolveJobs.containsKey(key)) {
                resolveJobs[key] = launchWindowResolve(song, key)
            }
        }
    }

    /**
     * How many tracks have been queued since the current one started, so a run of "add to queue"
     * taps stacks up in the order they were tapped instead of each one jumping in front of the
     * last. Reset on every track transition, where the count stops meaning anything.
     */
    private var pendingQueueInsertions = 0

    /**
     * The ExoPlayer's current audio session id, or 0 if no controller is connected yet.
     * [AudioEffectsManager] attaches the system Equalizer/BassBoost to this id -- since those
     * effects operate on the platform mixer for a given session, attaching from here (the UI
     * side) works without needing PlaybackService itself to own the effects.
     */
    fun getAudioSessionId(): Int = controller?.audioSessionId ?: 0

    private val _playbackSpeed = MutableStateFlow(1f)
    /** Current playback speed multiplier (0.5x-2.0x), independent of pitch. */
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    /**
     * Sets playback speed (0.5x-2.0x). Pitch is always preserved (`pitch = 1f`) so a sped-up or
     * slowed-down track doesn't also sound higher/lower-pitched -- ExoPlayer's default behavior
     * ties pitch to speed unless told otherwise.
     */
    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _playbackSpeed.value = clamped
        controller?.setPlaybackParameters(androidx.media3.common.PlaybackParameters(clamped, 1f))
    }

    /**
     * Seeks to the specified position in milliseconds.
     */
    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        player.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    /**
     * Skips to the next track in the queue.
     */
    fun skipToNext() {
        val player = controller ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
    }

    /**
     * Skips to the previous track in the queue.
     */
    fun skipToPrevious() {
        val player = controller ?: return
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }
    }

    /**
     * Jumps directly to a specific position in the current queue (e.g. tapping a song in the
     * "Up Next" queue view).
     */
    fun skipToIndex(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0L)
        }
    }

    /**
     * Moves the queue item at [from] to position [to] (e.g. dragging a track to reorder it in the
     * "Up Next" queue view). The resulting timeline change flows back through [onTimelineChanged]
     * automatically, keeping [playlist] in sync.
     */
    fun moveQueueItem(from: Int, to: Int) {
        val player = controller ?: return
        val count = player.mediaItemCount
        if (from !in 0 until count || to !in 0 until count || from == to) return
        player.moveMediaItem(from, to)
    }

    /**
     * Removes the queue items at [indices] (e.g. a bulk multi-select removal from the "Up Next"
     * queue view). Indices are removed highest-first so each `removeMediaItem` call still targets
     * the intended item even as earlier removals shift everything after them down by one.
     */
    fun removeQueueItems(indices: List<Int>) {
        val player = controller ?: return
        val count = player.mediaItemCount
        indices.distinct().sortedDescending().forEach { index ->
            if (index in 0 until count) player.removeMediaItem(index)
        }
    }

    /**
     * Toggles shuffle mode on/off.
     */
    fun toggleShuffle() {
        val player = controller ?: return
        val newMode = !player.shuffleModeEnabled
        player.shuffleModeEnabled = newMode
        _shuffleMode.value = newMode
    }

    /**
     * Cycles repeat mode between 3 states:
     * State 0: REPEAT_MODE_OFF (Default: Off)
     * State 1: REPEAT_MODE_ALL (1st tap: Loop Playlist/Queue)
     * State 2: REPEAT_MODE_ONE (2nd tap: Loop Current Song)
     * 3rd tap returns to State 0 (REPEAT_MODE_OFF).
     */
    fun toggleRepeat() {
        val player = controller ?: return
        val nextMode = getNextRepeatMode(player.repeatMode)
        player.repeatMode = nextMode
        _repeatMode.value = nextMode
    }

    companion object {
        /** How often [currentPositionMs] is refreshed while playing -- see [startPositionTicker]. */
        private const val POSITION_TICK_INTERVAL_MS = 100L

        /**
         * How long a burst of track transitions is allowed to settle before the window is acted on.
         * Long enough that holding "next" costs one pass rather than one per track, short enough
         * that a single deliberate skip still feels immediate.
         */
        private const val TRANSITION_SETTLE_MS = 250L

        /**
         * Calculates the next repeat mode in the exact 3-state loop:
         * REPEAT_MODE_OFF -> REPEAT_MODE_ALL -> REPEAT_MODE_ONE -> REPEAT_MODE_OFF
         */
        fun getNextRepeatMode(currentMode: Int): Int {
            return when (currentMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun buildMediaItem(song: Song): MediaItem {
        return MediaItem.Builder()
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

    fun release() {
        stopPositionTicker()
        windowJob?.cancel()
        resolveJobs.values.forEach { it.cancel() }
        resolveJobs.clear()
        prefetcher.cancelAll()
        controller?.release()
        controller = null
        scope.cancel()
    }
}
