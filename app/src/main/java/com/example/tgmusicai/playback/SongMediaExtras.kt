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
    private const val KEY_REPLAY_GAIN_DB = "tgmusicai.replay_gain_db"
    private const val KEY_REPLAY_PEAK = "tgmusicai.replay_peak"
    private const val KEY_ORIGIN_PLAYLIST_ID = "tgmusicai.origin_playlist_id"

    /**
     * [originPlaylistId] is the playlist this queue was started from, if any. It rides along with
     * the item because a play is counted on the service side, long after the screen that started
     * playback is gone -- there is nowhere else to ask. Absent for a queue that did not come from a
     * playlist, which is most of them.
     */
    fun fromSong(song: Song, originPlaylistId: Long? = null): Bundle = Bundle().apply {
        putLong(KEY_SONG_ID, song.id)
        originPlaylistId?.let { putLong(KEY_ORIGIN_PLAYLIST_ID, it) }
        song.artworkUri?.let { putString(KEY_ARTWORK_URI, it) }
        putLong(KEY_DURATION_MS, song.durationMs)
        song.youtubeId?.let { putString(KEY_YOUTUBE_ID, it) }
        putBoolean(KEY_IS_DOWNLOADED, song.isDownloaded)
        // Only written when known. Absent and "zero" mean different things for loudness -- 0 dB is
        // a real gain meaning "already at reference level" -- so the keys stay missing rather than
        // defaulting, and the readers below distinguish the two.
        song.replayGainDb?.let { putFloat(KEY_REPLAY_GAIN_DB, it) }
        song.replayPeak?.let { putFloat(KEY_REPLAY_PEAK, it) }
    }

    /** The track's stored loudness offset in dB, or null if it has never been determined. */
    fun replayGainDb(extras: Bundle?): Float? =
        if (extras?.containsKey(KEY_REPLAY_GAIN_DB) == true) extras.getFloat(KEY_REPLAY_GAIN_DB) else null

    /** The track's measured peak as a fraction of full scale, or null if unknown. */
    fun replayPeak(extras: Bundle?): Float? =
        if (extras?.containsKey(KEY_REPLAY_PEAK) == true) extras.getFloat(KEY_REPLAY_PEAK) else null

    /** The real DB song id embedded in [extras], or null if absent/never persisted (id == 0). */
    fun songId(extras: Bundle?): Long? = extras?.getLong(KEY_SONG_ID, 0L)?.takeIf { it != 0L }

    /** The playlist this item was queued from, or null if it was not queued from one. */
    fun originPlaylistId(extras: Bundle?): Long? =
        extras?.getLong(KEY_ORIGIN_PLAYLIST_ID, 0L)?.takeIf { it != 0L }

    /** The YouTube video id embedded in [extras], if any. */
    fun youtubeId(extras: Bundle?): String? = extras?.getString(KEY_YOUTUBE_ID)

    /** The song's own stored artwork URI, before any per-consumer content:// URI is minted from it. */
    fun artworkUri(extras: Bundle?): String? = extras?.getString(KEY_ARTWORK_URI)

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
            isDownloaded = extras?.getBoolean(KEY_IS_DOWNLOADED, true) ?: true,
            replayGainDb = replayGainDb(extras),
            replayPeak = replayPeak(extras)
        )
    }
}
