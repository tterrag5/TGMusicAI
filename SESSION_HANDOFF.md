# TGMusicAI — Session Handoff (state of the work)

Snapshot of everything completed in the previous working session, so no conversation history is needed to continue. For the one remaining task, see **`PHASE2_POTOKEN_HANDOFF.md`**.

---

## Current state at a glance

- **Committed** on branch `feature/ui-overhaul-and-keyless-ai` (`main` untouched, nothing pushed). Working tree clean, builds green, 51 unit tests pass.
- To hand this to a fresh AI session, paste the message in **`NEXT_SESSION_PROMPT.md`**.
- Five of six planned phases are **done and verified on an emulator**. Phase 2 (cloud stream resolution) is **not started**.
- `CLAUDE.md` was updated to match the new architecture — trust it over any older internal notes.

### Build environment

`./gradlew` works from a plain shell on **any JDK 17 or newer**. It previously hard-required a JDK 17 *installation* from two places — a `java { toolchain { languageVersion = 17 } }` block and `gradle/gradle-daemon-jvm.properties` — which made every build fail outside the Nix dev shell with *"Cannot find a Java installation … matching languageVersion=17"*. Both pins were removed; emitted bytecode is still 17. Verified on JDK 17 and 21. `nix develop` still works and is fine to use.

`adb` is at `~/Android/Sdk/platform-tools/adb`, not on `PATH`.

**If the emulator's UI doesn't match this document**, it has reverted to an older snapshot — this happened once after a host restart. Check `adb shell dumpsys package com.example.tgmusicai | grep lastUpdateTime` and reinstall with `./gradlew installDebug` before concluding code is missing.

---

## Two audit questions that were asked and answered

### The AI API key — removed entirely

It had exactly three consumers:

| Consumer | Verdict |
|---|---|
| Online metadata cleaning (`AiMetadataCleaner.fetchOnlineMetadata`) | **Broken and wasteful** |
| Lyrics translation (`LyricsRepository.translateLyrics`) | Worked on OpenAI keys only |
| Whisper transcription | **Never used the key** — already fully on-device |

Both call sites were pinned to `gemini-1.5-flash`, retired by Google in September 2025, so any key created today returned 404 and silently fell back with no user feedback. Worse, `MediaScanner` called the cleaner on *every* song *before* the dedupe check, firing one network request per song on **every app launch** and discarding the result for every song already in the database — and `CloudDownloadManager` re-triggered a full scan after every download.

**What shipped:** the key, its dialog, the Library top-bar entry point, the Settings section and the onboarding prompt are all gone. Metadata cleaning now uses only the on-device regex engine (`cleanOffline`, unit-tested, already used by six other call sites). Lyrics translation was rewritten on **ML Kit on-device translation** — 58 languages, no key, downloads a model once then works offline. The scan-order bug was fixed. `grep` for `aiApiKeyFlow|setAiApiKey|AI_API_KEY` returns nothing.

### Backup & Sync — export had never worked

`exportBackup` wrote into `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` with a raw `File`, while the app declares **no storage permission** at `targetSdk 37`. That throws `EACCES` on every supported Android version, and the `?:` fallbacks were unreachable because that call never returns null.

Import worked but carried four data-loss bugs: it reused the backup's primary keys against `REPLACE`-on-conflict inserts (silently overwriting unrelated local rows), never exported `totalListenTimeMs` so restoring **zeroed the Stats screen's headline metric**, had no transaction, and had no schema version. There was also no "Sync" in it at all — real sync lives in `YouTubePlaylistSyncManager`.

**What shipped:** export now writes to a stream from `ActivityResultContracts.CreateDocument` (system save dialog, no permission needed). Import assigns fresh row ids and remaps cross-refs/stats through `songIdMap`/`playlistIdMap`, runs inside one `withTransaction`, carries a `schemaVersion` that rejects newer backups, preserves listen time, and lifted the 1000-row stats cap. MediaStore-sourced songs with no bundled audio are re-matched locally by title+artist instead of restoring an unplayable foreign `content://` id. Section renamed **"Backup & Restore"**. The identical export bug was found and fixed in playlist CSV export (`PlaylistImportExportManager`).

