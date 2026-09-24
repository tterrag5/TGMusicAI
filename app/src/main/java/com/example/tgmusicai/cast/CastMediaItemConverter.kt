package com.example.tgmusicai.cast

import android.net.Uri
import android.util.Log
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem

/**
 * Prepares a queue item for a Cast device.
 *
 * Two things have to happen that the default converter does not do.
 *
 * First, local tracks have to be rewritten. A Cast device fetches media by URL over the network
 * and has no access to this phone's storage, so a `file://` or `content://` URI handed to it
 * simply fails. Each one is registered with [server] and replaced by a URL pointing back at this
 * device. Cloud tracks already carry an `http(s)` URL and are passed through untouched.
 *
 * Second, metadata has to be carried across explicitly. What shows on the TV comes from the queue
 * item, not from anything this app draws, so a track cast without its title and artwork appears
 * there as an anonymous audio file.
 */
@UnstableApi
class CastMediaItemConverter(
    private val server: LocalMediaHttpServer
) : MediaItemConverter {

    private val delegate = DefaultMediaItemConverter()

    override fun toMediaQueueItem(item: MediaItem): MediaQueueItem {
        val rewritten = rewriteForCast(item)
        return delegate.toMediaQueueItem(rewritten)
    }

    override fun toMediaItem(queueItem: MediaQueueItem): MediaItem = delegate.toMediaItem(queueItem)

    private fun rewriteForCast(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri ?: return item
        val scheme = uri.scheme?.lowercase()

        // Already fetchable by the Cast device; nothing to do but make sure the metadata travels.
        if (scheme == "http" || scheme == "https") {
            return item.buildUpon().setMediaMetadata(castMetadata(item.mediaMetadata)).build()
        }

        val servedUrl = server.register(uri)
        if (servedUrl == null) {
            // Without a served URL this track cannot play on the Cast device. Returning it
            // unchanged lets the failure surface as one skipped track rather than a crash that
            // takes the whole queue with it.
            Log.w(TAG, "Could not serve $uri to the Cast device; it will be skipped")
            return item
        }

        return item.buildUpon()
            .setUri(Uri.parse(servedUrl))
            // MIME type is what makes the receiver willing to play the stream at all. The served
            // URL has no file extension for it to infer one from, so it is stated outright.
            .setMimeType(MIME_TYPE_AUDIO)
            .setMediaMetadata(castMetadata(item.mediaMetadata))
            .build()
    }

    /**
     * Copies the fields the TV screen actually renders.
     *
     * Artwork is only included when it is a remote URL: a local cover art path is no more
     * reachable by the Cast device than a local audio file, and pointing at one produces a broken
     * image rather than no image.
     */
    private fun castMetadata(source: MediaMetadata): MediaMetadata {
        val artwork = source.artworkUri?.takeIf {
            val scheme = it.scheme?.lowercase()
            scheme == "http" || scheme == "https"
        }
        return source.buildUpon()
            .setArtworkUri(artwork)
            .build()
    }

    private companion object {
        const val TAG = "CastMediaItemConverter"
        const val MIME_TYPE_AUDIO = "audio/mpeg"
    }
}
