# TGMusicAI Codebase Audit Report

## 1. Critical Issues

### Database Integrity: Missing `onDelete = CASCADE` (data/)
* **Issue**: `PlaylistSongCrossRef` lacks foreign key constraints with `onDelete = CASCADE`. It only defines primary keys (`playlistId`, `songId`) and indices.
* **Why it occurs**: When a `Song` or `Playlist` is deleted from the database, the corresponding relationship records in `playlist_song_cross_ref` are left orphaned. Over time, this leads to database bloat. Furthermore, if a query attempts to resolve a playlist's songs using these orphaned IDs, it may return inconsistent data or crash depending on the DAO query structure.
* **How to fix**: Add `@ForeignKey` annotations to the `@Entity` definition in `PlaylistSongCrossRef.kt` linking `playlistId` and `songId` to their respective tables with `onDelete = ForeignKey.CASCADE`.

### Cloud Downloader: Process Death Vulnerability (cloud/)
* **Issue**: `CloudDownloadManager` executes downloads using a standard `CoroutineScope(Dispatchers.IO)`. It does not use `WorkManager` or a Foreground Service.
* **Why it occurs**: Android aggressively kills background processes to save memory and battery. If the user starts a download (e.g., for a large playlist) and then swipes the app away from the recent apps screen, the OS will immediately terminate the coroutines. While `pendingDownloadDao` exists to resume downloads upon the next app launch, the app cannot download anything in the background when minimized for long periods or closed.
* **How to fix**: Migrate the downloading execution logic to a `WorkManager` `CoroutineWorker` for guaranteed background execution, or utilize a Foreground Service with a notification to ensure the OS keeps the download process alive even if the user exits the UI.

## 2. Moderate Issues

### UI State Collection: Background Resource Leaks (ui/)
* **Issue**: Across the entire UI layer (`HomeScreen`, `LibraryScreen`, `MainScreen`, `YouTubeScreen`, etc.), StateFlows are collected using `.collectAsState()`.
* **Why it occurs**: `collectAsState()` does not respect the Android lifecycle. It keeps the upstream flow active and continues collecting emissions even when the app is in the background (e.g., when the user goes to the home screen). For flows observing database changes or executing network requests, this wastes CPU and battery.
* **How to fix**: Refactor all usages of `.collectAsState()` in Compose UI files to use `.collectAsStateWithLifecycle()` from the `androidx.lifecycle.compose` artifact. This automatically pauses collection when the UI is not visible.

### Concurrency Risk: Force Unwrapping Delegated Properties (ui/)
* **Issue**: In `NowPlayingScreen.kt`, there is a potential race condition with `remainingSleepTimeMs`. The code checks `if (remainingSleepTimeMs != null)` and then force unwraps `remainingSleepTimeMs!!` on the next line.
* **Why it occurs**: Because `remainingSleepTimeMs` is delegated (`by playerViewModel.remainingSleepTimeMs.collectAsState()`), evaluating it acts as a getter. If the underlying `StateFlow` emits `null` exactly between the null-check and the force unwrap, the app will crash with a `NullPointerException`. 
* **How to fix**: Use the safe call or scope function: `remainingSleepTimeMs?.let { remainingSec -> ... }` instead of separate null checks and `!!`.

## 3. Minor / Architecture Issues

### Media Playback: Service Release and Android 14 Requirements (playback/)
* **Issue**: The `PlaybackService.onDestroy()` handles releasing both the `ExoPlayer` and `MediaLibrarySession` correctly. The `AndroidManifest.xml` correctly declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
* **Why it occurs**: It's crucial for Media3 to cleanly tear down to avoid leaking audio resources and session callbacks. 
* **How to fix**: No immediate changes required here. The implementation adheres to Media3 best practices for `onDestroy` and Manifest configurations for Android 14, although monitoring for `SecurityException` during background starts is always recommended for newer OS versions.

### Error Handling: YouTube Extractor JSON Parsing
* **Issue**: `YouTubeExtractor.kt` parses deeply nested, unpredictable JSON from Piped/Invidious APIs.
* **Why it occurs**: Relying on external, unofficial APIs frequently leads to `NullPointerException`s or `JSONException`s if keys are missing.
* **How to fix**: The current implementation is actually quite robust. It safely utilizes `.optString()`, `.optJSONObject()`, and `.optJSONArray()` instead of hard assertions like `!!` or `.getString()`. No immediate refactoring needed, but keep this pattern intact during future updates to the extraction logic.
