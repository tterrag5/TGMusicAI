package com.example.tgmusicai.data.local

import com.example.tgmusicai.data.local.entity.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Songs the user disliked (thumbs down on the player, mini player or car). A disliked song is
 * skipped and kept out of our own recommendations, autoplay, radio and the For you feed.
 *
 * Persisted in [AppPreferences]; mirrored here in memory because the playback service and the UI
 * share one process and both filter on hot paths. A song is keyed by its YouTube video id when it
 * has one (so the same track is caught whether it is in the library or not), else by media URI.
 */
object DislikeStore {

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    @Volatile private var preferences: AppPreferences? = null

    /** Starts mirroring the saved set. Safe to call from both the activity and the service. */
    @Synchronized
    fun init(appPreferences: AppPreferences, scope: CoroutineScope) {
        if (preferences != null) return
        preferences = appPreferences
        scope.launch { appPreferences.dislikedKeysFlow.collect { _keys.value = it } }
    }

    fun keyOf(song: Song): String = song.youtubeId?.takeIf { it.isNotBlank() } ?: song.mediaUri

    fun isDisliked(song: Song): Boolean = keyOf(song) in _keys.value

    fun isDislikedVideo(videoId: String?): Boolean = !videoId.isNullOrBlank() && videoId in _keys.value

    suspend fun dislike(song: Song) {
        // A still-resolving voice search is not a song yet; there is nothing to dislike.
        if (com.example.tgmusicai.playback.MediaControllerManager.isSearchPlaceholder(song.mediaUri)) return
        val key = keyOf(song)
        _keys.value = _keys.value + key
        preferences?.addDislikedKey(key)
    }

    suspend fun undislike(song: Song) {
        val key = keyOf(song)
        _keys.value = _keys.value - key
        preferences?.removeDislikedKey(key)
    }
}
