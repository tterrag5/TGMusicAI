# YouTube Music Now Playing & Queue Sheet Redesign Specification

This document provides a production-grade blueprint for redesigning `NowPlayingScreen.kt` and `QueueSheet.kt` in the **TGMusicAI** project to match the exact YouTube Music mobile Android interface layout, visual tokens, interactive gestures, and state management conventions.

---

## 1. Overview & Architectural Principles

The redesign transforms the current generic player screen into an authentic **YouTube Music Mobile** experience adhering to modern **Material Design 3 (M3) Expressive** guidelines.

### Key UX Principles
1. **Visual Hierarchy**: Focus on high-contrast album art, prominent track metadata, and quick access action pills.
2. **Fluid Gestures**: Drag gestures for seeking and queue reordering; smooth modal sheet animations.
3. **YT Music Signature Patterns**:
   - Audio vs. Video top pill switcher (`🎧 Song` vs `▶ Video`).
   - Integrated Like/Dislike action pill (`👍 Liked | 👎`).
   - Circular prominent white Play/Pause action button (64dp icon container with black vector).
   - YT Music 3-State Loop icon (`REPEAT_MODE_OFF` ➔ `REPEAT_MODE_ALL` ➔ `REPEAT_MODE_ONE` with '1' badge).
   - "Playing from [Source]" bottom sheet header with scrollable recommendation filter chips.

---

## 2. Component Blueprint & UI Breakdowns

```
┌─────────────────────────────────────────────────────────┐
│ [∨]             [  🎧 Song  |  ▶ Video  ]       [📡] [⋮] │  <-- Top Bar Section
├─────────────────────────────────────────────────────────┤
│                                                         │
│                      [ ALBUM ART ]                      │  <-- 1:1 Aspect Square Artwork
│                                                         │
├─────────────────────────────────────────────────────────┤
│ Song Title Text                              [>]        │  <-- Title + Details Chevron
│ Artist Name • Subtitle                                  │  <-- Secondary Subtitle Text
├─────────────────────────────────────────────────────────┤
│ [ 👍 Liked | 👎 ]   [ ✦ ]   [ 💬 ]   [ ≡+ Save ]       │  <-- Action Pill Bar
├─────────────────────────────────────────────────────────┤
│  ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬●─────────────────────────────────  │  <-- Isolated Seek Slider
│  0:42                                            3:31   │  <-- Duration Labels
├─────────────────────────────────────────────────────────┤
│    [🔀]       [⏮]       (  ▶  )       [⏭]       [🔁¹]    │  <-- 5-Button Control Bar
├─────────────────────────────────────────────────────────┤
│  ─── Up Next • Playing from Liked Music ────────────────  │  <-- Queue Sheet Peek Handle
└─────────────────────────────────────────────────────────┘
```

---

## 3. Exhaustive Section Specifications

### 3.1 Top Bar Section
* **Left Element**: Collapse chevron (`Icons.Rounded.KeyboardArrowDown` or `Icons.Default.KeyboardArrowDown`, 32dp), triggers `onCollapse()`.
* **Center Element**: Segmented pill switch:
  - Surface container with `RoundedCornerShape(50)`.
  - Left segment: `🎧 Song` (Audio mode).
  - Right segment: `▶ Video` (Video mode / fallback preview).
  - Active segment receives high-contrast background (`MaterialTheme.colorScheme.surfaceVariant` / white container with dark text).
* **Right Elements**:
  - Cast button `Icons.Rounded.Cast` (opens MediaRoute / Cast dialog).
  - Overflow menu `Icons.Rounded.MoreVert` (opens options menu for Song Details, Sleep Timer, Playback Speed, Equalizer, and Share).

### 3.2 Album Artwork & Metadata Section
* **Artwork Container**:
  - `1:1` Aspect Ratio square box with rounded corners (`RoundedCornerShape(16.dp)`).
  - Optional subtle bottom-to-top gradient overlay (`Brush.verticalGradient`) to improve legibility if text overlaps or when in landscape.
  - Coil `AsyncImage` with `ContentScale.Crop` and placeholder fallback (`Icons.Rounded.MusicNote`).
