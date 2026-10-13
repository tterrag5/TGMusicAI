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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import com.example.tgmusicai.data.repository.LibraryAlbum
import com.example.tgmusicai.data.repository.LibraryArtist
import com.example.tgmusicai.data.repository.LibrarySort
import com.example.tgmusicai.ui.components.PlaylistCoverArt
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.viewmodel.LibraryPill

/** YT Music's Library header: filter pills, then the sort dropdown and a grid/list toggle. */
@Composable
fun LibraryPillsBar(
    pill: LibraryPill?,
    onPill: (LibraryPill) -> Unit,
    sort: LibrarySort,
    onSort: (LibrarySort) -> Unit,
    isGridView: Boolean?,
    onToggleGrid: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LibraryPill.entries.forEach { option ->
            FilterChip(
                selected = pill == option,
                onClick = { onPill(option) },
                label = { Text(option.label) },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp)
    ) {
        Box {
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = true }) {
                Text(sort.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Change sort order", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, fontWeight = if (option == sort) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { open = false; onSort(option) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (isGridView != null) {
            IconButton(onClick = onToggleGrid) {
                Icon(
                    if (isGridView) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                    contentDescription = if (isGridView) "Switch to list view" else "Switch to grid view"
                )
            }
        }
    }
}

/**
 * The Library with no pill selected: every playlist (Liked Music pinned first, the rest in the
 * chosen order), then the artists in the library, as YT Music's mixed list. A "New" button makes a
 * playlist.
 */
@Composable
fun LibraryOverviewList(
    playlists: List<PlaylistWithSongs>,
    artists: List<LibraryArtist>,
    playlistCovers: Map<Long, List<String>>,
    onPlaylistClick: (Long, String) -> Unit,
    onArtistClick: (LibraryArtist) -> Unit,
    onNewPlaylist: () -> Unit,
    isGrid: Boolean = false,
) {
    // Liked Music always exists, so an empty list only means playlists have not loaded yet. Drawing
    // the artists first would anchor the list on them and push the playlists above the top.
    if (playlists.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize()) {
        LibraryCollection(isGrid) {
            items(playlists, key = { "pl_${it.playlist.playlistId}" }) { pws ->
                val p = pws.playlist
                LibraryEntry(
                    isGrid = isGrid,
                    title = p.name,
                    subtitle = (if (p.isSmart) "Auto playlist" else "Playlist") + " • ${songCount(pws.songs.size)}",
                    pinned = p.isPinned || p.name == "Liked Music",
                    onClick = { onPlaylistClick(p.playlistId, p.name) },
                ) {
                    PlaylistCoverArt(artworkUris = playlistCovers[p.playlistId].orEmpty(), modifier = Modifier.fillMaxSize())
                }
            }
            items(artists, key = { "artist_${it.name}" }) { artist ->
                LibraryEntry(
                    isGrid = isGrid,
                    title = artist.name,
                    subtitle = "Artist • ${songCount(artist.songCount)}",
                    shape = CircleShape,
                    onClick = { onArtistClick(artist) },
                ) { Thumb(artist.artworkUri, Icons.Rounded.Person) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNewPlaylist,
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("New") },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }
}

@Composable
fun LibraryArtistList(artists: List<LibraryArtist>, isGrid: Boolean = false, onArtistClick: (LibraryArtist) -> Unit) {
    LibraryCollection(isGrid) {
        items(artists, key = { it.name }) { artist ->
            LibraryEntry(
                isGrid = isGrid,
                title = artist.name,
                subtitle = songCount(artist.songCount) + if (artist.plays > 0) " • ${artist.plays} plays" else "",
                shape = CircleShape,
                onClick = { onArtistClick(artist) },
            ) { Thumb(artist.artworkUri, Icons.Rounded.Person) }
        }
    }
}

@Composable
fun LibraryAlbumList(albums: List<LibraryAlbum>, isGrid: Boolean = false, onAlbumClick: (LibraryAlbum) -> Unit) {
    LibraryCollection(isGrid) {
        items(albums, key = { "${it.title}|${it.artist}" }) { album ->
            LibraryEntry(
                isGrid = isGrid,
                title = album.title,
                subtitle = "Album • ${album.artist} • ${songCount(album.songCount)}",
                onClick = { onAlbumClick(album) },
            ) { Thumb(album.artworkUri, Icons.Rounded.MusicNote) }
        }
    }
}

@Composable
private fun Thumb(uri: String?, placeholder: ImageVector) {
    if (!uri.isNullOrBlank()) {
        AsyncImage(
            model = FormatUtils.cacheBustedArtworkUri(uri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Icon(placeholder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The scrolling container for a Library view: a three-column grid of tiles, or a list of rows.
 * Both are a [LazyVerticalGrid] (one column for the list) so a view fills it the same way.
 */
@Composable
internal fun LibraryCollection(isGrid: Boolean, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (isGrid) 3 else 1),
        contentPadding = if (isGrid) PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 120.dp)
        else PaddingValues(bottom = 120.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

/** One playlist, artist or album: a [LibraryTile] in grid view, a [LibraryRow] in list view. */
@Composable
internal fun LibraryEntry(
    isGrid: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(6.dp),
    pinned: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    artwork: @Composable () -> Unit,
) {
    if (isGrid) {
        LibraryTile(title, subtitle, onClick, shape, pinned, onLongClick, artwork)
    } else {
        LibraryRow(title, subtitle, onClick, shape, pinned, onLongClick, artwork)
    }
}

/** Square artwork (round for artists) with the title and subtitle under it. */
@Composable
private fun LibraryTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    shape: Shape,
    pinned: Boolean,
    onLongClick: (() -> Unit)?,
    artwork: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = if (shape == CircleShape) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) { artwork() }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pinned) {
                Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(6.dp),
    pinned: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    artwork: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) { artwork() }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"
