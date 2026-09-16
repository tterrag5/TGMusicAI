# TGMusicAI - Project Explanation

Welcome to the **TGMusicAI** codebase! This document is designed to help you understand what this app does in simple, non-technical terms, and why the different parts of the code exist.

---

## Developer Cheat Sheet Reference
For a complete, highly technical breakdown of every class, method, data pipeline, and step-by-step bug testing guide, please refer to:
* **[ARCHITECTURE_CHEAT_SHEET.md](ARCHITECTURE_CHEAT_SHEET.md)** located in the project root.

---

## What is TGMusicAI?
TGMusicAI is a modern Android music player application. Its core purpose is to let users browse, manage, stream, and play musical tracks stored locally or discovered online via YouTube Cloud Search. Imagine a stylish, personal digital jukebox. It incorporates smart AI-driven features, YouTube Music redesign interface, multiple eye-pleasing soft color themes, an immutable "Liked Music" system playlist with Thumbs Up cover artwork, song transcript/synced lyrics view, online cloud streaming/downloading, lyrics and high-resolution cover art scraping, musical alarms, speed dial home tab, portable backups, storage management, sleep timer, and Android Auto support, built on top of a robust music player core.

---

## Why is it built this way?
Modern Android apps are like intricate clockworks. We separate different "jobs" into different layers so that the app is reliable, easy to update, and doesn't freeze up while you're using it.

### 1. The Database Layer (Room)
**Where to find it:** `app/src/main/java/com/example/tgmusicai/data/local/`

Think of the database as the app's permanent memory. Every time you open the app, it doesn't have to scan your entire phone all over again from scratch. It scans once and saves the details into a well-organized filing cabinet.
We use **Room**, which is Google's official persistence library.

*   **Entities (The File Folders):** 
    *   `Song`: Holds information about a specific music track (Title, Artist, Album, Duration, mediaUri, Producer metadata, Lyrics text, Artwork URI, `youtubeId`, `isDownloaded`, `isPinned`).
    *   `Playlist`: Represents a custom or smart playlist created by the user or system (`playlistId`, `name`, `description`, `createdAt`, `isPinned`, `isSmart`).
    *   `PlaylistSongCrossRef`: Keeps track of which songs belong to which playlists without duplicating song files.
    *   `SongStats`: Keeps track of play counts and last played timestamps (`lastPlayedAt`).
    *   `Alarm`: Represents user-configured musical alarms (Time, Repeat Days, Tone Type [SONG, PLAYLIST, RANDOM_LIKED], Tone URI/ID, Snooze duration).
*   **DAOs (The Librarians):**
    *   `SongDao`, `PlaylistDao`, `SongStatsDao`, `AlarmDao` are Data Access Objects handling asynchronous queries and reactive Kotlin Flows.
    *   `PendingDownload`: Tracks a cloud download still in progress so it can resume automatically if the app is killed mid-download.
*   **AppDatabase (The Library Building):**
    *   Connects all DAOs and Entities together in SQLite (Database version 8, upgraded via real additive migrations that never delete existing data).

### 2. Multiple Soft Color Themes
**Where to find it:** `app/src/main/java/com/example/tgmusicai/ui/theme/` (`Color.kt`, `Theme.kt`) & `AppPreferences.kt`

Supports 4 eye-pleasing, soft dark color schemes configurable via a Theme Switcher dialog in `HomeScreen`:
1.  `YT_DARK`: Classic soft dark (`#0F0F0F` background, `#1F1F1F` cards, `#E53935` soft red accent).
2.  `PASTEL_MIDNIGHT`: Soothing dark slate (`#121824` background, `#1B2436` cards, `#4FD1C5` soft teal accent).
3.  `WARM_AMBER`: Soft warm dark latte (`#1C1917` background, `#292524` cards, `#F59E0B` warm amber accent).
4.  `NORDIC_SLATE`: Cool Scandinavian gray (`#1E222A` background, `#282C34` cards, `#61AFEF` soft cyan accent).

Themes are stored in DataStore via `AppPreferences.selectedThemeFlow` and dynamically applied across the entire app via `TGMusicAITheme(themeName = selectedTheme)`.

### 3. Immutable "Liked Music" System Playlist
**Where to find it:** `app/src/main/java/com/example/tgmusicai/data/repository/MusicRepository.kt` & `ui/components/PlaylistCard.kt`