* **Track Title**:
  - Headline style (`MaterialTheme.typography.titleLarge` or `headlineSmall`), `FontWeight.Bold`.
  - Inline or trailing clickable chevron `>` (`Icons.Rounded.ChevronRight`) that opens Song / Artist Metadata Info dialog.
* **Artist Subtitle**:
  - Body medium text in light gray / secondary tint (`MaterialTheme.colorScheme.onSurfaceVariant`).
  - Supports tapping artist name to navigate to Artist page or view album info.

### 3.3 Interactive Action Pill Bar
Row of compact horizontal chips/pills in `Arrangement.SpaceBetween` or `Arrangement.spacedBy(8.dp)`:
1. **Segmented Like / Dislike Pill**:
   - Shape: `RoundedCornerShape(50)`.
   - Left side: `👍 Liked` or `👍 Like` (toggles `isLiked` state connected to Music DB via `playerViewModel.toggleLikeCurrentSong()`).
   - Vertical Divider: Thin line (`Divider` / `Box` width 1.dp).
   - Right side: `👎 Dislike` (triggers next track and updates recommendation preferences).
2. **Star / AI Icon Button (`✦`)**:
   - `Icons.Rounded.AutoAwesome` or `Icons.Rounded.Star`.
   - Triggers AI Metadata scraping, Smart Radio generation (`playerViewModel.startRadio(currentSong)`), or AI Song Insights.
3. **Comments / Transcript Button (`💬`)**:
   - `Icons.Rounded.ChatBubbleOutline` or `Icons.Rounded.Lyrics`.
   - Opens interactive synced lyrics & transcript sheet view.
4. **Save Button (`≡+ Save`)**:
   - `Icons.Rounded.PlaylistAdd`.
   - Opens "Add to Playlist" selection dialog.

### 3.4 Progress Seek Bar & Time Labels
* **Custom M3 Slider**:
  - Height: Thin track (`trackHeight = 4.dp`), compact thumb.
  - Active Track Color: `MaterialTheme.colorScheme.onSurface` or pure white in dark theme.
  - Inactive Track Color: `MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)`.
* **Ticker Snap-Back Isolation**:
  - Local state `var dragPosition by remember { mutableStateOf<Float?>(null) }`.
  - `sliderValue` evaluates `dragPosition ?: currentPositionMs.toFloat()`.
  - `onValueChange` updates `dragPosition` without invoking `playerViewModel.seekTo()`.
  - `onValueChangeFinished` dispatches final `playerViewModel.seekTo(dragPosition.toLong())` and clears `dragPosition = null`.
* **Duration Labels**:
  - Left: `FormatUtils.formatDuration(displayPositionMs)` (e.g., `0:42`).
  - Right: `FormatUtils.formatDuration(durationMs)` (e.g., `3:31`).

### 3.5 Playback Control Bar
Horizontal control layout with 5 aligned action items:
1. **Far Left (Shuffle Toggle `🔀`)**:
   - `Icons.Rounded.Shuffle`.
   - Tint: `MaterialTheme.colorScheme.primary` when active, `MaterialTheme.colorScheme.onSurfaceVariant` when inactive.
2. **Mid Left (Skip Previous `⏮`)**:
   - `Icons.Rounded.SkipPrevious` (36dp).
   - Calls `playerViewModel.restartOrPrevious()`.
3. **Center (Play / Pause Hero Button `▶` / `⏸`)**:
   - Container: 64dp to 72dp white circle (`CircleShape`, `containerColor = Color.White` or `MaterialTheme.colorScheme.onSurface`).
   - Icon: Black vector `Icons.Rounded.Pause` / `Icons.Rounded.PlayArrow` (36dp–40dp, `tint = Color.Black`).
