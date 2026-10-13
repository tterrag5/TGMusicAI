package com.example.tgmusicai.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps cloud tracks playable from inside the playback service, for when nothing else is: Android
 * Auto with the phone locked or the app closed.
 *
 * In the app, [MediaControllerManager] resolves the queue window and recovers expired streams --
 * but it lives with the app's UI and is gone when the Activity is. Without this, a car picking a
 * cloud track whose stored link is a watch URL (or an expired signed URL) left the service
 * "holding" for a resolver that no longer existed, paused for good.
 *
 * Same rules as the app's resolver: the playing track and three ahead, coalesced over
 * [SETTLE_MS], at most [MAX_CONCURRENT] at once (YouTube throttles bursts from one address), and
 * one re-resolve per track when a stream fails. It acts only while [isUiActive] is false, so the
 * two never resolve the same track twice.
 */
class ServiceStreamResolver(
    private val scope: CoroutineScope,
    private val extractor: () -> YouTubeExtractor,
    private val isUiActive: () -> Boolean,
) {

    private val permits = Semaphore(MAX_CONCURRENT)
    private val inFlight = ConcurrentHashMap<String, Job>()
    private var windowJob: Job? = null

    /** Video ids already re-resolved once after failing, cleared when playback succeeds. */
    private val reresolved = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Re-checks the window after the queue or the position changed. */
    fun onQueueOrPositionChanged(player: Player) {
        if (isUiActive()) return
        windowJob?.cancel()
        windowJob = scope.launch(Dispatchers.Main) {
            delay(SETTLE_MS)
            refreshWindow(player)
        }
    }

    /** Playback started for real; failures from here on are new failures. */
    fun onPlaying() {
        reresolved.clear()
    }

    /**
     * The player stopped on an unresolved item. Resolves it now, without the settle delay, and
     * resumes. Returns false when the app's own resolver is responsible instead.
     */
    fun onHolding(player: Player): Boolean {
        if (isUiActive()) return false
        refreshWindow(player, resumeIfCurrent = true)
        return true
    }

    /**
     * A resolved cloud stream failed (signed URLs expire after a few hours, and YouTube 403s a
     * throttled address). Drops the cached URL and resolves the track again, once. Returns true
     * when a retry is under way, so the caller does not skip the track.
     */
    fun onStreamFailed(player: Player): Boolean {
        if (isUiActive()) return false
        val item = player.currentMediaItem ?: return false
        val videoId = videoIdOf(item) ?: return false
        if (!reresolved.add(videoId)) return false
        Log.w(TAG, "Stream failed for $videoId; resolving again")
        scope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) { extractor().invalidateCachedStream(videoId) }
            resolve(player, item, videoId, resumeIfCurrent = true)
        }
        return true
    }

    private fun refreshWindow(player: Player, resumeIfCurrent: Boolean = false) {
        val current = player.currentMediaItemIndex
        if (current < 0) return
        val window = (0..AHEAD).map { current + it }.filter { it < player.mediaItemCount }
        val wanted = mutableSetOf<String>()
        for (index in window) {
            val item = player.getMediaItemAt(index)
            if (!isUnresolved(item)) continue
            val videoId = videoIdOf(item) ?: continue
            wanted += videoId
            // Registered before it starts, so a resolution that finishes at once cannot leave a
            // completed job behind that reads as "in flight" forever.
            val job = scope.launch(Dispatchers.Main, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                resolve(player, item, videoId, resumeIfCurrent && index == current)
            }
            if (inFlight.putIfAbsent(videoId, job) != null) continue
            job.invokeOnCompletion { inFlight.remove(videoId, job) }
            job.start()
        }
        // Anything that left the window is no longer worth a permit.
        inFlight.keys.filterNot { it in wanted }.forEach { inFlight.remove(it)?.cancel() }
    }

    private suspend fun resolve(player: Player, item: MediaItem, videoId: String, resumeIfCurrent: Boolean) {
        val stream = permits.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    extractor().extractAudioStream(videoId)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not resolve $videoId: ${e.message}")
                    null
                }
            }
        } ?: return
        if (stream.url.isBlank()) return
        // The queue may have changed while resolving; find the item again by its old id.
        val at = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == item.mediaId } ?: return
        val replacement = item.buildUpon()
            .setMediaId(stream.url)
            .setUri(Uri.parse(stream.url))
            .build()
        player.replaceMediaItem(at, replacement)
        Log.d(TAG, "Resolved $videoId in the service")
        if (resumeIfCurrent && at == player.currentMediaItemIndex) {
            player.prepare()
            player.play()
        }
    }

    companion object {
        private const val TAG = "ServiceStreamResolver"
        private const val AHEAD = 3
        private const val MAX_CONCURRENT = 3
        private const val SETTLE_MS = 250L

        /** Connection hint the app's own [MediaControllerManager] sets, so the service can tell it apart. */
        const val HINT_APP_UI = "com.example.tgmusicai.hint.APP_UI"

        /**
         * A cloud track with no playable stream yet: a watch URL, or the `yt:<videoId>` id that
         * voice / Android Auto search results carried (rows saved from those kept it as their URI,
         * and nothing resolved them, so skipping onto one failed after ~3 s; T034).
         */
        fun isUnresolvedUri(uri: String): Boolean =
            uri.startsWith("https://www.youtube.com/watch?v=") || uri.startsWith("https://youtu.be/") ||
                uri.startsWith(YT_ID_PREFIX)

        /** The prefix of the old `yt:<videoId>` media ids. */
        const val YT_ID_PREFIX = "yt:"

        internal fun isUnresolved(item: MediaItem): Boolean =
            isUnresolvedUri(item.localConfiguration?.uri?.toString() ?: item.mediaId)

        /** The track's video id: from its extras, else from a watch URL. */
        internal fun videoIdOf(item: MediaItem): String? =
            SongMediaExtras.youtubeId(item.mediaMetadata.extras)?.takeIf { it.isNotBlank() }
                ?: videoIdFromUrl(item.localConfiguration?.uri?.toString() ?: item.mediaId)

        internal fun videoIdFromUrl(url: String): String? = when {
            url.startsWith("https://www.youtube.com/watch?v=") ->
                url.substringAfter("v=").substringBefore('&').takeIf { it.isNotBlank() }
            url.startsWith("https://youtu.be/") ->
                url.removePrefix("https://youtu.be/").substringBefore('?').takeIf { it.isNotBlank() }
            url.startsWith(YT_ID_PREFIX) -> url.removePrefix(YT_ID_PREFIX).takeIf { it.isNotBlank() }
            else -> null
        }
    }
}
