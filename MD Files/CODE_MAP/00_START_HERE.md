# TGMusicAI Code Map — Start Here

This folder is a navigation aid, not a duplicate of the code. Every file listed here now also has
KDoc comments in the actual source (class-level "what is this and who uses it" + per-function
comments explaining parameters, return values, and any non-obvious behavior). Read this folder to
find *where* something lives, then open the file and read the comments to understand *how* it
works in detail.

The repo also has an older, much larger set of audit/spec documents one level up in `MD Files/`
(`ARCHITECTURE_CHEAT_SHEET.md`, `FULL_CODE_SUMMARY.md`, `CODEBASE_UNDERSTANDING.md`, etc.). Those
are historical deep-dives and feature specs written during earlier sessions — useful for backstory
on *why* a feature exists, but they can drift out of date. This `CODE_MAP` folder is meant to stay
a short, accurate, current index of "what file does what."

## Package layout

All source lives under `app/src/main/java/com/example/tgmusicai/`:

| Package | Role |
|---|---|
| `MainActivity.kt` (root) | App entry point. Wires the database, repositories, `MediaControllerManager`, network observer, and hosts the Compose UI tree. |
| `ui/` | **All UI lives here.** Screens, reusable components, ViewModels, navigation, theme. See `01_UI_LAYER.md`. |
| `data/` | Database (Room), repositories, and network/YouTube/Google clients. See `02_DATA_LAYER.md`. |
| `playback/` | The actual audio engine: `PlaybackService` (ExoPlayer + Android Auto), `MediaControllerManager` (UI-facing bridge), equalizer, sleep timer. See `03_OTHER_MODULES.md`. |
| `alarm/` | Musical alarm clock feature: scheduling, boot rescheduling, the full-screen alarm UI, and its own playback service. See `03_OTHER_MODULES.md`. |
| `ai/` | On-device AI (audio tagging + lyrics embeddings). Fully self-contained/fail-safe — a bug here cannot crash or break the rest of the app. See `03_OTHER_MODULES.md`. |

## If you want to fix something in the UI

Go straight to `01_UI_LAYER.md`. Short version: **`ui/screens/`** holds one file per tab/screen,
**`ui/components/`** holds reusable widgets (mini player, dialogs, list items), **`ui/viewmodel/`**
holds the state/logic behind each screen, and **`ui/screens/MainScreen.kt`** is the shell that
holds the bottom navigation bar, the side drawer, and the mini player, and switches between the
other screens.

## If you want to understand the database / DAO layer

Go to `02_DATA_LAYER.md`. Short version: **`data/local/entity/`** defines the tables,
**`data/local/dao/`** defines the queries against those tables, **`data/local/AppDatabase.kt`**
wires them together and holds the migration history, and **`data/repository/MusicRepository.kt`**
is the single class almost everything else talks to instead of touching DAOs directly.

## How a screen actually gets its data (the general pattern)

```
Room database (data/local/entity + data/local/dao)
        ↓ read/write
data/repository/MusicRepository (and LyricsRepository, CoverArtScraper for those specific features)
        ↓ exposes Flow<...> / suspend functions
ui/viewmodel/*ViewModel (one per screen, holds UI state, calls the repository)
        ↓ exposes StateFlow to the UI
ui/screens/*Screen.kt (Composable, collects the ViewModel's state and renders it)
        ↓ uses
ui/components/*.kt (shared building blocks: SongItem, MiniPlayer, dialogs, etc.)
```

Playback itself is a separate path: ViewModels don't play audio directly — they call
`playback/MediaControllerManager`, which talks to the always-running `playback/PlaybackService`
(the actual ExoPlayer instance) over Media3's `MediaController` API.