4. **Mid Right (Skip Next `⏭`)**:
   - `Icons.Rounded.SkipNext` (36dp).
   - Calls `playerViewModel.skipToNext()`.
5. **Far Right (YT Music 3-State Loop Button `🔁`)**:
   - Toggles state sequence: `Player.REPEAT_MODE_OFF` ➔ `Player.REPEAT_MODE_ALL` ➔ `Player.REPEAT_MODE_ONE`.
   - Display:
     - `REPEAT_MODE_OFF`: Gray `Icons.Rounded.Repeat` icon.
     - `REPEAT_MODE_ALL`: Primary color `Icons.Rounded.Repeat` icon.
     - `REPEAT_MODE_ONE`: Primary color `Icons.Rounded.RepeatOne` (or `Icons.Rounded.Repeat` with small '1' badge).

---

### 3.6 Interactive Queue / Up Next Bottom Sheet (`QueueSheet.kt`)
* **Drag Handle Bar**: Centered rounded bar (`32.dp` wide, `4.dp` high, `RoundedCornerShape(50)`).
* **Header Section**:
  - Line 1: `"Playing from"` subtitle (`MaterialTheme.typography.labelMedium`).
  - Line 2: Playlist / Context Name (e.g. `"Liked Music"`, `"Radio - Starboy"`, `"Top Hits"`) + `≡+ Save` action button.
* **Scrollable Filter Chips Row**:
  - Horizontal scrollable row (`LazyRow`, spacing `8.dp`).
  - Chips: `All`, `Familiar`, `Discover`, `Romance`, `Pump-up`.
  - Toggling chips filters or reprioritizes the upcoming queue items dynamically.
* **Reorderable / Scrollable Queue Item List**:
  - Item height: `56.dp`–`64.dp`.
  - Left: `48.dp` thumbnail artwork with `RoundedCornerShape(8.dp)`. Shows equalizer animated badge (`Icons.Rounded.Equalizer`) on currently playing item.
  - Center: Song Title (`FontWeight.Bold` if current) & Artist / View count subtitle.
  - Right: Drag Handle `≡` (`Icons.Rounded.DragHandle`) with pointer gesture input calling `onMoveSong(fromIndex, toIndex)`.

---

## 4. Exact Compose Layout Architecture & Code Snippets (Section 7)

Below are the complete, ready-to-implement Jetpack Compose snippets for both `NowPlayingScreen.kt` and `QueueSheet.kt`.

### 4.1 Production Redesign Code for `NowPlayingScreen.kt`

