# Where the UI Is Made — TGMusicAI

TGMusicAI's UI is 100% **Jetpack Compose** (no XML layouts, except system-required resource files
under `app/src/main/res/`). All Compose UI code lives under:

```
app/src/main/java/com/example/tgmusicai/ui/
```

with two extra UI-bearing files outside that folder: `MainActivity.kt` (root) and
`alarm/AlarmActivity.kt` (the full-screen alarm-ringing UI, a separate Activity).

If you want to **change how something looks or behaves on screen**, this doc tells you which
folder/file to open. For what the non-UI code underneath does, see `CODE_MAP.md`.

---

## 1. The shape of the UI layer

```
ui/
├── screens/       <- one file per full app screen (what you navigate between)
├── components/    <- reusable widgets/dialogs used by multiple screens
├── viewmodel/     <- state + business logic behind each screen (MVVM "VM")
├── navigation/    <- NavRoutes.kt: the list of screens and how they're keyed
├── onboarding/    <- first-run permissions screen
├── theme/         <- colors, typography, the 4 selectable app themes
└── util/          <- FormatUtils.kt: shared display-formatting helpers (durations, timestamps, file sizes)
```

**MVVM pattern used throughout:** a screen Composable in `ui/screens/` reads state from (and
calls functions on) a matching class in `ui/viewmodel/`. The ViewModel never touches Compose;
the screen never touches a DAO/repository directly. If a bug is "wrong data on screen," check the
ViewModel first; if it's "looks/lays out wrong," it's purely in the screen/component file.

---

## 2. Entry points — how the UI boots up

- **`MainActivity.kt`** (app root) — builds every singleton dependency (database, repositories,
  managers) by hand (no DI framework), then calls `setContent { MainScreen(...) }`. This is where
  you'd add a new top-level dependency if a screen/ViewModel needs one.
- **`ui/onboarding/OnboardingScreen.kt`** — shown once, before `MainScreen`, to request runtime
  permissions (media, notifications, battery-optimization exemption). Gate lives in
  `AppPreferences.onboardingCompletedFlow`.
- **`ui/screens/MainScreen.kt`** — the actual app shell after onboarding: hosts the bottom
  navigation, the nav-graph switch (which `Screen` from `NavRoutes.kt` is currently shown), the
  persistent `MiniPlayer`, and the side drawer (playlists shortcut list — see
  `DrawerPlaylistItem` in this same file).
- **`alarm/AlarmActivity.kt`** — a *separate* Activity+Compose tree, launched by `AlarmReceiver`
  when an alarm fires. Not part of the `MainScreen` nav graph at all.

---

## 3. `ui/screens/` — one file per full screen

| File | Screen | Backing ViewModel |
|---|---|---|
| `HomeScreen.kt` | "Speed Dial" home tab: pinned items, Listen Again, Most Played, Smart Playlists. Largest screen file — also defines `SectionTitle`, `PinnedPlaylistCard`, `PinnedSongCard`, `SongCardItem`, `SmartPlaylistCard` composables used only here. | `HomeViewModel` |
| `LibraryScreen.kt` | Full local song library list/grid, search, bulk-select, AI analyze/scrape actions. | `LibraryViewModel` |
| `PlaylistsScreen.kt` | Grid of all playlists (user + smart). | `PlaylistViewModel` |
| `PlaylistDetailScreen.kt` | Songs inside one playlist; reorder/remove/download. | `PlaylistViewModel` |
| `YouTubeScreen.kt` | YouTube cloud search + stream/download results list. | `YouTubeViewModel` |
| `DownloadsScreen.kt` | Active/queued download progress list. | `YouTubeViewModel` (via `CloudDownloadManager` state) |
| `GoogleSyncScreen.kt` | Google sign-in + pick which YouTube playlists to sync locally. | `GoogleSyncViewModel` |
| `NowPlayingScreen.kt` | Full-screen "now playing" player (art, lyrics, queue button, effects). | `PlayerViewModel` |
| `AlarmsScreen.kt` | List/create/edit/delete alarms (in-app, not the ringing screen). | `AlarmViewModel` |
| `StatsScreen.kt` | Listening-time charts, top artists/producers, storage breakdown. Biggest file in `ui/`; many private composables for individual chart/card types. | `StatsViewModel` |
| `MainScreen.kt` | App shell — see §2 above. | — (owns/hosts the others) |

## 4. `ui/components/` — reusable pieces used across screens

| File | What it is |
|---|---|
| `MiniPlayer.kt` | The persistent mini player bar pinned above the bottom nav; tap to expand into `NowPlayingScreen`. |
| `QueueSheet.kt` | Bottom sheet showing the current play queue with drag-to-reorder. |
| `SongItem.kt` | Single-row song list item (`SongItem`) and a grid-cell variant (`SongGridItem`) — used by Home/Library/Playlist screens. |
| `PlaylistCard.kt` | Grid card for one playlist (art collage, name, pin/delete affordances). |
| `AlarmItem.kt` | One alarm row on `AlarmsScreen` (time, repeat days, enable switch). |
| `AddEditAlarmDialog.kt` | Create/edit-alarm modal (time picker, tone type, repeat days, snooze). |
| `AddToPlaylistDialog.kt` | "Add song to playlist" picker modal. |
| `CreatePlaylistDialog.kt` | "New playlist" name+description modal. |
| `SongPickerDialog.kt` | Generic searchable song-picker modal (e.g. picking an alarm tone). |

