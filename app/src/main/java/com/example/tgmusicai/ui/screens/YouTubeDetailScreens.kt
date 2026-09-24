package com.example.tgmusicai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.youtube.YouTubeAlbumRef
import com.example.tgmusicai.data.youtube.YouTubeArtistRef
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import com.example.tgmusicai.ui.components.YouTubeSearchResultItem
import com.example.tgmusicai.ui.viewmodel.DiscoverViewModel

/**
 * A YouTube Music artist's page: top tracks first, then albums, singles and related artists.
 *
 * [artistName] comes from whatever linked here so the title bar is filled in immediately, rather
 * than sitting blank for the length of a network round trip and then jumping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    browseId: String,
    artistName: String,
    discoverViewModel: DiscoverViewModel,
    onBack: () -> Unit,
    onPlayTrack: (YouTubeSearchResult) -> Unit,
    onPlayAll: (List<YouTubeSearchResult>, Boolean) -> Unit = { _, _ -> },
    onDownloadTrack: (YouTubeSearchResult) -> Unit,
    onOpenAlbum: (YouTubeAlbumRef) -> Unit,
    onOpenArtist: (YouTubeArtistRef) -> Unit,
    modifier: Modifier = Modifier
) {
    val artist by discoverViewModel.artist.collectAsState()
    val isLoading by discoverViewModel.isLoadingDetail.collectAsState()

    LaunchedEffect(browseId) { discoverViewModel.loadArtist(browseId) }

    DetailScaffold(
        title = artist?.name ?: artistName,
        onBack = onBack,
        modifier = modifier
    ) { innerPadding ->
        when {
            isLoading -> LoadingBody(innerPadding)
            artist == null -> UnavailableBody(
                innerPadding,
                "Couldn't load this artist. YouTube Music's internal pages change often, and this one didn't come back in a shape the app understands."
            )
            else -> {
                val page = artist!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            AsyncImage(
                                model = page.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(128.dp)
                                    .clip(CircleShape)
                            )
                            Text(
                                text = page.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            page.description?.let { description ->
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    if (page.topTracks.isNotEmpty()) {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Button(onClick = { onPlayAll(page.topTracks, false) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text("Play all", modifier = Modifier.padding(start = 6.dp))
                                }
                                OutlinedButton(onClick = { onPlayAll(page.topTracks, true) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Shuffle,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text("Shuffle", modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                        item { DetailSectionHeading("Top songs") }
                        items(page.topTracks, key = { "top_${it.videoId}" }) { track ->
                            YouTubeSearchResultItem(
                                result = track,
                                isExtracting = false,
                                downloadState = null,
                                onPlayClick = { onPlayTrack(track) },
                                onDownloadClick = { onDownloadTrack(track) }
                            )
                        }
                    }

                    if (page.albums.isNotEmpty()) {
                        item { DetailSectionHeading("Albums") }
                        items(page.albums, key = { "album_${it.browseId}" }) { album ->
                            TileRow(playlist = album, onClick = { onOpenAlbum(album) })
                        }
                    }

                    if (page.singles.isNotEmpty()) {
                        item { DetailSectionHeading("Singles") }
                        items(page.singles, key = { "single_${it.browseId}" }) { single ->
                            TileRow(playlist = single, onClick = { onOpenAlbum(single) })
                        }
                    }

                    if (page.relatedArtists.isNotEmpty()) {
                        item { DetailSectionHeading("Fans might also like") }
                        items(page.relatedArtists, key = { "related_${it.browseId}" }) { related ->
                            Surface(
                                onClick = { onOpenArtist(related) },
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(related.name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A YouTube Music album's page and its track listing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    browseId: String,
    albumTitle: String,
    discoverViewModel: DiscoverViewModel,
    onBack: () -> Unit,
    onPlayTrack: (YouTubeSearchResult) -> Unit,
    onDownloadTrack: (YouTubeSearchResult) -> Unit,
    onPlayAll: (List<YouTubeSearchResult>, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val album by discoverViewModel.album.collectAsState()
    val isLoading by discoverViewModel.isLoadingDetail.collectAsState()

    LaunchedEffect(browseId) { discoverViewModel.loadAlbum(browseId) }

    DetailScaffold(
        title = album?.title ?: albumTitle,
        onBack = onBack,
        modifier = modifier
    ) { innerPadding ->
        when {
            isLoading -> LoadingBody(innerPadding)
            album == null -> UnavailableBody(
                innerPadding,
                "Couldn't load this album. YouTube Music's internal pages change often, and this one didn't come back in a shape the app understands."
            )
            else -> {
                val page = album!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            AsyncImage(
                                model = page.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Text(
                                text = page.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp)
                            )
                            Text(
                                text = listOfNotNull(page.artist, page.year).joinToString(" • "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // A playlist or mix is a thing you put on, not a list to pick one
                            // song out of. Without these the only way to hear it was to tap a
                            // single track and get a queue of one.
                            if (page.tracks.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(top = 16.dp)
                                ) {
                                    Button(onClick = { onPlayAll(page.tracks, false) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text("Play all", modifier = Modifier.padding(start = 6.dp))
                                    }
                                    OutlinedButton(onClick = { onPlayAll(page.tracks, true) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Shuffle,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text("Shuffle", modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                        }
                    }

                    if (page.tracks.isEmpty()) {
                        item {
                            Text(
                                "No tracks listed for this album.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    } else {
                        items(page.tracks, key = { "track_${it.videoId}" }) { track ->
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier,
        content = content
    )
}

@Composable
private fun LoadingBody(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun UnavailableBody(innerPadding: PaddingValues, message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp)
        )
    }
}

@Composable
private fun DetailSectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}