```kotlin
package com.example.tgmusicai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.viewmodel.EqualizerViewModel
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreenRedesign(
    playerViewModel: PlayerViewModel,
    equalizerViewModel: EqualizerViewModel,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSong by playerViewModel.currentSong.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val currentPositionMs by playerViewModel.currentPositionMs.collectAsState()
    val durationMs by playerViewModel.durationMs.collectAsState()
    val shuffleMode by playerViewModel.shuffleMode.collectAsState()
    val repeatMode by playerViewModel.repeatMode.collectAsState()
    val isLiked by playerViewModel.isCurrentSongLiked.collectAsState()
    val parsedLyrics by playerViewModel.parsedLyrics.collectAsState()

    var isVideoMode by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    // Song Details / Info Dialog
    if (showInfoDialog && currentSong != null) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(text = "Track Information") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Title: ${currentSong?.title}")
                    Text(text = "Artist: ${currentSong?.artist}")
                    if (!currentSong?.producer.isNullOrEmpty()) {
                        Text(text = "Producer: ${currentSong?.producer}")
                    }
                    Text(text = "Duration: ${FormatUtils.formatDuration(durationMs)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 1. Top Bar Section: Segmented Audio vs. Video Mode Switcher
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { isVideoMode = false },
                            shape = CircleShape,
                            color = if (!isVideoMode) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            contentColor = if (!isVideoMode) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = "🎧 Song",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                        Surface(
                            onClick = { isVideoMode = true },
                            shape = CircleShape,
                            color = if (isVideoMode) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            contentColor = if (isVideoMode) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = "▶ Video",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Collapse Player",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { /* Open Cast Dialog */ }) {
                        Icon(
                            imageVector = Icons.Rounded.Cast,
                            contentDescription = "Cast"
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Track Info") },
                                onClick = {
                                    showOverflowMenu = false
                                    showInfoDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Start Radio") },
                                onClick = {
                                    showOverflowMenu = false
                                    currentSong?.let { playerViewModel.startRadio(it) }
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 2. Album Artwork & Overlaid Metadata Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!currentSong?.artworkUri.isNullOrBlank()) {
                            AsyncImage(
                                model = FormatUtils.cacheBustedArtworkUri(currentSong?.artworkUri),
                                contentDescription = "Album Art",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Metadata Row: Track Title with Chevron & Artist Subtitle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { showInfoDialog = true }
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = currentSong?.title ?: "No Song Playing",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = "Song Info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = currentSong?.artist ?: "Unknown Artist",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 3. Interactive Action Pill Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Segmented Like/Dislike Pill
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { playerViewModel.toggleLikeCurrentSong() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (isLiked) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt,
                                contentDescription = "Like",
                                tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isLiked) "Liked" else "Like",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight(0.5f)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        IconButton(
                            onClick = { playerViewModel.skipToNext() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ThumbDownOffAlt,
                                contentDescription = "Dislike",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // AI / Star Radio Button (✦)
                Surface(
                    onClick = { currentSong?.let { playerViewModel.startRadio(it) } },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = "AI Radio",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Lyrics / Comments Button (💬)
                Surface(
                    onClick = { showLyricsSheet = !showLyricsSheet },
                    shape = CircleShape,
                    color = if (showLyricsSheet) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.ChatBubbleOutline,
                            contentDescription = "Lyrics",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Save to Playlist Button (≡+ Save)
                Surface(
                    onClick = { /* Open Playlist Dialog */ },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlaylistAdd,
                            contentDescription = "Save to Playlist",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 4. Progress Seek Bar & Time Labels (Isolated Drag State)
            var dragPosition by remember { mutableStateOf<Float?>(null) }
            val safeDuration = maxOf(1L, durationMs).toFloat()
            val currentPosFloat = currentPositionMs.toFloat().coerceIn(0f, safeDuration)
            val sliderValue = (dragPosition ?: currentPosFloat).coerceIn(0f, safeDuration)
            val displayPositionMs = (dragPosition ?: currentPosFloat).toLong()

            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = sliderValue,
                    onValueChange = { newPos -> dragPosition = newPos },
                    onValueChangeFinished = {
                        dragPosition?.let { playerViewModel.seekTo(it.toLong()) }
                        dragPosition = null
                    },
                    valueRange = 0f..safeDuration,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = MaterialTheme.colorScheme.onSurface,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = FormatUtils.formatDuration(displayPositionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = FormatUtils.formatDuration(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 5. Playback Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Far Left: Shuffle
                IconButton(onClick = playerViewModel::toggleShuffle) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Mid Left: Skip Previous
                IconButton(
                    onClick = playerViewModel::restartOrPrevious,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Skip Previous",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Center: YT Music Hero Play/Pause Button (64dp Circle with Contrast Vector)
                Surface(
                    onClick = playerViewModel::togglePlayPause,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                // Mid Right: Skip Next
                IconButton(
                    onClick = playerViewModel::skipToNext,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Skip Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Far Right: YT Music 3-State Loop Button (Off -> Repeat All -> Repeat One)
                IconButton(onClick = playerViewModel::toggleRepeat) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = "Repeat Mode",
                            tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}
```

---

### 4.2 Production Redesign Code for `QueueSheet.kt`

