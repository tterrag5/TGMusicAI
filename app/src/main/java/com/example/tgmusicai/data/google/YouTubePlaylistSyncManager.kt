package com.example.tgmusicai.data.google

import android.util.Log
import com.example.tgmusicai.data.local.AiMetadataCleaner
import com.example.tgmusicai.data.local.dao.PlaylistDao
import com.example.tgmusicai.data.local.dao.SongDao
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistSongCrossRef
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports a signed-in Google account's YouTube playlists (including Liked Videos) as local,
 * YouTube-synced [Playlist]s, and keeps them up to date: re-running a sync adds songs newly
 * present in the source playlist and removes ones no longer there, without touching the
 * underlying [Song] rows (which may also be downloaded or belong to other, non-synced playlists).
 *
 * Imported videos are stored as cloud/streamable songs (`isDownloaded = false`, `mediaUri` an
 * unresolved YouTube watch URL) -- the same convention [com.example.tgmusicai.ui.viewmodel.YouTubeViewModel.addCloudTrackToPlaylist]
 * already uses, which [com.example.tgmusicai.playback.MediaControllerManager] knows how to
 * resolve to a real stream URL at play time.
 */
class YouTubePlaylistSyncManager(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val musicRepository: MusicRepository,
    private val apiClient: YouTubeDataApiClient = YouTubeDataApiClient()
) {
    private val TAG = "YouTubePlaylistSync"

    /**
     * Imports [googlePlaylist] as a new local playlist (or returns the existing one's ID if
     * already imported), then performs an initial sync of its contents.
     */
    suspend fun importPlaylist(accessToken: String, googlePlaylist: GoogleYouTubePlaylist): Long = withContext(Dispatchers.IO) {
        val playlistId = getOrCreateSyncedPlaylist(googlePlaylist.playlistId, googlePlaylist.title)
        syncPlaylistById(accessToken, playlistId, googlePlaylist.playlistId)
        playlistId
    }

    /**
     * Resolves and imports the signed-in account's Liked Videos as a local playlist named
     * "Liked Videos (YouTube)". If [mergeIntoLikedMusic] is true, every imported song is also
     * added to the app's own immutable "Liked Music" playlist, so the two stay in sync instead
     * of the YouTube import sitting as a disconnected duplicate.
     */
    suspend fun importLikedVideos(accessToken: String, mergeIntoLikedMusic: Boolean = false): Long = withContext(Dispatchers.IO) {
        val likedPlaylistId = apiClient.resolveLikedVideosPlaylistId(accessToken)
        val playlistId = getOrCreateSyncedPlaylist(likedPlaylistId, "Liked Videos (YouTube)")
        val importedSongIds = syncPlaylistById(accessToken, playlistId, likedPlaylistId)

        if (mergeIntoLikedMusic) {
            val appLikedPlaylistId = musicRepository.getOrCreateLikedMusicPlaylistId()
            for (songId in importedSongIds) {
                playlistDao.insertPlaylistSongCrossRef(PlaylistSongCrossRef(appLikedPlaylistId, songId))
            }
            Log.d(TAG, "Merged ${importedSongIds.size} imported Liked Video(s) into the app's Liked Music playlist")
        }

        playlistId
    }

    /**
     * Re-syncs every already-imported YouTube-synced playlist against its current source content.
     * Intended to be called on app startup (if a valid access token is available) and from a
     * manual pull-to-refresh.
     */
    suspend fun syncAll(accessToken: String) = withContext(Dispatchers.IO) {
        val syncedPlaylists = playlistDao.getYoutubeSyncedPlaylistsList()
        for (playlist in syncedPlaylists) {
            val youtubePlaylistId = playlist.youtubePlaylistId ?: continue
            try {
                syncPlaylistById(accessToken, playlist.playlistId, youtubePlaylistId)
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed for playlist '${playlist.name}' ($youtubePlaylistId): ${e.message}", e)
            }
        }
    }

    /**
     * Returns the local playlist ID already linked to [youtubePlaylistId], or creates a new
     * local [Playlist] row named [title] the first time this source playlist is imported.
     */
    private suspend fun getOrCreateSyncedPlaylist(youtubePlaylistId: String, title: String): Long {
        val existing = playlistDao.getPlaylistByYoutubeId(youtubePlaylistId)
        if (existing != null) return existing.playlistId
        return playlistDao.insertPlaylist(
            Playlist(
                name = title,
                description = "Synced from your YouTube account",
                youtubePlaylistId = youtubePlaylistId
            )
        )
    }

    /**
     * Fetches [youtubePlaylistId]'s current videos and durations from the API, upserts each as
     * a local [Song] with a cross-ref into [localPlaylistId], then drops the cross-ref for any
     * song that was in the local playlist before but is no longer present remotely (the
     * underlying [Song] row itself is left untouched). Returns the resulting set of song IDs.
     */
    private suspend fun syncPlaylistById(accessToken: String, localPlaylistId: Long, youtubePlaylistId: String): Set<Long> {
        val remoteVideos = apiClient.fetchPlaylistVideos(accessToken, youtubePlaylistId)
        val durations = apiClient.fetchVideoDurations(accessToken, remoteVideos.map { it.videoId })

        val remoteSongIds = mutableSetOf<Long>()
        for (video in remoteVideos) {
            val songId = ensurePersistedCloudSong(video, durations[video.videoId] ?: 0L)
            remoteSongIds.add(songId)
            playlistDao.insertPlaylistSongCrossRef(PlaylistSongCrossRef(localPlaylistId, songId))
        }

        // Remove membership for songs no longer present in the source playlist (does not delete
        // the underlying Song row -- it may be downloaded or belong to other playlists too).
        val currentSongIds = playlistDao.getSongIdsInPlaylist(localPlaylistId)
        for (songId in currentSongIds) {
            if (songId !in remoteSongIds) {
                playlistDao.removeSongFromPlaylist(localPlaylistId, songId)
            }
        }

        playlistDao.updateLastSyncedAt(localPlaylistId, System.currentTimeMillis())
        Log.d(TAG, "Synced playlist $youtubePlaylistId -> local #$localPlaylistId: ${remoteVideos.size} video(s)")
        return remoteSongIds
    }

    /**
     * Finds or creates the local [Song] row backing [video]. Dedupes first by YouTube video ID,
     * then by cleaned title+artist (see comment below) so an artist's normal-channel and
     * auto-generated Topic-channel uploads of the same track resolve to one row instead of
     * duplicating on every sync.
     */
    private suspend fun ensurePersistedCloudSong(video: GoogleYouTubePlaylistVideo, durationSeconds: Long): Long {
        val existing = songDao.getSongByYoutubeId(video.videoId)
        if (existing != null) return existing.id

        val cleaned = AiMetadataCleaner.cleanOffline(video.title, video.channelTitle)
        val artist = cleaned.artist ?: video.channelTitle

        // The synced YouTube playlist can genuinely contain two separate real videos for the same
        // song -- one from the artist's real channel, one from their auto-generated Topic channel
        // -- each with its own distinct video ID, so getSongByYoutubeId above never catches this.
        // Falling back to a title+artist match reuses the existing row instead of creating a
        // fresh duplicate on every re-sync.
        val existingByTitleArtist = songDao.findByTitleAndNormalizedArtist(cleaned.cleanTitle, artist)
        if (existingByTitleArtist != null) {
            if (existingByTitleArtist.youtubeId.isNullOrBlank()) {
                songDao.insertSong(existingByTitleArtist.copy(youtubeId = video.videoId))
            }
            return existingByTitleArtist.id
        }

        val song = Song(
            title = cleaned.cleanTitle,
            artist = artist,
            album = "YouTube Cloud",
            durationMs = durationSeconds * 1000L,
            mediaUri = "https://www.youtube.com/watch?v=${video.videoId}",
            producer = cleaned.producer,
            youtubeId = video.videoId,
            isDownloaded = false
        )
        return songDao.insertSong(song)
    }
}
