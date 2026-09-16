# UI Layer Map

Where every screen, dialog, and view-model lives, and which function does what.
All paths below are relative to `app/src/main/java/com/example/tgmusicai/`.

## If you want to change what the app LOOKS like or how a screen BEHAVES, start here

- **Entry point**: `MainActivity.kt` — builds every DAO/repository/manager, wires them into
  view-model factories, and hosts the Compose tree via `setContent {}`. Also runs all the
  one-time startup jobs (media scan, library dedup passes, AI tag backfill, cover-art backfill).
- **Navigation shell**: `ui/screens/MainScreen.kt` — the `Scaffold`, nav-drawer, bottom content,
  and `NavDisplay` that switches between tabs. This is the file that decides "which screen is on
  screen right now." Route/tab definitions live in `ui/navigation/NavRoutes.kt` (`Screen` sealed
  interface: `Home`, `Library`, `Playlists`, `YouTube`, `Alarms`, `Stats`, `PlaylistDetail`,
  `NowPlayingFull`, `GoogleSync`, `Downloads`).
- **Theme/colors**: `ui/theme/Theme.kt` (color scheme per theme name, `TGMusicAITheme` composable
  wrapper), `ui/theme/Color.kt` (raw color constants), `ui/theme/Type.kt` (typography). To change
  the app's color palette or fonts, edit these three files.
- **Each tab is one file under `ui/screens/`**, paired with one view-model under `ui/viewmodel/`
  of the same feature name. Reusable pieces (song rows, dialogs, mini-player) live under
  `ui/components/` and are shared across screens.

## Screens (`ui/screens/`)

| File | Composable(s) | Role |
|---|---|---|
| `MainScreen.kt` (660 ln) | `MainScreen`, `DrawerPlaylistItem` | The shell: bottom navigation bar, side drawer, hosts the mini player, and switches between all the screens below via `ui/navigation/NavRoutes.kt`. Start here to change global chrome (nav bar, drawer, mini player placement). |
| `HomeScreen.kt` (1000 ln) | `HomeScreen`, `SectionTitle`, `PinnedPlaylistCard`, `PinnedSongCard`, `SongCardItem`, `SmartPlaylistCard` | Speed Dial home tab: pinned songs/playlists, Listen Again, Most Played, Smart Playlists. Largest screen file. |
| `LibraryScreen.kt` (444 ln) | `LibraryScreen`, `SetApiKeyDialog`, `LibraryScreenEmptyPreview` | Full local song library list/grid, search, bulk-select actions, AI tagging trigger. |
| `PlaylistsScreen.kt` (173 ln) | `PlaylistsScreen` | Grid/list of the user's playlists, create/delete entry point. |
| `PlaylistDetailScreen.kt` (470 ln) | `PlaylistDetailScreen` | Songs inside one playlist; reorder, remove, like/unlike, cover art, description editing. |
| `YouTubeScreen.kt` (347 ln) | `YouTubeScreen`, `YouTubeSearchResultItem` | Search YouTube, stream or download a result. |
| `GoogleSyncScreen.kt` (361 ln) | `GoogleSyncScreen` + 4 private composables | Google sign-in, list/import the user's real YouTube playlists. |
| `DownloadsScreen.kt` (369 ln) | `DownloadsScreen` + 4 private composables | Live list of in-progress/finished cloud downloads, pause/resume/cancel with progress bars. |
| `AlarmsScreen.kt` (223 ln) | `AlarmsScreen` | List of alarms, add/edit/toggle/delete entry point. |
| `StatsScreen.kt` (991 ln) | `StatsScreen` + 10 private composables (charts, storage overview, etc.) | Listening stats, most-played charts, storage usage. Second-largest screen file. |
| `NowPlayingScreen.kt` (924 ln) | `NowPlayingScreen`, `NowPlayingScreenPreview` + 1 private composable | Full-screen player: hero artwork, controls, lyrics, queue trigger, action pills (like, AI scrape, save to playlist). |

## Reusable components (`ui/components/`)

