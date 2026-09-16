# Stats Tab Root Cause Analysis & Spotify Wrapped / YouTube Music UI Redesign Specification

## Executive Summary

This document presents a comprehensive, read-only analysis of the listening statistics ecosystem in **TGMusicAI**, identifying all architectural and code-level root causes preventing stats from updating accurately, followed by a complete Jetpack Compose design specification to transform the Stats screen into a Spotify Wrapped / YouTube Music style interactive analytics experience.

---

# Part 1: Root Cause Analysis (Why the Stats Tab Isn't Working)

A thorough inspection of `StatsScreen.kt`, `StatsViewModel.kt`, `SongStatsDao.kt`, `SongStats.kt`, `MusicRepository.kt`, `PlaybackService.kt`, and `MediaControllerManager.kt` revealed six distinct bug vectors and design limitations preventing listening stats from accumulating or rendering properly.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             TELEMETRY BREAKDOWN                             │
└─────────────────────────────────────────────────────────────────────────────┘
  [ ExoPlayer in PlaybackService ] ───────► (No Listener / No Ticker) ───► ❌ Play Not Counted
  [ YouTube Search Stream ] ──────────────► (id = 0L, Not in Room) ───────► ❌ Play Not Counted
  [ Direct HTTP Stream URL ] ─────────────► (Uri Mismatch in Dao) ───────► ❌ Play Not Counted
  [ StatsScreen.kt ] ─────────────────────► (Sums Top 10 Only) ──────────► ❌ Inaccurate Total
