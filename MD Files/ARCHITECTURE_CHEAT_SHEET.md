# TGMusicAI - Developer Architecture Cheat Sheet

This document serves as an exhaustive, human-readable reference guide to the architecture, core classes, subsystem workflows, data pipelines, and testing procedures for the **TGMusicAI** codebase.

---

## 1. Executive Architectural Summary of Subsystems

### Subsystem 1: Room DB v9 (Local Persistence Layer)
* **Location:** `app/src/main/java/com/example/tgmusicai/data/local/`
* **Architecture & Schema:**
  * Uses Google Room persistence library configured at Database **Version 9**, upgraded via real versioned migrations (`MIGRATION_6_7`, `MIGRATION_7_8`, `MIGRATION_8_9` — additive `ALTER TABLE`/`CREATE TABLE IF NOT EXISTS` only), never `fallbackToDestructiveMigration()` for a real schema bump, since that would silently wipe every user's library on upgrade.
  * **Entities:**
    * `Song`: Stores track details (`id`, `title`, `artist`, `album`, `durationMs`, `mediaUri`, `producer`, `lyrics`, `artworkUri`, `youtubeId`, `isDownloaded`, `isPinned`).
    * `Playlist`: Custom or auto-generated playlists (`playlistId`, `name`, `description`, `createdAt`, `isPinned`, `isSmart`, `youtubePlaylistId`, `lastSyncedAt`).
      * **`description` Field:** Supports detailed playlist descriptions displayed in playlist headers and detail views.
      * **Immutable Smart Playlists:** Six auto-generated, protected `isSmart = true` rows — **Liked Music**, **Top 50 Most Played**, **Recently Added**, **Unplayed**, **Downloads** (all `isDownloaded=true` songs), and **Cloud Nine** (all not-yet-downloaded songs). `MusicRepository.isProtectedSmartPlaylist(playlist)` is the single shared check used everywhere (repository delete guard, `PlaylistCard`'s delete-button visibility, `PlaylistDetailScreen`'s read-only-computed-playlist check) — deleting any of them through `MusicRepository.deletePlaylist()` is a no-op. Their song lists are resolved by `MusicRepository.playlistWithSongsFlow(playlist)` (one shared function backing every screen that needs "the songs in this playlist," including the Stats screen's storage breakdown), not by real `playlist_song_cross_ref` rows.
    * `PlaylistSongCrossRef`: Many-to-many relationship mapping songs to playlists (`playlistId`, `songId`, `position`).
    * `SongStats`: Playback statistics (`songId`, `playCount`, `lastPlayedAt`).
    * `Alarm`: User-configured musical alarms (`id`, `timeInMillis`, `isEnabled`, `repeatDays`, `toneType`, `toneUriOrId`, `snoozeMinutes`, `label`).
    * `PendingDownload` (v8): Tracks an in-flight cloud download (`videoId`, `title`, `uploader`, `durationSeconds`, `queuedAt`) so it can be resumed if the app process is killed mid-download; row is deleted once the download reaches a terminal state (completed or failed).
    * `AiSongTags` (v9): Standalone table (`songId`, `tags`, `lyricsEmbedding`, `computedAt`) owned entirely by the `ai/` containment layer (Subsystem 12) -- deliberately never joined into core queries, so a bug in on-device AI code can only ever affect this one disposable table. Missing rows just mean "not analyzed yet."
  * **DAOs:** `SongDao`, `PlaylistDao`, `SongStatsDao`, `AlarmDao`, `PendingDownloadDao` exposing reactive Kotlin `Flow<T>` queries for real-time UI state updates and synchronous methods for background tasks.
  * **Threading:** All DB operations execute asynchronously on `Dispatchers.IO`.

### Subsystem 2: Media3 ExoPlayer & MediaLibraryService
* **Location:** `app/src/main/java/com/example/tgmusicai/playback/`
* **Architecture & Workflows:**
  * **`PlaybackService`:** Extends Media3 `MediaLibraryService` to run playback in a background service, handle audio focus, and expose an Android Auto browsable media tree (`ROOT_ID` -> `CATEGORY_SONGS`, `CATEGORY_PLAYLISTS`, `CATEGORY_ARTISTS`).
  * **`MediaControllerManager`:** Application-scoped controller manager that asynchronously binds to `PlaybackService` via `MediaController`. Exposes reactive `StateFlow`s for `currentSong`, `isPlaying`, `currentPositionMs`, `durationMs`, `playlist`, `shuffleMode`, `repeatMode`, and `isResolving`.
  * **Progressive queue resolution:** `playSong`/`playQueue` resolve only the starting song before handing off to ExoPlayer (near-instant), then resolve the rest of the queue concurrently in the background and patch each result into the live queue via `Player.replaceMediaItem` as it finishes — avoids blocking playback start on every cloud track in a large queue. A fast path (`queueMatchesCurrent`) skips re-marshaling the whole `MediaItem` list entirely when tapping a different track within the same already-loaded queue, just seeking instead.
  * **Queue reordering:** `moveQueueItem(from, to)` calls `Player.moveMediaItem` directly; the resulting timeline change is picked up automatically via the `onTimelineChanged` listener (`refreshQueueFromPlayer`), which also keeps the exposed `playlist` in sync with autoplay queue extensions made service-side (see `PlaybackService` below) — this is what backs the drag-to-reorder handles in `QueueSheet`.
  * **Position Ticker:** Runs a 500 ms coroutine loop while playing to update playback position and record real-listen-based play counts (30s or half-duration threshold).
  * **`setVolume(volume)` / `getAudioSessionId()`:** Thin passthroughs to the underlying `MediaController` (`controller?.volume`, `controller?.audioSessionId`). `setVolume` backs the sleep timer's fade-out; `getAudioSessionId` is what `EqualizerViewModel` attaches the system `Equalizer`/`BassBoost` effects to (see Subsystem 11).
  * **`setPlaybackSpeed(speed)` / `playbackSpeed: StateFlow<Float>`:** Wraps `controller?.setPlaybackParameters(PlaybackParameters(speed, 1f))`, clamped to 0.5x-2.0x. Pitch is pinned at `1f` so speeding up/down never also changes pitch. Exposed in `NowPlayingScreen`'s top bar as a speed-preset dropdown (0.75x/1.0x/1.25x/1.5x/2.0x).

### Subsystem 3: Alarm Subsystem (AlarmManager + Dedicated Playback Service)
* **Location:** `app/src/main/java/com/example/tgmusicai/alarm/`
* **Architecture & Workflows:**
  * **`AlarmScheduler`:** Schedules exact alarms using `AlarmManager.setAlarmClock` (or `setExactAndAllowWhileIdle`) with pending intents targeting `AlarmReceiver`.
  * **`AlarmReceiver`:** System `BroadcastReceiver` that intercepts alarm intents and starts `AlarmPlaybackService` directly (`AlarmPlaybackService.start(context, alarmId)`) -- it does **not** build or post a notification itself, and does not launch `AlarmActivity`.
  * **`AlarmPlaybackService`:** Foreground `Service` (added to fix a real "alarm sent a notification but made no sound" bug) that owns all alarm audio. `onStartCommand` calls `startForeground()` immediately with the full-screen-intent notification (Android 14's 10-second rule), then asynchronously resolves the configured tone (song/playlist/random-liked, same resolution logic as before) or falls back to the system alarm ringtone, and plays it looping via its own `ExoPlayer` (`C.USAGE_ALARM`, `handleAudioFocus=false`).
    * **Why this exists:** a full-screen-intent notification only auto-launches its Activity when the device is locked or the screen is off. With the screen already on and unlocked, Android instead shows a plain heads-up notification and waits for a tap. Audio used to live entirely inside `AlarmActivity.onCreate()`, so in the screen-on case the alarm fired, a notification appeared, and nothing audible ever happened unless the user happened to tap it open. Running playback from a service started directly by the receiver means the alarm is audible the instant it fires, regardless of screen state. Verified fixed on a real device (screen on, unlocked, foreground): sound now plays.
    * **Audible-volume floor / force-max / ramp-up:** Before playing, forces `STREAM_ALARM` either to a ~60% floor (default) or straight to max (`AppPreferences.alarmForceMaxVolumeFlow`, a user-facing "Force max alarm volume" toggle akin to Android Clock's alarm-volume setting) if it's currently lower -- `USAGE_ALARM` bypasses silent/DND but the alarm stream has its own independent volume slider that's easy to leave near zero without noticing. If `AppPreferences.alarmVolumeRampUpFlow` is enabled ("Gradually increase volume"), playback starts at `RAMP_START_VOLUME` (0.08f) and linearly ramps to full over `RAMP_DURATION_MS` (60s) instead of hitting full volume immediately. Both settings are edited from a gear icon on `AlarmsScreen`'s top bar.
    * **`toneTitle: StateFlow<String>`** (companion object): resolved display title for whatever is currently ringing, collected by `AlarmActivity` for its UI. **`stopRinging()`** (companion object): stops playback/cancels the notification/stops the service; called from `AlarmActivity`'s Dismiss/Snooze handlers.
  * **`AlarmActivity`:** Full-screen lock-screen `ComponentActivity` (`setShowWhenLocked(true)`, `setTurnScreenOn(true)`) -- now a pure UI shell. Owns no `ExoPlayer` of its own; it displays `AlarmPlaybackService.toneTitle` and forwards Dismiss/Snooze taps to `AlarmPlaybackService.stopRinging()`. Whether or not Android auto-launches this Activity, the service is already playing.
  * **`BootReceiver`:** Reschedules all enabled alarms from database upon device restart (`ACTION_BOOT_COMPLETED`).

### Subsystem 4: YouTubeExtractor, Direct Audio Streaming & CloudDownloadManager
* **Location:** `app/src/main/java/com/example/tgmusicai/data/youtube/`
* **Rebuilt from scratch** (see `plan.md` history) after the original 6-tier implementation (Cobalt API, YT1s API, a raw web-embed HTML scrape, and an unauthenticated raw InnerTube POST, alongside Piped/Invidious) turned out to be almost entirely non-functional — most of those tiers were third-party scraper sites never designed for this kind of API access, and the raw InnerTube call lacked the client key/PoToken YouTube requires. Only NewPipeExtractor and the Piped/Invidious fallbacks survived the rebuild.
* **Architecture & Workflows:**
  * **`YouTubeExtractor`:**
    * **Search Pipeline:** Tries **NewPipeExtractor** first (`ServiceList.YouTube.getSearchExtractor(query)`) — talks to YouTube directly and is confirmed reliable (19/19 real results in live device testing). Falls back to the Piped/Invidious REST API cluster if NewPipe returns nothing.
    * **Audio Stream Resolution does NOT use NewPipeExtractor.** It was tried and removed after being confirmed non-functional: YouTube's WEB client player endpoint (which NewPipeExtractor's `StreamInfo.getInfo` uses) is currently behind an anti-bot wall requiring a PoToken NewPipeExtractor can't produce (`ContentNotAvailableException: The page needs to be reloaded`, confirmed on every video tried, across two library versions and two real, independent networks — a cloud dev sandbox and a physical Android device). Instead, stream extraction goes straight to: Tier 1 **Piped** (`PIPED_ENDPOINTS`, currently `pipedapi.wireway.ch` — verified end-to-end on a physical device: real search → real stream → real HTTP 206 audio bytes), Tier 2 **Invidious** (`INVIDIOUS_ENDPOINTS` plus a live-fetched list from `api.invidious.io/instances.json`).
    * **Self-healing instance directories, fetched lazily.** Both Piped (`piped-instances.kavin.rocks`) and Invidious (`api.invidious.io`) have live-fetched fallback instance lists, but they're only consulted **after** the hardcoded host(s) fail, using a short 4s-timeout `directoryLookupClient`, with the attempt (success or failure) cached once per process. This was originally eager (fetched on every single resolution) with the normal 15s request timeout, which meant one dead hardcoded host turned every affected song into a 15s stall — on a "Play All" of several such songs this looked exactly like the app being frozen. Always keep new directory-lookup calls on the lazy, short-timeout pattern.
    * Public Piped/Invidious instances rotate and die frequently — most candidates checked while building this were already dead — re-verify periodically (`curl -o /dev/null -w '%{http_code}' https://<host>/streams/<videoId>`).
  * **Direct Cloud Streaming:**
    * `YouTubeViewModel.playTrack` resolves stream URLs using `YouTubeExtractor` and passes them directly to ExoPlayer via `MediaControllerManager.playSong(streamSong)` without local storage downloads.
    * A cloud track added to a playlist via `YouTubeViewModel.addCloudTrackToPlaylist` (without downloading) is stored with an unresolved `https://www.youtube.com/watch?v=...` `mediaUri`. `MediaControllerManager.playSong`/`playQueue` detect this pattern and re-resolve it through `YouTubeExtractor` before building the ExoPlayer `MediaItem`, so playing such a track from the Library/Playlist screens (not just the Explore tab) works correctly.
  * **`CloudDownloadManager` (Background Audio Downloader):**
    * **Deduplication Check (`checkAndDeduplicate`):** Checks Room DB by `youtubeId` or `title` + `artist` metadata to verify if the track is already stored locally. If found, reuses existing local file URI! The same widened lookup (by local URI, then `youtubeId`, then title+artist) is used when indexing a completed download, so a song already present as an undownloaded cloud placeholder gets updated in place instead of duplicated.
    * **Persistent download queue:** Every enqueued download is written to `PendingDownload` (via `PendingDownloadDao`) and removed once it reaches a terminal state; `resumePendingDownloads()` (called from `YouTubeViewModel.init`) re-enqueues anything still pending on next launch, so a download interrupted by a process kill picks back up automatically. `downloadPlaylist(songs)` marks the entire batch `IDLE` ("Queued") immediately, rather than only the actively-downloading track existing in `downloadMap` — the rest of the batch used to look like it wasn't enqueued at all until its turn came up in the sequential download loop.
    * **Storage Directory:** Downloads audio streams directly to local storage (`Android/data/com.example.tgmusicai/files/Music/` or `files/Music/`).
    * **Progress & Post-Processing:** Emits progress state (`IDLE`, `EXTRACTING`, `DOWNLOADING`, `COMPLETED`, `FAILED`) via `downloadMap` StateFlow, cleans track title and producer credits via `AiMetadataCleaner`, scrapes 1000x1000 album artwork via `CoverArtScraper` (saving to `Android/data/com.example.tgmusicai/files/Covers/`), and indexes the track into Room DB and MediaStore.
    * **Bulk Playlist Downloader:** Enqueues downloads for all un-downloaded tracks in a cloud playlist.
    * **`DownloadProgressState.thumbnailUri`:** Set once at enqueue time (from the `YouTubeSearchResult`'s thumbnail, or the `Song.artworkUri` for a playlist bulk download) and carried forward automatically through every later `updateProgress` call in `CloudDownloadManager` (same forwarding pattern already used for `title`/`uploader`/`durationSeconds`), so callers don't need to repeat it on every state transition.
    * **`DownloadsScreen`** groups `downloadMap` entries into three ordered sections: Downloading (EXTRACTING/DOWNLOADING), Queued (IDLE), Downloads (COMPLETED/FAILED). Each row (`DownloadRow`, in a rounded `Card`) shows a 48dp `ArtworkThumbnail` (the track's thumbnail, or a music-note placeholder) with a small status badge overlapping its corner, replacing the old plain-list-with-status-icon-only layout.

### Subsystem 5: AI Metadata Cleaner & MediaScanner
* **Location:** `app/src/main/java/com/example/tgmusicai/data/local/`
* **Architecture & Workflows:**
  * **`AiMetadataCleaner`:**
    * **Offline Pattern Engine:** Extremely fast (<1 ms), zero-memory regex engine stripping bracketed noise tags (`[Official Audio]`, `(Video)`), extracting producer credits (`prod.`, `produced by`), and featured artists (`feat.`, `ft.`).
    * **Optional Online AI Engine:** Supports Gemini (`AIza...`) or OpenAI (`sk-...`) API keys stored in `AppPreferences`. Executes transient HTTP POST on `Dispatchers.IO` and disconnects immediately to guarantee zero idle memory.
  * **`MediaScanner`:** Scans device audio via `MediaStore`, fallback duration detection via `MediaMetadataRetriever`, cleans raw titles through `AiMetadataCleaner`, and saves structured `Song` records in Room DB.

### Subsystem 6: LyricsRepository (Synced & Plain Lyrics)
* **Location:** `app/src/main/java/com/example/tgmusicai/data/repository/LyricsRepository.kt`
* **Architecture & Workflows:**
  * Queries **LrcLib API** (synced & plain), local embedded ID3 lyric tags (`MediaMetadataRetriever`), or YouTube/Piped captions (VTT/JSON).
  * Parses raw lyrics strings into structured `List<LyricLine>` (`timestampMs`, `text`) for real-time auto-scrolling synced lyrics display in `NowPlayingScreen`. Caches results in Room DB.

### Subsystem 7: CoverArtScraper (High-Resolution Album Art & Top-Song Cover System)
* **Location:** `app/src/main/java/com/example/tgmusicai/data/repository/CoverArtScraper.kt`
* **Architecture & Workflows:**
  * Queries iTunes Search API (replacing `100x100` thumbnails with `1000x1000` high-res URLs), MusicBrainz / Cover Art Archive, or YouTube thumbnails, in that fallback order.
  * **Match verification (`matchesSong`):** Each source's search results are scanned for the first entry whose (normalized) track title and artist actually match the `Song`, instead of blindly taking result `[0]`. This matters most for iTunes, whose Search API does loose keyword matching rather than artist+title pairing — an unverified first result is frequently a same-titled track by a different artist, a cover, or a remix, which was the cause of a "wrong cover art" bug report. iTunes and MusicBrainz require a verified match or move to the next source entirely (no art is better than wrong art); the YouTube-thumbnail tier (last resort, after both other sources failed) still falls back to the top search hit if nothing verifies, since YouTube's own search ranking for an "artist title" query is already reasonably relevant.
  * Downloads image files locally to `Android/data/com.example.tgmusicai/files/Covers/{songId}.jpg` and updates `artworkUri` in Room DB.
  * **Top-Song Cover Art Integration:** High-resolution cover artwork is automatically scraped and rendered across Top Tracks / Speed Dial carousels, playlist header cards, MiniPlayer, and expanded full-screen player views.

### Subsystem 8: BackupManager (.tgmusic Portable Package)
* **Location:** `app/src/main/java/com/example/tgmusicai/data/local/BackupManager.kt`
* **Architecture & Workflows:**
  * **Export:** Serializes all database records (`Song`, `Playlist`, `PlaylistSongCrossRef`, `SongStats`, `Alarm`) into `manifest.json` and zips it alongside downloaded local audio files into a `.tgmusic` zip file stored in `Downloads/`.
  * **Import:** Unzips `.tgmusic` package, validates against Zip Slip path traversal vulnerabilities, extracts audio files back into local storage, and restores DB tables without internet connection or re-downloading!

### Subsystem 9: NetworkObserver (Real-time Online/Offline State)
* **Location:** `app/src/main/java/com/example/tgmusicai/data/network/NetworkObserver.kt`
* **Architecture & Workflows:**
  * Registers a `ConnectivityManager.NetworkCallback` listening for `NET_CAPABILITY_INTERNET`.
  * Exposes `isOnline: StateFlow<Boolean>` used across the UI to display "Offline Mode — Internet Unavailable" banners and switch view modes.

### Subsystem 10: Compose UI, Soft Themes, Liked Music & 3-State Loop Mode
* **Location:** `app/src/main/java/com/example/tgmusicai/ui/`
* **Architecture & Workflows:**
  * **Soft Theme Engine:** Multi-theme system persisted in `AppPreferences.SELECTED_THEME` (`selectedThemeFlow`).
    * Options include:
      * **YT Dark (Default):** `#030303` background, `#212121` card surface, `#FF0000` YouTube Red accent.
      * **Deep Crimson:** Rich crimson and dark obsidian accents.
      * **Midnight Neon:** Vibrant neon blue and deep slate palette.
      * **Emerald Dusk:** Deep emerald green and dark forest tones.
      * **Soft Obsidian:** Elegant charcoal and soft monochrome accents.
  * **Liked Music System Playlist:** Integrated heart toggles in track lists and player screens connected to `MusicRepository.toggleLikeSong()`. Automatically populates the immutable "Liked Music" system playlist with custom descriptions.
  * **Playlist Descriptions:** Supports setting and editing descriptions (`Playlist.description`) via creation/edit dialogs and renders description text in `PlaylistDetailScreen`.
  * **Bulk like/unlike & in-place deletion (`PlaylistDetailScreen`):** A "more options" overflow menu offers "Like all songs" / "Remove liked tag from all" for the whole playlist (`PlaylistViewModel.likeAllSongs`/`unlikeAllSongs`, backed by `MusicRepository.likeSongs`/`unlikeSongs`). Long-pressing a song enters a multi-select mode (mirrors `LibraryViewModel`'s selection pattern) whose top bar exposes the same like/unlike actions scoped to just the selected songs. For read-only computed smart playlists (`isReadOnlyComputedPlaylist`, e.g. "Unplayed") that have no real cross-ref to remove, the per-row trailing icon is a full library delete (`PlaylistViewModel.deleteSongCompletely` → `MusicRepository.deleteSongsCompletely`) instead of nothing, so a song can be removed without hunting it down in Library.
  * **Layout:** Left sidebar navigation drawer with `Home`, `Explore`, `Library`, pill-shaped `+ New playlist` button, a "❤️ Liked Music" shortcut, and the real user playlist list under a "Your Playlists" header. (Previously also listed "Speed Dial" / "Top 50 Most Played" / "Recently Added" shortcuts under a `📌` "Pinned & Playlists" header, but all three just navigated to the Home tab with no actual filtering/scrolling behind them — pure dead weight duplicating the Home nav item already in the same drawer. Removed rather than faked.)
  * **Speed Dial Home Tab:** Category filter chips, Downloaded Only mode toggle, square rounded card carousels with translucent play button overlays, and high-res top-song cover art.
  * **YouTube Music Bottom Bar (`MiniPlayer`):** Left controls (Play/Pause, Skip Next, progress code `0:00 / 4:36`), Center metadata (Thumbnail, Title, Subtitle, Heart / Like toggle, 3-dots menu), Right controls (Volume slider toggle, **3-State Loop Button**, Shuffle toggle).
  * **3-State Loop Mode (`RepeatMode`):** Cycles exactly between State 0 (`Player.REPEAT_MODE_OFF`), State 1 (`Player.REPEAT_MODE_ALL`), and State 2 (`Player.REPEAT_MODE_ONE`). App launch default is **OFF**.

### Subsystem 11: Start Radio & 5-Band Equalizer / Bass Boost
* **Location:** `MusicRepository.buildRadioQueue`, `ui/viewmodel/EqualizerViewModel.kt`, `playback/AudioEffectsManager.kt`.
* **Architecture & Workflows:**
  * **Start Radio (`MusicRepository.buildRadioQueue(seedSong, limit=20)`):** Builds a queue related to a seed song -- same producer first, then same artist, then everything else -- each tier grouped into play-count bands (least-played first) and lightly shuffled within each band, so the radio leans toward under-played tracks without being perfectly deterministic. There's no `genre` field in the schema, so this is producer/artist overlap only (not the producer/artist/genre similarity originally envisioned in `RECOMMENDED_FEATURES_FOR_CLAUDE.md`). Triggered from a "Start Radio" item in `SongItem`'s overflow menu (wired in `LibraryScreen` and `PlaylistDetailScreen`) via `PlayerViewModel.startRadio(song)`, which plays the queue and shows a "Starting radio based on ..." toast (`PlayerViewModel.statusMessage`).
  * **`AudioEffectsManager`:** Thin wrapper around Android's system `Equalizer`/`BassBoost` audio effects (`android.media.audiofx`). Both operate on the platform mixer for a given audio session id, so they can be attached from the UI layer (via `MediaControllerManager.getAudioSessionId()` → `controller?.audioSessionId`) rather than needing to live inside `PlaybackService` itself. `attach(sessionId)` is idempotent -- re-attaching to the same session id it's already attached to is a no-op.
  * **`EqualizerViewModel`:** Owns one `AudioEffectsManager`. `ensureAttached()` (called when the equalizer dialog opens) attaches to the current session and re-applies persisted settings (`AppPreferences.equalizerEnabledFlow`/`equalizerBandLevelsFlow`/`equalizerPresetNameFlow`/`bassBoostStrengthFlow`); if no session exists yet (nothing has ever played), `isSessionAvailable` is `false` and the dialog shows a "play a song first" prompt instead of controls. `setBandLevel`/`applyPreset`/`setBassBoostStrength` all apply live to the effect *and* persist to `AppPreferences` in the same call.
  * **UI:** `NowPlayingScreen`'s top bar has a `GraphicEq` icon opening `EqualizerDialog` -- an enabled toggle, a preset dropdown (populated from the device's real `Equalizer.getPresetName` list, not hardcoded), one `Slider` per band (labeled with the device's real center frequency, since band count/frequencies vary by OEM rather than being a fixed 60Hz/230Hz/910Hz/3.6kHz/14kHz set), and a bass boost strength slider (0-1000 per the platform's `BassBoost.setStrength` range).

### Subsystem 12: On-Device AI Containment Layer (Audio Tagging & Lyrics Embeddings)
* **Location:** `app/src/main/java/com/example/tgmusicai/ai/`, models in `app/src/main/assets/ai/`.
* **Design principle:** this whole package exists so on-device ML can go wrong -- a missing/corrupt model file, an incompatible device, a bad decode, an OOM on a low-end phone -- without ever bricking the rest of the app. Every engine lazily self-initializes behind a try-catch that permanently (for the process) marks itself unavailable on failure instead of retrying or throwing; every public function returns [`AiModelResult`](#aimodelresultkt) instead of propagating exceptions; the facade ([`AiFeatureManager`](#aifeaturemanagerkt)) wraps everything a second time (defense in depth) on its own `SupervisorJob`-backed scope; results are cached in a standalone Room table (`ai_song_tags`, Subsystem 1) that nothing else in the app reads; and every dependency in the chain (`LibraryViewModel.aiFeatureManager`, `SongItem.onAnalyzeWithAiClicked`) is nullable/optional, so the feature can be entirely absent with zero code-path changes elsewhere.
* **Models (both real, downloaded and verified, not placeholders):**
  * **`ai/yamnet.tflite`** (Google YAMNet, Apache 2.0, ~4.1MB) -- classifies audio into 521 AudioSet classes. `ai/yamnet_class_map.csv` provides the label names; class indices **132-276** are the curated "music-relevant" range (genres, instruments, and even mood labels like "Happy music"/"Sad music") used to filter tags.
  * **`ai/minilm_quantized.onnx`** (`Xenova/all-MiniLM-L6-v2`, int8-quantized, Apache 2.0, ~23MB) -- turns text into a 384-dim sentence embedding. `ai/vocab.txt` (30522-entry BERT WordPiece vocab) backs `WordPieceTokenizer`. Verified input/output tensor names (`input_ids`/`attention_mask`/`token_type_ids` in, `last_hidden_state` out) by grepping the raw model bytes, not just assumed from convention.
  * `androidResources { noCompress += listOf("tflite", "onnx") }` (`app/build.gradle.kts`) keeps both files uncompressed in the APK so they can be mmap'd/loaded directly.
* **`SongTaggingEngine`:** Wraps a TFLite `Interpreter` over YAMNet. `PcmDecoder` (MediaExtractor + MediaCodec, synchronous API) decodes up to 15s of a local audio file into 16kHz mono float PCM (downmixed + linearly resampled) purely for tagging -- never touches the real playback pipeline. Uses TFLite's dynamic-input-shape pattern: `resizeInput` → `allocateTensors()` → read the now-concrete output shape → `run()`; mean-pools per-frame class scores, filters to the 132-276 music range above a score threshold, returns up to 8 tag names.
* **`WordPieceTokenizer` / `LyricsEmbeddingEngine`:** Standard BERT-style lowercase + punctuation-split + greedy-longest-match subword tokenization, then an ONNX Runtime session run, mean-pooled (attention-mask-weighted) and L2-normalized into a 384-dim embedding. `LyricsEmbeddingEngine.cosineSimilarity(a, b)` is just a dot product since both inputs are already normalized.
* **`AiFeatureManager`:** The only entry point other code should use. `analyzeSong(song)` tags the song's local audio file (skipped for remote/content URIs -- only `file:`/absolute-path URIs are supported, mirroring `MusicRepository.deleteSongsCompletely`'s existing local-path resolution) and embeds its lyrics if present, caching whatever succeeded into `ai_song_tags`. `rankBySimilarLyrics(seedSongId, candidateIds)` ranks purely from already-cached embeddings (never triggers new analysis) for feeding lyrical similarity into grouping/radio features later.
* **Wiring (deliberately minimal for this pass):** `MainActivity` constructs one `AiFeatureManager` (cheap -- engines are `by lazy`, nothing loads until first real call) and passes it into `LibraryViewModel` as a nullable dependency. `SongItem`'s overflow menu gets an "Analyze (AI)" item (only rendered if a callback is supplied) wired in `LibraryScreen` to `LibraryViewModel.analyzeSongWithAi(song)`, which shows the resulting tags (or "No AI tags found") via the existing `statusMessage` Toast pattern. No other screen, ViewModel, or the core `Song`/playback pipeline was touched.
* **Status:** builds and assembles clean, including native libs (`libtensorflowlite_jni.so`, `libonnxruntime.so`) correctly packaged for all ABIs; not yet run on a real device (built during a period without device access). The real end-user APK size impact is much smaller than a debug build's universal-ABI size suggests -- distribution via Android App Bundle serves only the one native `.so` set a device actually needs.

---

## 2. Core Class & Function Reference

### `com.example.tgmusicai.MainActivity`
* **What it does:** Main entry point activity for TGMusicAI. Sets up edge-to-edge UI, initializes preferences, database, repositories, network observer, and controller manager, and renders the Jetpack Compose screen tree.
* **Interacts with:** `AppPreferences`, `AppDatabase`, `MusicRepository`, `LyricsRepository`, `CoverArtScraper`, `MediaControllerManager`, `NetworkObserver`, `MainScreen`, `MediaScanner`.
* **How it works internally:**
  * Calls `enableEdgeToEdge()` in `onCreate`.
  * Instantiates DB, repositories, and ViewModels via custom ViewModel factories.
  * Checks DataStore `onboardingCompletedFlow` to route between `OnboardingScreen` and `MainScreen`.
  * Launches `MediaScanner.scanMediaStore` in a `LaunchedEffect` coroutine block upon opening main UI.
  * Releases resources in `onDestroy()`.

---

### `com.example.tgmusicai.playback.PlaybackService`
* **What it does:** Background service extending Media3 `MediaLibraryService` for audio playback and Android Auto support.
* **Interacts with:** ExoPlayer, `AppDatabase`, Android Auto head units / MediaControllers.
* **How it works internally:**
  * Builds ExoPlayer with `C.AUDIO_CONTENT_TYPE_MUSIC` and automatic audio focus handling.
  * Implements `MediaLibrarySession.Callback`:
    * `onGetLibraryRoot`: Returns root `MediaItem` (`ROOT_ID`).
    * `onGetChildren`: Resolves browsable folders (`CATEGORY_SONGS`, `CATEGORY_PLAYLISTS`, `CATEGORY_ARTISTS`) and builds media item trees asynchronously via `serviceScope.future`.
    * `onGetItem` & `onAddMediaItems`: Resolves media URIs to database records.
  * **Autoplay queue extension (`maybeExtendQueueForAutoplay`):** Triggered from `onMediaItemTransition` the moment playback reaches the current *last* queue item (repeat off) — appends a few random downloaded songs directly onto the live ExoPlayer queue via `addMediaItems`, before the current song even ends. This makes the added songs real, visible, skippable "Up Next" entries (since `MediaControllerManager` syncs from the real timeline) instead of a single song reactively swapped in after playback stops, which is what `playRandomSongAsAutoplay`/`STATE_ENDED` still does as a last-resort safety net if no downloaded songs existed at extension time.
  * **Notification tap-to-open:** `setSessionActivity` launches `MainActivity` with `ACTION_OPEN_NOW_PLAYING`; `MainActivity.onNewIntent` (Activity is `singleTop`) bumps a counter that a `LaunchedEffect` uses to call `PlayerViewModel.expandNowPlaying()`.

---

### `com.example.tgmusicai.playback.MediaControllerManager`
* **What it does:** Application-scoped client binding to `PlaybackService` via `MediaController`.
* **Interacts with:** `PlaybackService`, `MusicRepository`, UI ViewModels (`PlayerViewModel`).
* **Key Functions:**
  * `playSong(song, queue)`: Plays a single track, sets ExoPlayer queue items, prepares and starts playback.
  * `playQueue(queue, startIndex)`: Sets list of tracks into ExoPlayer and seeks to `startIndex`.
  * `togglePlayPause()`: Toggles play/pause state or restarts if ended.
  * `seekTo(positionMs)`: Seeks ExoPlayer to target time code.
  * `skipToNext()` / `skipToPrevious()`: Queue navigation.
  * `toggleShuffle()`: Toggles ExoPlayer shuffle mode on/off.
  * `toggleRepeat()`: Calls `getNextRepeatMode` to cycle between `REPEAT_MODE_OFF`, `REPEAT_MODE_ALL`, and `REPEAT_MODE_ONE`.
  * `getNextRepeatMode(currentMode)`: Companion object function computing exact 3-state cycle logic.
* **How it works internally:**
  * Connects to service via `SessionToken`.
  * Listens to ExoPlayer events (`onIsPlayingChanged`, `onMediaItemTransition`, `onRepeatModeChanged`, etc.) and emits updates through reactive `StateFlow`s.
  * Runs a position ticker coroutine every 500 ms when playing.

---

### `com.example.tgmusicai.playback.SleepTimerManager`
* **What it does:** Countdown timer for auto-pausing music playback after user-specified duration.
* **Interacts with:** `PlayerViewModel`, `MediaControllerManager`.
* **Key Functions:**
  * `startTimer(minutes)`: Cancels active timer, sets duration, and launches a 1-second interval coroutine decrementing `remainingMs`. Calls `onTimerExpired` (which invokes `pause()`) when time hits zero.
  * `cancelTimer()`: Stops active timer coroutine and resets `remainingMs` to null.
  * **Fade-out (in `PlayerViewModel`, not this class):** a `viewModelScope` collector on `remainingSleepTimeMs` linearly ramps `MediaControllerManager.setVolume()` down to 0 once `remaining <= SLEEP_FADE_WINDOW_MS` (30s), and resets it back to `1f` as soon as `remainingMs` goes `null` (timer expired or cancelled) -- so playback fades out gently instead of cutting off at full volume, and the next playback session isn't left silently quiet.

---

### `com.example.tgmusicai.data.local.AiMetadataCleaner`
* **What it does:** Lightweight metadata cleaning engine that cleans noisy titles and extracts producer/featured artist credits.
* **Interacts with:** `MediaScanner`, `CloudDownloadManager`, `AppPreferences`.
* **Key Functions:**
  * `cleanOffline(rawTitle, rawArtist)`: Fast (<1 ms) regex parser using `TAG_REGEX`, `PRODUCER_BRACKET_REGEX`, `PRODUCER_INLINE_REGEX`, `FEAT_BRACKET_REGEX`, `FEAT_INLINE_REGEX`, and `Artist - Title` splitting. Returns structured `CleanedMetadata`.
  * `clean(rawTitle, rawArtist, apiKey)`: Executes `cleanOffline`, and if an API key is present, performs an online Gemini/OpenAI HTTP POST request on `Dispatchers.IO`. Disconnects connection immediately after parsing.

---

### `com.example.tgmusicai.data.local.MediaScanner`
* **What it does:** Scans device media files via `MediaStore` and indexes clean tracks into Room database.
* **Interacts with:** `MediaStore`, `SongDao`, `AiMetadataCleaner`, `AppPreferences`.
* **Key Functions:**
  * `scanMediaStore(context, songDao)`: Queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`, resolves duration (fallback to `MediaMetadataRetriever`), passes titles through `AiMetadataCleaner`, and inserts non-duplicate `Song` records.

---

### `com.example.tgmusicai.data.local.BackupManager`
* **What it does:** Handles portable `.tgmusic` zip backup export and import.
* **Interacts with:** `SongDao`, `PlaylistDao`, `SongStatsDao`, `AlarmDao`, local file storage.
* **Key Functions:**
  * `exportBackup(context)`: Queries DB tables, builds `manifest.json`, copies local audio files (`audio/{fileName}`), and compresses everything into `TGMusic_Backup_{timestamp}.tgmusic` in `Downloads/`.
  * `importBackup(context, inputStream)`: Reads zip stream, checks against Zip Slip vulnerabilities, extracts audio files to `Android/data/com.example.tgmusicai/files/Music/`, parses `manifest.json`, and restores `Song`, `Playlist`, `PlaylistSongCrossRef`, `SongStats`, and `Alarm` entities into Room.

---

### `com.example.tgmusicai.data.repository.LyricsRepository`
* **What it does:** Multi-source lyrics search, fetching, parsing, and caching engine.
* **Interacts with:** LrcLib API, embedded ID3 tags (`MediaMetadataRetriever`), YouTube/Piped captions API, `MusicRepository`, `SongDao`.
* **Key Functions:**
  * `parseLyrics(rawLyrics)`: Static helper parsing raw LRC strings (`[mm:ss.xx] line`) into structured `LyricLine(timestampMs, text)` sorted chronologically.
  * `fetchAndSaveLyrics(song, forceFetch)`: Queries LrcLib API -> embedded ID3 tags -> YouTube captions sequentially. Caches non-blank results in Room DB.

---

### `com.example.tgmusicai.data.repository.CoverArtScraper`
* **What it does:** High-resolution album cover art scraper and local image cacher.
* **Interacts with:** iTunes Search API, MusicBrainz / Cover Art Archive API, YouTube thumbnails, `MusicRepository`.
* **Key Functions:**
  * `scrapeAndSaveArtwork(song)`: Queries iTunes Search API (replacing `100x100` with `1000x1000`), MusicBrainz, or YouTube thumbnails. Downloads image to `Android/data/com.example.tgmusicai/files/Covers/{songId}.jpg` and updates Room `artworkUri`.
  * `getHighResItunesUrl(rawUrl)`: Utility string replacement function converting iTunes thumbnail URLs to 1000x1000 resolution.

---

### `com.example.tgmusicai.data.repository.MusicRepository`
* **What it does:** Single source of truth repository managing songs, playlists, smart playlists, Liked Music, playback stats, alarms, storage cleanup, and backups.
* **Interacts with:** `SongDao`, `PlaylistDao`, `SongStatsDao`, `AlarmDao`, `BackupManager`.
* **Key Functions:**
  * `getRecentlyPlayedSongs(limit)` / `getMostPlayedSongsWithStats(limit)`: Combines `SongStatsDao` flows with `SongDao.getAllSongs()` to produce `SongWithStats` lists.
  * `getOrCreateLikedMusicPlaylistId()` / `toggleLikeSong()` / `isSongLiked()`: Manages Liked Music system playlist entries and heart states.
  * `deletePlaylist(playlist)`: Deletes playlist entity unless it is the protected "Liked Music" system playlist.
  * `removeDownloadKeepInPlaylist(songId)`: Deletes local audio file from disk, updates `isDownloaded = false`, resets media URI to stream URL, and preserves song metadata and playlist associations.
  * `ensureSmartPlaylistsExist()`: Ensures auto-generated smart playlists ("Liked Music", "Top 50 Most Played", "Recently Added", "Unplayed") exist in DB.

---

### `com.example.tgmusicai.data.youtube.YouTubeExtractor`
* **What it does:** Extracts YouTube search results and direct playable M4A/WebM audio stream URLs with multi-stage fallback pipelines.
* **Interacts with:** `NewPipeExtractor`, `NewPipeOkHttpDownloader`, Piped REST APIs, Invidious REST APIs.
* **Key Functions:**
  * `search(query)`: Uses NewPipe `SearchExtractor` for `ServiceList.YouTube`. On failure or throttling, automatically falls back to Piped (`pipedapi.kavin.rocks`, `api.piped.yt`, `pipedapi.tokhmi.xyz`) and Invidious (`inv.tux.pizza`, `invidious.nerdvpn.de`, `vid.puffyan.us`) search APIs. Returns `List<YouTubeSearchResult>`.
  * `extractAudioStream(videoId)`: Extracts direct audio streams via NewPipe `StreamInfo`, selecting highest bitrate M4A/WebM stream. On failure, falls back to Piped `/streams/{videoId}` and Invidious `/api/v1/videos/{videoId}` APIs. Returns `YouTubeAudioStream`.

---

### `com.example.tgmusicai.data.youtube.CloudDownloadManager`
* **What it does:** Manages background YouTube cloud downloads, local storage writing, progress tracking, metadata cleaning, cover art scraping, Room DB indexing, and deduplication.
* **Interacts with:** `YouTubeExtractor`, `SongDao`, `AiMetadataCleaner`, `CoverArtScraper`, `MediaScanner`.
* **Key Functions:**
  * `downloadTrack(videoId, title, uploader, durationSeconds)`: Enqueues single track background download on `Dispatchers.IO`.
  * `downloadPlaylist(songs)`: Iterates through undownloaded tracks in a playlist and enqueues downloads.
  * `checkAndDeduplicate(videoId, title, uploader)`: Checks Room DB for existing downloaded tracks by `youtubeId` or `title` + `artist`. If found, reuses existing local file URI and skips re-downloading!
  * `executeDownload(...)`: Executes stream download to `Android/data/com.example.tgmusicai/files/Music/`, cleans title/artist/producer with `AiMetadataCleaner`, scrapes high-res album artwork with `CoverArtScraper`, and indexes the track into Room DB. Emits real-time progress via `downloadMap: StateFlow<Map<String, DownloadProgressState>>`.

---

### `com.example.tgmusicai.alarm.AlarmScheduler`
* **What it does:** Schedules, cancels, and snoozes exact system alarms via `AlarmManager`.
* **Interacts with:** `AlarmManager`, `AlarmReceiver`, `AlarmActivity`, `Alarm` entity.
* **Key Functions:**
  * `scheduleAlarm(context, alarm)`: Uses `AlarmManager.setAlarmClock` (API 21+) or `setExactAndAllowWhileIdle` to schedule an exact alarm firing `AlarmReceiver`.
  * `cancelAlarm(context, alarm)`: Cancels pending intent in `AlarmManager`.
  * `scheduleSnooze(context, alarm)`: Schedules a temporary alarm offset by `alarm.snoozeMinutes`.
  * `calculateNextTriggerTime(alarm)`: Computes future trigger epoch timestamp based on target time and day-of-week repeat configuration.

---

### `com.example.tgmusicai.alarm.AlarmReceiver` & `BootReceiver`
* **What it does:** System `BroadcastReceiver`s handling alarm triggers and device reboots.
* **Interacts with:** `AlarmScheduler`, `AlarmActivity`, `AppDatabase`.
* **How it works internally:**
  * `AlarmReceiver`: Receives alarm intent, queries `AlarmDao`, starts `AlarmActivity` with `FLAG_ACTIVITY_NEW_TASK`, and reschedules or disables alarm in DB.
  * `BootReceiver`: Listens for `ACTION_BOOT_COMPLETED` and reschedules all active alarms from DB using `AlarmScheduler.scheduleAlarm`.

---

### `com.example.tgmusicai.alarm.AlarmActivity`
* **What it does:** Full-screen lock-screen activity displayed when an alarm fires.
* **Interacts with:** ExoPlayer, `AppDatabase`, `AlarmScheduler`.
* **How it works internally:**
  * Configures flags `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, `FLAG_KEEP_SCREEN_ON`.
  * Prepares ExoPlayer with `C.USAGE_ALARM` audio attributes. Plays custom song, playlist tracks, or random liked track (or system ringtone fallback).
  * Presents pulse-animated time display with Snooze and Dismiss action buttons.

---

### `com.example.tgmusicai.data.network.NetworkObserver`
* **What it does:** Monitors internet connectivity and exposes real-time `isOnline: StateFlow<Boolean>`.
* **Interacts with:** `ConnectivityManager`, `HomeViewModel`.
* **How it works internally:** Registers `NetworkCallback` listening for `NET_CAPABILITY_INTERNET`. Updates `_isOnline` state on availability/loss changes.

---

### ViewModels (`HomeViewModel`, `PlayerViewModel`, `YouTubeViewModel`, `LibraryViewModel`, `PlaylistViewModel`, `AlarmViewModel`, `StatsViewModel`)
* **`HomeViewModel`:** Manages Speed Dial pinned items, recently played, top tracks, smart playlists (including top-song artwork for each, via `smartPlaylistTopArtwork`), Liked Music, theme selection, offline state, and backup export/import.
* **`PlayerViewModel`:** Bridges UI to `MediaControllerManager`. Manages `SleepTimerManager`, lyrics parsing, cover art scraping coroutine tasks, queue reordering (`moveQueueItem`), and deferred full-screen-player expansion (`expandNowPlaying` fires only once playback actually starts, via `onStarted` callbacks from `MediaControllerManager`, not synchronously on tap).
* **`YouTubeViewModel`:** Handles YouTube search input, stream playback, cloud track additions (deduplicated by `youtubeId`/title+artist before insert — matches the download and playlist-sync paths), and `CloudDownloadManager` progress updates; calls `resumePendingDownloads()` on init.
* **`LibraryViewModel`:** Manages all tracks, sorting, search filtering, and manual lyrics/artwork scraping.
* **`PlaylistViewModel`:** Manages playlist creation, descriptions, deletion, track addition/removal, and cross-reference queries. `playlistsWithSongs`/`selectedPlaylistWithSongs` both delegate to `MusicRepository.allPlaylistsWithSongs`/`playlistWithSongsFlow` rather than re-implementing the smart-playlist song-resolution branching locally.
* **`AlarmViewModel`:** Manages adding, editing, toggling, and deleting alarms, and triggers `AlarmScheduler`.
* **`StatsViewModel`:** Exposes play count analytics/recently played history, plus on-demand storage usage stats (`refreshStorageOverview`): total bytes, per-playlist size breakdown, and biggest-songs ranking, computed by stat()-ing each downloaded song's local file.

---

## 3. Explicit Step-by-Step Bug Testing Guide

Use this checklist during human QA or future automated/AI testing sessions to verify application health and catch regression bugs.

### Step 1: Automated Build & Unit Test Verification
* **Action:** Open terminal and run:
  ```bash
  ./gradlew testDebugUnitTest assembleDebug
  ```
* **Expected Result:**
  * 100% unit tests pass — 51/51 across 9 suites (`LoopModeTest`, `AiMetadataCleanerTest`, `MusicRepositoryTest`, `YouTubeExtractionTest`, `LyricsAndCoverArtTest`, `ConvenienceFeaturesTest`, `ThemesAndLikedMusicTest`, `AuditAndSeekingTest`, `ExampleUnitTest`).
  * Build finishes successfully without compilation or KSP errors.

---

### Step 2: Local Playback & 3-State Loop Button Testing
* **Action:**
  1. Open the app and grant media permissions during onboarding.
  2. Tap any local track from the Library or Speed Dial Home tab.
  3. Verify audio plays and `MiniPlayer` appears at the bottom.
  4. Tap the **Repeat Button** on the right side of `MiniPlayer`:
     * **1st Tap:** Sets repeat mode to `REPEAT_MODE_ALL` (Loop Playlist/Queue). Repeat icon turns accent color.
     * **2nd Tap:** Sets repeat mode to `REPEAT_MODE_ONE` (Loop Current Song). Repeat icon shows '1' badge.
     * **3rd Tap:** Returns repeat mode to `REPEAT_MODE_OFF`. Icon turns outline grey.
  5. Close and relaunch the app. Verify default repeat mode on launch is **OFF**.

---

### Step 3: Liked Music System Playlist & Heart Toggle Testing
* **Action:**
  1. Play a song or browse the Library.
  2. Tap the **Heart / Like** icon on a song item or in the `MiniPlayer` / `NowPlayingScreen`.
  3. Verify:
     * Heart icon toggles to filled / active state.
     * The track is immediately added to the "Liked Music" system playlist in database.
  4. Open the "Liked Music" playlist from the Library or Navigation Drawer.
  5. Verify the liked track appears in the playlist.
  6. Attempt to delete the "Liked Music" playlist. Verify deletion is blocked and the system playlist remains protected.

---

### Step 4: Soft Theme Selection Testing
* **Action:**
  1. Open Settings or Theme Selector on the Home Screen / Drawer.
  2. Select different themes: `YT Dark`, `Deep Crimson`, `Midnight Neon`, `Emerald Dusk`, `Soft Obsidian`.
  3. Verify background colors, card surfaces, accents, and icons dynamically re-theme across the app.
  4. Relaunch the app and verify the selected theme is restored from `AppPreferences`.

---

### Step 5: Storage Management ("Remove Download, Keep in Playlist") Testing
* **Action:**
  1. Go to **Speed Dial** or **Library** containing a downloaded track.
  2. Tap the 3-dots overflow menu on the track item.
  3. Select **"Remove Download, Keep in Playlist"**.
  4. Verify:
     * The local audio file in `Android/data/com.example.tgmusicai/files/Music/` is deleted.
     * `isDownloaded` becomes `false`.
     * The song remains present in the database, library, and custom playlists as a streamable cloud track.

---

### Step 6: Full-Screen Lock-Screen Musical Alarm Testing
* **Action:**
  1. Navigate to the **Alarms** tab.
  2. Tap `+` to add a new alarm set for 1 minute in the future.
  3. Select a custom tone (Song, Playlist, or Random Liked).
  4. Save the alarm and lock the device screen.
  5. Wait 1 minute.
  6. Verify:
     * Device screen turns on automatically over the lock screen.
     * Full-screen `AlarmActivity` appears with animated time code and song title.
     * Configured song plays through ExoPlayer alarm stream.
     * Tapping **Snooze** reschedules alarm for 10 minutes and stops audio.
     * Tapping **Dismiss** stops audio and finishes activity.

---

### Step 7: Synced Lyrics & High-Res Cover Art Scraping Testing
* **Action:**
  1. Play a song and tap `MiniPlayer` to expand `NowPlayingScreen`.
  2. Tap the **Auto-Awesome Sparkles** icon to scrape cover art & lyrics.
  3. Verify high-resolution artwork (1000x1000) downloads and renders on screen.
  4. Tap the **Lyrics Tab** button.
  5. Verify synced LRC lines render and auto-scroll in time with playback progress. Tap any lyric line to seek playback directly to that timestamp.

---

### Step 8: Portable Backup (.tgmusic Zip) Export & Restore Testing
* **Action:**
  1. On the Home screen top bar, tap the **Save / Export Backup** icon.
  2. Check `Downloads/` directory for `TGMusic_Backup_{timestamp}.tgmusic`.
  3. Delete a song or playlist in the app to alter DB state.
  4. Tap the **Restore Backup** icon, select the `.tgmusic` zip file.
  5. Verify all DB entities, playlists, stats, and local audio files are completely restored without internet access!

---

### Step 9: Smart Playlists, Storage Stats & Up Next Reordering
* **Action:**
  1. Open **Playlists** — confirm six protected smart playlists exist (Liked Music, Top 50 Most Played, Recently Added, Unplayed, Downloads, Cloud Nine), none show a delete button, and each shows real top-song cover art (not a generic icon) once the library has artwork.
  2. Open **Stats** → **Storage** tab. Verify total storage, a per-playlist size breakdown with usage bars, and a "Biggest Songs" ranked list all populate (may take a moment the first time — it stats every downloaded file on disk).
  3. Start playback of a queue of 3+ songs, tap the queue icon to open **Up Next**. Verify it auto-scrolls to and highlights the currently playing song.
  4. Drag a track's handle up/down in Up Next. Verify the queue actually reorders (not just visually) — check by letting playback continue and confirming the new order plays.

---

### Step 10: Alarm Song Picker
* **Action:**
  1. In **Alarms**, create/edit an alarm and select **Specific Song** as the tone source.
  2. Tap the song row — verify a full search dialog opens (title/artist/album search, cover art thumbnails), not a plain unsearchable dropdown.
  3. Search for a song, select it, and verify the alarm dialog's preview row updates with its artwork/title/artist.

---

### Step 11: On-Device AI Tagging, Grid View Parity, Lyrics Fetch, and Stats
* **Action:**
  1. In **Library**, switch to grid view, long-press a song to enter multi-select, select several songs, and open the bulk "more" menu in the top bar — verify Fetch Cover Art & Lyrics, Start Radio, and Analyze (AI) all work from grid view exactly as they do from list view.
  2. Open a single song's overflow menu (either view) and tap **Analyze (AI)** — verify a toast shows real tags (not "No AI tags found") for an actual music file.
  3. Re-scrape cover art for a song already visible in both the Library list and Now Playing/MiniPlayer — verify the new artwork appears in *every* location, not just the one you scraped from.
  4. Force-fetch lyrics for a downloaded song that has no LrcLib match — verify it either shows real transcribed lyrics or, for a song with no available YouTube captions, cleanly shows "no lyrics available" (not silently doing nothing).
  5. Open **Stats** → **Listening** tab — verify "Total Track Plays" reflects your whole library's play count (not just the top 10 songs), a "Top Artists" section with ranked progress bars appears, and the "Most Played Songs" list shows real album art per track.
  6. Play a YouTube search result directly (not downloaded) via the cloud search "play" action, let it play for a few seconds, then check Stats — verify its play was actually counted.

---

### Step 12: Full Stats Redesign, Background Play Tracking, Cloud Nine, Lyrics Correctness
* **Action:**
  1. Play a song for 30+ seconds, then either force-close the app from Recents or leave the screen off for a few minutes while it keeps playing in the background, then reopen the app and check **Stats** — verify the play was still counted (this is the actual Bug 1: tracking used to die with the Activity, not the service).
  2. Open **Stats** → **Listening** tab — verify the hero card shows total listening time in `Xh Ym` format (not just a play count), a 7-day bar chart appears once you have any listening history, a "Top Artists" donut chart renders, and a horizontally-scrolling "Top Producers" row appears if any library songs have producer credits.
  3. Check the top 3 "Most Played Songs" rows show gold/silver/bronze circular rank badges, distinct from the plain numeral used for #4+.
  4. Open **Stats** → **Storage** tab — verify the hero card's segmented bar reflects the real relative size of your top playlists (not a fixed decorative split).
  5. Open **Cloud Nine** (or any all-undownloaded playlist) and tap **Play All** repeatedly across a few sessions — verify it either plays smoothly or, if mirrors are genuinely down, stops cleanly after a couple of failures instead of rapid-skipping through the whole queue; check logcat for `TGMusicCloud` "Using cached stream" lines on a replay of the same song.
  6. Force-fetch lyrics for several different downloaded songs (mix of ones likely to have LrcLib matches and ones that don't) — verify none of them ever show the literal text "null" as their lyrics.
  7. For a song with real synced (LRC) lyrics, verify the highlighted "active" line during playback actually corresponds to where the song currently is (not several lines/seconds ahead), and that dragging to scroll through the lyrics list doesn't get yanked back to the current line until you stop scrolling.
  8. Stream a song directly from YouTube search (not downloaded), fetch its lyrics, then close and reopen the song later — verify the lyrics are still there (previously lost because the song was never persisted before saving).

---

### Step 13: Now Playing / Queue Redesign
* **Action:**
  1. Play any song, expand to the full Now Playing screen — verify the bottom MiniPlayer bar and bottom navigation tabs are completely gone (not just covered) while it's open, and reappear cleanly when you collapse it.
  2. On the action pill bar, tap Like (toggles), Dislike (skips to next), the AI sparkle (fetches cover art/lyrics), the chat-bubble Lyrics icon (toggles to the lyrics view and highlights), and "Save" (opens a real Add to Playlist dialog with your actual playlists, plus "Create New Playlist").
  3. Tap the overflow (⋮) menu — verify Track Info, Start Radio, Sleep Timer, Playback Speed, and Equalizer are all present and each opens its correct dialog/menu.
  4. Tap the queue icon in the top bar — verify the Queue sheet shows a "Playing from / Your Queue" header with a "Save" button that creates a new playlist from the whole queue, and that drag-to-reorder on the ≡ handles still works.
