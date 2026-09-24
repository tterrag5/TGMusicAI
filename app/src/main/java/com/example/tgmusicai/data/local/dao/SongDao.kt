package com.example.tgmusicai.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.tgmusicai.data.local.AiMetadataCleaner
import com.example.tgmusicai.data.local.entity.Song
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for interacting with the [Song] entity in the database.
 */
@Dao
interface SongDao {
    /** Bulk-inserts songs (e.g. from a device media scan), silently skipping any that would collide with an existing row instead of overwriting it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<Song>)

    /** Inserts a single new song, or overwrites one with the same id if it already exists. Returns the row id (new or existing). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: Song): Long

    /** Live list of every song in the library, alphabetical by title; backs the main library UI. */
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<Song>>

    /** One-shot (non-Flow) read of every song, alphabetical by title. */
    @Query("SELECT * FROM songs ORDER BY title ASC")
    suspend fun getAllSongsList(): List<Song>

    /** Live list of songs whose audio is saved on-device (playable offline), alphabetical by title. */
    @Query("SELECT * FROM songs WHERE is_downloaded = 1 ORDER BY title ASC")
    fun getDownloadedSongs(): Flow<List<Song>>

    /** Live list of songs that are cloud-stream-only (no local audio file yet), alphabetical by title. */
    @Query("SELECT * FROM songs WHERE is_downloaded = 0 ORDER BY title ASC")
    fun getNotDownloadedSongs(): Flow<List<Song>>

    // Sync variant of getDownloadedSongs, used by the Android Auto "Downloaded Only" media tree
    // category, which must build its browse tree with a one-shot suspend query rather than a Flow.
    @Query("SELECT * FROM songs WHERE is_downloaded = 1 ORDER BY title ASC")
    suspend fun getDownloadedSongsSync(): List<Song>