**Verified on device:** export produced a real 2,768-byte `.tgmusic` file (first time ever); restoring it onto a *non-empty* library inserted new rows (ids 4–5) instead of clobbering ids 1–2, matched the existing local `TestTone` rather than duplicating it, and preserved `totalListenTimeMs = 681323`.

---

## Everything else that shipped

**Navigation.** The drawer had six entries, three of which (Home, Explore, Library) duplicated bottom tabs; the other three were drawer-only. Every tab and drawer item called `backStack.clear()`, which left the stack one deep, disabled the `BackHandler`, and made system back exit the app. The hamburger existed **only on Home**, so landing on Stats stranded the user with no visible way out — and Settings was Home-only too.

Now: drawer holds only Stats / Import from YouTube / Downloads / **Settings**, plus playlist shortcuts. A new `MainScreen.navigateTopLevel` pops back to an existing entry instead of clearing. Every top-level screen takes `onOpenDrawer` and renders a hamburger. *Verified: back from Stats returns to Library rather than exiting.*

**Explore merged into Library.** The Explore tab is gone; Library search now shows local matches then a "From YouTube" section. "Downloaded only" moved from Home to Library and is persisted in `AppPreferences` (it used to be a ViewModel-only flag that reset each launch and filtered only three Home sections); with it on, cloud results are suppressed entirely. `YouTubeScreen.kt` deleted, its result row extracted to `ui/components/YouTubeSearchResultItem.kt`. Bottom nav is now four tabs. **Cloud search works — only resolution is broken.**

**Theming.** Rebuilt data-driven: 8 palettes (added Violet Dusk, Forest, Rose Quartz, Mono), each with real light *and* dark variants, plus a **Dim** middle mode derived from the dark spec, a Material You toggle on Android 12+, and colour swatches in the picker. Previously there were four dark-only presets and a `darkTheme` parameter that was accepted but never applied. Hardcoded colours in the nav bar and mini-player were replaced with theme roles so light mode doesn't render a black slab.

**Settings** was restructured into collapsible sections, all collapsed by default, each header showing its current state ("Dim · Warm Amber", "Skip silence, Crossfade") so the whole menu fits one screen.

**Alarms.** Removed the paragraph subtitles under the two volume switches. Snooze is now a slider (1–60 min) plus an explicit off switch, using `0` as the "disabled" sentinel — **no schema change needed**, but three consumers are guarded: `AlarmScheduler.scheduleSnooze` ignores 0 (scheduling it would fire immediately), `AlarmActivity` hides the snooze button, `AlarmItem` renders "Snooze off".

**Playlists.** Grid/list toggle (persisted), 3-per-row compact cards, and bulk select-and-delete reusing the Library's selection pattern — with protected smart playlists made unselectable so the "N selected" count always matches what actually deletes.

**Library.** Grid is now the default and persisted. Before making that switch, the grid tile gained the download-status badge and Queue/Like actions the list row already had, so it wasn't a feature regression.

**Playlist detail** gained a song filter that deliberately does **not** narrow Play All, Export, Share or the song count.

**Search bars.** The real bug: `singleLine = true` constrains the typed value, not the placeholder `Text` composable, which wrapped to a second line and inflated the field. Fixed with shorter strings *and* `maxLines = 1` on the placeholders.

**Playback error surfacing.** A song that fails to resolve used to leave the UI on "No Song Selected" with no explanation. It now shows a toast and logs `Giving up on "<title>"…`. The extractor's connect timeout was cut 15s → 6s because exhausting the dead tiers took ~110 seconds.

