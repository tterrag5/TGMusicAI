# Data Layer Map

Everything under `app/src/main/java/com/example/tgmusicai/data/`. This is where songs,
playlists, alarms, settings, backups, and all network/YouTube/Google access live. Every DAO
and most files here now have full KDoc comments on every function — read the file itself for
exact behavior; this doc is for finding which file owns what.

## If you want to change how data is stored, fetched, or synced, start here

- **Central repository**: `repository/MusicRepository.kt` (695 ln) — the one class ViewModels
  actually talk to for songs/playlists/stats/alarms. Wraps all the DAOs below plus higher-level
  logic (Liked Music smart playlist, radio-queue building, library dedup, backup export/import).
  If a screen's data looks wrong, this is usually the first stop after the DAO itself.
- **Database schema**: `local/AppDatabase.kt` — the `@Database` annotation, DAO accessors, and
  every `Migration` (schema is at version 10). Add a new table/column here, with a matching
  `MIGRATION_x_y` — never `fallbackToDestructiveMigration`-only, that wipes user data.
- **Settings**: `local/AppPreferences.kt` — every simple app setting (theme, AI API key,
  equalizer state, one-shot migration flags) as DataStore `Flow`/`suspend fun set...` pairs.

## Room persistence (`local/`)

### DAOs (`local/dao/`) — one per table, called only from `MusicRepository` (or `YouTubePlaylistSyncManager`/`BackupManager` for a few)

| File | Table | Key functions |
|---|---|---|
| `SongDao.kt` | `songs` | `insertSong`/`insertSongs` (REPLACE), `getAllSongs`/`getDownloadedSongs`/`getNotDownloadedSongs`/`getPinnedSongs`/`getUnplayedSongs`/`getRecentlyAddedSongs` (all `Flow`), `getSongByUri`/`getSongByYoutubeId`/`getSongByTitleAndArtist`/`findByTitleAndNormalizedArtist` (dedup/lookup), `updateSongLyrics`/`updateSongArtwork`/`updatePinStatus`/`updateDownloadStatus`/`updateSongArtist`, `deleteSong` |
| `PlaylistDao.kt` | `playlists` + `playlist_song_cross_ref` | `insertPlaylist`, `insertPlaylistSongCrossRef`, `getAllPlaylists`/`getPinnedPlaylists`/`getSmartPlaylists`/`getYoutubeSyncedPlaylists` (`Flow`), `getPlaylistWithSongs` (JOIN via `@Relation`), `removeSongFromPlaylist`, `updatePinStatus`, `updatePlaylistDescription`, `getPlaylistByYoutubeId`/`updateLastSyncedAt` (YouTube sync bookkeeping), `deletePlaylist` (deletes cross-refs first, no `CASCADE` defined) |
| `SongStatsDao.kt` | `song_stats` | `insertOrUpdate`, `getStatsForSong`, `getMostPlayedStats`/`getRecentlyPlayedStats` (`Flow`), `getTotalPlayCount`/`getTotalListenTimeMs` (`Flow`, for Stats screen hero metrics), `incrementPlayCount` (upsert, keyed by `songId`), `addListenTime` (accumulates `totalListenTimeMs`), `deleteOrphanedStats` |
| `AlarmDao.kt` | `alarms` | `getAllAlarms` (`Flow`), `getEnabledAlarms`, `insertAlarm`/`updateAlarm`/`deleteAlarm`/`deleteAlarmById` |
| `PendingDownloadDao.kt` | `pending_downloads` | `insert`, `deleteByVideoId`, `getAll` — read once at launch by `CloudDownloadManager.resumePendingDownloads()` to resume interrupted downloads |
| `AiSongTagsDao.kt` | `ai_song_tags` | `insertOrUpdate`, `getForSong`, `getAll`, `deleteForSong`, `deleteOrphaned` — isolated table, only touched by `ai/AiFeatureManager` |
| `ListeningHistoryDao.kt` | `listening_history` | `insert` (one row per playback chunk), `getDailyTotalsSince` (grouped sum, powers Stats weekly trend chart), `deleteOrphanedHistory` |

### Entities (`local/entity/`)

`Song`, `Playlist`, `PlaylistSongCrossRef` (many-to-many join table), `PlaylistWithSongs`
(Room `@Relation` projection, not a real table), `SongStats`, `Alarm` (+ `AlarmToneType` enum),
`PendingDownload`, `AiSongTags`, `ListeningHistory`. Every field already has an inline KDoc
comment explaining what it stores — read the entity file directly for column-level detail.

### Other `local/` files

| File | Role |
|---|---|
| `AppDatabase.kt` | Schema + migrations, see above. |
| `AppPreferences.kt` | DataStore-backed settings, see above. |
| `MediaScanner.kt` | `scanMediaStore` — reads the device's local music via `MediaStore`, cleans titles through `AiMetadataCleaner`, dedupes by `mediaUri`, and inserts into `SongDao`. Run on every app launch from `MainActivity`. |
| `AiMetadataCleaner.kt` | `cleanOffline` (regex-based, <1ms: strips `[Official Audio]`-style tags, extracts producer/feat artist, splits "Artist - Title"), `clean` (offline + optional one-shot Gemini/OpenAI HTTP call if an API key is set), `stripTopicSuffix`/`normalizeArtistForMatching` (used everywhere artist-name dedup matters). |
| `BackupManager.kt` | `exportBackup` (zips a `manifest.json` of every table + `audio/` files for downloaded songs), `importBackup` (reverses it; has a Zip-Slip path-traversal guard on extraction). No manifest version field — export/import must be changed together. |

## Network (`network/`)

- `NetworkObserver.kt` — `isOnline: StateFlow<Boolean>`, backed by a live `ConnectivityManager`
  callback. Anything that shouldn't run offline (cover-art backfill, YouTube sync) checks this.

