# TGMusicAI — Claude Master Need-To-Know Guide

---

## Executive Overview

This consolidated master document is the central, definitive guide for Claude (and future engineering instances) working on the **TGMusicAI** codebase. It brings together architectural resolutions, audit findings, intentionality gaps, UI verification status, future roadmap specifications, and Android Auto Media3 integration guidelines into a single actionable reference.

### Core Architecture & System State Overview
- **Application Architecture**: Modern Android app built with **Jetpack Compose (M3 Expressive)**, **Media3 ExoPlayer**, **Room Persistence**, **Kotlin Coroutines / Flow**, and **AndroidX Navigation 3**.
- **Media Engine**: Powered by `PlaybackService` extending `MediaLibraryService`, managed by `MediaControllerManager`, and exposed to UI via `PlayerViewModel`.
- **Database & Offline**: Room DB with entities (`Song`, `Playlist`, `PlaylistSongCrossRef`, `Alarm`, `PlayStat`). Clean separation of local vs. cloud-backed tracks with smart deduplication.
- **Alarm Clock System**: Subsystem capable of triggering high-priority full-screen intent lock-screen activities with CPU wakelock handoff and ExoPlayer audio fallback.
- **Android Auto Engine**: Media3 `MediaLibraryService` tree for head unit navigation and voice integration.

---

## Section 1: Alarm Lock-Screen Fixes

*Source: `ALARM_FIX_REPORT_FOR_CLAUDE.md`*

### Background & Issue Solved
On Android 10+ (API 29+), starting an Activity directly from a background `BroadcastReceiver` is restricted by the OS and will fail silently if the app is minimized or the screen is locked. Additionally, CPU sleep races between `AlarmReceiver` and `AlarmActivity` caused alarms to fail to wake up the display.

### Architectural Fixes Implemented

1. **CPU Wakelock Handoff (`AlarmWakeLock`)**
   - When `AlarmManager` fires, `AlarmReceiver.onReceive()` acquires a static `PowerManager.PARTIAL_WAKE_LOCK` with a 10-minute safety timeout (`AlarmWakeLock`).
   - This ensures the CPU remains active while querying Room DB for tone URIs and preparing the notification.
   - **Handoff Sequence**: The lock is explicitly released inside `AlarmActivity.onCreate()`, smoothly transferring window lifecycle responsibility to `WindowManager` flags.

2. **Full-Screen Intent Notification (`AlarmReceiver.kt`)**
   - Uses a High-Priority `NotificationChannel` (`IMPORTANCE_HIGH`) targeting `CATEGORY_ALARM`.
   - Wraps `AlarmActivity` intent in a `PendingIntent` attached via `.setFullScreenIntent(pendingIntent, true)`.
   - Bypasses background activity launch restrictions on Android 10+ natively according to Android guidelines.

3. **Manifest Permissions**
   - `android.permission.USE_FULL_SCREEN_INTENT`: Grants rights to launch full-screen lock screen activity.
   - `android.permission.WAKE_LOCK`: Required for `PowerManager.WakeLock`.
   - `android.permission.SCHEDULE_EXACT_ALARM`: Required for exact wakeups.

4. **Lock-Screen Activity Setup (`AlarmActivity.kt`)**
   - `onCreate()` clears the status bar notification ID upon launch.
   - Sets window flags: `setShowWhenLocked(true)` and `setTurnScreenOn(true)` (with legacy fallback flags for older Android versions).
   - Configures `EdgeToEdge` display to render alarm controls over keyguards seamlessly.

---

## Section 2: Deep Architectural Audit & Edge Cases

*Source: `CLAUDE_AUDIT_REPORT.md`*

### 1. Database Integrity: Cascading Deletes in `PlaylistSongCrossRef`
- **Current Defect**: `PlaylistSongCrossRef` defines primary keys (`playlistId`, `songId`) and indices, but lacks foreign key relations with `onDelete = CASCADE`.
- **Risk**: Deleting a `Song` or `Playlist` leaves orphaned relationship records in `playlist_song_cross_ref`, bloating the database and potentially causing query inconsistencies.
- **Required Refactor**: Update `@Entity` annotations in `PlaylistSongCrossRef.kt`:
  ```kotlin
  @Entity(
      tableName = "playlist_song_cross_ref",
      primaryKeys = ["playlistId", "songId"],
      foreignKeys = [
          ForeignKey(
              entity = Playlist::class,
              parentColumns = ["id"],
              childColumns = ["playlistId"],
              onDelete = ForeignKey.CASCADE
          ),
          ForeignKey(
              entity = Song::class,
              parentColumns = ["id"],
              childColumns = ["songId"],
              onDelete = ForeignKey.CASCADE
          )
      ],
      indices = [Index("playlistId"), Index("songId")]
  )
  data class PlaylistSongCrossRef(
      val playlistId: Long,
      val songId: Long
  )
  ```

