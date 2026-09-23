# TGMusicAI

**TGMusicAI** is a native Android local music player and YouTube cloud streaming application. Built with **Kotlin**, **Jetpack Compose**, **AndroidX Media3 (ExoPlayer)**, **Room v10**, **TensorFlow Lite**, and **ONNX Runtime**, TGMusicAI combines offline audio playback, cloud extraction, synced lyrics, high-res artwork scraping, and on-device AI features.

---

## 🌟 Key Features

* 🎵 **Local Music Player & Media3 ExoPlayer Engine:** Automatically scans local storage (`Android/data/com.example.tgmusicai/files/Music`) for audio files, extracts ID3 metadata, and provides smooth background playback with system notification controls.
* ☁️ **YouTube Search & Multi-Tier Stream Extraction:**
  * **Search:** Uses `NewPipeExtractor` with realistic Chrome browser headers to query YouTube directly, falling back to Piped and Invidious search endpoints.
  * **Stream Extraction:** Bypasses YouTube's PoToken (Proof of Origin) bot wall using server-side extraction through **Piped** (`pipedapi.wireway.ch`) and **Invidious** API clusters with dynamic, self-healing instance directory lookups.
  * **Pre-Flight Liveness Check:** Sends 1KB HTTP `Range: bytes=0-1023` probes to verify stream URL liveness before feeding ExoPlayer or downloading.
* 📥 **Resumable Cloud Downloader:** Chunked HTTP Range downloads with pause/resume support, bounded concurrency (`Semaphore(3)`), and auto-resumption of interrupted downloads via Room DB (`pending_downloads`).
* 🔄 **Official YouTube Playlist Sync:** Imports official user playlists and Liked Videos (`LL`) via Google OAuth 2.0 and YouTube Data API v3 (`data.google`).
* 📜 **Synced Lyrics Engine:** Parses LRC timestamped lyrics (`[mm:ss.fff]`) with tap-to-seek support. Auto-fetches lyrics from embedded ID3 tags, LrcLib API, or YouTube captions.
* 🎨 **High-Res Cover Art Scraper:** Automatically fetches 1000x1000 high-res artwork from iTunes, MusicBrainz / Cover Art Archive, or YouTube thumbnails, caching them in `Covers/`.
* 🤖 **On-Device AI Suite:**
  * **Audio Tagging (`SongTaggingEngine`):** Uses Google's YAMNet TFLite model (~4MB, 521 AudioSet classes) and `PcmDecoder` (`MediaCodec`) to extract genre, instrument, and mood tags from song audio.
  * **Lyrical Vector Embeddings (`LyricsEmbeddingEngine`):** Uses a quantized MiniLM ONNX model and `WordPieceTokenizer` to compute 384-dimensional sentence embeddings for theme-based similarity radio.
  * **Isolated AI Sandbox:** Results are stored in a standalone `ai_song_tags` table with `SupervisorJob` error boundaries so model failures never disrupt audio playback.
* 🧹 **Offline AI Metadata Cleaner:** Sub-millisecond regex parser stripping bracketed noise (`[Official Video]`), producer tags (`prod. Metro Boomin`), featured artists (`feat. Drake`), and YouTube `- Topic` channel suffixes. Supports optional Gemini/OpenAI cloud cleaning API integration.
* ⏰ **Full-Screen Musical Alarm Clock:** System `AlarmManager` exact scheduler launching full-screen over keyguard (`setShowWhenLocked`), playing custom songs, playlists, or random liked tracks, with boot auto-reschedule.
* 🎛️ **Audio Effects & Equalizer:** 5-band parametric equalizer and bass boost built on Android's `AudioEffectsManager`.
* 💾 **Portable Backup & Restore:** Export and import `.tgmusic` ZIP backups containing database manifests and local audio files, equipped with Zip Slip security verification.
* 🚗 **Android Auto Support:** Full in-car library browsing via `MediaLibraryService`.
* 🎨 **4 Soft Color Themes:** Includes `YT_DARK`, `PASTEL_MIDNIGHT`, `WARM_AMBER`, and `NORDIC_SLATE` themes with DataStore persistence.
* 🧭 **5-Tab Bottom Navigation:** Home, Explore, Library, Playlists, and Alarms in the bottom bar; Stats lives in the navigation drawer alongside YouTube import/downloads shortcuts.
* 🌈 **Dynamic Ambient Artwork Backdrop:** The Now Playing screen samples dominant/muted colors from the current track's artwork via the Android Palette API and renders an animated, crossfading radial gradient behind the hero cover art.
* 👉 **Swipe Gestures on Track Rows:** Swipe a song row right in the Library to instantly append it to the Up Next queue; swipe left to toggle Like.
* 📡 **Zero-Wasted-Data Stream Caching:** YouTube audio streams are written through a 500MB LRU disk cache (`AudioCacheManager`), so replaying a recently heard cloud track costs 0MB of data and works offline.
* 🔊 **Loudness Normalization:** An Android `LoudnessEnhancer` is attached to the live ExoPlayer audio session, balancing volume across quiet local files and loud YouTube streams.
* ⚙️ **Centralized Settings Screen:** Theme selection, AI API key configuration, Backup/Restore, and Alarm sound settings are consolidated into one Settings screen reachable from a single gear icon on Home.