**YouTube Music sign-in ("untrusted browser").** The WebView sent a hardcoded *desktop Windows Chrome* User-Agent while its client hints still reported Android — that contradiction is what Google's sign-in flow rejects. It now derives from the device's real WebView UA and strips only the `; wv` marker. **Unverified** — needs a real Google account to test.

---

## Known-good verification baseline

```bash
nix develop                 # or -Dorg.gradle.java.home=<jdk17>
./gradlew test              # 51 tests, 0 failures
./gradlew assembleDebug     # BUILD SUCCESSFUL
```

---

## What is left — backlog

Four items, requested by the project owner. **They do not need to be done in this order**, though item 3 is the only one that unblocks broken functionality, so it is the most valuable. Items 1 and 4 are small and independent; item 2 produces a document, not code.

---

### 1. Drag-to-reorder playlists

Let the user reorder tracks *within* a playlist by dragging, and reorder the playlists themselves in the Playlists tab and in the navigation drawer's playlist list.

**Most of the groundwork already exists — check it before designing anything:**

- **`PlaylistSongCrossRef.position: Int` already exists** (`data/local/entity/PlaylistSongCrossRef.kt:34`). No schema change or migration is needed to order songs inside a playlist.
- **But nothing currently orders by it.** `PlaylistDao.getPlaylistWithSongs` / `getPlaylistWithSongsSync` are plain `SELECT * FROM playlists WHERE playlistId = :id` with a Room `@Relation`, so songs come back in whatever order the junction happens to yield. Room's `@Relation` cannot `ORDER BY` a junction column, so this needs a hand-written query (or a post-query sort against the cross-ref rows). **This is the crux of the task** — persisting a drag is pointless until reads respect `position`.
- **A working drag implementation already exists in this codebase**: `ui/components/QueueSheet.kt` (~line 246-267) hand-rolls drag-to-reorder over a `LazyColumn` with `pointerInput`/`detectDragGestures`, wired to `PlayerViewModel.moveQueueItem(from, to)` → `MediaControllerManager.moveQueueItem`. Reuse that pattern rather than adding a third-party reorderable-list dependency; note its comment about the gesture not restarting mid-drag, which is a real trap.

**Reordering the playlists themselves** is the part with no existing support: `Playlist` has `isPinned` and `createdAt` but **no `position` column**, and `PlaylistsScreen` currently sorts by `isPinned` only. That half *does* need a schema change — add a `position` column with an additive `MIGRATION_11_12`. Per `CLAUDE.md`, never use `fallbackToDestructiveMigration()` for a shipped schema bump.

Scope note: the drawer's playlist list (`MainScreen.kt`) and the Playlists tab must share one ordering, or the two views will disagree.

**Done when:** dragging a song inside a playlist persists across app restart; dragging a playlist persists and is reflected in both the Playlists tab and the drawer; the six protected smart playlists behave sensibly (they are computed, not cross-ref-backed — decide explicitly whether they are reorderable or pinned to a fixed position, and say which).

---

### 2. Recommendations — produce a complete implementation specification

**Read this framing carefully, because the deliverable is not what "research" usually means here.**

You are **not** producing a general survey of how recommendation systems work, and you are **not** writing the feature yet. You are producing a **full, implementation-ready breakdown** — a document so complete that the session which picks it up afterwards has no design decisions left to make and no unknowns to resolve. It should only need to write code.

Concretely, the document must nail down **all** of the following, with no "we could either…" left unresolved:

