# TGMusicAI Code Map

One-stop index: every source file, what it's for, and its key functions/composables.
Package root: `app/src/main/java/com/example/tgmusicai/`

Companion doc: **UI_GUIDE.md** in this same folder — read that first if you specifically want to
find/edit UI screens. This file covers the *whole* codebase (data layer included).

---

## Root

| File | Purpose |
|---|---|
| `MainActivity.kt` | App entry point (`ComponentActivity`). Constructs every manager/repository singleton (DB, DAOs, `MusicRepository`, `LyricsRepository`, `CoverArtScraper`, `AiFeatureManager`, `GoogleAuthManager`, `NetworkObserver`, `MediaScanner`) and hosts the root `@Composable` (`MainScreen`) via `setContent`. Also triggers the onboarding flow and initial media scan. |

## `ai/` — on-device AI (audio tagging, lyrics embeddings)

| File | Purpose | Key functions |
|---|---|---|
| `AiFeatureManager.kt` | Single entry point the rest of the app uses for AI features. Catches all failures internally so a broken model never crashes anything else. | class `AiFeatureManager` |
| `AiModelResult.kt` | Sealed result type (`Success` / `Unavailable` / `Error`) every AI engine call returns instead of throwing. | `getOrNull()` |
| `SongTaggingEngine.kt` | Wraps Google's YAMNet TFLite model to derive genre/instrument/mood tags from a song's audio. | `tagAudioFile()`, `release()` |
| `LyricsEmbeddingEngine.kt` | Wraps a quantized MiniLM ONNX model to turn lyrics text into a 384-dim embedding for similarity/radio-mode grouping. | `embed()`, `release()`, companion `cosineSimilarity()` |
| `PcmDecoder.kt` | Decodes a short audio window into 16kHz mono float PCM for `SongTaggingEngine`'s input. Playback-independent. | `decodeToMonoPcm16k()` |
| `WordPieceTokenizer.kt` | Minimal BERT/MiniLM-style WordPiece tokenizer for `LyricsEmbeddingEngine`. | `encode()`, `attentionMask()` |

## `alarm/` — music alarm clock feature

