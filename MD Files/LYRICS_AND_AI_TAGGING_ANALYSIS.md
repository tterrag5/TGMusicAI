# Comprehensive Analysis: Lyrics Fetching Mechanism & AI Tagging Engine

---

## Part 1: Root Cause Analysis (Why Lyrics Are Broken)

### 1. `LyricsRepository.kt` Bugs & Failure Modes

#### A. Overly Aggressive & Naïve Metadata Cleaning in `fetchFromLrcLib`
* **Location**: `LyricsRepository.kt` (lines 142–143)
* **Code Issue**:
  ```kotlin
  val cleanTitle = song.title.replace(Regex("(?i)\\(.*\\)|\\[.*\\]"), "").trim()
  val cleanArtist = song.artist.replace(Regex("(?i)unknown.*"), "").trim()
  ```
* **Root Cause**:
  1. **Destructive Regex**: The regex `\\(.*\\)|\\[.*\\]` strips **all** parenthetical and bracketed text. For song titles where parentheses are an integral part of the actual song title (e.g., `(Sittin' On) The Dock of the Bay`, `(Don't Fear) The Reaper`, `(I Can't Get No) Satisfaction`), the regex completely strips the title down to `The Dock of the Bay` or `The Reaper` or `Satisfaction`, causing exact-match queries on LrcLib to fail with `404 Not Found`.
  2. **Bypassing `AiMetadataCleaner`**: The codebase has a dedicated, robust metadata cleaner (`com.example.tgmusicai.data.local.AiMetadataCleaner`), yet `LyricsRepository` completely ignores it and executes a crude 1-line regex replace.
  3. **Empty Artist Query Parameter**: If `cleanArtist` evaluates to an empty string (e.g. for `Unknown Artist`), `fetchFromLrcLib` sends `artist_name=` as an empty query parameter to LrcLib's `/api/get` endpoint (`https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=`). LrcLib requires both `track_name` and `artist_name` on `/api/get`, so an empty `artist_name` parameter returns HTTP `400 Bad Request` or `404 Not Found`.

#### B. Rigid LrcLib Duration Matching Mismatch
* **Location**: `LyricsRepository.kt` (lines 147–150)
* **Code Issue**:
  ```kotlin
  var url = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist"
  if (durationSec > 0) {
      url += "&duration=$durationSec"
  }
  ```
* **Root Cause**:
  LrcLib's strict GET endpoint compares the track duration within a narrow ±2 second margin. Audio files downloaded from YouTube or local media files often have track durations that differ slightly (e.g., intro silence, video outro padding, or altered sample rates) from the official album track length stored in LrcLib. Because `durationSec` is unconditionally appended to the initial GET request, LrcLib rejects valid matches with a 404 response. Furthermore, `LyricsRepository` does not attempt a secondary GET lookup without the `duration` parameter before falling back to search.

#### C. Flawed LRC Timestamp Parser (`parseLyrics`) & Sorting Corruption
* **Location**: `LyricsRepository.kt` (lines 48–90)
* **Code Issue**:
  ```kotlin
  private val LRC_PATTERN: Pattern = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?](.*)")

  // ...
  return if (parsedLines.any { it.timestampMs > 0 }) {
      parsedLines.sortedBy { it.timestampMs }
  } else {
      parsedLines
  }
  ```
* **Root Cause**:
  1. **Inflexible Regex**: `LRC_PATTERN` only matches standard dot-separated hundredths/milliseconds `[mm:ss.ff]`. It fails to match:
     - Colon-separated centiseconds `[mm:ss:ff]` (common in many LRC providers).
     - 3-digit minute timestamps `[mmm:ss.ff]`.
     - Multi-timestamp tags on a single line (e.g. `[00:12.34][01:15.20] Chorus line`), causing the line to fail matching.
  2. **Metadata & Non-Timestamp Sorting Bug**: When lines do not match `LRC_PATTERN` (e.g. metadata tags like `[ar: Artist]`, `[ti: Title]`, section headers like `[Verse 1]`, or unparsed intro lines), they fall through to `LyricLine(timestampMs = 0L, text = trimmed)`. Because `parsedLines.any { it.timestampMs > 0 }` evaluates to `true` when valid synced lines exist, `parsedLines.sortedBy { it.timestampMs }` is invoked. Since all unparsed/metadata lines have `timestampMs = 0L`, they are **sorted to the very top** (index 0, 1, 2...) of the lyrics list! This corrupts the song sequence and renders section headers and intro text at the beginning of playback.