### 2. Cloud Downloader Process Death Resilience
- **Current Defect**: `CloudDownloadManager` executes downloads using standard `CoroutineScope(Dispatchers.IO)`.
- **Risk**: Swiping the app away from recent apps causes the OS to terminate background coroutines. Large playlist downloads stall until the app is reopened.
- **Required Refactor**: Migrate download task execution to AndroidX `WorkManager` using a `CoroutineWorker` or run `CloudDownloadManager` in a Foreground Service with a persistent progress notification.

### 3. UI State Lifecycle Leak Protection
- **Current Defect**: Standard `.collectAsState()` is used across Compose UI screens (`HomeScreen`, `LibraryScreen`, `NowPlayingScreen`, etc.).
- **Risk**: `.collectAsState()` does not pause flow collection when the activity goes into the background, causing continuous DB/network flow emissions and draining battery.
- **Required Refactor**: Replace all usages of `.collectAsState()` with `.collectAsStateWithLifecycle()` from `androidx.lifecycle.compose`.

### 4. Null Pointer Force-Unwrap Risk in `NowPlayingScreen`
- **Current Defect**: In `NowPlayingScreen.kt`, code checks `if (remainingSleepTimeMs != null)` and then evaluates `remainingSleepTimeMs!!`.
- **Risk**: Since `remainingSleepTimeMs` is a delegated state property (`by playerViewModel.remainingSleepTimeMs.collectAsStateWithLifecycle()`), its getter is invoked on every access. If the StateFlow emits `null` between the check and the unwrap, the app crashes with NPE.
- **Required Refactor**:
  ```kotlin
  // Replace: if (remainingSleepTimeMs != null) { ... remainingSleepTimeMs!! ... }
  // With safe scoping:
  remainingSleepTimeMs?.let { timeMs ->
      Text(text = formatTime(timeMs))
  }
  ```

### 5. OkHttp Socket Timeout Thresholds on Slow Cellular Networks
- On 3G/slow cellular connections, OkHttp's default 10-second timeout can cause `SocketTimeoutException` during InnerTube/Cobalt API requests. Configure explicit timeouts (`connectTimeout(15, SECONDS)`, `readTimeout(20, SECONDS)`) on the OkHttp client in `YouTubeExtractor` and `CloudDownloadManager`.

### 6. Coil Image Memory Downsampling for 1000x1000 Album Covers
- The cover art scraper fetches high-res 1000x1000 iTunes images. On budget devices with low RAM (e.g. 2GB/3GB RAM), displaying many 1000x1000 bitmaps in a fast-scrolling `LazyColumn` can trigger garbage collection pauses. Always specify Coil `.size()` or `.scale(Scale.FILL)` in `AsyncImage` to downsample images in memory to match target thumbnail dimensions.

### 7. Room DB Column Default Values for Migration Safety (v1-v8)
- When adding new columns like `description`, `is_smart`, `pending_download`, ensure Room `@ColumnInfo` default values are explicitly provided in Kotlin data class constructors so legacy database upgrades never throw `SQLiteException` on null columns.

### 8. Transient Audio Focus Loss Recovery in `PlaybackService`
- ExoPlayer handles transient audio focus loss (e.g. GPS navigation prompts). However, if an incoming phone call lasts several minutes, ExoPlayer pauses playback. `PlaybackService` should re-sync `MediaSession` playback state with `MediaControllerManager` upon focus regain to ensure UI play/pause state remains 100% synchronized.

### 9. Android 14 (API 34) Foreground Service 10-Second Notification Rule
- Android 14 enforces that services declared with `foregroundServiceType="mediaPlayback"` must call `startForeground()` with a valid `Notification` within 10 seconds of `onStartCommand()`. `PlaybackService` complies via Media3's `MediaNotificationProvider`, but any custom service initialization must occur *after* `super.onCreate()`.

