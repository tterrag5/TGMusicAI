package com.example.tgmusicai.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.repository.ArtistPlayStats
import com.example.tgmusicai.data.repository.DailyListeningData
import com.example.tgmusicai.data.repository.ProducerStat
import com.example.tgmusicai.data.repository.SongWithStats
import com.example.tgmusicai.ui.theme.generateDistinctChartPalette
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel
import com.example.tgmusicai.ui.viewmodel.PlaylistStorageInfo
import com.example.tgmusicai.ui.viewmodel.SongStorageInfo
import com.example.tgmusicai.ui.viewmodel.StatsViewModel

/**
 * Screen displaying listening statistics: total listening time/plays, a weekly listening trend
 * chart, top tracks/artists/producers, and on-disk storage usage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    statsViewModel: StatsViewModel,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val mostPlayed by statsViewModel.mostPlayedSongs.collectAsState()
    val totalPlays by statsViewModel.totalPlayCount.collectAsState()
    val totalListenTimeMs by statsViewModel.totalListenTimeMs.collectAsState()
    val weeklyTrend by statsViewModel.weeklyListeningTrend.collectAsState()
    val topArtists by statsViewModel.topArtists.collectAsState()
    val topProducers by statsViewModel.topProducers.collectAsState()
    val storageOverview by statsViewModel.storageOverview.collectAsState()
    val isLoadingStorage by statsViewModel.isLoadingStorage.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && storageOverview == null) {
            statsViewModel.refreshStorageOverview()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stats", style = MaterialTheme.typography.headlineMedium) },
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
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Listening") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Storage") }
                )
            }

            if (selectedTab == 0) {
                ListeningStatsTab(
                    mostPlayed = mostPlayed,
                    totalPlays = totalPlays,
                    totalListenTimeMs = totalListenTimeMs,
                    weeklyTrend = weeklyTrend,
                    topArtists = topArtists,
                    topProducers = topProducers,
                    playerViewModel = playerViewModel
                )
            } else {
                StorageStatsTab(
                    overview = storageOverview,
                    isLoading = isLoadingStorage,
                    onRemoveDownload = statsViewModel::removeDownload
                )
            }
        }
    }
}

@Composable
private fun ListeningStatsTab(
    mostPlayed: List<SongWithStats>,
    totalPlays: Int,
    totalListenTimeMs: Long,
    weeklyTrend: List<DailyListeningData>,
    topArtists: List<ArtistPlayStats>,
    topProducers: List<ProducerStat>,
    playerViewModel: PlayerViewModel
) {
    if (mostPlayed.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            WrappedHeroSummaryCard(
                totalListenTimeMs = totalListenTimeMs,
                totalPlays = totalPlays,
                weeklyTrend = weeklyTrend
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.BarChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No play statistics yet. Start listening to songs to record your stats!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            WrappedHeroSummaryCard(
                totalListenTimeMs = totalListenTimeMs,
                totalPlays = totalPlays,
                weeklyTrend = weeklyTrend
            )
        }

        if (weeklyTrend.any { it.minutes > 0 }) {
            item {
                WeeklyListeningBarChart(weeklyTrend)
            }
        }

        if (topArtists.isNotEmpty()) {
            item {
                SectionHeader("Top Artists")
            }
            item {
                TopArtistsDonutCard(topArtists)
            }
        }

        if (topProducers.isNotEmpty()) {
            item {
                SectionHeader("Top Producers")
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(topProducers, key = { it.producer }) { stat ->
                        TopProducerCard(stat)
                    }
                }
            }
        }

        item {
            SectionHeader("Most Played Songs")
        }

        itemsIndexed(mostPlayed, key = { _, item -> item.song.id }) { index, item ->
            StatSongItem(
                rank = index + 1,
                item = item,
                onClick = {
                    playerViewModel.playSong(
                        song = item.song,
                        queue = mostPlayed.map { it.song }
                    )
                },
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * Hero card: total listening time (h/m), total plays, and the daily average over the trailing
 * 7 days (from [weeklyTrend]) -- not all-time total minutes divided by 7, which understates the
 * real recent pace for any listener with history older than a week.
 */