    // Used for autoplay: when a queue ends with repeat off, pull a random locally-playable
    // (already-downloaded) song to keep music going instead of just stopping.
    @Query("SELECT * FROM songs WHERE is_downloaded = 1 ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomDownloadedSongs(limit: Int): List<Song>

    /** Live list of songs pinned to the Home tab's speed-dial row, alphabetical by title. */
    @Query("SELECT * FROM songs WHERE is_pinned = 1 ORDER BY title ASC")
    fun getPinnedSongs(): Flow<List<Song>>

    // Used for the automatic cover-art backfill pass: songs that either never got artwork or
    // whose scrape attempt previously failed and left a blank marker.
    @Query("SELECT * FROM songs WHERE artworkUri IS NULL OR artworkUri = ''")
    suspend fun getSongsMissingArtwork(): List<Song>

    /** Looks up a single song by its row id, or null if it no longer exists. */
    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongById(songId: Long): Song?

    /** Looks up a song by its exact local/stream URI, e.g. to match a currently-playing MediaItem back to its library row. */
    @Query("SELECT * FROM songs WHERE mediaUri = :uri LIMIT 1")
    suspend fun getSongByUri(uri: String): Song?

    /** Looks up a song already downloaded/imported from a given YouTube video id, used to detect "already have this" before re-downloading. */
    @Query("SELECT * FROM songs WHERE youtube_id = :youtubeId LIMIT 1")
    suspend fun getSongByYoutubeId(youtubeId: String): Song?

    /** Case-insensitive exact match on title and artist; the fast-path "same song" check before falling back to [findByTitleAndNormalizedArtist]'s fuzzier artist matching. */
    @Query("SELECT * FROM songs WHERE LOWER(title) = LOWER(:title) AND LOWER(artist) = LOWER(:artist) LIMIT 1")
    suspend fun getSongByTitleAndArtist(title: String, artist: String): Song?

    // Used when an exact title+artist match fails but the artist name might still refer to the
    // same real artist under a different variant (e.g. "Jamie Paige" vs "Jamie Paige / JamieP" or
    // "Jamie Paige - Topic") -- callers filter these candidates with normalized artist matching.
    @Query("SELECT * FROM songs WHERE LOWER(title) = LOWER(:title)")
    suspend fun getSongsByTitle(title: String): List<Song>

    /**
     * Finds an existing song matching [title] and [artist], the same "same song" definition used
     * everywhere a cloud track gets inserted (downloads, playlist-add, YouTube playlist sync):
     * exact match first, then a normalized-artist match (strips a YouTube Topic-channel suffix
     * and any "/alias" tail) against every song sharing the title. Centralized here so all three
     * insertion paths share one definition instead of drifting out of sync with each other.
     */
    @Transaction
    suspend fun findByTitleAndNormalizedArtist(title: String, artist: String): Song? {
        getSongByTitleAndArtist(title, artist)?.let { return it }
        val normalized = AiMetadataCleaner.normalizeArtistForMatching(artist)
        return getSongsByTitle(title).firstOrNull {
            AiMetadataCleaner.normalizeArtistForMatching(it.artist) == normalized
        }
    }

    /** Live list of the [limit] most recently inserted songs (highest id first); backs the "Recently Added" smart list. */
    @Query("SELECT * FROM songs ORDER BY id DESC LIMIT :limit")
    fun getRecentlyAddedSongs(limit: Int = 50): Flow<List<Song>>

    /** Live list of songs with zero recorded plays (via a NOT IN subquery against [SongStatsDao]'s table); backs the "Unplayed" smart list. */
    @Query("SELECT * FROM songs WHERE id NOT IN (SELECT songId FROM song_stats WHERE playCount > 0) ORDER BY title ASC")
    fun getUnplayedSongs(): Flow<List<Song>>

    /** Deletes a song row (matched by primary key). Does not clean up related `song_stats`, `playlist_song_cross_ref`, `ai_song_tags`, or `listening_history` rows -- callers must clean those up separately (see each DAO's orphan-deletion queries). */
    @Delete
    suspend fun deleteSong(song: Song)

    /** Updates just a song's stored lyrics text, leaving every other field untouched. */
    @Query("UPDATE songs SET lyrics = :lyrics WHERE id = :id")
    suspend fun updateSongLyrics(id: Long, lyrics: String?)

    /** Updates just a song's cover-art URI, e.g. after [com.example.tgmusicai.data.repository.CoverArtScraper] finds artwork. */
    @Query("UPDATE songs SET artworkUri = :artworkUri WHERE id = :id")
    suspend fun updateSongArtwork(id: Long, artworkUri: String?)

    /** Sets whether a song is pinned to the Home tab's speed-dial row. */
    @Query("UPDATE songs SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: Long, isPinned: Boolean)

    /** Marks a song downloaded/not-downloaded and updates its playable URI in one step, so the two fields never go out of sync mid-download. */
    @Query("UPDATE songs SET is_downloaded = :isDownloaded, mediaUri = :mediaUri WHERE id = :id")
    suspend fun updateDownloadStatus(id: Long, isDownloaded: Boolean, mediaUri: String)

    /** Updates just a song's artist name, e.g. after [com.example.tgmusicai.data.local.AiMetadataCleaner] cleans up a messy YouTube channel name. */
    @Query("UPDATE songs SET artist = :artist WHERE id = :id")
    suspend fun updateSongArtist(id: Long, artist: String)

    /** Stores a track's measured (or tag-supplied) loudness, used to normalize playback volume. */
    @Query("UPDATE songs SET replay_gain_db = :gainDb, replay_peak = :peak WHERE id = :id")
    suspend fun updateReplayGain(id: Long, gainDb: Float?, peak: Float?)

    /**
     * Local tracks whose loudness has never been determined, for the background measurement pass.
     *
     * Cloud-only tracks are excluded: measuring one means downloading it, and a stream that has
     * never been on the device would be re-fetched on every pass and still never gain a value.
     */
    @Query("SELECT * FROM songs WHERE replay_gain_db IS NULL AND is_downloaded = 1 LIMIT :limit")
    suspend fun getSongsMissingReplayGain(limit: Int): List<Song>

    /** How many local tracks still need measuring, for the progress readout in Settings. */
    @Query("SELECT COUNT(*) FROM songs WHERE replay_gain_db IS NULL AND is_downloaded = 1")
    fun countSongsMissingReplayGain(): Flow<Int>

    /** Records which directory a track's file lives in, for the folder browser. */
    @Query("UPDATE songs SET folder_path = :folderPath WHERE id = :id")
    suspend fun updateFolderPath(id: Long, folderPath: String?)

    @Query("UPDATE songs SET genre = :genre WHERE id = :id")
    suspend fun updateGenre(id: Long, genre: String?)

    /**
     * Downloaded tracks whose genre has not been read yet, so the backfill can work through the
     * library a batch at a time instead of parsing every file at once.
     */
    @Query("SELECT * FROM songs WHERE genre IS NULL AND is_downloaded = 1 LIMIT :limit")
    suspend fun getSongsMissingGenre(limit: Int): List<Song>

    /** Local tracks whose folder is not known yet, for the background backfill pass. */
    @Query("SELECT * FROM songs WHERE folder_path IS NULL AND is_downloaded = 1 LIMIT :limit")
    suspend fun getSongsMissingFolderPath(limit: Int): List<Song>

    /** Every known folder with the number of tracks in it, for rendering the folder tree. */
    @Query("SELECT * FROM songs WHERE folder_path IS NOT NULL AND is_downloaded = 1 ORDER BY title ASC")
    fun getSongsWithFolder(): Flow<List<Song>>

    /** Applies an in-app tag edit to the library row backing the file that was just rewritten. */
    @Query(
        "UPDATE songs SET title = :title, artist = :artist, album = :album, producer = :producer WHERE id = :id"
    )
    suspend fun updateEditedMetadata(
        id: Long,
        title: String,
        artist: String,
        album: String,
        producer: String?
    )
}
