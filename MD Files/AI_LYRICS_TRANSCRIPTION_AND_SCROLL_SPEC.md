# AI Lyrics Transcription & Smooth Scroll Specification

This document provides a comprehensive root cause analysis of current lyrics fetching, parsing, persistence, and scrolling issues in TGMusicAI, followed by a complete architectural redesign specification for the YouTube Music-style LYRICS tab, smooth non-jittery scrolling, and an OpenAI Whisper AI lyrics transcription engine.

---

## Part 1: Root Cause Analysis — Why Lyrics Fetching & Scrolling Are Broken

### 1. Network / Query Failures
* **Overly Aggressive Query Cleaning (`LyricsRepository.cleanQuery`)**:
  * **Issue**: The current cleaning logic strips all parenthetical expressions from track titles (e.g., `(Sittin' On) The Dock of the Bay` becomes `The Dock of the Bay`, or `Song Title (Live at ...)` has its main identifying title altered).
  * **Impact**: Exact GET requests to LrcLib (`/api/get`) fail with HTTP 404 because the title parameter sent to the API does not match LrcLib's database records.
* **Blank Artist Fallback Failure**:
  * **Issue**: When `artist_name` is blank, empty, or generic (e.g., `artist_name=`), LrcLib fails strict matching requests.
  * **Impact**: Track queries fail to return synced lyrics even when available under title search.
* **Strict Duration Matching & Missing Multi-Pass Fallback**:
  * **Issue**: The LrcLib `/api/get` endpoint enforces strict duration matching (0-second tolerance). YouTube audio streams often differ by 2–3 seconds from official studio album track durations.
  * **Impact**: Direct exact lookup returns 404. Without a multi-pass search query fallback (`GET /api/search?q=...`), no lyrics are retrieved even when fuzzy matches exist.

### 2. Database Persistence Failure
* **Unpersisted Cloud Songs (`id == 0L`)**:
  * **Issue**: Cloud search songs or YouTube stream items have an unassigned Room primary key (`id == 0L`). Calling `updateSongLyrics(0L, ...)` executes `UPDATE song SET lyrics = :lyrics WHERE id = 0`, updating 0 rows in SQLite.
  * **Impact**: Lyrics fetched from LrcLib or online sources during playback are lost as soon as the user navigates away or switches tabs, forcing redundant network calls and causing blank lyrics on return.

### 3. LRC Timestamp Parser & Metadata Sorting Bug
* **Regex Parsing Failures**:
  * **Issue**: The `LRC_PATTERN` regex fails to match colon centiseconds (e.g., `[mm:ss:ff]`), 3-digit minute markers (e.g., `[102:15.00]`), or multi-timestamp lines (e.g., `[00:10.00][01:15.00]Lyric text`).
  * **Impact**: Valid LRC lines are ignored or fail to parse into structured `LyricLine` objects.
* **Unparsed Metadata Tag Pollution**:
  * **Issue**: Unparsed metadata tags (such as `[ar: Artist]`, `[ti: Title]`, `[by: ...]` or `[offset: ...]`) fail timestamp regex extraction, default to `timestampMs = 0L`, and get sorted to index 0 at the top of the lyrics list.
  * **Impact**: Metadata strings render as readable lyric lines at the very top of the lyrics view before the first actual song line.
* **Raw HTML / WebVTT Cue Tag Leakage**:
  * **Issue**: YouTube closed captions contain raw HTML formatting and WebVTT cue annotations (e.g., `<c.colorFFFFFF>`, `<v Speaker>`, `<b>`, `<i>`).
  * **Impact**: Uncleaned markup is rendered directly in the UI as raw tag strings (e.g., `<c.colorFFFFFF>Hello world</c>`).

### 4. Scroll-Jitter Bug
* **Unchecked Auto-Scroll Ticks (`LaunchedEffect(activeIndex)`)**:
  * **Issue**: In `NowPlayingScreen.kt`, `LaunchedEffect(activeIndex)` triggers `lazyListState.animateScrollToItem(activeIndex)` every time the playback tick updates the `activeIndex`, without verifying whether `lazyListState.isScrollInProgress` is true.
  * **Impact**: If a user attempts to manually swipe or scroll through lyrics while music is playing, the automated scroll effect interrupts user gestures, causing severe list jerking, jumping, and screen lockup.

---

## Part 2: YouTube Music LYRICS Tab & Smooth Scroll Redesign Spec

### 1. Top Tab Navigation Bar
* **Tab Items**: `UP NEXT` | `LYRICS` | `RELATED`
* **Styling & Indicator**:
  * Surface color matching dark player sheet background.
  * Active tab title styled in bold white typography (`#FFFFFF`, 14sp, SemiBold).
  * Inactive tab titles styled in muted gray (`#FFFFFFB3` or `#999999`, 14sp, Medium).
  * Smooth sliding white underline indicator anchored to the selected tab bounds with custom spring animation (`Material3 TabRow` with `TabRowDefaults.SecondaryIndicator`).

### 2. Ambient Blurred Background
* **Dynamic Color Overlay**:
  * Extract dominant color from current track album cover art (or fallback to `#300000` dark burgundy / deep plum).
  * Apply a heavy radial blur and dark gradient overlay behind the lyrics text container.
  * Ensure background opacity is set to 85%–90% dark overlay to maintain high contrast ratio (WCAG AAA standard for readability).

### 3. Typography & Active Line Highlight
* **Active Line**:
  * **Text Color**: `#FFFFFF` (Bright White, 100% opacity `1.0f`).
  * **Font Weight & Size**: `FontWeight.Bold`, `20sp`, line height `28sp`.
  * **Visual Cue**: Subtle glow/scale effect (`1.02f` scale transition) for active focus line.