#### D. Raw HTML & Unstripped Cue Tags in WebVTT / YouTube Captions Parser
* **Location**: `LyricsRepository.kt` (lines 284–327)
* **Code Issue**:
  ```kotlin
  } else if (currentTimestamp.isNotEmpty() && !trimmed.contains("-->")) {
      builder.append("$currentTimestamp $trimmed\n")
  }
  ```
* **Root Cause**:
  YouTube WebVTT subtitle files contain formatting and speaker tags (e.g. `<c.colorFFFFFF>`, `<v Speaker>`, `<i>`, `<b>`, `<00:12.345>`). `parseVttOrJsonCaptions` appends `trimmed` directly without stripping XML/HTML cue tags. This results in raw HTML markup being displayed in the UI as song lyrics.

---

### 2. `PlayerViewModel.kt` & State Propagation Bugs

#### A. Streamed / Unpersisted Songs (`id == 0L`) Discard Fetched Lyrics
* **Location**: `PlayerViewModel.kt` (lines 148–158)
* **Code Issue**:
  ```kotlin
  fun fetchLyrics(song: Song? = currentSong.value, forceFetch: Boolean = false) {
      val target = song ?: return
      if (lyricsRepository == null) return

      viewModelScope.launch {
          _isScraping.value = true
          val fetched = lyricsRepository.fetchAndSaveLyrics(target, forceFetch = forceFetch)
          _lyrics.value = fetched
          _isScraping.value = false
      }
  }
  ```
* **Root Cause**:
  When a song is streamed directly from YouTube search or radio (without downloading first), its `Song.id` is `0L`. Inside `LyricsRepository.fetchAndSaveLyrics`, when lyrics are fetched, it executes:
  `musicRepository.updateSongLyrics(song.id, fetchedLyrics)`
  Since `song.id == 0L` is not present in Room DB, `updateSongLyrics` silently affects 0 rows. While `_lyrics.value = fetched` updates the currently visible UI state in `PlayerViewModel`, the fetched lyrics are never saved to disk. When the song is subsequently played again or added to a playlist, `song.lyrics` remains `null`.

#### B. Disconnect Between DB Updates and `MediaControllerManager.currentSong`
* **Location**: `PlayerViewModel.kt` (lines 115–129)
* **Root Cause**:
  `MediaControllerManager.currentSong` holds an immutable in-memory snapshot of `Song`. When `LyricsRepository` fetches lyrics and updates the Room DB row via `musicRepository.updateSongLyrics`, `MediaControllerManager` is not notified and does not re-emit an updated `Song` object. If UI components query `currentSong.value?.lyrics`, they receive stale `null` data.

---

### 3. `NowPlayingScreen.kt` UI Bugs

#### A. User Manual Scroll Conflict during Synced Lyrics Playback
* **Location**: `NowPlayingScreen.kt` (lines 389–397)
* **Code Issue**:
  ```kotlin
  val listState = rememberLazyListState()

  LaunchedEffect(activeIndex) {
      if (activeIndex >= 0) {
          listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
      }
  }
  ```
* **Root Cause**:
  `LaunchedEffect(activeIndex)` triggers `animateScrollToItem` unconditionally whenever `activeIndex` updates as playback progresses. If a user attempts to manually drag or scroll through lyrics to read upcoming verses, the `LaunchedEffect` forces the scroll position back to `activeIndex - 2` on every timestamp update, competing with user interaction.

---

## Part 2: How the AI Audio & Lyrics Tagging Engine Works

