# MiniPlayer Bar & Back Button System Specification

This document defines the comprehensive technical specification and blueprint for the YouTube Music MiniPlayer Bar redesign and the deterministic Android System Back Button & Gesture Navigation priority architecture using Jetpack Compose.

---

## Part 1: Minimal YouTube Music MiniPlayer Bar Specification

### 1. Visual & Physical Layout Requirements

The MiniPlayer is a compact, persistent bottom bar that displays active audio playback details and essential controls.

#### Component Breakdown

1. **Top Edge Progress Line**:
   - **Position**: Absolute top border of the MiniPlayer bar container.
   - **Height**: `2.dp` (ultra-thin, non-intrusive line).
   - **Track Background Color**: `Color.White.copy(alpha = 0.2f)` or `MaterialTheme.colorScheme.surfaceVariant`.
   - **Active Progress Color**: `MaterialTheme.colorScheme.primary` or vibrant accent color.
   - **Value**: Driven dynamically by `currentPositionMs / totalDurationMs` (normalized float `0f..1f`).

2. **Far Left Artwork Thumbnail**:
   - **Dimensions**: Fixed `48.dp` x `48.dp` square.
   - **Corner Radius**: `4.dp` rounded corners (`RoundedCornerShape(4.dp)`).
   - **Content**: Song album art image loaded asynchronously via Coil (`AsyncImage`).
   - **Content Scale**: `ContentScale.Crop`.
   - **Padding**: `12.dp` padding from container start.

3. **Center Metadata Column**:
   - **Layout**: `Column` with `Arrangement.Center`, centered vertically in row, occupying remaining space (`Modifier.weight(1f)`).
   - **Horizontal Padding**: `12.dp` after artwork, `12.dp` before action icons.
   - **Truncation**: Both title and artist use `maxLines = 1` with `TextOverflow.Ellipsis`.
   - **Song Title**:
     - *Example Text*: `Hymn for the Weekend`
     - *Typography*: Bold White (`color = Color.White`, `fontWeight = FontWeight.Bold`, `fontSize = 14.sp`).
   - **Artist Subtitle**:
     - *Example Text*: `Coldplay`
     - *Typography*: Light Gray (`color = Color.LightGray` / `Color(0xFFAAAAAA)`, `fontWeight = FontWeight.Normal`, `fontSize = 12.sp`).

4. **Far Right Action Controls (Strict 2-Icon Limit)**:
   - Exactly two action buttons aligned horizontally on the right side:
   - **Icon 1: Cast Button**:
     - *Visual*: Cast icon (`Icons.Rounded.Cast` or `Icons.Default.Cast`).
     - *Size*: Standard `24.dp` vector icon inside a touch-friendly `IconButton`.
     - *Color*: Solid White / Light Gray tint (`Color.White`).
     - *Action*: Opens media casting / device connection dialog (`onCastClick()`).
   - **Icon 2: Play / Pause Button**:
     - *Visual*: Dynamic Play (`Icons.Rounded.PlayArrow`) / Pause (`Icons.Rounded.Pause`) icon.
     - *Size*: `28.dp` vector icon inside `IconButton`.
     - *Color*: Solid White (`Color.White`).
     - *Action*: Toggles playback state (`play()` / `pause()`) on `PlayerViewModel` / `MediaController`.

---

### 2. Full Surface Touch Target & Click Consumption Architecture

#### Surface Touch Target Rules
- **Full Container Clickability**:
  - The root `Surface` or `Row` container MUST apply `Modifier.clickable { expandPlayer() }`.
  - Tapping anywhere on the artwork, title, artist name, progress line, or blank space opens the full `NowPlayingScreen` expanded sheet.

#### Event Propagation & Click Disambiguation
- **Child Button Isolation**:
  - `IconButton` composables (Play/Pause, Cast) consume their own touch pointer events within their bounding box.
  - Tapping the Play/Pause button toggles audio state **without** propagating the click to the parent container, preventing accidental player sheet expansion.

---

### 3. Jetpack Compose Code Blueprint