---

## 🧠 AI-Assisted Engineering: How Gemini & Claude Were Used

TGMusicAI was engineered using a hybrid AI pair-programming workflow leveraging both **Google Gemini** and **Anthropic Claude**. Each model played a specialized role in building, optimizing, and auditing the application.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    AI Pair-Programming Architecture                     │
├────────────────────────────────────┬────────────────────────────────────┤
│           Anthropic Claude         │            Google Gemini           │
│       (Architecture & System)      │      (Implementation & UI)         │
├────────────────────────────────────┼────────────────────────────────────┤
│ • Multi-Tier Extraction Strategy   │ • Real-time Code Completions       │
│ • Media3 ExoPlayer Synchronization │ • Material 3 UI Layout Generation  │
│ • Room v10 Migration Architectures │ • TFLite & ONNX Model Integration  │
│ • On-Device AI Error Isolation     │ • Unit Testing & Refactoring       │
└────────────────────────────────────┴────────────────────────────────────┘
```

### 1. Anthropic Claude (System Architecture, Extraction Resilience & Core State)
* **Multi-Tier Stream Extraction Strategy:** Engineered the Piped & Invidious fallback architecture to bypass YouTube's client-side PoToken (Proof of Origin) requirement that broke traditional client-side extraction libraries.
* **Media3 ExoPlayer Integration & Stutter-Free Seeking:** Designed state synchronization between `PlaybackService` and Jetpack Compose UI, decoupling slider drag state from ExoPlayer seek commands to eliminate position jitter during fast seeking.
* **Database Schema & Migrations:** Designed Room DB v10 migrations (`MIGRATION_6_7` through `MIGRATION_9_10`) to ensure additive, zero-data-loss upgrades for statistics, AI embeddings, and pending downloads.
* **On-Device AI Containment:** Architected the `SupervisorJob` containment layer for TensorFlow Lite and ONNX inference, ensuring audio decoding errors or model initialization failures stay strictly isolated from core music playback.

### 2. Google Gemini (IDE Integration, UI Redesign & Android Ecosystem Alignment)
* **Android Studio IDE Integration:** Handled real-time code autocompletion, refactoring, and lint compliance within Android Studio.
* **Material 3 UI Redesign:** Generated and polished Jetpack Compose components, including the YouTube Music-style bottom player bar, Now Playing screen, QueueSheet, and Speed Dial card carousels with category filter chips.
* **On-Device AI Model Integration:** Assisted in configuring Android CAPI/Java bindings for Google's YAMNet TFLite audio classifier and ONNX Runtime tensor bindings for MiniLM text embeddings.
* **Unit Testing & Edge-Case Verification:** Built unit test suites for LRC lyrics parsing, metadata cleaning regexes, loop playback modes, and extraction fallback logic.

---

## 🛠️ Tech Stack & Dependencies

* **Language:** Kotlin 2.0+
* **UI:** Jetpack Compose, Material 3, Navigation3, Coil Compose
* **Audio Engine:** AndroidX Media3 (ExoPlayer, MediaSession, UI)
* **Database & Storage:** Room v10 (KSP), Jetpack DataStore Preferences
* **Networking:** OkHttp 4, Retrofit 2, Moshi
* **On-Device AI:** TensorFlow Lite, ONNX Runtime Android, Google Guava (Coroutines)
* **Extraction:** NewPipeExtractor, Piped API, Invidious API, YouTube Data API v3

---

## 📁 Repository Structure

```
TGMusicAI/
├── app/
│   └── src/main/java/com/example/tgmusicai/
│       ├── ai/             # On-device AI (YAMNet TFLite, MiniLM ONNX, WordPiece, PcmDecoder)
│       ├── alarm/          # AlarmScheduler, AlarmReceiver, AlarmActivity over keyguard
│       ├── data/
│       │   ├── google/     # Official YouTube Data API v3 OAuth & Sync Manager
│       │   ├── local/      # Room DB v10, DAOs, Entities, DataStore Preferences, BackupManager
│       │   ├── network/    # NetworkObserver
│       │   ├── repository/ # CoverArtScraper, LyricsRepository, MusicRepository
│       │   └── youtube/    # YouTubeExtractor (Piped/Invidious), CloudDownloadManager
│       ├── playback/       # PlaybackService (Media3), AudioEffectsManager (EQ), SleepTimer
│       └── ui/             # Jetpack Compose Screens, Components, Navigation, ViewModels
└── README.md
```

---

## 🚀 Building & Running

1. **Prerequisites:**
   * Android Studio Ladybug (2024.2.1) or newer
   * JDK 17
   * Android SDK 35 (Compile SDK 37)
2. **Setup:**
   * Clone the repository:
     ```bash
     git clone https://github.com/YOUR_USERNAME/TGMusicAI.git
     cd TGMusicAI
     ```
   * Open the project in Android Studio.
   * Sync Gradle (`Sync Project with Gradle Files`).
   * Build & Run on an Android device or emulator running **Android 8.0 (API level 26)** or higher.

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
