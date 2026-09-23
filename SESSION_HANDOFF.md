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

## What is left

Only **Phase 2: durable cloud stream resolution**. Cloud playback and cloud downloads are both non-functional until it lands, because every public Piped/Invidious instance the app depends on is dead or blocking. Full research, verified API signatures, architecture, code sketches, risks and a step-by-step plan are in **`PHASE2_POTOKEN_HANDOFF.md`**.

One deliberately deferred follow-up, documented at the end of that file: prefetching whole tracks through the existing `AudioCacheManager` for playback reliability. It is useless before resolution works.