*   **Permanent System Playlist:** Automatically created and maintained as `"Liked Music"`.
*   **Immutable Rules:** User deletion is explicitly blocked in both the UI (`PlaylistCard` hides delete action for "Liked Music") and `MusicRepository.deletePlaylist()`.
*   **Thumbs Up Cover Art:** Renders a prominent **Thumbs Up (👍)** vector artwork card on `PlaylistsScreen`, `HomeScreen`, `PlaylistCard`, and `PlaylistDetailScreen`.
*   **Liking Songs:** Tapping the Thumbs Up / Like button on `MiniPlayer` or `NowPlayingScreen` toggles adding/removing the currently playing track to/from "Liked Music". Songs can also be removed directly from within the "Liked Music" detail screen.

### 4. Song Transcript / Synced Lyrics View
**Where to find it:** `app/src/main/java/com/example/tgmusicai/ui/screens/NowPlayingScreen.kt` & `LyricsRepository.kt`

*   **View Toggle:** A clear, prominent "Cover" vs. "Lyrics / Transcript" toggle selector button.
*   **Auto-Fetching:** Whenever a song starts playing, lyrics are automatically fetched via `LyricsRepository`.
*   **Synced Display:** Displays LRC synced lyrics with smooth line highlighting and tap-to-seek playback.
*   **Empty State:** Displays `"No transcript/lyrics available for this track"` with a manual **"Refetch Lyrics"** button.

### 5. Dynamic Playlist Cover Art & Descriptions
**Where to find it:** `app/src/main/java/com/example/tgmusicai/ui/components/PlaylistCard.kt`, `CreatePlaylistDialog.kt`, `PlaylistDetailScreen.kt`

*   **Top Song Artwork:** Dynamically renders the album cover art of the **first (top) song** in a playlist as the playlist cover art. Shows default queue music icon if empty.
*   **Playlist Description:** Users can enter an optional playlist description when creating a playlist in `CreatePlaylistDialog` or edit description in `PlaylistDetailScreen`.

### 6. AI Metadata Cleaner & Media Scanner
**Where to find it:** `app/src/main/java/com/example/tgmusicai/data/local/AiMetadataCleaner.kt` & `MediaScanner.kt`

Before the app inserts tracks into Room DB during media scanning or cloud downloads:
*   **AiMetadataCleaner:** A lightweight metadata parsing engine.
    *   **Offline Pattern Engine:** Extremely fast (<1 ms) regex parser extracting producer names (`prod.`, `produced by`), featured artists (`feat.`, `ft.`), and stripping noise tags (`[Official Audio]`, `(Video)`).
    *   **Optional Online AI Engine:** Supports Gemini (`AIza...`) or OpenAI (`sk-...`) API keys configured in `AppPreferences`.
*   **MediaScanner:** Scans device media via `MediaStore`, cleans raw titles with `AiMetadataCleaner`, and saves structured `Song` entities with producer metadata.

### 7. Lyrics & High-Resolution Cover Art Scraper
**Where to find it:** `app/src/main/java/com/example/tgmusicai/data/repository/LyricsRepository.kt` & `CoverArtScraper.kt`

Provides automated lyrics/transcripts fetching and high-resolution album cover art scraping:
*   **LyricsRepository:** Queries **LrcLib API** or Piped/Invidious captions, parses synced LRC strings into structured `LyricLine(timestampMs, text)` for real-time synced display in the UI.
*   **CoverArtScraper:** Searches iTunes Search API / Cover Art Archive for 1000x1000 high-resolution album covers.

### 8. YouTube Music Redesign UI & Navigation Layout
**Where to find it:** `app/src/main/java/com/example/tgmusicai/ui/` (`HomeScreen.kt`, `MainScreen.kt`, `MiniPlayer.kt`, `Theme.kt`, `Color.kt`)

*   **Category Filter Chips:** Scrollable filter chips at the top of the Home screen (`Podcasts`, `Relax`, `Energize`, `Party`, `Feel good`, `Workout`, `Romance`, `Sad`, `Commute`, `Sleep`, `Focus`).
*   **Left Sidebar / Drawer Layout:** Navigation drawer featuring `Home`, `Explore` (YouTube Cloud), `Library`, a pill-shaped `+ New playlist` button, and a list of pinned/auto-playlists with `📌` indicators.
*   **Card Carousels:** Square rounded artwork cards with translucent center play button overlays, subtitles formatted as `📌 Song • Artist` or `📌 Playlist • X tracks`, and section headers ("Listen again", "Recaps / Favorites", "Speed Dial").
*   **Bottom Player Bar:** Persistent YouTube Music bottom bar featuring:
    *   **Left:** Play/Pause, Skip Next, progress time code (`0:00 / 4:36`).
    *   **Center:** Thumbnail, Title, Subtitle ("From your Android device" / artist), Thumbs up / Thumbs down / 3-dots overflow.
    *   **Right:** Volume icon, **3-State Loop Button**, Shuffle toggle.