| File | Composable(s) | Role |
|---|---|---|
| `SongItem.kt` | `SongItem`, `SongGridItem` | One song row / grid tile — used in Home, Library, Playlist Detail, search results, queue. |
| `MiniPlayer.kt` | `MiniPlayer` | The persistent bottom mini-player bar (artwork, title, progress line, play/pause). Tap expands to `NowPlayingScreen`. |
| `QueueSheet.kt` | `QueueSheet` + 1 private composable | Bottom sheet showing/reordering the current play queue. Opened from the mini player. |
| `PlaylistCard.kt` | `PlaylistCard` | One playlist tile in grids (used in `PlaylistsScreen` and Home). |
| `AlarmItem.kt` | `AlarmItem` (+ `formatRepeatDaysSummary` helper) | One alarm row on `AlarmsScreen`, shows time/repeat/toggle. |
| `AddEditAlarmDialog.kt` | `AddEditAlarmDialog` | Create/edit an alarm (time, days, sound, volume ramp). |
| `AddToPlaylistDialog.kt` | `AddToPlaylistDialog` | Pick an existing or brand-new playlist to add song(s) to. |
| `CreatePlaylistDialog.kt` | `CreatePlaylistDialog` | Name + description entry for a new playlist. |
| `SongPickerDialog.kt` | `SongPickerDialog` + 1 private composable | Multi-select/searchable song picker (e.g. choosing an alarm's tone). |

## Other UI folders

- `ui/onboarding/OnboardingScreen.kt` — first-run permission request flow (`OnboardingScreen`,
  `PermissionItem`, plus plain (non-composable) helper functions `checkMediaPermission`,
  `checkNotificationPermission`, `checkBatteryOptimization`). Shown by `MainActivity` until
  `AppPreferences.onboardingCompletedFlow` is `true`.
- `ui/util/FormatUtils.kt` — plain formatting helpers used across screens: `formatDuration`,
  `formatTimestamp`, `formatTimeOfDay`, `formatBytes`, `sanitizeFileName`,
  `cacheBustedArtworkUri`.

## View-models (`ui/viewmodel/`) — one per feature area, each exposes `StateFlow`s the matching screen collects

| File | Key functions | Backs | Talks to |
|---|---|---|---|
| `HomeViewModel.kt` | `setDownloadedOnly`, `togglePinSong`, `togglePinPlaylist`, `removeDownload`, `exportBackup`, `importBackup`, `clearBackupStatus` | `HomeScreen` | `MusicRepository`, `NetworkObserver`, `BackupManager` |
| `LibraryViewModel.kt` | search, playlist-add dialogs, `scrapeArtworkAndLyrics`, `analyzeSongWithAi`, bulk variants, multi-select (`startSelection`/`toggleSongSelected`/`deleteSelectedSongs`), `toggleGridView` | `LibraryScreen` | `MusicRepository`, `LyricsRepository`, `CoverArtScraper`, `AiFeatureManager` |
| `PlaylistViewModel.kt` | `createPlaylist`, `updatePlaylistDescription`, `deletePlaylist`, `togglePinPlaylist`, `removeSongFromPlaylist`, `deleteSongCompletely`, like/unlike (all + selected), multi-select | `PlaylistsScreen`, `PlaylistDetailScreen` | `MusicRepository` |
| `PlayerViewModel.kt` (406 ln, largest view-model) | `playSong`, `playQueue`, `togglePlayPause`, `seekTo`, `skipToNext/Previous`, `skipToIndex`, `moveQueueItem`, `toggleShuffle`, `toggleRepeat`, `toggleLikeCurrentSong`, `startSleepTimer`, `fetchLyrics`, `transcribeLyricsWithAi`, `startRadio`, `saveQueueAsPlaylist`, `expandNowPlaying`/`collapseNowPlaying` | `NowPlayingScreen`, `MiniPlayer`, `QueueSheet` — the central playback-control view-model | `MediaControllerManager`, `LyricsRepository`, `CoverArtScraper`, `MusicRepository`, `AppPreferences` |
| `YouTubeViewModel.kt` (`AndroidViewModel`) | `onSearchQueryChanged`, `performSearch`, `playTrack`, `downloadTrack`, `addCloudTrackToPlaylist` | `YouTubeScreen`, `DownloadsScreen` | builds its own `YouTubeExtractor`/`CloudDownloadManager` from `Application` |
| `GoogleSyncViewModel.kt` | `signInAndLoadPlaylists`, `importLikedVideos`, `importPlaylist`, `refreshAllSynced`, `clearError` | `GoogleSyncScreen`, also called once at startup by `MainActivity` | `GoogleAuthManager`, `YouTubeDataApiClient`, `YouTubePlaylistSyncManager` |
| `AlarmViewModel.kt` | `setForceMaxVolume`, `setVolumeRampUp`, `saveAlarm`, `toggleAlarm`, `deleteAlarm` | `AlarmsScreen` | `MusicRepository`, `AppPreferences`, `AlarmScheduler` |
| `StatsViewModel.kt` | `refreshStorageOverview` | `StatsScreen` | `MusicRepository` |
| `EqualizerViewModel.kt` | `ensureAttached`, `setEnabled`, `setBandLevel`, `applyPreset`, `setBassBoostStrength`, `centerFreqHz` | Equalizer dialog inside `NowPlayingScreen` | `MediaControllerManager` (→ `AudioEffectsManager`), `AppPreferences` |

Every view-model has a nested `Factory` class (`ViewModelProvider.Factory`) — that's how
`MainActivity` constructs it with its real dependencies instead of a no-arg constructor.
`YouTubeViewModel` is the one exception (plain `viewModel()`, it's an `AndroidViewModel`).

## Practical "where do I go to fix X" guide

- Change a button/layout on a specific tab → its file in `ui/screens/`.
- Change what happens when a button is pressed (business logic, not layout) → the matching file
  in `ui/viewmodel/`.
- Change a shared widget's look (song row, mini-player, a dialog) → `ui/components/`.
- Change navigation (add a tab, change routes) → `ui/navigation/NavRoutes.kt` +
  `ui/screens/MainScreen.kt`.
- Change colors/dark-mode/fonts → `ui/theme/`.
- Change what happens on first app launch → `ui/onboarding/OnboardingScreen.kt`.
- Change how playback actually works (not just the buttons) → `playback/` (see
  `03_OTHER_MODULES.md`), reached through `PlayerViewModel` → `MediaControllerManager`.