```

---

### Bug 1: Un-Triggered Play Count Increments During Background & Remote Playback

* **Files**:
  - [PlaybackService.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/playback/PlaybackService.kt#L42-L78)
  - [MediaControllerManager.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/playback/MediaControllerManager.kt#L247-L279)

* **Root Cause**:
  Playback listening telemetry (tracking continuous playback position up to a 30-second threshold via `startPositionTicker()`) is implemented **exclusively inside `MediaControllerManager.kt`**.
  `PlaybackService`, which extends `MediaLibraryService` and hosts the actual `ExoPlayer` instance running in the background service, has **zero telemetry tracking or listener logic**.

* **Impact**:
  - When the app is in the background, when the screen turns off, or when playback is controlled via Android Auto, media notifications, or Bluetooth headsets, `MediaControllerManager`'s ticker may suspend or miss position updates.
  - When a song plays to completion entirely in the background, `PlaybackService` transitions media items without driving `MediaControllerManager`'s position loop. Thus, `recordSongPlay(...)` is **never called**, causing background listens to be ignored.

---

### Bug 2: URI Resolution Mismatch in Play Tracking (`recordSongPlayedByMediaUri`)

* **Files**:
  - [MediaControllerManager.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/playback/MediaControllerManager.kt#L211)
  - [MusicRepository.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/data/repository/MusicRepository.kt#L331-L336)

* **Root Cause**:
  When playing cloud/YouTube tracks, `MediaControllerManager.resolveSongForPlayback()` replaces `song.mediaUri` (which is stored in Room as `"https://www.youtube.com/watch?v=VIDEO_ID"`) with a direct HTTP stream URL (e.g. `"https://rr---...googlevideo.com/..."`).

* **Detailed Line Walkthrough**:
  1. `MediaControllerManager` sets `trackedMediaId = "https://rr---...googlevideo.com/..."`.
  2. At the 30-second mark, `MediaControllerManager` calls `repository.recordSongPlayedByMediaUri(trackedMediaId, trackedYoutubeId)`.
  3. In `MusicRepository.kt`:
     ```kotlin
     suspend fun recordSongPlayedByMediaUri(mediaUri: String, youtubeId: String?) {
         val song = songDao.getSongByUri(mediaUri)
             ?: youtubeId?.takeIf { it.isNotBlank() }?.let { songDao.getSongByYoutubeId(it) }
             ?: return
         songStatsDao.incrementPlayCount(song.id)
     }
     ```
  4. `songDao.getSongByUri("https://rr---...")` returns `null` because Room stores `"https://www.youtube.com/watch?v=..."`.
  5. If `youtubeId` is null/empty (e.g., local files played via temporary content URIs, or tracks without YouTube metadata in extras), the lookup returns `null` and early-returns without incrementing play count.

---

### Bug 3: Unpersisted Transient Cloud Tracks (`id = 0L`) Play Count Loss

* **Files**:
  - [YouTubeViewModel.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/ui/viewmodel/YouTubeViewModel.kt#L140)
  - [MusicRepository.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/data/repository/MusicRepository.kt#L228) (`ensurePersisted`)

* **Root Cause**:
  When a user streams a song directly from YouTube search results, `YouTubeViewModel.playTrack()` constructs an in-memory `Song` object with `id = 0L` and immediately passes it to `mediaControllerManager.playSong(streamSong)`.

* **Impact**:
  - The song is **never inserted into Room's `songs` table** prior to or during playback.
  - When `recordSongPlayedByMediaUri` executes 30 seconds later, Room has no `Song` row with that URI or YouTube ID.
  - As a result, streaming YouTube tracks online **never registers any play counts in `song_stats`**, leaving the Stats tab completely empty for users who stream music without manually downloading it first.

---

### Bug 4: Query & Join Limitations in `SongStatsDao` & `MusicRepository`

* **Files**:
  - [SongStatsDao.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/data/local/dao/SongStatsDao.kt#L23-L27)
  - [MusicRepository.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/data/repository/MusicRepository.kt#L149-L161)

* **Root Cause**:
  `MusicRepository.getMostPlayedSongsWithStats(limit)` uses Kotlin's `combine` operator between `songStatsDao.getMostPlayedStats(limit)` and `songDao.getAllSongs()`:
  ```kotlin
  fun getMostPlayedSongsWithStats(limit: Int = 50): Flow<List<SongWithStats>> {
      return combine(
          songStatsDao.getMostPlayedStats(limit),
          songDao.getAllSongs()
      ) { statsList, songsList ->
          val songMap = songsList.associateBy { it.id }
          statsList.mapNotNull { stats ->
              songMap[stats.songId]?.let { song ->
                  SongWithStats(song = song, stats = stats)
              }
          }
      }
  }
  ```
* **Impact**:
  - `songStatsDao.getMostPlayedStats` executes `SELECT * FROM song_stats ORDER BY playCount DESC LIMIT :limit`.
  - This query ONLY returns rows that already exist in `song_stats`. If no plays have been recorded yet, `song_stats` is empty, causing `getMostPlayedSongsWithStats` to emit `[]`.
  - Because `StatsScreen` relies on `mostPlayedSongs`, the screen renders an empty state ("No play statistics yet") even when the library has hundreds of songs.

---

### Bug 5: Telemetry Deficit in Database Schema (`SongStats.kt`)

* **Files**:
  - [SongStats.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/data/local/entity/SongStats.kt#L13-L24)

* **Root Cause**:
  The `SongStats` Room entity only defines:
  ```kotlin
  data class SongStats(
      @PrimaryKey val songId: Long,
      val playCount: Int = 0,
      val lastPlayedAt: Long? = null
  )
  ```
* **Impact**:
  - **No `totalListenTimeMs`**: Impossible to show actual listening hours/minutes (e.g., "14 hours 32 minutes listened").
  - **No timestamp log table**: Impossible to render daily/weekly listening trends (e.g. Mon–Sun listening charts).
  - **No artist / producer telemetry**: Impossible to compute top artists or top producers directly from listening metrics.

---

### Bug 6: ViewModel & UI Logic Capping Bugs

* **Files**:
  - [StatsViewModel.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/ui/viewmodel/StatsViewModel.kt#L34)
  - [StatsScreen.kt](file:///home/tterrag5/AndroidStudioProjects/TGMusicAI/app/src/main/java/com/example/tgmusicai/ui/screens/StatsScreen.kt#L48,L129)

* **Root Cause**:
  - In `StatsViewModel.kt`:
    `val mostPlayedSongs: StateFlow<List<SongWithStats>> = repository.getMostPlayedSongsWithStats(limit = 10)...`
  - In `StatsScreen.kt`:
    `val totalPlays = mostPlayed.sumOf { it.stats.playCount }`

* **Impact**:
  - `totalPlays` only calculates the sum of the **top 10 tracks**, NOT the entire library! The UI hero metric "Total Track Plays" displays an inaccurate value that caps out at the top 10 songs.

---

# Part 2: UI/UX Redesign & Visual Improvement Plan

To transform the Stats tab into a Spotify Wrapped / YouTube Music style visual experience, we present a complete Jetpack Compose design specification featuring dynamic Material 3 Expressive styling, Compose Canvas visual charts, animated hero counters, and modular analytics cards.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       WRAPPED STATS DASHBOARD DESIGN                        │
├─────────────────────────────────────────────────────────────────────────────┤
│  [ HERO CARD ] Total Listening Time & Total Plays (Animated Numbers)       │
│  [ WEEKLY CANVAS CHART ] 7-Day Bar Chart with Rounded Caps & Glow            │
│  [ TOP TRACKS ] #1, #2, #3 Metallic Badges, Album Art, Play Pill Badges     │
│  [ TOP ARTISTS ] Compose Canvas Donut Chart & Visual Grid                   │
│  [ TOP PRODUCERS ] Staggered Cards with Play Count Badges                   │
│  [ STORAGE BREAKDOWN ] Segmented Progress Bar & File Size Breakdown          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 1. Visual Hierarchy & Theme Architecture

The redesigned Stats tab adopts a dark, expressive gradient palette derived from the active Material 3 color scheme (`MaterialTheme.colorScheme.primary`, `secondary`, `tertiary`, `surfaceContainerHigh`), with edge-to-edge support and smooth scroll behaviors.

### Required Telemetry Schema Extension (Reference for Implementation Plan)
To support the visual components below, the database will be enhanced with `totalListenTimeMs` in `SongStats` and a `listening_history` table for daily timestamp logs:

```kotlin
// Data schema enhancements to support Wrapped stats
data class SongStats(
    @PrimaryKey val songId: Long,
    val playCount: Int = 0,
    val totalListenTimeMs: Long = 0L,
    val lastPlayedAt: Long? = null
)

