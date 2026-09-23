package com.example.tgmusicai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.youtube.YouTubeAlbumRef
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import com.example.tgmusicai.ui.components.YouTubeSearchResultItem
import com.example.tgmusicai.ui.viewmodel.DiscoverViewModel

/**
 * Discovery for the cloud half of the library: the mood and genre categories YouTube Music itself
 * publishes, and the current chart.
 *
 * The categories are fetched rather than hardcoded. Hardcoding a list of moods would be simpler,
 * but each one is addressed by an opaque token that cannot be guessed and has to come from that
 * page regardless -- and the set changes over time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    discoverViewModel: DiscoverViewModel,
    onOpenDrawer: () -> Unit,
    onPlayTrack: (YouTubeSearchResult) -> Unit,
    onDownloadTrack: (YouTubeSearchResult) -> Unit,
    onOpenAlbum: (YouTubeAlbumRef) -> Unit,
    modifier: Modifier = Modifier
) {
    val moods by discoverViewModel.moods.collectAsState()
    val charts by discoverViewModel.charts.collectAsState()
    val selectedMood by discoverViewModel.selectedMood.collectAsState()
    val moodPlaylists by discoverViewModel.moodPlaylists.collectAsState()
    val isLoading by discoverViewModel.isLoadingDiscover.collectAsState()

    LaunchedEffect(Unit) { discoverViewModel.loadDiscover() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Open navigation menu")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            if (moods.isNotEmpty()) {
                item {
                    SectionHeading("Moods & genres")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(moods, key = { it.browseId + it.title }) { mood ->
                            FilterChip(
                                selected = selectedMood?.title == mood.title,
                                // Tapping the open chip closes it, so the chart below is reachable
                                // again without a back gesture.
                                onClick = {
                                    discoverViewModel.selectMood(
                                        if (selectedMood?.title == mood.title) null else mood
                                    )
                                },
                                label = { Text(mood.title) },
                                colors = FilterChipDefaults.filterChipColors()
                            )
                        }
                    }
                }
            }

            if (selectedMood != null) {
                item {
                    SectionHeading(selectedMood?.title.orEmpty())
                }
                if (moodPlaylists.isEmpty()) {
                    item { EmptyNote("Nothing in this category right now.") }
                } else {
                    items(moodPlaylists, key = { "mood_${it.browseId}" }) { playlist ->
                        TileRow(playlist = playlist, onClick = { onOpenAlbum(playlist) })
                    }
                }
            } else {
                item { SectionHeading("Charts") }
                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (charts.isEmpty()) {
                    item {
                        EmptyNote("Charts aren't available right now. They're region-specific and YouTube doesn't publish them everywhere.")
                    }
                } else {
                    items(charts, key = { "chart_${it.videoId}" }) { track ->
                        YouTubeSearchResultItem(
                            result = track,
                            isExtracting = false,
                            downloadState = null,
                            onPlayClick = { onPlayTrack(track) },
                            onDownloadClick = { onDownloadTrack(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
    )
}

/** One album, single or playlist tile rendered as a list row. */
@Composable
internal fun TileRow(playlist: YouTubeAlbumRef, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            AsyncImage(
                model = playlist.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (playlist.subtitle.isNotBlank()) {
                    Text(
                        text = playlist.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
