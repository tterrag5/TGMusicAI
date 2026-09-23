# Copy-paste this into a new Claude Code session

Everything below the line is the message. Paste it as your first message in the new session.

---

I'm continuing work on TGMusicAI, an Android music player (Kotlin, Jetpack Compose, Media3, Room). A previous session finished a large UI/UX overhaul and left four items outstanding. Nothing is in your context, so start by reading these three files in the repo root, in this order:

1. `SESSION_HANDOFF.md` — what was done and why, plus the current state of the repo.
2. `CLAUDE.md` — project architecture and the rules that matter (read the build-environment note near the top).
3. `PHASE2_POTOKEN_HANDOFF.md` — the full research paper for backlog item 3 (cloud streaming).

**There are four outstanding items**, listed under "What is left — backlog" in `SESSION_HANDOFF.md`. They don't have to be done in that order:

1. **Drag-to-reorder playlists** — reorder songs inside a playlist, and reorder the playlists themselves in the Playlists tab and the drawer.
2. **Recommendations** — produce a *complete implementation specification*. This one is a document, not code: read its section carefully, because the bar is "a fresh session could implement it without asking a single design question", not a general survey.
3. **Fix cloud streaming and downloads** — the unfinished Phase 2, and the only item that unblocks broken functionality. Tapping any cloud song currently shows an error toast, and downloads fail identically, because the app resolves stream URLs through public Piped/Invidious instances and every one of them is dead or blocking. The fix is to resolve through NewPipeExtractor directly using a `PoTokenProvider` backed by a hidden WebView running BotGuard. `PHASE2_POTOKEN_HANDOFF.md` has the verified API signatures, architecture, code sketches, the upstream files to port, the risks, and a 10-step plan — follow it rather than improvising a different approach.
4. **"Import from YouTube" tab** on the Playlists screen, so it isn't drawer-only.

Each backlog entry lists what already exists in the codebase for that task. Read those notes before designing anything — several of them point at working code that is currently dormant, and a couple of the tasks are much smaller than they sound because of it. Ask me which item to start with if it isn't obvious.

Two things that will waste your time if you don't know them:

- **Build environment.** `./gradlew` works from a plain shell now (any JDK 17+). If you somehow still hit a toolchain error, `nix develop` gives you the project's JDK 17. `adb` is not on `PATH` — it's at `~/Android/Sdk/platform-tools/adb`. An emulator may already be running; check `adb devices`.
- **The emulator can silently revert to an old snapshot.** If the UI doesn't match what the docs describe, check `adb shell dumpsys package com.example.tgmusicai | grep lastUpdateTime` before assuming code is missing — reinstall with `./gradlew installDebug`.

Before you change anything, confirm the baseline: `./gradlew test` should report 51 passing tests and `./gradlew assembleDebug` should succeed. The working tree should be clean, with the latest commit on branch `feature/ui-overhaul-and-keyless-ai`.

If you start with item 3, begin at **step 3 of the plan in `PHASE2_POTOKEN_HANDOFF.md`** — get a poToken minting standalone and logged before integrating it into anything. That checkpoint exists because if token generation doesn't work, nothing downstream can, and integrating first makes the failure much harder to localise.

Two standing constraints from the previous work, both documented in `CLAUDE.md`:

- **Do not reintroduce any API key setting.** The app deliberately requires none; on-device replacements are in place.
- **Test playback against both a streamed track and a real local file.** A past change to the data-source layer broke local playback entirely while streams kept working, so verifying only one proves nothing.

When you're done, update `CLAUDE.md` and `MD Files/CODE_MAP.md` to match what actually shipped.
