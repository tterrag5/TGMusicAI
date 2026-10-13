package com.example.tgmusicai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.youtube.YouTubeAlbumRef
import com.example.tgmusicai.data.youtube.YouTubeArtistRef
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.viewmodel.HomeViewModel
import com.example.tgmusicai.ui.viewmodel.SearchFilter
import com.example.tgmusicai.ui.viewmodel.SearchViewModel

/**
 * YT Music's Search tab: a search pill, then before typing your recent searches and discovery
 * picks from our own recommendation system; after typing, filter chips over YouTube Music results
 * (songs, artists, albums, playlists) and matches already in the library.
 */
@Composable
fun SearchScreen(
    searchViewModel: SearchViewModel,
    homeViewModel: HomeViewModel,
    onPlayCloud: (YouTubeSearchResult) -> Unit,
    onDownloadCloud: (YouTubeSearchResult) -> Unit,
    onPlayLibrarySong: (Song, List<Song>) -> Unit,
    onOpenArtist: (YouTubeArtistRef) -> Unit,
    onOpenAlbum: (YouTubeAlbumRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by searchViewModel.query.collectAsState()
    val filter by searchViewModel.filter.collectAsState()
    val songs by searchViewModel.songs.collectAsState()
    val artists by searchViewModel.artists.collectAsState()
    val albums by searchViewModel.albums.collectAsState()
    val playlists by searchViewModel.playlists.collectAsState()
    val library by searchViewModel.librarySongs.collectAsState()
    val isSearching by searchViewModel.isSearching.collectAsState()
    val recent by searchViewModel.recentSearches.collectAsState()
    val cloudRecs by homeViewModel.cloudRecommendations.collectAsState()
    val madeForYou by homeViewModel.recommendedSongs.collectAsState()
    val focus = LocalFocusManager.current

    // No Scaffold here (the theme scene shows through), so set the text colour Scaffold would.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground
    ) {
    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        TextField(
            value = query,
            onValueChange = searchViewModel::onQueryChanged,
            placeholder = { Text("Search songs, albums, artists") },
            leadingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { searchViewModel.clear(); focus.clearFocus() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { searchViewModel.onQueryChanged("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { searchViewModel.commitQuery(); focus.clearFocus() }),
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (query.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { searchViewModel.setFilter(option) },
                        label = { Text(option.label) },
                        shape = RoundedCornerShape(8.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                            selectedLabelColor = MaterialTheme.colorScheme.surface,
                        )
                    )
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (query.isBlank()) {
                if (recent.isNotEmpty()) {
                    sectionHeader("Recent searches")
                    items(recent, key = { "recent-$it" }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { searchViewModel.onQueryChanged(item) }
                                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(item, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { searchViewModel.removeRecentSearch(item) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove $item from recent searches", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (cloudRecs.isNotEmpty()) {
                    sectionHeader("Recommended for you")
                    items(cloudRecs, key = { "rec-${it.videoId}" }) { result ->
                        CloudSongRow(result, onPlay = { onPlayCloud(result) }, onDownload = { onDownloadCloud(result) })
                    }
                }
                if (madeForYou.isNotEmpty()) {
                    sectionHeader("Made for you")
                    items(madeForYou, key = { "mfy-${it.id}" }) { song ->
                        LibrarySongRow(song) { onPlayLibrarySong(song, madeForYou) }
                    }
                }
                return@LazyColumn
            }

            if (isSearching && songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
            }

            val all = filter == SearchFilter.ALL
            if ((all || filter == SearchFilter.LIBRARY) && library.isNotEmpty()) {
                sectionHeader("In your library")
                items(if (all) library.take(4) else library, key = { "lib-${it.id}" }) { song ->
                    LibrarySongRow(song) { searchViewModel.commitQuery(); onPlayLibrarySong(song, library) }
                }
            }
            if ((all || filter == SearchFilter.SONGS) && songs.isNotEmpty()) {
                sectionHeader("Songs")
                items(if (all) songs.take(5) else songs, key = { "song-${it.videoId}" }) { result ->
                    CloudSongRow(
                        result,
                        onPlay = { searchViewModel.commitQuery(); onPlayCloud(result) },
                        onDownload = { onDownloadCloud(result) }
                    )
                }
            }
            if ((all || filter == SearchFilter.ARTISTS) && artists.isNotEmpty()) {
                sectionHeader("Artists")
                items(if (all) artists.take(3) else artists, key = { "artist-${it.browseId}" }) { artist ->
                    EntityRow(
                        imageUrl = artist.thumbnailUrl,
                        title = artist.name,
                        subtitle = "Artist",
                        shape = CircleShape,
                    ) { searchViewModel.commitQuery(); onOpenArtist(artist) }
                }
            }
            if ((all || filter == SearchFilter.ALBUMS) && albums.isNotEmpty()) {
                sectionHeader("Albums")
                items(if (all) albums.take(3) else albums, key = { "album-${it.browseId}" }) { album ->
                    EntityRow(album.thumbnailUrl, album.title, album.subtitle) { searchViewModel.commitQuery(); onOpenAlbum(album) }
                }
            }
            if ((all || filter == SearchFilter.PLAYLISTS) && playlists.isNotEmpty()) {
                sectionHeader("Playlists")
                items(if (all) playlists.take(3) else playlists, key = { "pl-${it.browseId}" }) { playlist ->
                    EntityRow(playlist.thumbnailUrl, playlist.title, playlist.subtitle) { searchViewModel.commitQuery(); onOpenAlbum(playlist) }
                }
            }
            if (!isSearching && query.trim().length >= 2 &&
                library.isEmpty() && songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
            ) {
                item {
                    Text(
                        "No results for \"${query.trim()}\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun Thumb(url: String?, shape: Shape = RoundedCornerShape(6.dp)) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RowText(title: String, subtitle: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CloudSongRow(result: YouTubeSearchResult, onPlay: () -> Unit, onDownload: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Thumb(result.thumbnailUri)
        Spacer(modifier = Modifier.width(14.dp))
        val duration = if (result.durationSeconds > 0) " • " + FormatUtils.formatDuration(result.durationSeconds * 1000) else ""
        RowText(result.title, "Song • ${result.uploader}$duration", Modifier.weight(1f))
        IconButton(onClick = onDownload) {
            Icon(Icons.Rounded.Download, contentDescription = "Download ${result.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibrarySongRow(song: Song, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Thumb(FormatUtils.cacheBustedArtworkUri(song.artworkUri))
        Spacer(modifier = Modifier.width(14.dp))
        RowText(song.title, (if (song.isDownloaded) "Downloaded • " else "Song • ") + song.artist, Modifier.weight(1f))
    }
}

@Composable
private fun EntityRow(
    imageUrl: String?,
    title: String,
    subtitle: String,
    shape: Shape = RoundedCornerShape(6.dp),
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Thumb(imageUrl, shape)
        Spacer(modifier = Modifier.width(14.dp))
        RowText(title, subtitle, Modifier.weight(1f))
    }
}