### 9. Exact 3-State Loop Cycle (`RepeatMode`)
**Where to find it:** `app/src/main/java/com/example/tgmusicai/playback/MediaControllerManager.kt`, `PlaybackService.kt`, `PlayerViewModel.kt`, `LoopModeTest.kt`

Implements exact YouTube Music 3-state repeat loop logic mapping directly to ExoPlayer:
*   **State 0:** `Player.REPEAT_MODE_OFF` (Default: Off, unselected icon).
*   **State 1:** `Player.REPEAT_MODE_ALL` (1st tap: Loop Playlist/Queue, highlighted repeat icon).
*   **State 2:** `Player.REPEAT_MODE_ONE` (2nd tap: Loop Current Song, repeat icon with '1' badge).
*   **3rd tap:** Returns to State 0 (`Player.REPEAT_MODE_OFF`).

### 10. The Media Player & Android Auto Layer (Media3 MediaLibraryService)
**Where to find it:** `app/src/main/java/com/example/tgmusicai/playback/` & `res/xml/automotive_app_desc.xml`

*   **PlaybackService:** Extends `MediaLibraryService` to run playback in the background and provide full **Android Auto** support.
*   **MediaControllerManager:** Connects the background service to the UI layer, exposing real-time `StateFlow`s for song state, playback position, and queue.
*   **SleepTimerManager:** Manages sleep countdown timers (15, 30, 45, 60 min) to pause playback automatically.

### 11. YouTube Cloud Search, Direct Audio Streaming, Background Downloader & Deduplication
**Where to find it:** `app/src/main/java/com/example/tgmusicai/data/youtube/` & `app/src/main/java/com/example/tgmusicai/ui/screens/YouTubeScreen.kt`

*   **YouTube Cloud Search (`YouTubeExtractor`):**
    *   **Primary Search:** Uses `NewPipeExtractor` with custom `NewPipeOkHttpDownloader` enforcing realistic Chrome User-Agent header to search YouTube tracks directly.
    *   **Fallback:** If NewPipe returns nothing, falls back to a curated, verified Piped API endpoint and then an Invidious API endpoint cluster.
*   **Direct Audio Stream Extraction (`YouTubeExtractor`):**
    *   Resolves direct M4A/WebM audio stream URLs via NewPipeExtractor's `StreamInfo.getInfo`, filtered to progressive-HTTP streams (a single playable file, as opposed to DASH/HLS which only expose a manifest) and sorted by bitrate.
    *   Falls back to the Piped/Invidious cluster if NewPipe fails, with each candidate URL pre-flight-checked before use.
    *   **Known caveat:** direct NewPipe-to-YouTube extraction can be blocked by YouTube's anti-bot measures on datacenter/cloud networks (this was observed and confirmed systemic while rebuilding this subsystem, not tied to any specific video). It's expected to work normally on a real device's residential/mobile network; the Piped fallback exists specifically to route around it when it happens, since Piped resolves the stream on its own server first.
*   **Direct Cloud Streaming (`YouTubeViewModel` & `MediaControllerManager`):**
    *   Extracts direct audio stream URL and plays audio directly in ExoPlayer via `MediaControllerManager.playSong(streamSong)` without forcing a full download first.
    *   A cloud track added to a playlist without downloading is re-resolved to a fresh stream URL automatically the next time it's played, even from the Library/Playlist screens rather than the Explore tab.
*   **Background Audio Downloader (`CloudDownloadManager`):**
    *   **Deduplication Check:** Before downloading, queries Room DB by `youtubeId` or `title` + `artist` metadata. If an existing local track is found, reuses its local file URI and updates the `youtubeId` field!
    *   **Local Storage Saving:** Streams audio data directly to device storage (`Android/data/com.example.tgmusicai/files/Music/`) with sanitized filenames.
    *   **Progress Tracking:** Emits real-time progress state (`IDLE`, `EXTRACTING`, `DOWNLOADING`, `COMPLETED`, `FAILED`) and download fraction via `downloadMap` StateFlow.
    *   **Metadata Cleaning & Artwork Scraping:** Cleans track title/artist/producer with `AiMetadataCleaner`, scrapes 1000x1000 album artwork with `CoverArtScraper` (saved to `Android/data/com.example.tgmusicai/files/Covers/`), and indexes the track into Room DB via `SongDao`.
    *   **Bulk Playlist Downloader:** Enqueues background downloads for all undownloaded tracks in a cloud playlist.

