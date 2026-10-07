# TGMusicAI

**TGMusicAI** is a native Android local music player and YouTube cloud streaming application. Built with **Kotlin**, **Jetpack Compose**, **AndroidX Media3 (ExoPlayer)**, **Room v19**, **TensorFlow Lite**, and **ONNX Runtime**, TGMusicAI combines offline audio playback, cloud extraction, synced lyrics, high-res artwork scraping, and on-device AI features.

**It requires no API keys of any kind.** Everything that used to need one has either been replaced by an on-device equivalent or by a credential the *user* owns.

---

## 🌟 Key Features

* 🎵 **Local Music Player & Media3 ExoPlayer Engine:** Automatically scans local storage (`Android/data/com.example.tgmusicai/files/Music`) for audio files, extracts ID3 metadata, and provides smooth background playback with system notification controls.
* ☁️ **YouTube Search & Tiered Stream Extraction:**
  * **Search:** Uses `NewPipeExtractor` with realistic Chrome browser headers to query YouTube directly, falling back to Piped and Invidious search endpoints.
  * **Stream Extraction:** Tier 0 is `NewPipeExtractor` talking to YouTube directly through InnerTube clients that need **no PoToken**; Piped and Invidious remain as insurance tiers behind it, reached only when Tier 0 fails. The extractor dependency is pinned to a commit rather than a tag, because JitPack deletes tag-built artifacts over time.
  * **Pre-Flight Liveness Check:** Sends 1KB HTTP `Range: bytes=0-1023` probes to verify stream URL liveness before feeding ExoPlayer or downloading.
  * **Windowed resolution:** Only five tracks around the playing one are ever resolved and cached -- two back, playing, two ahead -- so skipping deep into a cloud queue does not leave the resolver grinding through everything that was skipped past.
* 📥 **Resumable Cloud Downloader:** Chunked HTTP Range downloads with pause/resume support, bounded concurrency (`Semaphore(3)`), and auto-resumption of interrupted downloads via Room DB (`pending_downloads`).
* 🔄 **YouTube Music Playlist Sync:** Imports the signed-in account's own playlists and Liked Videos (`LL`) through a captured `music.youtube.com` web session (InnerTube) -- no OAuth client, no Data API key. Catalogue browsing (artists, albums, moods, charts) works unauthenticated, so a user who never signs in still gets Discover.
* 📜 **Synced Lyrics Engine:** Parses LRC timestamped lyrics (`[mm:ss.fff]`) with tap-to-seek support. Auto-fetches lyrics from embedded ID3 tags, LrcLib API, or YouTube captions.
* 🎨 **High-Res Cover Art Scraper:** Automatically fetches 1000x1000 high-res artwork from iTunes, MusicBrainz / Cover Art Archive, or YouTube thumbnails, caching them in `Covers/`.
* 🤖 **On-Device AI Suite:**
  * **Audio Tagging (`SongTaggingEngine`):** Uses Google's YAMNet TFLite model (~4MB, 521 AudioSet classes) and `PcmDecoder` (`MediaCodec`) to extract genre, instrument, and mood tags from song audio.
  * **Lyrical Vector Embeddings (`LyricsEmbeddingEngine`):** Uses a quantized MiniLM ONNX model and `WordPieceTokenizer` to compute 384-dimensional sentence embeddings for theme-based similarity radio.
  * **Lyric Transcription (`WhisperTranscriptionEngine`):** A bundled quantized **Whisper-base (multilingual)** ONNX export transcribes a song's own audio when no lyrics exist anywhere, decoding Whisper's timestamp tokens into real per-line LRC timings. Offline, no key, and no audio leaves the device.
  * **Song Recognition (`AudioFingerprinter`):** Identifies what is playing in the room by listening, matching landmark hashes against an on-device index of the user's **own** library. No fingerprinting service, no key, nothing sent anywhere.
  * **Isolated AI Sandbox:** Results are stored in standalone tables (`ai_song_tags`, `song_fingerprints`) with `SupervisorJob` error boundaries so model failures never disrupt audio playback.
