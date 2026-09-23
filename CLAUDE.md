# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

TGMusicAI: native Android local music player + YouTube cloud streaming app. Kotlin, Jetpack Compose, Media3 ExoPlayer, Room, TensorFlow Lite, ONNX Runtime. Single Gradle module (`:app`), no multi-module split.

## Commands

Build/test via Gradle wrapper from repo root:

```bash
./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # build + install on connected device/emulator
./gradlew test                   # run all JVM unit tests (app/src/test)
./gradlew testDebugUnitTest --tests "com.example.tgmusicai.LoopModeTest"   # single test class
./gradlew testDebugUnitTest --tests "com.example.tgmusicai.LoopModeTest.someTestMethod"  # single test method
./gradlew connectedAndroidTest   # instrumented tests (app/src/androidTest), needs device/emulator
./gradlew lint                   # Android lint
```

Unit tests live in `app/src/test/java/com/example/tgmusicai/` (one file per feature area, e.g. `MusicRepositoryTest.kt`, `YouTubeExtractionTest.kt`, `LoopModeTest.kt`) — not mirrored 1:1 to source files. `testOptions.unitTests.isReturnDefaultValues = true` is set, so Android framework calls in unit-tested code return defaults instead of throwing. Baseline: **51 unit tests, 0 failures.**

Cloud playback is covered by **instrumented** tests, because it can only be verified against a real device and a real network:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.tgmusicai.StreamResolutionTest
```

- `StreamResolutionTest` — resolution, cloud search (the thing a NewPipeExtractor bump would break), and the download candidate list.
- `PlaybackVerificationTest` — drives a real ExoPlayer through the real `SchemeAwareCacheDataSourceFactory` and asserts a cloud stream **and** a local file both reach `STATE_READY`. Both halves are required; `CLAUDE.md` records that a past change here broke local playback while streams kept working, so checking one proves nothing.
- `StreamPrefetchTest` — prefetching commits bytes to the audio cache.

These need network access to YouTube and will fail without it; that's the environment, not a regression. Note they must not all fetch the same track at once: YouTube throttles repeated fetches from one address and answers 403, which produced a suite that failed only when run as a whole.

There is a Nix flake (`flake.nix`) for the dev environment; not required if Android Studio/JDK 17/SDK 35+ are already set up.

The build runs on **any JDK 17 or newer**. It used to hard-require a JDK 17 *installation* via
`gradle/gradle-daemon-jvm.properties` (`toolchainVersion=17`) plus a `java { toolchain { ... } }`
block, which meant `./gradlew` failed outright with *"Cannot find a Java installation ... matching
languageVersion=17"* in any shell that hadn't run `nix develop` — including a fresh terminal on
this machine, where only JDK 21 is on `PATH`. Both pins were removed; `compileOptions` and the
Kotlin `jvmTarget` still pin the emitted bytecode to 17, so output is unchanged. Don't reintroduce
either pin.

`adb` is not on `PATH` — it lives at `~/Android/Sdk/platform-tools/adb`.

## Reference docs (read before large changes)

`MD Files/` (gitignored, local-only — not in git) holds detailed internal docs kept as the working reference:

- `MD Files/ARCHITECTURE_CHEAT_SHEET.md` — per-subsystem architecture writeups, including root causes of real bugs that were fixed (e.g. the `SchemeAwareCacheDataSource` local-file-playback regression, the `MediaLibrarySession.onConnect` command-grant bug). Read the relevant subsystem section before touching playback, alarms, YouTube extraction, or the AI containment layer.
- `MD Files/CODE_MAP.md` (+ `UI_GUIDE.md` for `ui/`) — index of every source file, its purpose, and key functions. Use this to find the right file before grepping blind.

These are working notes, not committed docs — treat them as a snapshot to verify against the actual code, not ground truth.

## Architecture

### Layering
`ui/` (Compose screens + ViewModels) → `data/repository/` (orchestration: `MusicRepository`, `LyricsRepository`, `CoverArtScraper`) → `data/local/` (Room DAOs) and `data/youtube/`/`data/google/` (network). `playback/` is a parallel layer: `PlaybackService` (Media3 `MediaLibraryService`) owns the actual `ExoPlayer`; all UI code talks to it only through `MediaControllerManager` (`MediaController` client), never directly.

`MainActivity` constructs every top-level singleton (DB, DAOs, repositories, `AiFeatureManager`, `NetworkObserver`, `MediaScanner`) and injects them manually — there is no DI framework.

### Playback (`playback/`)
- `PlaybackService` is a `MediaLibraryService`; survives independent of any open UI screen. It builds ExoPlayer with **one** `DataSource.Factory` (`SchemeAwareCacheDataSource`) that must stay scheme-aware: `http(s)://` streams route through `AudioCacheManager`'s 500MB disk cache, everything else (`file://`/`content://` local tracks) goes through a plain `DefaultDataSource`. Wiring this cache-only ever broke local playback entirely in the past — any change here needs testing against a real downloaded/local track, not just a stream.
- The session's `onConnect()` override must build from `MediaSession.ConnectionResult.AcceptedResultBuilder(...)`, not rely on the deprecated `onConnect` overload's default (`SessionCommands.EMPTY`/`Player.Commands.EMPTY`), or every controller silently loses play/pause/etc. permissions with no exception thrown.
- **The HTTP data source must send `YouTubeExtractor.REALISTIC_USER_AGENT`** (`youTubeHttpDataSourceFactory()` in `SchemeAwareCacheDataSource.kt`). YouTube's signed `googlevideo` URLs answer 206 to that agent and **403 to Media3's default**, so a mismatch makes a correctly resolved stream fail at playback with `ERROR_CODE_IO_BAD_HTTP_STATUS` — a symptom that points at resolution, which is not at fault. Playback and prefetch share this one factory so they can't drift apart.
- Queue resolution is progressive: the starting song resolves first (fast start), the rest of the queue resolves concurrently and patches in via `Player.replaceMediaItem`.
- `StreamPrefetcher` pulls the current and next track fully into the `AudioCacheManager` cache, so a signed URL expiring or throttling mid-song can't kill playback partway through. Best-effort: every failure is swallowed and never reaches the player. It commits every 512KB rather than Media3's 5MB default, because cached bytes only survive a process kill once a fragment commits.
- `MediaControllerManager.recoverFromPlaybackError` re-resolves a **cloud** track once when its stream URL fails (signed URLs expire and YouTube throttles per address, so a working URL can start returning 403). It invalidates the cached URL first via `YouTubeExtractor.invalidateCachedStream`, retries only cloud tracks, and only once between successful plays.

