# Recommended Features for TGMusicAI

## Title & Purpose
This document serves as a comprehensive technical guidance document for Claude to implement the next phase of UI/UX, audio playback, and smart personalization enhancements for **TGMusicAI**. It details specifications, API usage, design parameters, and architectural guidance to ensure seamless integration into the existing codebase.

---

## 1. Detailed Feature Specifications

### A. Audio & Playback Features

1. **5-Band Equalizer & Bass Boost**
   - **APIs**: Android `android.media.audiofx.Equalizer` and `android.media.audiofx.Virtualizer` / `BassBoost`.
   - **Details**:
     - Connect to ExoPlayer's `audioSessionId` provided by `PlaybackService` / `MediaSession`.
     - 5 bands corresponding to Standard Frequency Bands (e.g., 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz).
     - Presets support: Normal, Classical, Dance, Flat, Folk, Heavy Metal, Hip Hop, Jazz, Pop, Rock, and Custom.
     - Bass Boost slider (range 0 to 1000 millibels / strength percentage).
     - Persist enabled state, band levels, preset selections, and bass strength in `AppPreferences` DataStore.

2. **Gentle Sleep Timer Volume Fade-Out**
   - **APIs**: ExoPlayer `setVolume()` / Kotlin Coroutines `ValueAnimator` or custom timer loop.
   - **Details**:
     - Allow user to set sleep timer durations (e.g., 15m, 30m, 45m, 60m, or end of current track).
     - When remaining time reaches 30 seconds, trigger a smooth volume linear fade-out from current user volume down to `0.0f`.
     - Upon reaching 0 seconds, pause playback, reset volume back to normal, and stop `PlaybackService` foreground execution if configured.

3. **Playback Speed & Pitch Control**
   - **APIs**: ExoPlayer `PlaybackParameters(speed, pitch)` via `player.setPlaybackParameters()`.
   - **Details**:
     - Speed range: `0.5x` to `2.0x` in steps of `0.05x` or standard preset chips (0.75x, 1.0x, 1.25x, 1.5x, 2.0x).
     - Toggle for pitch correction / preserve pitch mode (`pitch = 1.0f` vs `pitch = speed`).
     - Persistent or per-session settings toggle in settings.