### 12. Portable Backup & Restore (.tgmusic Zip)
**Where to find it:** `app/src/main/java/com/example/tgmusicai/data/local/BackupManager.kt`

*   **Export:** Packages full database manifest (`manifest.json`) + local downloaded audio files into a single `.tgmusic` zip file saved in `Downloads/`.
*   **Import:** Parses `.tgmusic` zip packages, extracts audio files back to Music storage, and restores DB records without needing internet access or re-downloading!

### 13. Auto-Generated Smart Playlists
**Where to find it:** `MusicRepository.kt` (song-list resolution and the shared immutability check), `PlaylistViewModel.kt` (UI wiring)

Automatically generates six dynamic, undeletable smart playlists:
1. **Liked Music** (real playlist, populated by the heart/like toggle)
2. **Top 50 Most Played**
3. **Recently Added**
4. **Unplayed**
5. **Downloads** — every song that's actually saved to the device
6. **Cloud Nine** — every song that's cloud-only / not yet downloaded

All six are protected from deletion by one shared check (`MusicRepository.isProtectedSmartPlaylist`), and all but Liked Music are computed live from other tables rather than stored as an actual song list.

### 14. Downloads Queue That Survives an App Restart
**Where to find it:** `data/local/entity/PendingDownload.kt`, `CloudDownloadManager.kt`

Every time a download is started, it's recorded in the database first. If the app gets closed or killed partway through, the next launch automatically picks up any unfinished downloads and starts them again — nothing silently gets lost. The Downloads screen also groups everything into three clear sections: **Downloading**, **Queued**, and **Downloads** (finished), and starting a bulk "download all" on a playlist marks the whole batch as queued immediately instead of only showing whichever one track happens to be downloading right now.

### 15. Storage Usage Stats
**Where to find it:** `ui/screens/StatsScreen.kt`, `ui/viewmodel/StatsViewModel.kt`

The Stats screen now has two tabs. Alongside the existing "Listening" tab (play counts, most-played songs), a new "Storage" tab shows how much space your downloaded music is actually using: a total, a breakdown by playlist with a usage bar, and a ranked list of your biggest individual songs.

### 16. Smarter Queue Behavior
**Where to find it:** `playback/MediaControllerManager.kt`, `playback/PlaybackService.kt`, `ui/components/QueueSheet.kt`

Several playback quality-of-life fixes: tapping a song starts it immediately and resolves the rest of a cloud playlist's queue in the background instead of making you wait for the whole thing to resolve first; when a queue is about to run out (and you're not looping), a few more songs get quietly added to the end so the music never just stops — and unlike before, those extra songs actually show up in "Up Next" and can be skipped to or back from, not just silently played. Up Next itself now scrolls to and highlights whatever's currently playing when you open it, and each track has a small drag handle so you can manually reorder what's coming up next.

### 17. Better Alarm Song Picker
**Where to find it:** `ui/components/SongPickerDialog.kt`, `ui/components/AddEditAlarmDialog.kt`

Picking a specific song for a musical alarm used to mean scrolling through a flat, unsearchable dropdown of every song in your library. It's now a proper full-size picker with a search bar and cover art thumbnails, styled the same way the Library tab looks.

### Summary
TGMusicAI provides a rich, modern, offline-ready music ecosystem with YouTube Music UI redesign, multiple soft color themes, six immutable smart playlists (including Downloads/Cloud Nine), synced lyrics view, 3-state loop control, AI metadata cleaning, lyrics, high-res artwork, Speed Dial Home Tab, deduplicated cloud downloads that survive a restart, storage usage stats, smarter never-ending queues with reorderable Up Next, portable backups, and sleep timer!

---

## Development Environment (Nix Flake)
**Where to find it:** `flake.nix` in the project root.

The project includes a Nix Flake (`flake.nix`) providing a reproducible development environment with Java 17 and Android SDK toolchains.

### How to Use `nix develop`:
1. **Build Debug App:**
   ```bash
   nix develop --command ./gradlew assembleDebug
   ```
2. **Run Unit Tests:**
   ```bash
   nix develop --command ./gradlew testDebugUnitTest
   ```