@Composable
private fun WrappedHeroSummaryCard(
    totalListenTimeMs: Long,
    totalPlays: Int,
    weeklyTrend: List<DailyListeningData>
) {
    val totalMinutes = (totalListenTimeMs / (1000 * 60)).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val dailyAvgMinutes = if (weeklyTrend.isNotEmpty()) {
        weeklyTrend.sumOf { it.minutes } / weeklyTrend.size
    } else {
        0
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total Listening Time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$hours",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "h ",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "$minutes",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "m",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Total Plays",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$totalPlays",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Daily Avg (7d)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$dailyAvgMinutes min",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 7-day listening trend bar chart, rendered with Canvas. Peak day is the same hue as the other
 * bars but at full opacity against dimmed neighbors, so it reads as "highlighted" regardless of
 * which ColorScheme role a theme happens to map -- unlike [MaterialTheme.colorScheme.tertiary],
 * which is a low-contrast grey in every [com.example.tgmusicai.ui.theme.AppTheme] here. Tapping a
 * bar shows its exact value; value labels above each bar make that visible without a tap too.
 */
@Composable
private fun WeeklyListeningBarChart(weeklyData: List<DailyListeningData>) {
    val maxMinutes = (weeklyData.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)
    val peakBarColor = MaterialTheme.colorScheme.primary
    val normalBarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurface
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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
                }
                val tappedDay = selectedIndex?.let { weeklyData.getOrNull(it) }
                if (tappedDay != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${tappedDay.dayName}: ${tappedDay.minutes} min",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(weeklyData) {
                        detectTapGestures { offset ->
                            val barWidth = 28.dp.toPx()
                            val spacing = (size.width - (barWidth * weeklyData.size)) / (weeklyData.size + 1)
                            val tappedIndex = weeklyData.indices.firstOrNull { index ->
                                val x = spacing + index * (barWidth + spacing)
                                offset.x in x..(x + barWidth)
                            }
                            selectedIndex = if (tappedIndex == selectedIndex) null else tappedIndex
                        }
                    }
            ) {
                val barWidth = 28.dp.toPx()
                val totalWidth = size.width
                val chartHeight = size.height
                val spacing = (totalWidth - (barWidth * weeklyData.size)) / (weeklyData.size + 1)
                val labelPaint = android.graphics.Paint().apply {
                    color = labelColor.toArgb()
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 11.sp.toPx()
                    isAntiAlias = true
                }

                weeklyData.forEachIndexed { index, day ->
                    val x = spacing + index * (barWidth + spacing)
                    val barHeightFraction = day.minutes.toFloat() / maxMinutes
                    val currentBarHeight = (chartHeight * barHeightFraction).coerceAtLeast(8.dp.toPx())
                    val y = chartHeight - currentBarHeight

                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, chartHeight),
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                    )

                    val isPeak = day.minutes == maxMinutes && maxMinutes > 0
                    drawRoundRect(
                        color = if (isPeak) peakBarColor else normalBarColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, currentBarHeight),
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                    )

                    if (day.minutes > 0) {
                        drawContext.canvas.nativeCanvas.drawText(
                            "${day.minutes}",
                            x + barWidth / 2f,
                            (y - 6.dp.toPx()).coerceAtLeast(labelPaint.textSize),
                            labelPaint
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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

@Composable
private fun TopArtistsDonutCard(topArtists: List<ArtistPlayStats>) {
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val colored = remember(topArtists, isDarkTheme) {
        val palette = generateDistinctChartPalette(topArtists.size, isDarkTheme)
        topArtists.zip(palette)
    }
    val totalPlays = topArtists.sumOf { it.totalPlayCount }.coerceAtLeast(1)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(110.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    var startAngle = -90f
                    val strokeWidth = 16.dp.toPx()
                    colored.forEach { (stat, color) ->
                        val sweepAngle = (stat.totalPlayCount.toFloat() / totalPlays) * 360f
                        drawArc(
                            color = color,
                            startAngle = startAngle,
                            sweepAngle = (sweepAngle - 4f).coerceAtLeast(1f),
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                        startAngle += sweepAngle
                    }
                }
                Text(
                    text = "${topArtists.size}\nArtists",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            Column(modifier = Modifier.weight(1f)) {
                colored.forEach { (stat, color) ->
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
                                .background(color)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stat.artist,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${((stat.totalPlayCount.toFloat() / totalPlays) * 100).toInt()}%",
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

@Composable
private fun TopProducerCard(stat: ProducerStat) {
    Card(
        modifier = Modifier
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
                text = stat.producer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${stat.trackCount} ${if (stat.trackCount == 1) "track" else "tracks"} in library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${stat.totalPlayCount} total plays",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun StatSongItem(
    rank: Int,
    item: SongWithStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Muted metallic tones instead of web-bright gold/silver/bronze, which clash against the
    // app's desaturated dark surfaces.
    val rankBadgeColor = when (rank) {
        1 -> Color(0xFFD4AF37) // muted gold
        2 -> Color(0xFFA8A8A8) // muted silver
        3 -> Color(0xFFB08D57) // muted bronze
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val rankTextColor = when (rank) {
        1, 2, 3 -> Color.Black
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Metallic rank badge for the top 3, plain numeral otherwise
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

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!item.song.artworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = FormatUtils.cacheBustedArtworkUri(item.song.artworkUri),
                        contentDescription = "Cover art for ${item.song.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.song.artist} • Last played ${FormatUtils.formatTimestamp(item.stats.lastPlayedAt ?: 0L)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Play count pill badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${item.stats.playCount} plays",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun StorageStatsTab(
    overview: com.example.tgmusicai.ui.viewmodel.StorageOverview?,
    isLoading: Boolean,
    onRemoveDownload: (Long) -> Unit
) {
    if (overview == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = "No storage data yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            StorageHeaderCard(overview)
        }

        item {
            Text(
                text = "Storage by Playlist",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        val maxPlaylistBytes = overview.playlists.maxOfOrNull { it.sizeBytes }?.coerceAtLeast(1L) ?: 1L
        items(overview.playlists, key = { "playlist_${it.playlist.playlistId}" }) { info ->
            StoragePlaylistRow(info = info, fraction = info.sizeBytes.toFloat() / maxPlaylistBytes)
        }

        item {
            Text(
                text = "Biggest Songs",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            )
        }

        if (overview.biggestSongs.isEmpty()) {
            item {
                Text(
                    text = "No downloaded songs yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            itemsIndexed(overview.biggestSongs, key = { _, item -> "song_${item.song.id}_${item.song.mediaUri}" }) { index, item ->
                StorageSongRow(
                    rank = index + 1,
                    item = item,
                    onRemoveDownload = { onRemoveDownload(item.song.id) }
                )
            }
        }
    }
}

/** Storage hero card with a segmented bar showing the top few playlists' share of total storage. */
@Composable
private fun StorageHeaderCard(overview: com.example.tgmusicai.ui.viewmodel.StorageOverview) {
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val topPlaylists = remember(overview) {
        overview.playlists.sortedByDescending { it.sizeBytes }.take(4)
    }
    val palette = remember(topPlaylists, isDarkTheme) {
        generateDistinctChartPalette(topPlaylists.size, isDarkTheme)
    }
    val totalBytes = overview.totalBytes.coerceAtLeast(1L)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(24.dp)
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = FormatUtils.formatBytes(overview.totalBytes),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            if (topPlaylists.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    topPlaylists.forEachIndexed { index, info ->
                        val fraction = (info.sizeBytes.toFloat() / totalBytes).coerceIn(0.01f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(fraction)
                                .background(palette[index])
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                topPlaylists.forEachIndexed { index, info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(palette[index])
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = info.playlist.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = FormatUtils.formatBytes(info.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoragePlaylistRow(info: PlaylistStorageInfo, fraction: Float) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = info.playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${info.songCount} ${if (info.songCount == 1) "song" else "songs"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = FormatUtils.formatBytes(info.sizeBytes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Biggest-downloaded-song row. The trailing trash icon frees local storage for that one song
 *  (behind a confirmation, since deleting the file is not undoable) while keeping the song in
 *  the library and every playlist to stream from YouTube instead. */
@Composable
private fun StorageSongRow(rank: Int, item: SongStorageInfo, onRemoveDownload: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Remove download?") },
            text = {
                Text(
                    "Deletes the local file for \"${item.song.title}\" to free " +
                        "${FormatUtils.formatBytes(item.sizeBytes)}. It stays in your library " +
                        "and playlists, streaming from YouTube instead."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onRemoveDownload()
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(36.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.song.title,
                    style = MaterialTheme.typography.bodyLarge,
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

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = FormatUtils.formatBytes(item.sizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }

            IconButton(onClick = { showConfirm = true }) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Remove download for ${item.song.title}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
