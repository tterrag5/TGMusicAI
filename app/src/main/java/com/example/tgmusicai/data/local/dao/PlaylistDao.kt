package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.PlaylistSongCrossRef
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for interacting with [Playlist] and [PlaylistSongCrossRef].
 */
@Dao
interface PlaylistDao {
    /** Inserts a new playlist, or overwrites one with the same id if it already exists. Returns the row id (new or existing). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    /** Adds one song to one playlist at a given [PlaylistSongCrossRef.position], or overwrites the existing link if that (playlist, song) pair is already present. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongCrossRef(crossRef: PlaylistSongCrossRef)

    /** Live list of every playlist (user-made and smart), newest-created first; backs the Playlists screen. */
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    /** One-shot (non-Flow) read of every playlist, newest-created first. */
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylistsList(): List<Playlist>

    /** Live list of playlists pinned to the Home tab's speed-dial row. */
    @Query("SELECT * FROM playlists WHERE is_pinned = 1 ORDER BY createdAt DESC")
    fun getPinnedPlaylists(): Flow<List<Playlist>>

    /** Live list of app-generated smart playlists (e.g. Top Played, Recently Added, Unplayed), as opposed to user-created ones. */
    @Query("SELECT * FROM playlists WHERE is_smart = 1 ORDER BY createdAt DESC")
    fun getSmartPlaylists(): Flow<List<Playlist>>

    /** Looks up one playlist by exact name match; used to avoid creating duplicate-named playlists. Ambiguous if names aren't unique -- see [getPlaylistsByName]. */
    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getPlaylistByName(name: String): Playlist?

    /** Looks up every playlist sharing a given name, since playlist names aren't enforced unique in the schema. */
    @Query("SELECT * FROM playlists WHERE name = :name")
    suspend fun getPlaylistsByName(name: String): List<Playlist>

    /** Looks up a single playlist by its row id, or null if it no longer exists. */
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    suspend fun getPlaylistById(playlistId: Long): Playlist?

    /** Live playlist plus its full song list (via [PlaylistSongCrossRef]) in one query; backs the Playlist Detail screen so both playlist metadata and contents update together. */
    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    fun getPlaylistWithSongs(playlistId: Long): Flow<PlaylistWithSongs?>

    /** Sync (suspend, one-shot) variant of [getPlaylistWithSongs], e.g. for building an Android Auto browse node or a one-time export. */
    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    suspend fun getPlaylistWithSongsSync(playlistId: Long): PlaylistWithSongs?

    /** Removes every song-link row for a playlist, without touching the playlist row or the songs themselves. Call before deleting a playlist (see [deletePlaylist]) or when clearing its contents. */
    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun deleteCrossRefsForPlaylist(playlistId: Long)

    /** Raw delete of the playlist row only. Prefer [deletePlaylist], which also cleans up its cross-ref rows first. */
    @Delete
    suspend fun deletePlaylistEntity(playlist: Playlist)

    /**
     * Safely deletes a playlist and removes all of its associated cross-reference entries
     * without affecting the underlying songs or causing null pointer errors.
     */
    @Transaction
    suspend fun deletePlaylist(playlist: Playlist) {
        deleteCrossRefsForPlaylist(playlist.playlistId)
        deletePlaylistEntity(playlist)
    }

    /** Unlinks one song from one playlist without deleting either the playlist or the song. */
    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    /** Sets whether a playlist is pinned to the Home tab's speed-dial row. */
    @Query("UPDATE playlists SET is_pinned = :isPinned WHERE playlistId = :playlistId")
    suspend fun updatePinStatus(playlistId: Long, isPinned: Boolean)

    /** Updates just a playlist's description text, leaving its name and other fields untouched. */
    @Query("UPDATE playlists SET description = :description WHERE playlistId = :playlistId")
    suspend fun updatePlaylistDescription(playlistId: Long, description: String?)

    /** Live boolean: is a given song currently in a given playlist? Drives an "already added" checkmark in the UI that updates live. */
    @Query("SELECT EXISTS(SELECT 1 FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId)")
    fun isSongInPlaylistFlow(playlistId: Long, songId: Long): Flow<Boolean>

    /** Sync (suspend, one-shot) variant of [isSongInPlaylistFlow], e.g. to decide whether an add-to-playlist action should insert or no-op. */
    @Query("SELECT EXISTS(SELECT 1 FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND songId = :songId)")
    suspend fun isSongInPlaylistSync(playlistId: Long, songId: Long): Boolean

    /** Looks up the local playlist already linked to a given YouTube playlist id (if this library has synced it before). */
    @Query("SELECT * FROM playlists WHERE youtube_playlist_id = :youtubePlaylistId LIMIT 1")
    suspend fun getPlaylistByYoutubeId(youtubePlaylistId: String): Playlist?

    /** Live list of playlists that are mirrors of a YouTube playlist (i.e. [Playlist.youtubePlaylistId] is set); backs the Google Sync screen. */
    @Query("SELECT * FROM playlists WHERE youtube_playlist_id IS NOT NULL ORDER BY createdAt DESC")
    fun getYoutubeSyncedPlaylists(): Flow<List<Playlist>>

    /** One-shot variant of [getYoutubeSyncedPlaylists], used when running a sync pass over every linked playlist. */
    @Query("SELECT * FROM playlists WHERE youtube_playlist_id IS NOT NULL")
    suspend fun getYoutubeSyncedPlaylistsList(): List<Playlist>

    /** Stamps a YouTube-synced playlist with the time its most recent sync finished. */
    @Query("UPDATE playlists SET last_synced_at = :timestamp WHERE playlistId = :playlistId")
    suspend fun updateLastSyncedAt(playlistId: Long, timestamp: Long)

    /** Lists the song ids contained in a playlist, without pulling full [Song] rows (see [getPlaylistWithSongs] when full song data is needed). */
    @Query("SELECT songId FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun getSongIdsInPlaylist(playlistId: Long): List<Long>

    /** Lists every playlist id a given song belongs to, e.g. to show "in N playlists" or to update all of them when a song changes. */
    @Query("SELECT playlistId FROM playlist_song_cross_ref WHERE songId = :songId")
    suspend fun getPlaylistIdsForSong(songId: Long): List<Long>

    /** Removes every playlist-link row for a song. Call when a song itself is deleted, since there's no Room-level cascade for this cross-ref table. */
    @Query("DELETE FROM playlist_song_cross_ref WHERE songId = :songId")
    suspend fun deleteCrossRefsForSong(songId: Long)
}