* **Upcoming & Passed Lines**:
  * **Text Color**: `#FFFFFF66` (Soft muted white, 40% opacity `0.4f`).
  * **Font Weight & Size**: `FontWeight.Medium`, `18sp`, line height `26sp`.
* **Instrumental Breaks**:
  * Lines with instrumental gaps or musical interludes rendered with musical note symbols `♪ ♪ ♪`.

### 4. Smooth Non-Jittery Auto-Scroll & Touch Guard Architecture
* **Scroll Offset Calculation**:
  * Scroll target line into view with offset: `scrollOffset = -viewportHeight / 3` so that the active line is centered roughly 33% from the top of the viewport.
* **Touch Guard Cooldown State**:
  * Maintain a `userScrollActive` state flag coupled with a 5-second inactivity timer.
  * When `lazyListState.isScrollInProgress` becomes `true`, set `userScrollActive = true` and reset timer.
  * Auto-scroll execution rule:
    ```kotlin
    LaunchedEffect(activeIndex) {
        if (activeIndex in lyricLines.indices && !lazyListState.isScrollInProgress && !userScrollActive) {
            lazyListState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -viewportHeight / 3
            )
        }
    }
    ```
* **Tap-to-Seek Interaction**:
  * Clicking/tapping any lyric line triggers:
    ```kotlin
    onLineClick = { line ->
        mediaController.seekTo(line.timestampMs)
        // Re-enable auto-scroll alignment immediately on user selection
        userScrollActive = false
    }
    ```

---

## Part 3: OpenAI Whisper AI Lyrics Transcription Engine Spec

### 1. Fallback & UI Trigger
* **Trigger Condition**: When no local ID3 lyrics or LrcLib lyrics are found for a track, display an **"AI Transcribe Lyrics"** action button in the center of the `LYRICS` tab.
* **User Feedback**: Display loading shimmer / progress bar with state label `"Transcribing audio with OpenAI Whisper..."` during processing.

### 2. OpenAI Whisper API Integration & Payload Specs
* **Endpoint**: `POST https://api.openai.com/v1/audio/transcriptions`
* **Headers**:
  * `Authorization: Bearer <OPENAI_API_KEY>`
  * `Content-Type: multipart/form-data`
* **Form Parameters**:
  * `file`: Audio file chunk/stream (mp3/m4a/wav format).
  * `model`: `whisper-1`
  * `response_format`: `verbose_json`
  * `timestamp_granularities[]`: `segment`
* **Expected Response Schema**:
  ```json
  {
    "task": "transcribe",
    "language": "english",
    "duration": 210.5,
    "segments": [
      {
        "id": 0,
        "start": 12.4,
        "end": 16.8,
        "text": " I sitting in the morning sun"
      },
      {
        "id": 1,
        "start": 17.2,
        "end": 21.5,
        "text": " I'll be sitting when the evening comes"
      }
    ]
  }
  ```

### 3. Parsing, Persistence & Display Workflow
1. **LRC Format Conversion**:
   * Map each segment in `segments` to standard `.lrc` timestamp lines:
     * Convert `start` seconds (e.g., `12.4`) to `[mm:ss.xx]` (e.g., `[00:12.40]`).
     * Format line: `[00:12.40] I sitting in the morning sun`.
2. **Database Persistence**:
   * Ensure song is persisted in Room DB (acquire valid `songId`).
   * Update song entity: `updateSongLyrics(songId, formattedLrcString)`.
3. **UI Stream Update**:
   * Emit updated `LyricLine` list to UI state flow.
   * Transition `LYRICS` view automatically from loading state to synced scrolling lyric view.

---

## Part 4: Null Lyrics String Guard & Multilingual CJK/Japanese Lyrics Support Spec

### 1. Null Lyrics String Guard ("null" -> Friendly Empty State)
* **Problem**: Missing or uninitialized lyrics values in database fields or API responses can return or resolve to the literal string `"null"`. When passed to UI composables, this causes the screen to literally display the word `"null"` instead of an empty state.
* **Lyrics Extraction Guard**:
  ```kotlin
  val cleanLyrics = if (!lyrics.isNullOrBlank() && lyrics.trim() != "null") lyrics else null
  ```
* **Friendly Empty State UI**:
  * When `cleanLyrics == null`, render a clean and friendly empty state card displaying:
    * Primary Message: `"No lyrics available for this song"`
    * Call to Action: **"AI Transcribe Lyrics"** button (which triggers the OpenAI Whisper AI transcription engine).
  * Prevent literal `"null"` strings from ever reaching the `LrcParser` or UI render tree.

### 2. Multilingual & Japanese/CJK Lyrics Support (`LyricsRepository.kt` & Whisper AI)
* **Unicode & CJK Title Preservation in LrcLib Queries**:
  * **Title Cleaning Refinement**: Do NOT strip non-ASCII or CJK (Japanese/Chinese/Korean) characters in `cleanQuery`. Preserve full Unicode character support.
  * **URL Encoding**: Properly URL-encode LrcLib query parameters with `java.net.URLEncoder.encode(param, "UTF-8")` so Japanese and international track titles (e.g., `ジェラシス (feat. KAFU)`, `食虫植物 / ダズビー COVER`) search LrcLib's international database successfully without encoding corruption or invalid HTTP query strings.
* **Whisper AI Multilingual Auto-Detection**:
  * **Automatic Language Detection**: Ensure the OpenAI Whisper API request enables automatic language detection (`language = null` or omitted in request body).
  * **Multilingual Transcription**: Allows Whisper to transcribe Japanese, Spanish, Korean, and other non-English singing voices into native script lyrics (Kanji/Hiragana/Romaji) with accurate segment timestamps.