```kotlin
package com.example.tgmusicai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.ui.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheetRedesign(
    queue: List<Song>,
    currentSong: Song?,
    playingFromSource: String = "Liked Music",
    onSongClick: (index: Int) -> Unit,
    onMoveSong: (from: Int, to: Int) -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val currentIndex = queue.indexOfFirst { it.mediaUri == currentSong?.mediaUri }
    val listState = rememberLazyListState()
    var selectedFilterChip by remember { mutableStateOf("All") }

    val filterChips = listOf("All", "Familiar", "Discover", "Romance", "Pump-up")

    LaunchedEffect(currentSong?.mediaUri, queue.size) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // "Playing from" Header Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Playing from",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = playingFromSource,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    onClick = { /* Save Queue as Playlist */ },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Filter Chips Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filterChips) { chip ->
                    val isSelected = chip == selectedFilterChip
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilterChip = chip },
                        label = { Text(chip) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                            selectedLabelColor = MaterialTheme.colorScheme.surface,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }

            // Queue List
            if (queue.isEmpty()) {
                Text(
                    text = "No tracks in queue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
                ) {
                    itemsIndexed(queue, key = { _, song -> song.mediaUri }) { index, song ->
                        QueueRowRedesign(
                            song = song,
                            index = index,
                            isCurrent = index == currentIndex,
                            queueSize = queue.size,
                            onClick = { onSongClick(index) },
                            onMove = onMoveSong
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRowRedesign(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    queueSize: Int,
    onClick: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit
) {
    val latestIndex = rememberUpdatedState(index)
    val latestQueueSize = rememberUpdatedState(queueSize)

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val rowHeightPx = remember(density) { with(density) { 60.dp.toPx() } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffsetY }
            .background(
                if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface
            )
            .clickable(enabled = !isDragging, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail (48dp)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!song.artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = FormatUtils.cacheBustedArtworkUri(song.artworkUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Icon(
                    imageVector = if (isCurrent) Icons.Rounded.Equalizer else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title & Artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Drag Handle (≡)
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = "Reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .pointerInput(song.mediaUri) {
                    detectDragGestures(
                        onDragStart = { isDragging = true; dragOffsetY = 0f },
                        onDragEnd = { isDragging = false; dragOffsetY = 0f },
                        onDragCancel = { isDragging = false; dragOffsetY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetY += dragAmount.y
                            val curIndex = latestIndex.value
                            val size = latestQueueSize.value
                            if (dragOffsetY > rowHeightPx / 2 && curIndex < size - 1) {
                                onMove(curIndex, curIndex + 1)
                                dragOffsetY -= rowHeightPx
                            } else if (dragOffsetY < -rowHeightPx / 2 && curIndex > 0) {
                                onMove(curIndex, curIndex - 1)
                                dragOffsetY += rowHeightPx
                            }
                        }
                    )
                }
        )
    }
}
```

---

## 5. State Integration & Verification Plan

### ViewModel Binding Matrix

| UI Component | State Property / Flow | Action Trigger |
| :--- | :--- | :--- |
| **Like Button** | `playerViewModel.isCurrentSongLiked` | `playerViewModel.toggleLikeCurrentSong()` |
| **Seek Slider** | `playerViewModel.currentPositionMs` & `durationMs` | `playerViewModel.seekTo(positionMs)` |
| **Play / Pause** | `playerViewModel.isPlaying` | `playerViewModel.togglePlayPause()` |
| **Shuffle Mode**| `playerViewModel.shuffleMode` | `playerViewModel.toggleShuffle()` |
| **Repeat Mode** | `playerViewModel.repeatMode` | `playerViewModel.toggleRepeat()` |
| **AI Radio** | `currentSong` | `playerViewModel.startRadio(currentSong)` |
| **Queue Items** | `playerViewModel.playlist` | `playerViewModel.skipToIndex(i)`, `playerViewModel.moveQueueItem(from, to)` |

---

## 6. Implementation Checklist for Developer

