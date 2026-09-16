# TGMusicAI — Code Audit Harness & Quality Policy

---

## 1. Executive Summary & Purpose

The **CODE_AUDIT_HARNESS** defines the mandatory rules, verification standards, and quality criteria for all Kotlin, Jetpack Compose, Room DB, Coroutines, Media3 ExoPlayer, and Network components in the TGMusicAI repository. 

Every production line in `app/src/main/java/com/example/tgmusicai/` must continuously satisfy the 5 Harness Rules established in this document.

---

## 2. Core Audit Harness Rules

### Rule A: Fake Calls & Hardcoded Stubs Audit
* **Directive**: Zero tolerance for fake, stubbed, or mock network calls in production code.
* **Inspection Protocol**:
  1. Scan all data and network repositories for hardcoded fake JSON string responses, dummy media URLs (e.g. `example.com`, `dummy.mp3`), or mock delays standing in for real backend logic.
  2. All network calls must target live, verified REST/HTTP endpoints (iTunes Search API, MusicBrainz/CoverArtArchive, LrcLib API, Piped API, YouTube/NewPipe) or utilize native parsing engines.
  3. All fallback mechanisms must be functional real-endpoint failovers (e.g., Piped API fallback when NewPipe extraction fails).

---

### Rule B: Nonsensical Arguments & Invalid Defaults
* **Directive**: Ensure strict parameter validity, range safety, and elimination of dead code or invalid default parameters.
* **Inspection Protocol**:
  1. Check seek positions, index lookups, and queue ranges to ensure bounds checking (e.g. `coerceIn`, `coerceAtLeast`).
  2. Verify math and timestamp conversions (e.g. `FormatUtils.formatDuration`, LRC timestamp parsing) against negative or overflow inputs.
  3. Ensure no unreachable branches, unused variables, or mismatched types exist in ViewModels or Data Layers.

---

### Rule C: State & Coroutine Safety
* **Directive**: Guarantee thread safety across Room DB operations, background I/O tasks, and Compose UI flows.
* **Inspection Protocol**:
  1. All database access (Room DAOs, BackupManager, MediaScanner) and network/disk operations must be dispatched explicitly on `Dispatchers.IO`.
  2. UI state updates in ViewModels and UI controllers must run on `Dispatchers.Main` / `viewModelScope`.
  3. Reactive flows in Compose screens must be collected safely without blocking the UI thread or creating redundant background collection tasks.
  4. Database transactions modifying cross-references or multi-table entities must use `@Transaction` or atomic suspend operations.

---

### Rule D: Edge-Case Exception Handling
* **Directive**: Every network request, file stream, system broadcast, and ExoPlayer playback call must catch target exceptions and execute clean fallback paths.
* **Inspection Protocol**:
  1. **Network & Web API**: Wrap all OkHttp requests in specific exception blocks (`IOException`, `SocketTimeoutException`, `UnknownHostException`).
  2. **Media3 ExoPlayer**: Attach error listeners (`Player.Listener.onPlayerError`) to ExoPlayer instances so unplayable media or invalid URIs failover cleanly to system ringtones or notify the user without crashing.
  3. **Exact System Alarms**: Wrap `setAlarmClock` and `setExactAndAllowWhileIdle` in `try-catch (SecurityException)` blocks to gracefully fall back to inexact alarms on Android 12+ (API 31+) if exact alarm permission is missing.
  4. **Zip & File Operations**: Guard Zip archive reads/writes against corrupt archives and Zip Slip path traversal security vulnerabilities.

---

### Rule E: Resource Leaks & Lifecycle Safety
* **Directive**: Ensure zero resource leaks across network connections, input/output streams, BroadcastReceivers, and background services.
* **Inspection Protocol**:
  1. **OkHttp Response Bodies**: Every executed OkHttp response must be closed immediately via `.execute().use { response -> ... }` and `body.byteStream().use { ... }`.
  2. **File Streams & Retrievers**: All `FileInputStream`, `FileOutputStream`, `ZipInputStream`, and `MediaMetadataRetriever` instances must be enclosed in `.use { ... }` or `try-finally` blocks ensuring `.release()` / `.close()`.
  3. **BroadcastReceivers & Coroutines**: Async receivers (`AlarmReceiver`, `BootReceiver`) must call `goAsync()` and guarantee `pendingResult.finish()` in `finally`. `Service` and `ViewModel` coroutine scopes (`serviceScope`, `viewModelScope`) must be canceled on `onDestroy()` or `onCleared()`.
  4. **MediaController & Observers**: `NetworkObserver` and `MediaControllerManager` must unregister callbacks and release controllers in activity lifecycle hooks (`onDestroy()`).

---

## 3. Verification Protocol & Execution Checklist

Before declaring any audit or code modifications complete, run the full verification pipeline:

```bash
./gradlew testDebugUnitTest assembleDebug
```

1. **Unit Tests**: Must achieve 100% pass rate.
2. **Build Verification**: Zero compilation errors across debuggable variants.
3. **Audit Compliance**: All 5 Harness Rules verified across every production file in `app/src/main/java/com/example/tgmusicai/`.