* 🧹 **Offline Metadata Cleaner:** Sub-millisecond regex parser stripping bracketed noise (`[Official Video]`), producer tags (`prod. Metro Boomin`), featured artists (`feat. Drake`), and YouTube `- Topic` channel suffixes. Entirely on device; the optional Gemini/OpenAI cloud tier it once had was removed along with the API-key setting.
* ⏰ **Full-Screen Musical Alarm Clock:** System `AlarmManager` exact scheduler launching full-screen over keyguard (`setShowWhenLocked`), playing custom songs, playlists, or random liked tracks, with boot auto-reschedule.
* 🎛️ **Audio Effects & Equalizer:** 5-band parametric equalizer and bass boost built on Android's `AudioEffectsManager`.
* 💾 **Portable Backup & Restore:** Export and import `.tgmusic` ZIP backups containing database manifests and local audio files, equipped with Zip Slip security verification.
* 🚗 **Android Auto Support:** Full in-car library browsing via `MediaLibraryService`.
* 🎨 **4 Soft Color Themes:** Includes `YT_DARK`, `PASTEL_MIDNIGHT`, `WARM_AMBER`, and `NORDIC_SLATE` themes with DataStore persistence.
* 🧭 **3-Tab Bottom Navigation:** Home, Library and Alarms. Everything that is a way of looking at the same library lives *on* Library, chosen from a segmented control: **Songs** (grid or list, with tag-filter chips and cloud search folded into one search box), **Playlists** (always a grid, covers drawn as a 2x2 mosaic of the four songs most played *from that playlist*) and **Discover** (YouTube Music's own shelves, moods and charts, plus an endless "For you" feed walked outwards from the user's listening). Stats, YouTube import, Downloads and Settings live in the navigation drawer.
* 🌈 **Dynamic Ambient Artwork Backdrop:** The Now Playing screen samples dominant/muted colors from the current track's artwork via the Android Palette API and renders an animated, crossfading radial gradient behind the hero cover art.
* 👉 **Swipe Gestures on Track Rows:** Swipe a song row right in the Library to instantly append it to the Up Next queue; swipe left to toggle Like.
* 📡 **Zero-Wasted-Data Stream Caching:** YouTube audio streams are written through a 500MB LRU disk cache (`AudioCacheManager`), so replaying a recently heard cloud track costs 0MB of data and works offline.
* 🔊 **Per-Track Volume Normalization:** `ReplayGainAudioProcessor` scales each track's samples inside the audio pipeline, using the file's own `REPLAYGAIN_TRACK_GAIN` tag where it has one and an on-device gated-loudness measurement (ITU-R BS.1770 / EBU R128, via `LoudnessAnalyzer`) where it does not. A peak-aware limiter keeps a boosted track from clipping. The old blanket `LoudnessEnhancer` boost is kept only for when the setting is off.
* 📺 **Google Cast:** Plays to Chromecast and other Cast devices, including *local* files -- a small LAN HTTP server makes registered files reachable by URL under unguessable tokens, since a Cast device cannot read the phone's storage. Cast availability is always probed through `CastAvailability`, because `CastContext.getSharedInstance` throws rather than returning null on a device without Play Services.
* ⏭️ **SponsorBlock:** Opt-in skipping of sponsor reads, intros and other non-music segments baked into YouTube uploads. Lookups use the hash-prefix endpoint, so the server never learns which track is playing.
* 📈 **ListenBrainz Scrobbling:** Submits listening history with a token the user generates on their own profile -- a user credential, not a developer one, which is why it is ListenBrainz and not Last.fm. Self-hosted compatible servers are configurable.
* 🏠 **Glance Home-Screen Widget:** Renders from a snapshot the playback service writes on every track and play-state change, so it never binds to the service just to draw.
* 🏷️ **In-Place Tag Editor:** Writes ID3/Vorbis/MP4 tags to the file, the Room row and MediaStore together via jaudiotagger, including the per-file consent request `targetSdk 37` requires for a MediaStore-backed file.
* ⚙️ **Centralized Settings Screen:** Appearance, playback, song recognition, listening history, Backup/Restore and an About section (version, commit, build time, device) in one screen reached from the navigation drawer. There is no API-key setting -- the app needs none.

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
│ • Room Migration Architectures     │ • TFLite & ONNX Model Integration  │
│ • On-Device AI Error Isolation     │ • Unit Testing & Refactoring       │
└────────────────────────────────────┴────────────────────────────────────┘
```

### 1. Anthropic Claude (System Architecture, Extraction Resilience & Core State)
* **Tiered Stream Extraction Strategy:** Engineered the Piped & Invidious fallback architecture behind direct NewPipeExtractor resolution, and the containment that keeps an unresolved track from cascading into a dead queue.
* **Media3 ExoPlayer Integration & Stutter-Free Seeking:** Designed state synchronization between `PlaybackService` and Jetpack Compose UI, decoupling slider drag state from ExoPlayer seek commands to eliminate position jitter during fast seeking.
* **Database Schema & Migrations:** Designed the Room migration chain (`MIGRATION_6_7` through `MIGRATION_18_19`) to ensure additive, zero-data-loss upgrades for statistics, AI embeddings, pending downloads, loudness, genres and per-playlist play counts -- with the destructive fallback scoped to the pre-release schemas so a future missing migration fails loudly instead of wiping a library.
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
* **Database & Storage:** Room v19 (KSP), Jetpack DataStore Preferences
* **Networking:** OkHttp 4, Retrofit 2, Moshi
* **On-Device AI:** TensorFlow Lite, ONNX Runtime Android, Google Guava (Coroutines)
* **Extraction:** NewPipeExtractor (pinned to a commit), Piped API, Invidious API, YouTube Music InnerTube
* **Also:** AndroidX Glance (widget), Media3 Cast + MediaRouter, jaudiotagger (Android fork), ML Kit on-device translation

---

## 📁 Repository Structure

```
TGMusicAI/
├── app/
│   └── src/main/java/com/example/tgmusicai/
│       ├── ai/             # On-device AI (YAMNet TFLite, MiniLM ONNX, WordPiece, PcmDecoder)
│       ├── alarm/          # AlarmScheduler, AlarmReceiver, AlarmActivity over keyguard
│       ├── data/
│       │   ├── google/     # YouTube Music playlist sync (InnerTube session)
│       │   ├── local/      # Room DB v19, DAOs, Entities, DataStore Preferences, BackupManager
│       │   ├── network/    # NetworkObserver
│       │   ├── repository/ # CoverArtScraper, LyricsRepository, MusicRepository, recommendations
│       │   ├── scrobble/   # ListenBrainz
│       │   ├── sponsorblock/
│       │   └── youtube/    # YouTubeExtractor, YouTubeMusicBrowser, CloudDownloadManager
│       ├── playback/       # PlaybackService (Media3), caching, ReplayGain, EQ, SleepTimer
│       ├── cast/           # Cast wiring + the LAN media server local files are cast through
│       ├── widget/         # Glance home-screen widget
│       └── ui/             # Jetpack Compose Screens, Components, Navigation, ViewModels
└── README.md
```

---

## 🚀 Building & Running

1. **Prerequisites:**
   * Android Studio Ladybug (2024.2.1) or newer
   * JDK 17 **or newer** (the emitted bytecode is pinned to 17; no JDK 17 *installation* is required)
   * Android SDK 35 or newer (Compile SDK 37)
2. **Setup:**
   * Clone the repository:
     ```bash
     git clone https://github.com/YOUR_USERNAME/TGMusicAI.git
     cd TGMusicAI
     ```
   * Open the project in Android Studio.
   * Sync Gradle (`Sync Project with Gradle Files`).
   * Build & Run on an Android device or emulator running **Android 7.0 (API level 24)** or higher.

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