---

## Section 3: Intentionality & Edge-Case Safety

*Source: `INTENTIONALITY_AUDIT_REPORT.md`*

### 1. Offline Mode Enforcement
- **Gap Identified**: The app detects physical network loss via `NetworkObserver`, but lacks an active user toggle to enforce an "Offline Mode". When connected to metered or weak networks, tapping cloud tracks attempts live network streaming.
- **Fix Requirement**: Introduce an `offlineModeEnabled` setting in `AppPreferences`. In `PlaybackService` and `MediaControllerManager`, intercept play requests for non-downloaded items (`isDownloaded == false` or remote URI) when Offline Mode is enabled, showing a user toast: `"Offline Mode Enabled — Cannot stream remote track"`.

### 2. BackupManager Scalability for 5GB+ Libraries
- **Gap Identified**: `BackupManager` streams audio files safely in 8KB chunks into `.tgmusic` zip archives. However, it serializes and deserializes the metadata JSON manifest as a single in-memory `JSONObject` / `String`.
- **Fix Requirement**: Upgrade metadata serialization/deserialization to use streaming `JsonWriter` and `JsonReader` to prevent memory pressure when importing/exporting multi-thousand song databases.

### 3. YouTube Extractor Edge Cases
- **Gap Identified**: YouTube extractor proxies (Piped / Invidious) fail when processing age-restricted or premium tracks that require authentication.
- **Fix Requirement**: Implement defensive fallback handling when stream URIs return HTTP 403 / 401, surfacing a clear user-facing error message: `"This track requires YouTube login or is age-restricted"`.

---

## Section 4: UI & Feature Purpose Verification

*Source: `FEATURE_PURPOSE_AUDIT_REPORT.md`*

### Verified Features & Operational Matrix

| UI Component / Feature | Operational Purpose | Backing Implementation | Status |
| :--- | :--- | :--- | :--- |
| **Bottom Navigation Bar** | Primary 6-destination navigation | Jetpack Navigation 3 (`NavDisplay`) with routes: `Home`, `YouTube`, `Library`, `Playlists`, `Alarms`, `Stats`. | ✅ Fully Operational |
| **Drawer Navigation** | Quick access, smart playlists & new playlist dialog | `HomeScreen` top bar trigger; manages shortcuts for Speed Dial, Top 50, Recently Added, Import & Downloads. | ✅ Fully Operational |
| **MiniPlayer & NowPlaying** | Continuous audio control & expanded view | `MediaControllerManager` state binding; handles Play/Pause, Seek, Skip Next/Prev, Shuffle, Loop. | ✅ Fully Operational |
| **3-State Loop Button** | Repeat modes | Cycles `OFF` -> `ALL` -> `ONE` -> `OFF` directly updating `ExoPlayer.repeatMode`. | ✅ Fully Operational |
| **Interactive Seek Slider** | Track position control | `dragPosition` local state prevents gesture jitter; triggers `playerViewModel.seekTo()` on release. | ✅ Fully Operational |
| **Synced Lyrics / Transcript** | Interactive lyric view | Tap timestamped lines to seek track position; includes refetch and scraper trigger buttons. | ✅ Fully Operational |
| **Liked Music & Thumbs Up** | Favorite tracks toggle | `playerViewModel.toggleLikeCurrentSong()`; auto-persists transient cloud tracks before saving liked state. | ✅ Fully Operational |
| **Soft Theme Switcher** | Visual customisation | 4 palettes (`YT_DARK`, `PASTEL_MIDNIGHT`, `WARM_AMBER`, `NORDIC_SLATE`); persists to DataStore, updates M3 color tokens dynamically. | ✅ Fully Operational |
| **Alarms Subsystem** | Music alarm clock | `AlarmDao` -> `AlarmScheduler` -> `AlarmReceiver` -> `AlarmActivity` with ExoPlayer fallback tone. | ✅ Fully Operational |
| **Library Search & Bulk Ops** | Song filtering & management | Reactive `combine` flow on title/artist/album/producer; multi-select bulk delete/playlist add. | ✅ Fully Operational |

---

## Section 5: Recommended Feature Roadmap

*Source: `RECOMMENDED_FEATURES_FOR_CLAUDE.md`*