- [ ] Import all Material 3 Expressive and `androidx.compose.material.icons.rounded.*` dependencies.
- [ ] Replace existing `NowPlayingScreen.kt` layout body with `NowPlayingScreenRedesign`.
- [ ] Replace existing `QueueSheet.kt` layout body with `QueueSheetRedesign`.
- [ ] Verify non-blocking drag behavior on seek slider during active audio ticker updates.
- [ ] Ensure full Edge-to-Edge inset compliance using `Scaffold(contentWindowInsets = ...)`.
- [ ] Test YouTube Music 3-state loop button transitions across `Player.REPEAT_MODE_OFF`, `Player.REPEAT_MODE_ALL`, and `Player.REPEAT_MODE_ONE`.
- [ ] Implement MiniPlayer-to-Full NowPlayingScreen progressive alpha crossfade and bottom navigation bar hiding (Section 8).

---

## 8. MiniPlayer Crossfade & Expansion Transition (Matching YouTube Music)

This section specifies the seamless visual transition between the collapsed `MiniPlayer` bar (docked above the bottom navigation bar) and the expanded full `NowPlayingScreen`.

### 8.1 Zero Dual-Visibility Constraint
* **Strict UX Requirement**: The bottom `MiniPlayer` bar (overlaid on Home/Library tabs) and the full `NowPlayingScreen` must **NEVER** be visible simultaneously as static overlapping layers.
* **Non-Overlapping Render**: When fully collapsed (`sheetProgress == 0f`), only the `MiniPlayer` is active. When fully expanded (`sheetProgress == 1f`), only the `NowPlayingScreen` is active. During interactive sheet dragging or sliding, elements transition dynamically using hardware-accelerated graphics layers without double-rendering controls or overlapping titles.

### 8.2 Progressive Alpha Crossfade Mechanics
As the user drags or expands the player sheet upward, expansion progress is tracked dynamically as `sheetProgress` from `0.0f` (collapsed) to `1.0f` (fully expanded):

1. **MiniPlayer Content Fade-Out**:
   - `miniPlayerAlpha = (1.0f - (sheetProgress / 0.20f)).coerceIn(0.0f, 1.0f)`
   - MiniPlayer content opacity (`alpha`) fades out from `1.0` to `0.0` within the first 15–20% of expansion (`sheetProgress in 0.0f..0.2f`).
   - Beyond `0.20f` progress, `miniPlayerAlpha` remains `0.0f` to prevent touch event interception and visual bleeding.

2. **Full NowPlayingScreen Content Fade-In**:
   - `fullPlayerAlpha = ((sheetProgress - 0.15f) / 0.85f).coerceIn(0.0f, 1.0f)`
   - Full `NowPlayingScreen` content opacity (`alpha`) fades in from `0.0` to `1.0` as progress increases beyond `0.15f` (`sheetProgress > 0.15f`).
   - Remains completely invisible (`0.0` opacity) until `sheetProgress` reaches `0.15f`.

3. **Bottom Navigation Bar Hiding**:
   - `navBarTranslationY = (sheetProgress / 0.10f).coerceIn(0.0f, 1.0f) * navBarHeightPx`
   - `navBarAlpha = (1.0f - (sheetProgress / 0.10f)).coerceIn(0.0f, 1.0f)`
   - As the player sheet expands past 10% progress (`sheetProgress > 0.10f`), the main bottom navigation bar translates downwards off-screen while fading out, giving 100% screen focus to `NowPlayingScreen`.

---

### 8.3 Jetpack Compose Code Snippets for Transition & State Handling

Below is the complete Jetpack Compose pattern demonstrating `BottomSheetScaffold` progress calculation, `graphicsLayer(alpha = ...)` crossfading, and bottom navigation bar translation for Claude to implement.