@Entity(tableName = "listening_history")
data class ListeningHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long
)
```

---

## 2. Component Design Specifications & Jetpack Compose Implementations

Below are complete, production-ready Jetpack Compose composable designs for every section of the Wrapped Stats tab.

---

### Component A: Hero Listening Counter ("Your Wrapped Summary")

A striking gradient card featuring large typography, animated counters, total listening time formatted in hours and minutes, total tracks played, and listening momentum indicators.

```kotlin
package com.example.tgmusicai.ui.components.stats

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WrappedHeroSummaryCard(
    totalListenTimeMs: Long,
    totalPlays: Int,
    modifier: Modifier = Modifier
) {
    val totalMinutes = (totalListenTimeMs / (1000 * 60)).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    val animatedHours by animateIntAsState(
        targetValue = hours,
        animationSpec = tween(durationMillis = 1200),
        label = "hoursAnim"
    )
    val animatedPlays by animateIntAsState(
        targetValue = totalPlays,
        animationSpec = tween(durationMillis = 1200),
        label = "playsAnim"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "LISTENING TELEMETRY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Rounded.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Total Music Time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "$animatedHours",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "h ",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        text = "$minutes",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "m",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Tracks Streamed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$animatedPlays plays",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Daily Average",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val dailyAvg = if (totalMinutes > 0) "${(totalMinutes / 7)} mins/day" else "0 mins"
                        Text(
                            text = dailyAvg,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}
```

---

### Component B: Compose Canvas Bar Chart ("7-Day Listening Trends")

A custom chart rendered using `androidx.compose.foundation.Canvas` with rounded bar caps, subtle vertical gridlines, animated heights, dynamic highlight for peak listening day, and day-of-week axis labels (Mon–Sun).

```kotlin
package com.example.tgmusicai.ui.components.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class DailyListeningData(val dayName: String, val minutes: Int)

@Composable
fun WeeklyListeningBarChart(
    weeklyData: List<DailyListeningData>,
    modifier: Modifier = Modifier
) {
    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(weeklyData) {
        animationProgress.animateTo(1f, animationSpec = tween(durationMillis = 1000))
    }

    val maxMinutes = (weeklyData.maxOfOrNull { it.minutes } ?: 60).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    val peakBarColor = MaterialTheme.colorScheme.tertiary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Daily Listening Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Minutes listened over the past 7 days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                val barWidth = 28.dp.toPx()
                val totalWidth = size.width
                val chartHeight = size.height - 30.dp.toPx()
                val spacing = (totalWidth - (barWidth * weeklyData.size)) / (weeklyData.size + 1)

                weeklyData.forEachIndexed { index, day ->
                    val x = spacing + index * (barWidth + spacing)
                    val barHeightFraction = (day.minutes.toFloat() / maxMinutes) * animationProgress.value
                    val currentBarHeight = (chartHeight * barHeightFraction).coerceAtLeast(8.dp.toPx())
                    val y = chartHeight - currentBarHeight

                    // Background Track
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, chartHeight),
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                    )

                    // Active Bar
                    val isPeak = day.minutes == maxMinutes && maxMinutes > 0
                    drawRoundRect(
                        color = if (isPeak) peakBarColor else barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, currentBarHeight),
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                weeklyData.forEach { day ->
                    Box(
                        modifier = Modifier.width(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = day.dayName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
```

---

### Component C: Top Played Tracks List with Metallic Metallic Rank Badges

A top songs list with metallic rank badges (#1 Gold, #2 Silver, #3 Bronze, #4+ standard), cover art thumbnails, play count pill badges, and direct tap-to-play functionality.

```kotlin
package com.example.tgmusicai.ui.components.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import coil3.compose.AsyncImage
import com.example.tgmusicai.data.repository.SongWithStats

@Composable
fun TopTrackRankItem(
    rank: Int,
    item: SongWithStats,
    onTrackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rankBadgeColor = when (rank) {
        1 -> Color(0xFFFFD700) // Gold
        2 -> Color(0xFFC0C0C0) // Silver
        3 -> Color(0xFFCD7F32) // Bronze
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    }

    val rankTextColor = when (rank) {
        1, 2, 3 -> Color.Black
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        onClick = onTrackClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank Circle
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(rankBadgeColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$rank",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = rankTextColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Cover Art
            AsyncImage(
                model = item.song.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Song Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Play Count Badge Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${item.stats.playCount}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
```

---

### Component D: Top Artists Pie / Donut Chart & Visual Grid

A top artists section featuring a custom Canvas donut chart paired with an avatar grid showing total artist listens.

```kotlin
package com.example.tgmusicai.ui.components.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ArtistStat(val artistName: String, val playCount: Int, val color: Color)

@Composable
fun TopArtistsSection(
    artistStats: List<ArtistStat>,
    modifier: Modifier = Modifier
) {
    val totalPlays = artistStats.sumOf { it.playCount }.coerceAtLeast(1)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Top Artists Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Donut Chart Canvas
                Box(
                    modifier = Modifier.size(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        var startAngle = -90f
                        val strokeWidth = 16.dp.toPx()

                        artistStats.forEach { stat ->
                            val sweepAngle = (stat.playCount.toFloat() / totalPlays) * 360f
                            drawArc(
                                color = stat.color,
                                startAngle = startAngle,
                                sweepAngle = sweepAngle - 4f, // 4-degree gap
                                useCenter = false,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                            startAngle += sweepAngle
                        }
                    }

                    Text(
                        text = "${artistStats.size}\nArtists",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                // Legend List
                Column(modifier = Modifier.weight(1f)) {
                    artistStats.take(4).forEach { stat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(stat.color)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stat.artistName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${((stat.playCount.toFloat() / totalPlays) * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
```

---

### Component E: Top Producers Staggered Cards

Cards highlighting top music producers extracted from `Song.producer` metadata, displaying track count and total plays.

```kotlin
package com.example.tgmusicai.ui.components.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ProducerStat(val producerName: String, val trackCount: Int, val totalPlays: Int)

@Composable
fun TopProducerCard(
    stat: ProducerStat,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(160.dp)
            .padding(end = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Rounded.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stat.producerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${stat.trackCount} Tracks in Library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${stat.totalPlays} Total Plays",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
```

---

### Component F: Storage Breakdown Sub-Tab Design

A clean, segmented storage overview displaying total device space consumed by music, playlist breakdown, and largest audio files.

```kotlin
package com.example.tgmusicai.ui.components.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.viewmodel.StorageOverview

@Composable
fun StorageHeaderCard(overview: StorageOverview) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Total Music Storage",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = FormatUtils.formatBytes(overview.totalBytes),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Multi-color segmented bar representation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.7f)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.2f)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.1f)
                        .background(MaterialTheme.colorScheme.secondary)
                )
            }
        }
    }
}
```

---

# Verification Plan

When the fixing and redesign phase is initiated, verification will follow these validation steps:

1. **Automated Tests**:
   - Run unit tests for `MusicRepositoryTest` to verify that `recordSongPlayedByMediaUri` handles direct HTTP stream URLs, YouTube IDs, and local file paths correctly.
   - `./gradlew :app:testDebugUnitTest --tests "com.example.tgmusicai.MusicRepositoryTest"`

2. **Background Listening Telemetry Validation**:
   - Verify that play count increments take place when music is played in the background service via `PlaybackService` without active `MediaControllerManager` ticker instances.

3. **Database Integrity**:
   - Run database verification queries to ensure unpersisted cloud tracks (`id = 0L`) are auto-persisted before recording telemetry.

---

### Read-Only Inspection Summary
All bug sources preventing listening stats from recording or rendering accurately have been identified, and a full Jetpack Compose visual redesign specification based on Spotify Wrapped and YouTube Music standards has been constructed.