```kotlin
package com.example.tgmusicai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun MiniPlayerBar(
    songTitle: String,
    artistName: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    progressFraction: Float,
    onExpandPlayer: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onCastClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onExpandPlayer),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        tonalElevation = 3.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Top Edge Progress Line
            LinearProgressIndicator(
                progress = { progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.2f),
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Far Left: 48dp Artwork Thumbnail with 4dp Rounded Corners
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = "Album Artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Center: Song Title (14sp Bold White) + Artist Subtitle (12sp Light Gray)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = songTitle.ifEmpty { "Not Playing" },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artistName.ifEmpty { "Unknown Artist" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.LightGray
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Far Right: Cast Button
                IconButton(onClick = onCastClick) {
                    Icon(
                        imageVector = Icons.Rounded.Cast,
                        contentDescription = "Cast",
                        tint = Color.White
                    )
                }

                // Far Right: Play/Pause Button
                IconButton(onClick = onPlayPauseClick) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
```

---

## Part 2: Android System Back Button & Gesture Navigation Blueprint

### 1. Problem Statement

Currently, executing a system back swipe or pressing the device physical back button exits or minimizes the application, even when:
1. The full `NowPlayingScreen` player sheet is expanded over the main UI.
2. Active modal overlays (Queue Sheet, Add To Playlist dialog, overflow menus) are visible.
3. The user has navigated deep into sub-screens (`PlaylistDetailScreen`, `DownloadsScreen`, `SearchScreen`).

---

### 2. Back Handler Priority Architecture (Jetpack Compose `BackHandler`)

In Jetpack Compose, back navigation events are intercepted by `BackHandler` composables. When multiple `BackHandler` composables are registered, the one with `enabled = true` that was added last in composition (or at the highest active UI priority layer) takes precedence.

#### Priority Matrix Overview

```
+-------------------------------------------------------------------------+
| Priority 1: Expanded Player Sheet (isPlayerExpanded == true)           |
| Action: collapsePlayerSheet()                                           |
+-------------------------------------------------------------------------+
                                   |
                                   v (if collapsed)
+-------------------------------------------------------------------------+
| Priority 2: Active Dialogs / Bottom Sheets (Queue/Playlist Dialog Open)  |
| Action: closeActiveDialog() / closeQueueSheet()                         |
+-------------------------------------------------------------------------+
                                   |
                                   v (if no dialogs open)
+-------------------------------------------------------------------------+
| Priority 3: Navigation Backstack (canPopBackStack == true)             |
| Action: navController.popBackStack()                                    |
+-------------------------------------------------------------------------+
                                   |
                                   v (if on root HomeScreen)
+-------------------------------------------------------------------------+
| Priority 4: System Root Handler                                         |
| Action: Minimize / Exit Application                                     |
+-------------------------------------------------------------------------+
```

---

### 3. Detailed Priority Layers

#### Priority 1: Expanded Player Sheet
- **Condition**: `isPlayerExpanded == true`
- **Behavior**: Pressing back MUST collapse the player sheet back down to the MiniPlayer. It MUST NOT close active sub-screens or exit the app.
- **Compose Implementation**:
  ```kotlin
  BackHandler(enabled = isPlayerExpanded) {
      playerViewModel.collapsePlayerSheet()
  }
  ```

#### Priority 2: Active Dialogs / Modal Sheets
- **Condition**: `!isPlayerExpanded && (isQueueSheetOpen || isAddToPlaylistOpen || isOverflowMenuOpen)`
- **Behavior**: Dismisses the top-most dialog or bottom sheet while maintaining current screen navigation state.
- **Compose Implementation**:
  ```kotlin
  BackHandler(enabled = !isPlayerExpanded && isQueueSheetOpen) {
      playerViewModel.closeQueueSheet()
  }

  BackHandler(enabled = !isPlayerExpanded && !isQueueSheetOpen && isAddToPlaylistOpen) {
      mainViewModel.dismissAddToPlaylistDialog()
  }
  ```

#### Priority 3: Sub-Screen Navigation Backstack
- **Condition**: `!isPlayerExpanded && !isAnyDialogOpen && currentRoute != NavRoutes.Home`
- **Behavior**: Pops the navigation backstack entry to return to the previous screen (e.g. from `PlaylistDetailScreen` back to `HomeScreen`).
- **Compose Implementation**:
  ```kotlin
  val canPopBackStack = currentRoute != NavRoutes.Home && !isPlayerExpanded && !isQueueSheetOpen
  BackHandler(enabled = canPopBackStack) {
      navController.popBackStack()
  }
  ```

#### Priority 4: Root Home Screen Exit
- **Condition**: `!isPlayerExpanded && !isAnyDialogOpen && currentRoute == NavRoutes.Home`
- **Behavior**: No custom `BackHandler` is enabled. The event passes through to the Android system to exit/background the app cleanly.

