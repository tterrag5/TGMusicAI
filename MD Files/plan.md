/btw# TGMusic AI - Master Implementation Plan & Architecture Summary

## 1. Executive Summary & Project Goal
**TGMusic AI** is a state-of-the-art Android music application combining local audio playback with online YouTube cloud streaming, automated AI metadata cleaning, synced lyrics transcription, high-resolution artwork scraping, custom musical alarms, multiple soft color themes, portable backup/restore, six immutable smart playlists (including Downloads/Cloud Nine), and storage usage stats. Built according to modern Android architecture guidelines (Jetpack Compose, Room DB v8, Media3 ExoPlayer, Coroutines Flow, DataStore), the app provides a seamless offline/cloud music experience with full edge-to-edge support, Android Auto integration, responsive touch controls, resumable downloads, and Google account YouTube playlist import/sync.

---

## 2. Completed Task Timeline (Tasks 1 through 14)

| Task # | Feature / Milestone | Description | Acceptance Criteria | Status |
|---|---|---|---|---|
| **Task 1** | **Room DB v6 Schema & Data Layer** | Implemented Room v6 persistence layer with `Song`, `Playlist` (with `description`), `PlaylistSongCrossRef`, `SongStats`, and `Alarm` entities and reactive DAOs. | • Room database schema upgraded to v6 cleanly.<br>• All DAOs expose reactive Kotlin `Flow<T>` queries.<br>• Data operations execute asynchronously on `Dispatchers.IO`. | ✅ Complete |
| **Task 2** | **AI Metadata Cleaner & MediaScanner** | Offline pattern engine stripping bracketed noise, producer credits (`prod.`), and featured artists (`feat.`), plus optional online Gemini/OpenAI API key integration and MediaStore scanner. | • Sub-millisecond offline regex parsing of titles.<br>• Safe HTTP handling with connection timeouts.<br>• MediaStore scans non-duplicate tracks into Room. | ✅ Complete |
| **Task 3** | **Media3 ExoPlayer & MediaLibraryService** | Background playback engine with `PlaybackService`, application-scoped `MediaControllerManager`, position ticker, and browsable Android Auto media tree. | • Seamless background playback and notification controls.<br>• Android Auto browsable categories (`Songs`, `Playlists`, `Artists`).<br>• Real-time position updates every 500ms. | ✅ Complete |
| **Task 4** | **YouTube Search & Fallback Extraction** | YouTube cloud music search and stream extraction utilizing `NewPipeExtractor` with automatic fallback to Piped and Invidious REST API endpoints. | • High quality direct stream URL extraction.<br>• Resilient multi-stage fallback prevents stream failures.<br>• Realistic Chrome User-Agent header enforced. | ✅ Complete |
| **Task 5** | **Cloud Downloader & Deduplication** | `CloudDownloadManager` streaming YouTube audio to local storage with progress flows, bulk playlist downloader, and deduplication check. | • Reuses existing local file if YouTube ID or title+artist matches.<br>• Bulk enqueues all un-downloaded songs in cloud playlists.<br>• Progress state tracked and sanitized file naming. | ✅ Complete |
| **Task 6** | **Synced Lyrics & LRC Transcript View** | `LyricsRepository` querying LrcLib, embedded ID3 tags, and YouTube captions, rendering auto-scrolling LRC synced lyrics with tap-to-seek support. | • LRC timestamps parsed into structured `LyricLine` objects.<br>• Interactive toggle between Cover Art and Lyrics view.<br>• Manual "Refetch Lyrics" button provided. | ✅ Complete |
| **Task 7** | **High-Res Cover Art Scraper System** | `CoverArtScraper` fetching 1000x1000 iTunes covers, MusicBrainz, or YouTube thumbnails, driving the playlist top-song artwork display. | • Replaces 100x100 iTunes thumbnails with 1000x1000 high-res URLs.<br>• First song artwork used as dynamic playlist cover.<br>• Local image caching in `Covers/` directory. | ✅ Complete |
| **Task 8** | **Immutable "Liked Music" System Playlist** | Permanent system playlist `"Liked Music"` with deletion protection, heart/thumbs-up toggle in players, and custom artwork. | • System playlist auto-created and protected from deletion.<br>• Heart button toggles song inclusion in Liked Music.<br>• Thumbs Up vector artwork displayed in UI cards. | ✅ Complete |
| **Task 9** | **Speed Dial Home Screen & Navigation Drawer** | YouTube Music layout redesign with scrollable category filter chips, left navigation drawer with pinned items (`📌`), and persistent `MiniPlayer` bottom bar. | • Responsive layout with drawer and bottom player bar.<br>• Category filter chips ("Podcasts", "Relax", "Energize", etc.).<br>• Speed Dial Home tab with card carousels. | ✅ Complete |
| **Task 10** | **Interactive Seeking & Stutter-Free Dragging** | Interactive Compose `Slider` in `MiniPlayer` and `NowPlayingScreen` with local drag position state and zero-duration guards. | • Stutter-free 60 FPS dragging without position ticker jumps.<br>• Command dispatched on drag release (`onValueChangeFinished`).<br>• Division-by-zero protection (`maxOf(1L, duration)`). | ✅ Complete |
| **Task 11** | **Storage Management, Sleep Timer & Portable Backup** | "Remove Download, Keep in Playlist" feature, portable `.tgmusic` zip backup export/import with Zip Slip protection, and `SleepTimerManager`. | • Local file deletion keeps track in DB as cloud streamable.<br>• `.tgmusic` zip contains DB manifest and audio files.<br>• Sleep timer countdown auto-pauses audio playback. | ✅ Complete |
| **Task 12** | **Multiple Soft Color Themes** | 4 eye-pleasing soft color schemes (`YT_DARK`, `PASTEL_MIDNIGHT`, `WARM_AMBER`, `NORDIC_SLATE`) with Theme Switcher dialog and DataStore persistence. | • Soft color schemes applied globally via `TGMusicAITheme`.<br>• Dynamic theme switcher accessible from Home top bar.<br>• Theme selection persisted across app restarts. | ✅ Complete |
| **Task 13** | **YouTube Music UI Redesign & 3-State Loop Button** | YouTube Music UI redesign and 3-State Loop button implementation for enhanced playback control. | • Dark theme (#030303 background, category filter chips, card carousels with play overlays, left sidebar/drawer with pinned playlists, YT Music bottom player bar with progress time codes).<br>• 3-State Loop Button (OFF -> Loop Playlist -> Loop Song -> OFF) integrated into PlaybackService and ExoPlayer. | ✅ Complete |
| **Task 14** | **Full-Screen Lock-Screen Musical Alarm** | System `AlarmManager` scheduler with `AlarmReceiver`, `AlarmActivity` waking screen over lock screen, custom tone options, and `BootReceiver`. | • Lock screen wake-up with `setShowWhenLocked(true)`.<br>• Plays custom song, playlist, or random liked track.<br>• Snooze and Dismiss actions with boot rescheduling. | ✅ Complete |
| **Task 15** | **Full YouTube Extraction Rebuild** | The original YouTube search/stream pipeline (6 fallback tiers, mostly third-party scraper sites and an unauthenticated InnerTube call) was almost entirely non-functional. Rebuilt around real `NewPipeExtractor` APIs (`StreamInfo.getInfo`, `DeliveryMethod.PROGRESSIVE_HTTP` filtering) as the primary path, with a curated, verified Piped/Invidious fallback cluster, plus a fix so cloud tracks added to a playlist (not downloaded) re-resolve their stream URL at play time instead of failing. | • Real search returns real YouTube results (live-verified).<br>• Real stream resolution returns a playable URL (live-verified via HTTP 206 partial-content check).<br>• Playing a non-downloaded cloud track from Library/Playlist screens works, not just from Explore.<br>• 50/50 unit tests pass; `assembleDebug` succeeds. | ✅ Complete |
| **Task 16** | **Google Account YouTube Playlist Import & Sync** | `GoogleAuthManager` (Play Services Identity/Authorization API, `youtube.readonly` scope, no backend server needed) + `YouTubeDataApiClient` (`playlists.list`/`playlistItems.list`/`videos.list`) + `YouTubePlaylistSyncManager` let a user sign in with Google, see every playlist on their account (Liked Videos pinned first) in a new `GoogleSyncScreen`, pick which to import as local playlists, and have them diff-synced automatically on every app launch. `Playlist` gained `youtubePlaylistId`/`lastSyncedAt` via a real `AppDatabase` `Migration(6, 7)` (no data loss on upgrade). | • Drawer has an "Import from YouTube" entry.<br>• Sign-in shows every account playlist, not just Liked Videos.<br>• Import creates a local playlist tied to the source YouTube playlist.<br>• Re-sync adds/removes cross-refs by diff, never re-downloads or orphans songs.<br>• `refreshAllSynced()` never prompts sign-in for a user with zero imported playlists.<br>• 50/50 unit tests pass; `assembleDebug` succeeds. | ✅ Complete (code); ⏳ pending on-device OAuth/import/sync verification |
| **Task 17** | **Three Live-Device Bug Reports Fixed** | (1) Nav drawer colors were hardcoded to YT_DARK literals instead of `MaterialTheme.colorScheme.*` tokens, so it never matched other themes — fixed and device-confirmed. (2) Liking an unpersisted cloud song was a no-op (`songId == 0L`) — fixed via `MusicRepository.ensurePersisted()` called from `PlayerViewModel.toggleLikeCurrentSong()`. (3) Rotating the phone changed audio output/speaker because `MediaControllerManager` is Activity-scoped and rotation was tearing down/rebuilding the whole controller — fixed by adding `android:configChanges` to `MainActivity` so rotation no longer recreates the Activity. | • Drawer matches the active theme (confirmed on-device).<br>• Liking any song, including unplayed cloud search results, adds it to Liked Music.<br>• Rotating the phone mid-playback keeps the same audio output. | ✅ Complete (code); ⏳ (2) and (3) pending on-device re-verification — device is temporarily unavailable |
| **Task 18** | **Playback Reliability, Resumable Downloads, Storage Stats & Queue UX** | Resolved a live-device report of "Play All" appearing frozen: a self-healing Piped/Invidious instance-directory fallback was fetched eagerly with a 15s timeout, so one dead hardcoded host stalled every affected song by 15s — made lazy (only consulted after the hardcoded host fails), capped at 4s, cached once per process. `MediaControllerManager` now resolves only the starting song before playback begins (was resolving the whole queue sequentially first) and skips full re-queuing when tapping within an already-loaded queue. `PlaybackService` proactively extends the queue with random songs before it runs dry (repeat off) instead of reactively swapping in one song after a stop, and the extension is now visible/skippable in "Up Next" via an `onTimelineChanged` sync. Added `PendingDownload` (DB v7→v8) so interrupted downloads resume on next launch; `DownloadsScreen` now groups Downloading/Queued/Downloads. Fixed `YouTubeViewModel.addCloudTrackToPlaylist` inserting a duplicate `Song` row on every add (no existing-row check) and ran a one-time on-device cleanup merging 78 pre-existing duplicates. Added two new immutable smart playlists (Downloads, Cloud Nine) and, while wiring their protection, found/fixed a real gap where Top 50/Recently Added/Unplayed were actually deletable through the UI — generalized to one shared `isProtectedSmartPlaylist()` check. Centralized the smart-playlist song-resolution logic (previously duplicated across `PlaylistViewModel` flows) into `MusicRepository.playlistWithSongsFlow`/`allPlaylistsWithSongs`. Added a Storage tab to Stats (total usage, per-playlist breakdown, biggest songs) and drag-to-reorder + auto-scroll-to-current in the Up Next sheet. Replaced the alarm screen's flat unsearchable song dropdown with a searchable `SongPickerDialog` styled like the Library tab. | • "Play All" on a cloud playlist starts promptly instead of stalling.<br>• Tapping a song in an already-loaded queue is instant.<br>• Queue never stops unexpectedly (repeat off) and extensions show in Up Next.<br>• A download interrupted by a process kill resumes automatically.<br>• No duplicate `Song` rows created going forward; existing device duplicates merged.<br>• All 6 smart playlists (including new Downloads/Cloud Nine) are genuinely undeletable.<br>• Stats screen shows real storage usage; Up Next scrolls/highlights/reorders.<br>• Alarm song selection is searchable with cover art.<br>• 51/51 unit tests pass; `assembleDebug` succeeds; installed and smoke-tested on a physical device. | ✅ Complete (code + on-device smoke test) |

---

## 3. Architecture Summary

```
+-----------------------------------------------------------------------------------+
|                                 TGMusic AI UI                                     |
|  [HomeScreen]  [LibraryScreen]  [PlaylistsScreen]  [YouTubeScreen]  [AlarmsScreen] |
|            [NowPlayingScreen]  [MiniPlayer]  [ThemeSwitcherDialog]                |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                                 ViewModel Layer                                   |
|  HomeVM  |  LibraryVM  |  PlaylistVM  |  YouTubeVM  |  PlayerVM  |  AlarmVM       |
+-----------------------------------------------------------------------------------+
                                          |
                   +----------------------+----------------------+
                   |                                             |
                   v                                             v
+-------------------------------------+       +-------------------------------------+
|        Playback Subsystem           |       |         Data & Network Layer        |
|  • PlaybackService (Media3)         |       |  • MusicRepository (Central Truth)  |
|  • MediaControllerManager           |       |  • YouTubeExtractor & CloudDownloader |
|  • SleepTimerManager                |       |  • LyricsRepository & CoverArtScraper |
|  • Android Auto MediaTree           |       |  • AiMetadataCleaner & MediaScanner |
+-------------------------------------+       |  • BackupManager (.tgmusic Zip)     |
                                              +-------------------------------------+
                                                                 |
                                                                 v
                                              +-------------------------------------+
                                              |         Room DB v6 (SQLite)         |
                                              |  Songs | Playlists | CrossRefs      |
                                              |  SongStats | Alarms                 |
                                              +-------------------------------------+
```

### Key Architectural Components

1. **Room Database v6 (`data/local/`)**:
   - Entities: `Song`, `Playlist` (with `description`), `PlaylistSongCrossRef`, `SongStats`, `Alarm`.
   - Thread-safe singleton with reactive Kotlin `Flow<T>` queries for real-time UI updates.

2. **Media3 ExoPlayer & Service (`playback/`)**:
   - `PlaybackService` running background audio and serving Android Auto media trees.
   - `MediaControllerManager` managing connection state, position ticker, and queue navigation.

3. **AlarmManager Subsystem (`alarm/`)**:
   - Exact alarm scheduling using `AlarmManager.setAlarmClock`.
   - `AlarmActivity` waking screen over keyguard with custom music or ringtone fallback.
   - `BootReceiver` restoring alarms after device reboot.

4. **YouTube Extractor & Cloud Downloader (`data/youtube/`)**:
   - Multi-stage extraction pipeline (`NewPipeExtractor` -> Piped API -> Invidious API).
   - `CloudDownloadManager` with deduplication check (matching YouTube ID or Title+Artist).

5. **AI Metadata Cleaner & MediaScanner (`data/local/`)**:
   - Sub-millisecond offline regex pattern engine stripping brackets, producer credits, and featured artists.
   - Optional online Gemini/OpenAI API integration with strict connection timeouts.

6. **Lyrics & High-Res Cover Scraper (`data/repository/`)**:
   - `LyricsRepository` fetching LRC synced lyrics from LrcLib, ID3 tags, and YouTube captions.
   - `CoverArtScraper` converting 100x100 iTunes thumbnails to 1000x1000 high-res artwork.

7. **3-State Loop Mode (`playback/MediaControllerManager.kt`)**:
   - State 0: `REPEAT_MODE_OFF` (Default on launch).
   - State 1: `REPEAT_MODE_ALL` (Loop Playlist / Loop Queue).
   - State 2: `REPEAT_MODE_ONE` (Loop Song / Loop Current Track).

8. **Multiple Soft Color Themes (`ui/theme/`)**:
   - 4 soft dark color schemes (`YT_DARK`, `PASTEL_MIDNIGHT`, `WARM_AMBER`, `NORDIC_SLATE`).
   - Dynamic selection via top bar Palette dialog and DataStore persistence.

9. **Immutable "Liked Music" Playlist (`data/repository/MusicRepository.kt`)**:
   - Protected system playlist (`isSmart = true`).
   - Heart/Thumbs Up toggles in player screens automatically manage membership.

---

## 4. Verification & Test Matrix

All **51 unit tests** across 9 test suites pass cleanly with **100% success rate (51/51 unit tests passed)**.

| Test Suite | Test Class | Total Tests | Passed | Failed | Key Functionality Tested |
|---|---|---|---|---|---|
| **1** | `AiMetadataCleanerTest` | 6 | 6 | 0 | Producer/Feat parsing, Video tag stripping, sub-ms performance |
| **2** | `AuditAndSeekingTest` | 7 | 7 | 0 | Filename sanitization, control chars, duration division-by-zero, seek labels |
| **3** | `ConvenienceFeaturesTest` | 5 | 5 | 0 | Entity defaults, Zip backup manifest serialization, SleepTimer, deduplication |
| **4** | `ExampleUnitTest` | 1 | 1 | 0 | Basic environment sanity and setup assertion |
| **5** | `LoopModeTest` | 2 | 2 | 0 | 3-state loop mode transition cycle (`OFF` -> `Loop Playlist` -> `Loop Song` -> `OFF`) |
| **6** | `LyricsAndCoverArtTest` | 4 | 4 | 0 | LRC timestamp parsing, plain lyrics, 1000x1000 iTunes cover art conversion |
| **7** | `MusicRepositoryTest` | 6 | 6 | 0 | Fake DAO CRUD, record plays, smart playlist deduplication, safe playlist deletion |
| **8** | `ThemesAndLikedMusicTest` | 5 | 5 | 0 | Soft color theme resolution, all 6 smart playlists' immutability, Thumbs Up toggle, top artwork |
| **9** | `YouTubeExtractionTest` | 15 | 15 | 0 | YouTube search/stream data models, format filtering, Piped/Invidious JSON parsing (NewPipe's own selection logic is verified via live integration test instead, since `AudioStream` has a private constructor) |
| **Total** | **All Test Suites (9 Suites)** | **51** | **51** | **0** | **100% Verification Success (51/51 Passed)** |

---

## 5. Testing & Documentation Reference Guide

### Running Automated Build & Unit Tests
To execute the complete unit test suite and build the debug APK, run:
```bash
./gradlew testDebugUnitTest assembleDebug
```
Alternatively, in a Nix environment:
```bash
nix develop --command ./gradlew testDebugUnitTest assembleDebug
```

### Reference Documentation Index
For further technical details, architecture diagrams, and auditing specifications, consult the following documentation files located in the project root:

* **[`FULL_CODE_SUMMARY.md`](FULL_CODE_SUMMARY.md)**: Exhaustive file-by-file summary of every class across `data`, `playback`, `youtube`, `alarm`, `utils`, and `ui` packages.
* **[`ARCHITECTURE_CHEAT_SHEET.md`](ARCHITECTURE_CHEAT_SHEET.md)**: Developer reference guide detailing subsystem workflows, class function parameters, and human QA bug testing steps.
* **[`PROJECT_EXPLANATION.md`](PROJECT_EXPLANATION.md)**: Plain-language explanation of TGMusic AI's design philosophy, features, and user-facing capabilities.
* **[`DEEP_CODE_AUDIT_AND_ANALYSIS.md`](DEEP_CODE_AUDIT_AND_ANALYSIS.md)**: Detailed audit report certifying compliance with edge-case exception handling, coroutine safety, and memory leak prevention.
* **[`CODE_AUDIT_HARNESS.md`](CODE_AUDIT_HARNESS.md)**: Quality policy defining the 5 Core Audit Rules (No Stubs, Range Safety, Coroutine Safety, Exception Handling, Resource Lifecycle).