## 5. `ui/viewmodel/` — state & logic behind each screen

Each ViewModel exposes `StateFlow`s the matching screen collects with `collectAsState()`, and
plain functions the screen calls on user actions (button clicks, etc). None of them import
anything from `androidx.compose.*` — that's the dividing line if you're unsure whether a change
belongs in the screen or the ViewModel.

| ViewModel | Backs | Depends on |
|---|---|---|
| `HomeViewModel` | HomeScreen | `MusicRepository`, `NetworkObserver` |
| `LibraryViewModel` | LibraryScreen | `MusicRepository`, `LyricsRepository`, `CoverArtScraper`, `AiFeatureManager` |
| `PlaylistViewModel` | PlaylistsScreen, PlaylistDetailScreen | `MusicRepository` |
| `YouTubeViewModel` | YouTubeScreen, DownloadsScreen | `YouTubeExtractor`, `CloudDownloadManager`, `MusicRepository`, `MediaControllerManager`, `AiFeatureManager` |
| `GoogleSyncViewModel` | GoogleSyncScreen | `GoogleAuthManager`, `YouTubeDataApiClient`, `YouTubePlaylistSyncManager` |
| `PlayerViewModel` | NowPlayingScreen, MiniPlayer | `MediaControllerManager`, `SleepTimerManager`, `LyricsRepository`, `CoverArtScraper` |
| `EqualizerViewModel` | equalizer UI inside NowPlayingScreen | `AudioEffectsManager`, `MediaControllerManager` |
| `AlarmViewModel` | AlarmsScreen | `MusicRepository`, `AppPreferences`, `AlarmScheduler` |
| `StatsViewModel` | StatsScreen | `MusicRepository` |

## 6. Navigation

- **`ui/navigation/NavRoutes.kt`** — defines the `Screen` sealed interface (`Home`, `Library`,
  `Playlists`, `YouTube`, etc.) using Jetpack Navigation 3's type-safe `NavKey`s. To add a new
  screen: add a `data object` here, add a `when` branch in `MainScreen.kt`'s nav switch, and wire
  up a bottom-nav/drawer entry point to it.
- Screen-to-screen navigation itself (back stack, current screen state) is owned inside
  `MainScreen.kt`, not a separate NavHost file.

## 7. Theming

- **`ui/theme/Color.kt`** — raw color constants for all 4 selectable themes (YT Dark, Pastel
  Midnight, Warm Amber, Nordic Slate).
- **`ui/theme/Theme.kt`** — `AppTheme` enum + `getSoftColorScheme()` (name → Material3
  `ColorScheme`) + the `TGMusicAITheme` composable wrapper every screen renders inside of.
- **`ui/theme/Type.kt`** — Material3 `Typography` definitions.
- Theme selection is persisted via `AppPreferences.selectedThemeFlow` and changed from a settings
  dialog (see `LibraryScreen.kt`'s `SetApiKeyDialog`-adjacent settings UI / theme picker entry).

## 8. Formatting helpers

- **`ui/util/FormatUtils.kt`** — `formatDuration()`, `formatTimestamp()`, `formatTimeOfDay()`,
  `formatBytes()`, `sanitizeFileName()`, `cacheBustedArtworkUri()`. Use these instead of
  hand-rolling date/duration/size formatting in a new screen.

---

## Quick "I want to change X" index

| You want to... | Open |
|---|---|
| Change the home tab layout | `ui/screens/HomeScreen.kt` |
| Change what data the home tab shows | `ui/viewmodel/HomeViewModel.kt` |
| Change the mini player bar | `ui/components/MiniPlayer.kt` |
| Change the full-screen player | `ui/screens/NowPlayingScreen.kt` + `ui/viewmodel/PlayerViewModel.kt` |
| Add a new bottom-nav tab | `ui/navigation/NavRoutes.kt` + `ui/screens/MainScreen.kt` |
| Change app colors/themes | `ui/theme/Color.kt`, `ui/theme/Theme.kt` |
| Change the alarm-ringing screen | `alarm/AlarmActivity.kt` |
| Change alarm list/editing UI | `ui/screens/AlarmsScreen.kt`, `ui/components/AlarmItem.kt`, `ui/components/AddEditAlarmDialog.kt` |
| Change onboarding/permissions screen | `ui/onboarding/OnboardingScreen.kt` |
| Change stats charts | `ui/screens/StatsScreen.kt` + `ui/viewmodel/StatsViewModel.kt` |