- **The chosen algorithm**, stated exactly: what signals feed it, how candidates are generated, how they are scored and ranked, how ties and cold-start cases are handled, and why that approach over the alternatives you rejected. Name the rejected options and the reason each lost.
- **How comparable apps actually do it** (Spotify, YouTube Music, Plexamp, and any on-device recommender worth copying), and specifically which parts of their approach are and are not viable for an offline-first app with no backend and no user-behaviour corpus beyond this one device.
- **Every library or model** you intend to use: exact artifact coordinates, version, licence, size on disk, whether it runs on-device, and whether it needs a dependency bump. If you propose a new model asset, state its size and confirm it can be bundled (`androidResources { noCompress += ... }` already keeps `.tflite`/`.onnx` uncompressed).
- **The exact data model changes**: new tables/columns, the Room migration number and its SQL, and what backfills existing rows.
- **The exact new/changed files**, with the function signatures you intend to add.
- **Where it surfaces in the UI**, tied to real screens that exist today.
- **Compute and storage budget**: when the work runs (foreground? on scan? background?), how long it takes for a library of N songs, and what it costs in battery and disk.
- **How you will evaluate it.** "Recommendations feel good" is not a test. Define something checkable.
- **What will go wrong**, and the failure behaviour for each case.

**Start from what this codebase already has, because it is more than it looks and a survey that ignores it would be wasted work:**

- **`ai_song_tags` table** already stores, per song: YAMNet audio tags (up to 8, score ≥ 0.08, from `SongTaggingEngine`) and a **384-dimension MiniLM lyrics embedding** (`LyricsEmbeddingEngine.EMBEDDING_DIM`), plus `computedAt`. `AiFeatureManager.backfillAll()` can populate it across the library.
- **`AiFeatureManager.rankBySimilarLyrics(seedSongId, candidateSongIds, limit)` already exists and works** — cosine similarity over those embeddings, with `LyricsEmbeddingEngine.cosineSimilarity` as the primitive. **It is currently dead code: nothing in the app calls it.**
- **`listening_history` table** (songId, timestampMs, durationMs) is **written on every play but never read by anything**. It is a complete play log sitting unused — almost certainly your richest behavioural signal.
- **`song_stats`** holds `playCount`, `lastPlayedAt`, `totalListenTimeMs` per song.
- **`MusicRepository.buildRadioQueue(seedSong, limit)` is the existing "recommendation"**, and it is purely metadata-based: same producer, then same artist, then random, each bucket shuffled with under-played songs first. **It ignores the embeddings and tags entirely.** Wiring the existing similarity ranking into this is probably the cheapest meaningful win available, and your spec should say whether that is step one or whether you are replacing it wholesale.
- All AI work must stay inside the containment rules in `CLAUDE.md`: engines self-initialise behind try-catch, mark themselves permanently unavailable on failure, and return `AiModelResult` rather than throwing. **A model failure must never be able to affect playback.**

**Done when:** a reviewer can hand the document to a fresh session and that session can implement the feature without asking a single design question.

---

### 3. Fix cloud streaming and downloads (the unfinished Phase 2)

Tapping a cloud song currently shows an error toast; cloud downloads fail identically, because both go through the same resolver and every public Piped/Invidious instance it depends on is dead or blocking.

This is fully specified already in **`PHASE2_POTOKEN_HANDOFF.md`** — verified API signatures, architecture, code sketches, the upstream files to port, licensing notes, risks, a verification plan, and a 10-step order of work. Follow that plan rather than improvising.

Its deferred follow-up also belongs here: prefetching whole tracks through the existing `AudioCacheManager` for playback reliability, which is useless until resolution works.

---

### 4. "Import from YouTube" tab on the Playlists screen

Importing from YouTube currently lives only in the navigation drawer (`Screen.GoogleSync`), which is an odd place for something that produces playlists. Surface it at the top of the Playlists screen as a small tab/segmented control so it sits next to the thing it creates.

Relevant context: `PlaylistsScreen.kt` already has a top bar carrying a grid/list toggle and a file-import action, and a selection-mode variant of that bar; the destination already exists as `Screen.GoogleSync` and is reachable via `MainScreen.navigateTopLevel`. Keep the drawer entry as well, or remove it deliberately and say so — do not end up with the same ambiguity the duplicate create-playlist buttons caused.

**Done when:** the entry point is visible from the Playlists tab without opening the drawer, and navigating to it and back leaves the back stack intact (see the `navigateTopLevel` rule above).