```
                        [ Audio File / Path ]
                                  │
                                  ▼
                          ┌──────────────┐
                          │  PcmDecoder  │  (MediaExtractor + MediaCodec)
                          └──────┬───────┘
                                 │ Decodes 15s window @ 25% track position
                                 │ Downmixes stereo to mono float [-1.0, 1.0]
                                 │ Resamples to 16,000 Hz PCM
                                 ▼
                     ┌───────────────────────┐
                     │   SongTaggingEngine   │  (YAMNet TFLite Model)
                     └───────────┬───────────┘
                                 │ Runs inference on 521 AudioSet classes
                                 │ Mean-pools frame scores over time window
                                 │ Filters music range (132..276) @ threshold >= 0.08
                                 ▼
┌──────────────────┐    ┌─────────────────┐    ┌────────────────────────┐
│ AiMetadataCleaner│ ──►│AiFeatureManager │ ◄──│ LyricsEmbeddingEngine  │
└──────────────────┘    └────────┬────────┘    └────────────────────────┘
  Offline Regex +                │                all-MiniLM-L6-v2 ONNX
  Online AI (Gemini/GPT)         │                384-dim Sentence Vectors
                                 ▼
                     ┌───────────────────────┐
                     │   Room Database DAO   │  (ai_song_tags table)
                     └───────────────────────┘
```

### 1. `PcmDecoder.kt`: Audio Decoding Pipeline
* **Role**: Decodes audio files into 16kHz mono float PCM samples normalized to `[-1.0, 1.0]` for YAMNet model inference.
* **Execution Steps**:
  1. **Track Inspection**: Uses `MediaExtractor` to inspect `filePath` and locate the primary audio track format (`audio/*`).
  2. **Smart Position Seeking**: Checks track duration (`KEY_DURATION`). If duration > 30 seconds, seeks to ~25% into the track (`durationUs / 4`) using `SEEK_TO_CLOSEST_SYNC`. This avoids silent or ambient intros and extracts representative musical audio.
  3. **MediaCodec Synchronous Decoding**: Instantiates a synchronous `MediaCodec` decoder. Loops through input/output buffers to extract raw 16-bit PCM little-endian short samples.
  4. **Downmixing & Normalization**: Converts multi-channel PCM chunks into mono float samples normalized to `[-1.0, 1.0]`:
     $$\text{sample}_{\text{float}} = \frac{\sum_{c=0}^{N-1} \text{sample}_c}{N \times 32768.0}$$
  5. **Linear Resampling**: Uses `resampleLinear` to convert native sample rates (e.g. 44.1kHz / 48kHz) to 16,000 Hz.
  6. **Resource Cleanup**: Guarantees `MediaCodec.stop()/release()` and `MediaExtractor.release()` execution inside a `finally` block.

### 2. `SongTaggingEngine.kt`: On-Device TFLite Audio Classification
* **Role**: Classifies audio content into genre, instrument, and mood tags using Google's YAMNet TFLite model (`ai/yamnet.tflite`).
* **Execution Steps**:
  1. **Defensive Lazy Initialization**: Loads `ai/yamnet.tflite` model asset and `ai/yamnet_class_map.csv` class labels into direct byte buffers. If initialization fails, sets `initFailed = true` and returns `AiModelResult.Unavailable` defensively.
  2. **Label Filtering**: Restricts labels to AudioSet indices 132..276 (`MUSIC_LABEL_RANGE`), covering genres, instruments, and music moods.
  3. **Inference Execution**: Passes decoded PCM array from `PcmDecoder` into TFLite `Interpreter.run(pcm, output)`. The output tensor shape is `[numFrames, 521]`.
  4. **Time-Series Mean Pooling**: Computes the average classification score for each class across all time frames:
     $$\text{MeanScore}[c] = \frac{1}{F} \sum_{f=1}^{F} \text{Output}[f][c]$$
  5. **Ranking & Output**: Filters classes in `MUSIC_LABEL_RANGE` with score $\ge 0.08$, sorts in descending order, and returns up to the top 8 tags.

### 3. `AiFeatureManager.kt`: Orchestrator & Containment Boundary
* **Role**: The single gateway for on-device AI operations, isolating AI tasks on a `SupervisorJob() + Dispatchers.IO` scope.
* **Execution Steps**:
  1. **Isolation**: Exceptions in tagging or lyrics embedding are caught individually. A failure on one song never crashes the app or impairs other tracks.
  2. **Dual Extraction**:
     - Calls `SongTaggingEngine.tagAudioFile(localPath)` for audio tags.
     - Calls `LyricsEmbeddingEngine.embed(lyrics)` (384-dim all-MiniLM-L6-v2 ONNX model) for sentence embeddings.
  3. **Persistence**: Saves results into the `ai_song_tags` Room database table via `AiSongTagsDao.insertOrUpdate()`.
  4. **Lyrical Similarity Ranking**: Offers `rankBySimilarLyrics(seedSongId, candidateSongIds)`, calculating dot product (cosine similarity) on normalized 384-dim embeddings to drive smart recommendations and radio queues.