---

### 4. Integration Blueprint Code (`MainScreen.kt`)

```kotlin
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    playerViewModel: PlayerViewModel,
    navController: NavHostController
) {
    val isPlayerExpanded by playerViewModel.isPlayerExpanded.collectAsState()
    val isQueueSheetOpen by playerViewModel.isQueueSheetOpen.collectAsState()
    val isAddToPlaylistOpen by mainViewModel.isAddToPlaylistOpen.collectAsState()
    val currentRoute by mainViewModel.currentRoute.collectAsState()

    // =========================================================================
    // PRIORITY 1: Expanded Player Sheet Interception
    // =========================================================================
    BackHandler(enabled = isPlayerExpanded) {
        playerViewModel.collapsePlayerSheet()
    }

    // =========================================================================
    // PRIORITY 2: Active Dialogs & Bottom Sheets Interception
    // =========================================================================
    BackHandler(enabled = !isPlayerExpanded && isQueueSheetOpen) {
        playerViewModel.closeQueueSheet()
    }

    BackHandler(enabled = !isPlayerExpanded && !isQueueSheetOpen && isAddToPlaylistOpen) {
        mainViewModel.dismissAddToPlaylistDialog()
    }

    // =========================================================================
    // PRIORITY 3: Navigation Backstack Interception
    // =========================================================================
    val canPopNavigation = !isPlayerExpanded && 
                           !isQueueSheetOpen && 
                           !isAddToPlaylistOpen && 
                           currentRoute != NavRoutes.Home
                           
    BackHandler(enabled = canPopNavigation) {
        navController.popBackStack()
    }

    // =========================================================================
    // PRIORITY 4: Root HomeScreen (No BackHandler active -> System backgrounding)
    // =========================================================================

    Scaffold(
        bottomBar = {
            Column {
                // MiniPlayer shown when a song is loaded and sheet is collapsed
                if (playerViewModel.hasMediaItem && !isPlayerExpanded) {
                    MiniPlayerBar(
                        songTitle = playerViewModel.currentSongTitle,
                        artistName = playerViewModel.currentArtistName,
                        artworkUrl = playerViewModel.currentArtworkUrl,
                        isPlaying = playerViewModel.isPlaying,
                        progressFraction = playerViewModel.progressFraction,
                        onExpandPlayer = { playerViewModel.expandPlayerSheet() },
                        onPlayPauseClick = { playerViewModel.togglePlayPause() },
                        onCastClick = { playerViewModel.openCastDialog() }
                    )
                }
                // Bottom Navigation Bar
                BottomNavigationBar(navController = navController)
            }
        }
    ) { paddingValues ->
        // NavHost Screen Content
        NavHostContent(
            navController = navController,
            modifier = Modifier.padding(paddingValues)
        )
    }
}
```

---

## Part 3: Acceptance Criteria & Verification Matrix

| Area | Requirement | Expected Result | Pass Criteria |
|---|---|---|---|
| **MiniPlayer Artwork** | 48dp x 48dp with 4dp rounded corners | Displayed on far left with Coil `AsyncImage` | Rounded image rendered cleanly |
| **MiniPlayer Title** | Bold white text, 14sp | Truncates with ellipsis if long | Single line bold white text |
| **MiniPlayer Subtitle**| Light gray text, 12sp | Truncates with ellipsis if long | Single line light gray text |
| **MiniPlayer Actions** | Exactly 2 buttons (Cast + Play/Pause) | Rendered on far right | Action buttons trigger correct state |
| **MiniPlayer Progress**| Thin 2dp progress bar along top edge | Shows relative song playback progress | Line updates dynamically during playback |
| **Full Surface Click** | Tap anywhere on bar (except action buttons) | Expands full `NowPlayingScreen` | Sheet elevates to expanded view |
| **Action Isolation** | Tap Play/Pause or Cast button | Action executes without expanding sheet | Audio plays/pauses; sheet remains collapsed |
| **Back Priority 1** | Back press when `isPlayerExpanded == true` | Collapses player sheet to MiniPlayer | Sheet collapses; app remains open |
| **Back Priority 2** | Back press when Queue/Dialog open | Closes open dialog or queue sheet | Dialog closes; player state intact |
| **Back Priority 3** | Back press when inside sub-screen | Pops navigation backstack | Returns to previous screen |
| **Back Priority 4** | Back press on root screen with player collapsed | Minimizes/exits application | Standard system exit behavior |