### Data layer (`data/local/`)
Room DB, currently versioned with real additive migrations only (`MIGRATION_x_y` chains — never `fallbackToDestructiveMigration()` for a shipped schema bump, since that wipes user libraries). Six protected `isSmart = true` playlists (Liked Music, Top 50, Recently Added, Unplayed, Downloads, Cloud Nine) are computed via `MusicRepository.playlistWithSongsFlow`, not backed by real cross-ref rows — `MusicRepository.isProtectedSmartPlaylist` is the single shared guard against deleting/mutating them. `AiSongTags` is a standalone table deliberately never joined into core queries.

### YouTube (`data/youtube/`, `data/google/`)
- Search: NewPipeExtractor first, falls back to Piped/Invidious REST. **Search works.**
- **Stream resolution works.** It runs in tiers: **Tier 0 is NewPipeExtractor talking to YouTube directly** (`YouTubeExtractor.tryNewPipeStreamExtractions`), then Piped, then Invidious. Tier 0 resolves in a few seconds; exhausting the old dead tiers took ~110s. Downloads share the resolver (`CloudDownloadManager` calls `extractAudioStreams`), so they were broken for the same reason and are fixed by the same change.
- **No PoToken is involved, and none should be added.** Earlier notes planned a hidden-WebView BotGuard `PoTokenProvider`. That was built, proven to mint real tokens, and then removed: current NewPipeExtractor resolves through InnerTube clients that need no token (look for `c=VISIONOS` in resolved URLs), and its own `YoutubeStreamExtractor.setPoTokenProvider` is documented as a **no-op** "until SABR support is added to the extractor". It is recoverable from git history if YouTube ever forces poTokens back.
- **The extractor dependency is pinned to a commit, not a tag**, on the `com.github.TeamNewPipe:NewPipeExtractor` coordinate (not the older `...NewPipeExtractor:extractor` submodule one). JitPack deletes tag-built artifacts over time — `v0.26.5` resolves to nothing — while commit builds persist; NewPipe pins the same way for the same reason. `maven.schabi.org` is not a usable alternative, it serves a certificate that doesn't match its hostname. Note that v0.24.8 is far too old: it only ever called `getWebEmbedClientPoToken`, and its web metadata request now fails with `ContentNotAvailableException: The page needs to be reloaded`.
- Tiers 1 and 2 are insurance only and were measured dead (Piped `/streams` → 500, all Invidious hosts → 401/403). They cost nothing because they're reached only when Tier 0 fails. Piped's **`/search`** endpoint does still work, which is why the Piped search fallback is kept. `piped-instances.kavin.rocks` was removed entirely: the host no longer resolves, so that tier could never self-heal, and consulting it cost a multi-second timeout per resolution.
- If Tier 0 ever breaks, the durable fix is to bump NewPipeExtractor — **not** to hunt for new public mirrors. No list of them stays working.
- When resolution fails for every source, `MediaControllerManager` surfaces a toast instead of silently handing an unplayable watch URL to ExoPlayer (which looked like "No song selected" and a dead tap).
- Playlist sync (`data/google/YouTubePlaylistSyncManager.kt`) and account auth (`data/youtube/InnerTubeCookieManager.kt`, `YouTubeInnerTubeClient.kt`) use a captured music.youtube.com web session (InnerTube), **not** Google OAuth / YouTube Data API v3 — that OAuth path was removed. Don't reintroduce `GoogleAuthManager`/`YouTubeDataApiClient`-style API-key/OAuth flows for playlist sync.
- `CloudDownloadManager` dedupes by `youtubeId` or title+artist before downloading, and persists in-flight downloads to `PendingDownload` so they resume after a process kill.