```kotlin
package com.example.tgmusicai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.ui.screens.NowPlayingScreenRedesign
import com.example.tgmusicai.ui.viewmodel.EqualizerViewModel
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel

/**
 * Container component handling sheet progress calculation, graphicsLayer alpha crossfading,
 * and bottom navigation bar offset hiding matching YouTube Music.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandablePlayerContainer(
    playerViewModel: PlayerViewModel,
    equalizerViewModel: EqualizerViewModel,
    bottomNavContent: @Composable (modifier: Modifier) -> Unit,
    mainScreenContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val miniPlayerHeight = 64.dp
    val miniPlayerHeightPx = with(density) { miniPlayerHeight.toPx() }

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalHeightPx = with(density) { maxHeight.toPx() }

        // Dynamic sheet expansion progress: 0.0f (Collapsed MiniPlayer) -> 1.0f (Full NowPlayingScreen)
        val sheetProgress by remember {
            derivedStateOf {
                val currentOffset = runCatching { sheetState.requireOffset() }.getOrDefault(totalHeightPx - miniPlayerHeightPx)
                val collapsedOffset = totalHeightPx - miniPlayerHeightPx
                val expandedOffset = 0f
                val totalDistance = (collapsedOffset - expandedOffset).coerceAtLeast(1f)
                ((collapsedOffset - currentOffset) / totalDistance).coerceIn(0f, 1f)
            }
        }

        // MiniPlayer Alpha: Fades out 1.0 -> 0.0 within 0.0f..0.20f progress
        val miniPlayerAlpha by remember {
            derivedStateOf { (1.0f - (sheetProgress / 0.20f)).coerceIn(0.0f, 1.0f) }
        }

        // Full NowPlayingScreen Alpha: Fades in 0.0 -> 1.0 beyond 0.15f progress
        val fullPlayerAlpha by remember {
            derivedStateOf { ((sheetProgress - 0.15f) / 0.85f).coerceIn(0.0f, 1.0f) }
        }

        // Bottom Nav Bar Hiding: Translates down and fades out past 10% progress
        val navBarTranslationYPx by remember {
            derivedStateOf { (sheetProgress / 0.10f).coerceIn(0.0f, 1.0f) * miniPlayerHeightPx * 2f }
        }
        val navBarAlpha by remember {
            derivedStateOf { (1.0f - (sheetProgress / 0.10f)).coerceIn(0.0f, 1.0f) }
        }

        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = miniPlayerHeight,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContent = {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Full NowPlayingScreen Layer (Visible & Fading In when fullPlayerAlpha > 0f)
                    if (fullPlayerAlpha > 0f) {
                        NowPlayingScreenRedesign(
                            playerViewModel = playerViewModel,
                            equalizerViewModel = equalizerViewModel,
                            onCollapse = {
                                // Collapse sheet programmatically back to MiniPlayer
                            },
                            onOpenQueue = { /* Open Queue Sheet */ },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = fullPlayerAlpha
                                }
                        )
                    }

                    // Collapsed MiniPlayer Bar Layer (Visible & Fading Out when miniPlayerAlpha > 0f)
                    if (miniPlayerAlpha > 0f) {
                        MiniPlayerBar(
                            playerViewModel = playerViewModel,
                            onClick = {
                                // Expand sheet programmatically
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(miniPlayerHeight)
                                .align(Alignment.TopCenter)
                                .graphicsLayer {
                                    alpha = miniPlayerAlpha
                                }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Main Tab Screen Content (Home / Library / Search)
                mainScreenContent()

                // Main Bottom Navigation Bar with dynamic translation & alpha hiding
                bottomNavContent(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer {
                            translationY = navBarTranslationYPx
                            alpha = navBarAlpha
                        }
                )
            }
        }
    }
}

/**
 * Collapsed MiniPlayer Bar Component docked above Main Bottom Navigation Bar.
 */
@Composable
fun MiniPlayerBar(
    playerViewModel: PlayerViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Render MiniPlayer artwork thumbnail, track title, play/pause & skip controls
    }
}
```