### 1. Audio & Playback Enhancements
- **5-Band Equalizer & Bass Boost**:
  - Connect `android.media.audiofx.Equalizer` and `BassBoost` to ExoPlayer's `audioSessionId` in `PlaybackService`.
  - Bands: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz. Presets: Normal, Rock, Pop, Jazz, Heavy Metal, Custom.
  - Persist settings in `AppPreferences`.
- **Gentle Sleep Timer Volume Fade-Out**:
  - When remaining sleep timer time reaches 30 seconds, execute a smooth linear volume ramp down to `0.0f` before pausing playback.
- **Playback Speed & Pitch Control**:
  - Expose ExoPlayer `PlaybackParameters(speed, pitch)` controls (0.5x to 2.0x).
- **Crossfade & Gapless Playback**:
  - Enable native gapless metadata processing; add optional 0s-12s audio blending between consecutive tracks.

### 2. Interactive UI Features
- **"Up Next" Queue Drawer**:
  - `ModalBottomSheet` displaying active queue with reorderable handles (`Modifier.reorderable`) and swipe-to-dismiss deletion.
- **Android Home Screen Widgets**:
  - Jetpack Glance widgets (4x1 Compact Controls & 4x2 Speed Dial + Artwork Controls).
- **Album Art Gestures in `NowPlayingScreen`**:
  - Horizontal swipe to skip tracks, double-tap to Heart/Like, vertical drag down to minimize screen.

### 3. Smart Personalization
- **"Start Radio" Mode**:
  - Contextual queue generator seeding 20 related local/cloud tracks based on producer, artist, or genre overlaps.
- **Manual Tag & Custom Cover Editor**:
  - Edit ID3 fields (Title, Artist, Album, Producer, Genre) and pick custom cover art via `ActivityResultContracts.PickVisualMedia`.
- **Visual Analytics on Stats Tab**:
  - Custom Jetpack Compose `Canvas` charts for daily listening time, top artists/producers, and genre distribution.

---

## Section 6: Android Auto Elite Enhancements (NEW)

To make TGMusicAI a tier-1 Android Automotive & Android Auto application using AndroidX Media3, the following 4 features must be implemented in `PlaybackService` and its `MediaLibrarySession.Callback`.

---

### 1. Google Assistant Voice Search (`onSearch`)

Native integration with Android Auto voice control allows drivers to say: *"Hey Google, play Drake on TGMusic"* or *"Play my Liked Music on TGMusic"*.

#### Implementation in `PlaybackService.kt`:
Override `onSearch` in `MediaLibrarySession.Callback`:

```kotlin
override fun onSearch(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: MediaLibrarySession.LibraryParams?
): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    return CoroutineScope(Dispatchers.IO).future {
        val searchResults = repository.searchSongsSync(query)
        val mediaItems = searchResults.map { song ->
            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(song.mediaUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.coverArtUri?.let { Uri.parse(it) })
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build()
                )
                .build()
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(mediaItems), params)
    }
}
```

---

### 2. Custom Playback Buttons on Car Screen (`setCustomLayout`)

Head units allow custom controls (such as Thumbs Up / Like and 3-State Loop) directly on the automotive playback control screen.

#### Defining Commands & Layout:
```kotlin
companion object {
    const val ACTION_TOGGLE_LIKE = "com.tgmusicai.ACTION_TOGGLE_LIKE"
    const val ACTION_CYCLE_REPEAT = "com.tgmusicai.ACTION_CYCLE_REPEAT"
}

private fun setupCustomCarLayout(session: MediaSession, isLiked: Boolean, repeatMode: Int) {
    val likeIcon = if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
    val likeButton = CommandButton.Builder()
        .setDisplayName("Like")
        .setIconResId(likeIcon)
        .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
        .build()

    val repeatIcon = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
        Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_all
        else -> R.drawable.ic_repeat_off
    }
    val repeatButton = CommandButton.Builder()
        .setDisplayName("Repeat Mode")
        .setIconResId(repeatIcon)
        .setSessionCommand(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
        .build()

    session.setCustomLayout(listOf(likeButton, repeatButton))
}
```

