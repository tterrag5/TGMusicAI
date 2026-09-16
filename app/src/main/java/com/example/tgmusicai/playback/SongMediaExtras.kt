package com.example.tgmusicai.playback

import android.os.Bundle
import com.example.tgmusicai.data.local.entity.Song

/**
 * Embeds enough of a [Song]'s real database fields (id, artwork, duration, YouTube id, download
 * status) into a [androidx.media3.common.MediaItem]'s [androidx.media3.common.MediaMetadata]
 * extras Bundle that it can be faithfully reconstructed anywhere only the raw [MediaItem] is
 * available -- most importantly when [PlaybackService] appends an autoplay song directly to the
 * live ExoPlayer queue on the service side. Without this, [MediaControllerManager] had no way to
 * tell such a song apart from a truly unknown one, and fell back to a lossy placeholder with
 * `id = 0` and no artwork -- which is why newly-appended queue songs (after a single song was
 * played to its end, or a playlist ran out) showed no cover art anywhere in the UI.
 */
object SongMediaExtras {
    private const val KEY_SONG_ID = "tgmusicai.song_id"
    private const val KEY_ARTWORK_URI = "tgmusicai.artwork_uri"
    private const val KEY_DURATION_MS = "tgmusicai.duration_ms"
    private const val KEY_YOUTUBE_ID = "tgmusicai.youtube_id"
    private const val KEY_IS_DOWNLOADED = "tgmusicai.is_downloaded"

    fun fromSong(song: Song): Bundle = Bundle().apply {
        putLong(KEY_SONG_ID, song.id)
        song.artworkUri?.let { putString(KEY_ARTWORK_URI, it) }
        putLong(KEY_DURATION_MS, song.durationMs)
        song.youtubeId?.let { putString(KEY_YOUTUBE_ID, it) }
        putBoolean(KEY_IS_DOWNLOADED, song.isDownloaded)
    }

    /** The real DB song id embedded in [extras], or null if absent/never persisted (id == 0). */
    fun songId(extras: Bundle?): Long? = extras?.getLong(KEY_SONG_ID, 0L)?.takeIf { it != 0L }

    /** The YouTube video id embedded in [extras], if any. */
    fun youtubeId(extras: Bundle?): String? = extras?.getString(KEY_YOUTUBE_ID)

    fun toSong(mediaUri: String, title: String, artist: String, album: String, extras: Bundle?): Song {
        return Song(
            id = extras?.getLong(KEY_SONG_ID, 0L) ?: 0L,
            title = title,
            artist = artist,
            album = album,
            durationMs = extras?.getLong(KEY_DURATION_MS, 0L) ?: 0L,
            mediaUri = mediaUri,
            artworkUri = extras?.getString(KEY_ARTWORK_URI),
            youtubeId = extras?.getString(KEY_YOUTUBE_ID),
            isDownloaded = extras?.getBoolean(KEY_IS_DOWNLOADED, true) ?: true
        )
    }
}