### 4. `AiMetadataCleaner.kt`: Metadata Normalization Engine
* **Role**: Two-stage cleaner (Offline Regex + Optional Online AI) that normalizes raw song titles and metadata.
* **Execution Steps**:
  1. **Offline Regex Stage (< 1 ms)**:
     - `TAG_REGEX`: Removes bracketed video/audio noise tags (`[Official Audio]`, `(Official Video)`, `[Lyric Video]`).
     - `PRODUCER_BRACKET_REGEX` & `PRODUCER_INLINE_REGEX`: Extracts producer credits (`prod. Metro Boomin`, `produced by Cardo`) into `CleanedMetadata.producer` and strips them from the title.
     - `FEAT_BRACKET_REGEX` & `FEAT_INLINE_REGEX`: Extracts featured artists (`feat. Drake`, `ft. Travis Scott`) into `CleanedMetadata.featuredArtist`.
     - `TOPIC_SUFFIX_REGEX`: Strips YouTube auto-generated `- Topic` channel suffixes.
     - `Artist - Title` Splitting: Splits raw strings on ` - ` when artist is missing.
  2. **Optional Online AI Stage**:
     - If an API key is configured (Gemini `AIza...` or OpenAI `sk-...`), executes a POST request on `Dispatchers.IO` requesting JSON metadata.
     - Immediately calls `connection.disconnect()` after parsing to ensure zero idle memory footprint.

---

## Part 3: Recommended Fixes & Pipeline Architecture

### 1. Enhanced `LyricsRepository.kt` Blueprint

#### A. Utilize `AiMetadataCleaner` & Robust LrcLib Query Fallbacks
Replace crude regex replacing in `fetchFromLrcLib` with `AiMetadataCleaner.cleanOffline`:

```kotlin
private fun fetchFromLrcLib(song: Song): String? {
    val cleaned = AiMetadataCleaner.cleanOffline(song.title, song.artist)
    val cleanTitle = cleaned.cleanTitle
    val cleanArtist = cleaned.artist ?: ""

    if (cleanTitle.isBlank()) return null

    val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8").replace("+", "%20")
    val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8").replace("+", "%20")
    val durationSec = (song.durationMs / 1000).toInt()

    // 1. Try exact GET with duration tolerance
    if (encodedArtist.isNotBlank() && durationSec > 0) {
        val exactUrl = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist&duration=$durationSec"
        executeLrcLibGet(exactUrl)?.let { return it }
    }

    // 2. Try GET without duration (handles minor duration mismatches)
    if (encodedArtist.isNotBlank()) {
        val noDurationUrl = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist"
        executeLrcLibGet(noDurationUrl)?.let { return it }
    }

    // 3. Search Fallback
    val query = if (encodedArtist.isNotBlank()) "$encodedArtist%20$encodedTitle" else encodedTitle
    val searchUrl = "https://lrclib.net/api/search?q=$query"
    executeLrcLibSearch(searchUrl)?.let { return it }

    return null
}
```

#### B. Robust LRC Timestamp Parser (`parseLyrics`)
Expand `LRC_PATTERN` to support multi-timestamp lines, colon centiseconds, and prevent metadata/unparsed line sorting bugs:

```kotlin
companion object {
    // Matches [mm:ss.ff], [mm:ss:ff], [m:ss.f], [mmm:ss.fff]
    private val LRC_TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{1,3})\]""")
    private val METADATA_TAG_REGEX = Regex("""^\[(ar|ti|al|by|offset|length):.*\]""", RegexOption.IGNORE_CASE)

    fun parseLyrics(rawLyrics: String?): List<LyricLine> {
        if (rawLyrics.isNullOrBlank()) return emptyList()

        val parsedLines = mutableListOf<LyricLine>()
        val lines = rawLyrics.split("\n")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || METADATA_TAG_REGEX.matches(trimmed)) continue

            val matches = LRC_TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val text = LRC_TIMESTAMP_REGEX.replace(trimmed, "").trim()
                for (match in matches) {
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = match.groupValues[3]

                    val millis = when (fracStr.length) {
                        1 -> fracStr.toLong() * 100
                        2 -> fracStr.toLong() * 10
                        else -> fracStr.take(3).toLong()
                    }
                    val totalMs = (minutes * 60 * 1000) + (seconds * 1000) + millis
                    parsedLines.add(LyricLine(timestampMs = totalMs, text = text))
                }
            } else {
                // Keep plain text without placing timestamp = 0L to avoid sorting corruption
                parsedLines.add(LyricLine(timestampMs = -1L, text = trimmed))
            }
        }

        val timestamped = parsedLines.filter { it.timestampMs >= 0 }.sortedBy { it.timestampMs }
        val plainText = parsedLines.filter { it.timestampMs < 0 }

        return if (timestamped.isNotEmpty()) timestamped else plainText
    }
}
```

#### C. WebVTT Cue Tag Cleaning
Strip HTML/cue formatting tags in `parseVttOrJsonCaptions`:

```kotlin
val cleanText = trimmed.replace(Regex("<[^>]*>"), "").trim()
if (currentTimestamp.isNotEmpty() && cleanText.isNotEmpty()) {
    builder.append("$currentTimestamp $cleanText\n")
}
```

---

### 2. `PlayerViewModel` & UI Persistence Blueprint

1. **Persist Streamed Songs before Saving Lyrics**:
   In `PlayerViewModel.fetchLyrics`:
   ```kotlin
   fun fetchLyrics(song: Song? = currentSong.value, forceFetch: Boolean = false) {
       val target = song ?: return
       if (lyricsRepository == null || repository == null) return

       viewModelScope.launch {
           _isScraping.value = true
           // Guarantee track is persisted in Room DB so updateSongLyrics succeeds
           val songId = repository.ensurePersisted(target)
           val persistedSong = repository.getSongById(songId) ?: target

           val fetched = lyricsRepository.fetchAndSaveLyrics(persistedSong, forceFetch = forceFetch)
           _lyrics.value = fetched
           _isScraping.value = false
       }
   }
   ```

2. **User-Scroll Friendly Synced Lyrics in `NowPlayingScreen.kt`**:
   Track user interaction state so `animateScrollToItem` is paused while the user is actively dragging:

   ```kotlin
   val isScrollInProgress = listState.isScrollInProgress

   LaunchedEffect(activeIndex) {
       if (activeIndex >= 0 && !isScrollInProgress) {
           listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
       }
   }
   ```

---

### 3. Integrated AI Audio/Text Pipeline Blueprint

```
[ New Song Downloaded / Added to Library ]
                    │
                    ▼
       ┌─────────────────────────┐
       │   MusicRepository       │
       └────────────┬────────────┘
                    │ Triggers analyzeSongIfNeeded(song)
                    ▼
       ┌─────────────────────────┐
       │    AiFeatureManager     │
       └────────────┬────────────┘
         ┌──────────┴──────────┐
         ▼                     ▼
┌──────────────────┐  ┌────────────────────────┐
│SongTaggingEngine │  │ LyricsEmbeddingEngine  │
└────────┬─────────┘  └───────────┬────────────┘
         │ Audio Tags             │ 384-dim Vector
         └──────────┬─────────────┘
                    ▼
       ┌─────────────────────────┐
       │   ai_song_tags Table    │
       └────────────┬────────────┘
                    │ Cached Tags & Vector
                    ▼
       ┌─────────────────────────┐
       │ MusicRepository.buildRadioQueue() │
       └─────────────────────────┘
         • Primary: Match Producer & Artist
         • Secondary: Cosine similarity on Lyrics Embedding
         • Tertiary: Match YAMNet Mood/Genre Audio Tags
```

By connecting `AiFeatureManager.analyzeSongIfNeeded(song)` to post-download triggers in `MusicRepository` and integrating `rankBySimilarLyrics` into `MusicRepository.buildRadioQueue()`, the app achieves a unified pipeline combining robust LrcLib/WebVTT lyrics fetching with on-device AI audio and text tagging.