#### Handling Custom Commands in Callback:
```kotlin
override fun onCustomCommand(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    customCommand: SessionCommand,
    args: Bundle
): ListenableFuture<SessionResult> {
    when (customCommand.customAction) {
        ACTION_TOGGLE_LIKE -> {
            val currentSong = currentPlayingSong Flow.value
            currentSong?.let { song ->
                repository.toggleLikeSong(song.id)
                // Refresh custom layout icons on car display
                setupCustomCarLayout(session, !song.isLiked, player.repeatMode)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        ACTION_CYCLE_REPEAT -> {
            val nextMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            player.repeatMode = nextMode
            setupCustomCarLayout(session, currentPlayingSong.value?.isLiked == true, nextMode)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
    return super.onCustomCommand(session, controller, customCommand, args)
}
```

---

### 3. Rich Automotive Media Tree (`onGetChildren`)

Car displays browse category folders structured in an intuitive tree. Expand `onGetChildren` to include specialized driver-centric nodes:

#### Media Tree Node Constants:
- `MEDIA_ID_ROOT`: Root node
- `MEDIA_ID_DOWNLOADED_ONLY`: Downloaded music folder (critical for signal dead zones while driving)
- `MEDIA_ID_LIKED_MUSIC`: Quick access to favorite tracks
- `MEDIA_ID_LISTEN_AGAIN`: Speed Dial / Top 50 Most Played
- `MEDIA_ID_PRODUCERS`: Browse songs by producer

#### Implementation Structure:
```kotlin
override fun onGetChildren(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?
): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    return CoroutineScope(Dispatchers.IO).future {
        val children = when (parentId) {
            MEDIA_ID_ROOT -> listOf(
                createBrowsableMediaItem(MEDIA_ID_DOWNLOADED_ONLY, "Downloaded Only", R.drawable.ic_download_done, isGrid = false),
                createBrowsableMediaItem(MEDIA_ID_LIKED_MUSIC, "Liked Music", R.drawable.ic_heart_filled, isGrid = true),
                createBrowsableMediaItem(MEDIA_ID_LISTEN_AGAIN, "Listen Again", R.drawable.ic_history, isGrid = true),
                createBrowsableMediaItem(MEDIA_ID_PRODUCERS, "Producers", R.drawable.ic_producer, isGrid = false)
            )
            MEDIA_ID_DOWNLOADED_ONLY -> repository.getDownloadedSongsSync().map { it.toPlayableMediaItem() }
            MEDIA_ID_LIKED_MUSIC -> repository.getLikedSongsSync().map { it.toPlayableMediaItem() }
            MEDIA_ID_LISTEN_AGAIN -> repository.getTopPlayedSongsSync(50).map { it.toPlayableMediaItem() }
            else -> emptyList()
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
    }
}
```

---

### 4. Car Content Style Hints (`CONTENT_STYLE` Extras)

Android Auto uses style hints attached to `MediaDescription` extras to render browsable folders as visual grids (for albums/playlists) or clean lists (for individual track rows).

#### Constants:
```kotlin
import androidx.media.utils.MediaConstants

// Style Hint Keys
const val CONTENT_STYLE_KEY = MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE
const val PLAYABLE_STYLE_KEY = MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE

// Style Hint Values
const val STYLE_GRID = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID
const val STYLE_LIST = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST
```

#### Helper to Attach Style Hints:
```kotlin
private fun createBrowsableMediaItem(
    id: String,
    title: String,
    iconResId: Int,
    isGrid: Boolean
): MediaItem {
    val extras = Bundle().apply {
        putInt(CONTENT_STYLE_KEY, if (isGrid) STYLE_GRID else STYLE_LIST)
        putInt(PLAYABLE_STYLE_KEY, STYLE_LIST)
    }

    return MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setExtras(extras)
                .build()
        )
        .build()
}
```

---

## Conclusion & Action Checklist for Engineers

When initiating new development tasks on TGMusicAI, consult this checklist:
1. [ ] **Database**: Verify `PlaylistSongCrossRef` cascade rules before adding new playlist features.
2. [ ] **Background Tasks**: Ensure new long-running network downloads use `WorkManager` rather than raw coroutine scopes.
3. [ ] **UI State**: Always use `collectAsStateWithLifecycle()` in Jetpack Compose screens.
4. [ ] **Alarms**: Preserve full-screen intent notifications and `AlarmWakeLock` CPU handoff logic when touching alarm activities.
5. [ ] **Android Auto**: Test custom head unit layouts (`setCustomLayout`), voice search (`onSearch`), and content style hints whenever updating `PlaybackService`.