4. **Crossfade & Gapless Playback Toggle**
   - **APIs**: ExoPlayer dual player instance blending or custom audio processor / volume ramp transition near track end.
   - **Details**:
     - Toggle for Gapless Playback mode (eliminating silent gaps between consecutive tracks using ExoPlayer's native gapless metadata support).
     - Crossfade duration slider (0s to 12s).
     - Automated smooth fade-out of current track and fade-in of next track in queue during transition window.

---

### B. UI/UX & Interactive Features

1. **Interactive "Up Next" Queue Drawer**
   - **UI Components**: Modal bottom sheet or slide-over drawer in Jetpack Compose (`ModalBottomSheet` or `BottomSheetScaffold`).
   - **Details**:
     - Reorderable list using `LazyColumn` with drag-and-drop handles (`Modifier.reorderable` or Compose drag gestures).
     - Swipe-to-dismiss gesture (`SwipeToDismissBox`) to remove individual tracks from active session queue.
     - Displays "Now Playing", "Up Next", and "Autoplay / History" sub-headers.
     - Direct tap to skip immediately to selected queue item.

2. **Android Home Screen Widgets**
   - **Framework**: Jetpack Glance (`androidx.glance`) or standard `RemoteViews` + `AppWidgetProvider`.
   - **Widget Types**:
     - **4x1 Compact Player**: Album art preview, Track Title, Artist, Play/Pause toggle, Skip Next button.
     - **4x2 Speed Dial & Controls**: Album art background/thumbnail, full playback controls (Previous, Play/Pause, Next, Like/Favorite), and 3 quick-launch speed dial buttons for favorite playlists or generated mixes.
   - **State Handling**: Synchronize widget state via state flow listener / broadcast receiver linked to `MediaSession` and `PlaybackService`.

3. **Album Art Swipe & Double-Tap Gestures on `NowPlayingScreen`**
   - **UI Component**: Custom `Modifier.pointerInput` / `DetectTapGestures` and drag physics on `NowPlayingScreen` artwork display.
   - **Gestures**:
     - **Swipe Left / Right**: Skip to Next track / Previous track with animated artwork slide transition.
     - **Double-Tap**: Toggle "Like / Favorite" status with heart burst particle / scale animation overlay.
     - **Swipe Down**: Minimize `NowPlayingScreen` into bottom mini-player bar with smooth shared element / offset transition.

---

### C. Smart Personalization & Library

1. **"Start Radio" Mode**
   - **Logic**: Dynamic queue generator algorithm.
   - **Details**:
     - Triggered from any track, artist, producer, or genre context menu.
     - Queries local media database for matching / similar attributes (same producer, overlapping genre tags, same artist, or acoustic similarity metrics).
     - Generates a dynamic 20-song queue shuffled with weighted priority to under-played tracks.
     - Autoplay toggle: Seamlessly appends additional similar tracks as queue reaches end.

2. **Manual Tag & Custom Cover Picker**
   - **Components**: Tag Editor dialog / sheet and Android `ActivityResultContracts.PickVisualMedia` / `GetContent`.
   - **Details**:
     - Editable fields: Title, Artist, Album, Producer, Genre, Track Number, Year.
     - Custom Cover Picker: Choose custom image from device photo picker/gallery.
     - Save metadata updates into local app Room database layer and optionally persist custom cover cached file path or write ID3 tags where permissions permit.

3. **Visual Analytics on Stats Tab**
   - **UI Components**: Jetpack Compose `Canvas` custom charts or M3 Expressive layout cards.
   - **Metrics**:
     - **Listening Time by Day of Week**: Bar chart showing total playback minutes from Monday through Sunday.
     - **Top Producers & Artists**: Horizontal breakdown bar chart / pie chart ranking top 5 producers and artists based on playback history.
     - **Top Genres Distribution**: Visual chip or donut/bar breakdown.
   - **Filters**: Time range filter chips (7 Days, 30 Days, All Time).

---

## 2. Architectural Implementation Tips for Claude

1. **DataStore Preferences (`AppPreferences`)**:
   - Location: Extend the existing `DataStore<Preferences>` repository (`AppPreferences` or `UserPreferences`).
   - Define strongly typed `PreferencesKeys` for all new settings:
     - `EQUALIZER_ENABLED` (`Boolean`), `EQUALIZER_PRESET` (`Int`), `EQUALIZER_BANDS` (`String` / JSON array), `BASS_BOOST_STRENGTH` (`Int`).
     - `SLEEP_TIMER_FADE_OUT` (`Boolean`), `CROSSFADE_DURATION_SEC` (`Int`), `GAPLESS_ENABLED` (`Boolean`).
     - `PLAYBACK_SPEED` (`Float`), `PRESERVE_PITCH` (`Boolean`).
   - Collect preferences via `Flow` in ViewModels and convert to `StateFlow` using `stateIn(viewModelScope, ...)`.

2. **Audio Effects Integration in `PlaybackService`**:
   - Obtain `audioSessionId` from ExoPlayer (`player.audioSessionId`).
   - Instantiation: Instantiate `Equalizer(0, audioSessionId)` and `BassBoost(0, audioSessionId)` within `PlaybackService`.
   - Re-attach effect instances whenever audio session ID changes or player resets.
   - Properly release audio effect objects in `PlaybackService.onDestroy()` to prevent system audio framework leaks.

3. **State Isolation in Jetpack Compose**:
   - Keep UI components decoupled from service implementations: exposing state via `StateFlow` / UI State data classes in ViewModels.
   - Pass immutable state holders and event lambda callbacks to screen composables (e.g., `NowPlayingScreen(uiState: NowPlayingUiState, onEvent: (NowPlayingEvent) -> Unit)`).
   - Use `LaunchedEffect` or `rememberUpdatedState` for gesture animations and dynamic volume fade transitions.