| File | Purpose | Key functions |
|---|---|---|
| `AlarmScheduler.kt` | Schedules/cancels/snoozes system alarms via `AlarmManager`. | `scheduleAlarm()`, `cancelAlarm()`, `scheduleSnooze()`, `calculateNextTriggerTime()` |
| `AlarmReceiver.kt` | `BroadcastReceiver` that fires when a scheduled alarm's time hits; starts `AlarmPlaybackService`/`AlarmActivity`. Also hosts `AlarmWakeLock` (acquire/release a wake lock while the alarm rings). | |
| `AlarmPlaybackService.kt` | Foreground `Service` that actually plays the alarm tone (song/playlist/random-liked) via its own `ExoPlayer`, independent of the main playback session. | |
| `AlarmActivity.kt` | Full-screen UI shown when an alarm rings (snooze/dismiss). **This is a UI file** — see UI_GUIDE.md. | `@Composable AlarmScreenContent()` |
| `BootReceiver.kt` | Re-registers every enabled alarm with `AlarmManager` after a device reboot (system alarms don't survive a reboot on their own). | |

## `data/local/` — Room database, preferences, backup, media scan

| File | Purpose |
|---|---|
| `AppDatabase.kt` | The Room `@Database` itself: entity list, version history/migrations (v6→v10), and the `getDatabase()` singleton accessor. |
| `AppPreferences.kt` | Typed wrapper over Jetpack DataStore for every simple setting (onboarding flag, AI API key, theme, equalizer state, alarm volume behavior, one-shot migration flags). |
| `BackupManager.kt` | Exports/imports a portable `.tgmusic` zip (JSON manifest + local audio files) for backup/restore. `exportBackup()`, `importBackup()`. |
| `AiMetadataCleaner.kt` | Regex-based (+ optional online AI) cleaner that strips noise from raw track titles and extracts artist/producer/featured-artist. `cleanOffline()`, `clean()`, `normalizeArtistForMatching()`. |
| `MediaScanner.kt` | Reads the device's local library via `MediaStore` and mirrors it into the Room `songs` table. `scanMediaStore()`. |

### `data/local/dao/` — Room DAOs (database access)

| File | Table | Purpose |
|---|---|---|
| `SongDao.kt` | `songs` | Library CRUD/search: all songs, downloaded/pinned/unplayed/recently-added filters, title+artist matching for dedup. |
| `PlaylistDao.kt` | `playlists`, `playlist_song_cross_ref` | Playlist CRUD, pin/smart/YouTube-synced filters, song↔playlist membership queries, `deletePlaylist()` transaction. |
| `SongStatsDao.kt` | `song_stats` | Play counts & listen time. `incrementPlayCount()` and `addListenTime()` transactions read-modify-write the row. |
| `AlarmDao.kt` | `alarms` | Alarm CRUD, enabled-alarms lookup (used on boot). |
| `PendingDownloadDao.kt` | `pending_downloads` | Tracks in-flight YouTube downloads so they resume after a process kill. |
| `AiSongTagsDao.kt` | `ai_song_tags` | AI tag/embedding cache, owned exclusively by the `ai` package. |
| `ListeningHistoryDao.kt` | `listening_history` | Append-only listening-time log; `getDailyTotalsSince()` powers the Stats screen's daily chart. |

### `data/local/entity/` — Room entities (table row shapes)

| File | Table | Notes |
|---|---|---|
| `Song.kt` | `songs` | Core track record: title/artist/album/duration/mediaUri/lyrics/artwork/youtubeId/download+pin flags. |
| `Playlist.kt` | `playlists` | Name/description, pinned/smart flags, YouTube sync source + last-synced timestamp. |
| `PlaylistSongCrossRef.kt` | `playlist_song_cross_ref` | Many-to-many join table (playlist ↔ song) with a `position` column for custom ordering. |
| `PlaylistWithSongs.kt` | *(not a table)* | `@Relation` helper Room uses to fetch a playlist + its songs in one query. |
| `SongStats.kt` | `song_stats` | Play count / last-played / total listen time, split out from `Song` to avoid write contention. |
| `Alarm.kt` | `alarms` | Alarm config + `AlarmToneType` enum (SONG / PLAYLIST / RANDOM_LIKED). |
| `PendingDownload.kt` | `pending_downloads` | One row per in-progress/interrupted download. |
| `AiSongTags.kt` | `ai_song_tags` | Cached AI tags + lyrics embedding, isolated from the core schema. |
| `ListeningHistory.kt` | `listening_history` | One logged chunk of listening time (append-only). |

## `data/network/`

| File | Purpose |
|---|---|
| `NetworkObserver.kt` | Wraps `ConnectivityManager` and exposes a live `isOnline: StateFlow<Boolean>` for UI/ViewModels. |

## `data/repository/` — orchestration layer between DAOs/network and ViewModels

| File | Purpose | Key functions |
|---|---|---|
| `MusicRepository.kt` | The big orchestrator: combines `SongDao`/`PlaylistDao`/`SongStatsDao`/`AlarmDao`/`ListeningHistoryDao` into higher-level flows (recently played, most played, top artists/producers, liked songs, playlist-with-songs) and wraps `BackupManager`. Most ViewModels talk to the DB only through this class. | `getPlaylistWithSongs()`, `getRecentlyPlayedSongs()`, `getMostPlayedSongsWithStats()`, `getTopProducers()`, `getTopArtistsByPlayCount()`, `isSongLiked()`, etc. |
| `LyricsRepository.kt` | Fetches/synchronizes song lyrics from external sources (and AI transcription), returns timed `LyricLine`s for the synced-lyrics UI. | |
| `CoverArtScraper.kt` | Fetches high-res album art from iTunes/MusicBrainz/YouTube thumbnails and caches it locally, updating `Song.artworkUri`. | |

## `data/google/` — Google sign-in & YouTube playlist sync

| File | Purpose |
|---|---|
| `GoogleAuthManager.kt` | Wraps Play Services' Authorization API to get an OAuth token scoped to the YouTube Data API (no backend server needed). |
| `YouTubeDataApiClient.kt` | Thin HTTP client for the YouTube Data API v3 (list account playlists, list playlist videos). |
| `YouTubePlaylistSyncManager.kt` | Imports a signed-in account's YouTube playlists (incl. Liked Videos) as local YouTube-synced `Playlist`s and keeps them in sync on re-run. |

## `data/youtube/` — search, stream extraction, downloading

| File | Purpose | Key functions |
|---|---|---|
| `YouTubeExtractor.kt` | Wraps NewPipeExtractor (+ Piped/Invidious fallbacks) to search YouTube and resolve a video ID to a playable direct audio stream URL. Largest file in the data layer. | `extractVideoId()`, `parsePipedSearchItems()`, `parseInvidiousSearchItems()`, `verifyStreamUrl()`, plus the search/stream-extraction entry points |
| `YouTubeSearchResult.kt` | Data class for one search hit (videoId/title/uploader/duration/thumbnail). | |
| `YouTubeAudioStream.kt` | Data class for a resolved direct audio stream (url/format/bitrate). | |
| `CloudDownloadManager.kt` | Orchestrates downloading a YouTube track (or a whole playlist) to local storage: progress state, pause/resume/cancel, resuming pending downloads on launch. | `downloadTrack()`, `downloadPlaylist()`, `pauseDownload()`, `resumeDownload()`, `cancelDownload()`, `resumePendingDownloads()` |

## `playback/` — Media3/ExoPlayer session layer

| File | Purpose | Key functions |
|---|---|---|
| `PlaybackService.kt` | `MediaLibraryService` hosting the actual `ExoPlayer` + `MediaSession`. This is the real playback engine; survives independent of any UI screen being open. | |
| `MediaControllerManager.kt` | UI-side client that talks to `PlaybackService` through a `MediaController`. Every ViewModel that plays music goes through this. | `playSong()`, `playQueue()`, `togglePlayPause()`, `seekTo()`, `skipToNext()/Previous()`, `skipToIndex()`, `moveQueueItem()`, `toggleShuffle()`, `toggleRepeat()` |
| `AudioEffectsManager.kt` | Wraps the platform `Equalizer`/`BassBoost` effects attached to the ExoPlayer's audio session id. | `attach()`, `setBandLevel()`, `applyBandLevels()`, `usePreset()`, `setBassBoostStrength()` |
| `SleepTimerManager.kt` | Simple countdown timer that invokes a callback (pause playback) when it expires. | `startTimer()`, `cancelTimer()` |
| `SongMediaExtras.kt` | Packs/unpacks extra `Song` fields (id, artwork, duration, youtubeId, download flag) into a `MediaItem`'s metadata `Bundle`, so a `Song` can be reconstructed from a raw `MediaItem` (needed when the service appends an autoplay track directly to the ExoPlayer queue). | `fromSong()`, `toSong()`, `songId()`, `youtubeId()` |

## `ui/` — see **UI_GUIDE.md** for the full breakdown of screens/components/viewmodels/theme/navigation.