### No API keys
The app requires no API keys of any kind. A user-supplied Gemini/OpenAI key used to drive online metadata cleaning and lyrics translation; it was removed entirely (the model it targeted had been retired, and the metadata path fired one request per song on every launch while discarding the result for songs already in the DB). The replacements are on-device: `AiMetadataCleaner.cleanOffline` (regex, unit-tested) for metadata, and ML Kit's on-device translator for lyrics. Don't reintroduce an API-key setting.

### On-device AI (`ai/`)
Isolated containment layer — every engine (`SongTaggingEngine` for YAMNet TFLite tagging, `LyricsEmbeddingEngine` for MiniLM ONNX embeddings, `WhisperTranscriptionEngine` for on-device transcription) self-initializes behind try-catch, permanently marks itself unavailable on failure instead of retrying, and returns a sealed `AiModelResult` instead of throwing. `AiFeatureManager` is the facade the rest of the app should use for tagging/embeddings; it wraps everything again on its own `SupervisorJob` scope. Results cache in the standalone `ai_song_tags` table. Model assets live in `app/src/main/assets/ai/` and must stay uncompressed in the APK (`androidResources { noCompress += listOf("tflite", "onnx") }` in `app/build.gradle.kts`) so they can be mmap'd. Keep this isolation when extending AI features — a model/decode failure must never be able to affect playback or core app state.

### UI structure
Bottom tabs are Home, Library, Playlists, Alarms. There is no Explore tab — cloud search was folded into the Library's search, which renders local matches followed by a "From YouTube" section; the "Downloaded only" toggle (persisted in `AppPreferences`) hides the cloud half and restricts the library to on-device tracks. Everything not a bottom tab (Stats, Import from YouTube, Downloads, Settings) lives in the navigation drawer, which every top-level screen exposes via a hamburger. Top-level navigation goes through `MainScreen.navigateTopLevel`, which pops back to an existing entry rather than calling `backStack.clear()` — clearing the stack disabled the back handler and stranded users on drawer-only screens.

### Backup (`data/local/BackupManager.kt`)
Export writes into a caller-supplied `OutputStream` obtained from `ActivityResultContracts.CreateDocument`, never a self-chosen path: the app holds no storage permission at `targetSdk 37`, so the old direct write to public Downloads failed with EACCES on every device. Import assigns fresh row ids and remaps cross-refs/stats through `songIdMap`/`playlistIdMap` — reusing the manifest's primary keys against REPLACE inserts silently overwrote unrelated local rows. The whole import runs in one `withTransaction`, the manifest carries a `schemaVersion` that a newer backup is rejected on, and MediaStore-sourced songs with no bundled audio are re-matched locally by title+artist instead of restoring an unplayable `content://` id from another device.

### Alarms (`alarm/`)
`AlarmReceiver` starts `AlarmPlaybackService` (a foreground `Service`) directly — audio playback lives in the service, not in `AlarmActivity`, because a full-screen-intent notification doesn't auto-launch its Activity when the screen is already on/unlocked. `AlarmActivity` is a pure UI shell that only displays state and forwards Dismiss/Snooze to the service. Per-alarm volume behavior (`forceMaxVolume`, `volumeRampUp`) lives on the `Alarm` entity, not in global `AppPreferences`. `snoozeMinutes == 0` is the "snooze disabled" sentinel — it needs no schema change, but `AlarmScheduler.scheduleSnooze` must keep ignoring it (scheduling 0 fires immediately) and the snooze button stays hidden.

### AI-assisted engineering history
The README documents a two-model workflow: Claude for architecture/extraction-resilience/DB migrations/AI containment design, Gemini for IDE-level implementation/Compose UI generation/unit tests. Not a constraint on future work, just context for why some design docs reference both.