## Google / YouTube Data API (`google/`)

| File | Key functions | Role |
|---|---|---|
| `GoogleAuthManager.kt` | `getAccessToken` (handles sign-in + consent-screen launch + token refresh) | Google Sign-In + OAuth for the YouTube read-only scope. |
| `YouTubeDataApiClient.kt` | `fetchMyPlaylists`, `resolveLikedVideosPlaylistId`, `fetchPlaylistVideos`, `fetchVideoDurations` | Thin wrapper over the real YouTube Data API v3 (requires the user's own Google account + OAuth token — separate from the anonymous scraping in `data/youtube/`). |
| `YouTubePlaylistSyncManager.kt` | `importPlaylist`, `importLikedVideos`, `syncAll` | Imports/refreshes a signed-in user's real YouTube playlists into local `Playlist`/`Song` rows. |

## YouTube extraction & download — anonymous, no login (`youtube/`)

| File | Key functions | Role |
|---|---|---|
| `YouTubeExtractor.kt` (769 ln, largest file in `data/`) | `search`, `extractAudioStream(s)`, `verifyStreamUrl`, `extractVideoId` | Finds playable audio stream URLs for a video without any account, via NewPipeExtractor plus Piped/Invidious instance fallbacks (rotates through live public instances since any one can go down). |
| `CloudDownloadManager.kt` (599 ln) | `downloadTrack`, `downloadPlaylist`, `pauseDownload`/`resumeDownload`/`cancelDownload`, `resumePendingDownloads`, `checkAndDeduplicate` | Runs the actual download of an extracted stream to local storage, tracks per-video progress state, and persists in-flight downloads to `PendingDownloadDao` so a killed app resumes them. |
| `YouTubeAudioStream.kt` / `YouTubeSearchResult.kt` | data classes | Plain result types passed between `YouTubeExtractor`, `CloudDownloadManager`, and `ui/viewmodel/YouTubeViewModel`. |

## Repositories (`repository/`)

| File | Key functions | Role |
|---|---|---|
| `MusicRepository.kt` (695 ln) | see function list below | Central facade over all the local DAOs — described at the top of this doc. |
| `LyricsRepository.kt` (554 ln) | `fetchAndSaveLyrics`, `transcribeWithWhisper` | Lyrics pipeline: tries LRCLIB API → embedded ID3 tags → falls back to Whisper AI transcription or YouTube's own auto-captions, in that order, first success wins. |
| `CoverArtScraper.kt` (260 ln) | `scrapeAndSaveArtwork` | Album-art pipeline: tries iTunes Search API → MusicBrainz/Cover Art Archive → the source YouTube video's thumbnail, first success wins; downloads and saves the image locally. |

### `MusicRepository` function groups (all delegate to the DAOs above; this is the layer with real business logic)

- **Reads**: `getPlaylistWithSongs`, `getRecentlyPlayedSongs`, `getMostPlayedSongsWithStats`,
  `getTopProducers`, `getTopArtistsByPlayCount`, `getWeeklyListeningTrend`, `getUnplayedSongs`,
  `getRecentlyAddedSongs`, `getTotalPlayCount`, `getTotalListenTimeMs`.
- **Playlists**: `createPlaylist`, `updatePlaylistDescription`, `deletePlaylist`,
  `addSongToPlaylist`, `removeSongFromPlaylist`, `togglePinPlaylist`,
  `ensureSmartPlaylistsExist` (mutex-guarded, builds "Liked Music"/"Most Played"/etc.).
- **Liking**: `getOrCreateLikedMusicPlaylistId` (mutex-guarded to avoid creating it twice from a
  race), `toggleLikeSong`, `likeSongs`/`unlikeSongs` (batch), `isSongLiked`/`isSongLikedSync`.
- **Songs**: `insertSong`, `ensurePersisted`, `updateSongLyrics`, `updateSongArtwork`,
  `togglePinSong`, `removeDownloadKeepInPlaylist` (un-downloads but keeps the library row),
  `deleteSongsCompletely` (removes row + local audio file).
- **Radio / discovery**: `buildRadioQueue` (seeds a queue of similar songs off one track).
- **Maintenance**: `deduplicateLibrary` + private `mergeDuplicateSongGroup` (the logic behind the
  `V1..V6` one-time dedup passes `MainActivity` runs on startup), `cleanupOrphanedStats`.
- **Alarms**: `insertOrUpdateAlarm`, `deleteAlarm`, `getAlarmById` (thin pass-through to `AlarmDao`).
- **Backup**: `exportBackup`/`importBackup` (delegate to `BackupManager`).

## Practical "where do I go to fix X" guide

- A song/playlist/stat looks wrong on screen → check the relevant `ViewModel` first (see
  `01_UI_LAYER.md`), then `MusicRepository`, then the specific DAO query.
- Library scan isn't picking up a file / titles look messy → `MediaScanner.kt` +
  `AiMetadataCleaner.kt`.
- YouTube search/download broken → `data/youtube/YouTubeExtractor.kt` (search/extraction) or
  `CloudDownloadManager.kt` (the download itself).
- "Import my YouTube playlists" broken → `data/google/` (auth + real API), not `data/youtube/`
  (those are two separate, unrelated YouTube integrations).
- Lyrics or cover art missing/wrong → `repository/LyricsRepository.kt` /
  `repository/CoverArtScraper.kt`, each tries multiple sources in order.
- Need a new persisted field → add it to the entity in `local/entity/`, add a `Migration` in
  `AppDatabase.kt`, add DAO queries in the matching `local/dao/` file, expose it through
  `MusicRepository`.
- Backup/restore missing a field → `BackupManager.kt` (`exportBackup` and `importBackup` must be
  edited together, no schema version to catch a mismatch).
