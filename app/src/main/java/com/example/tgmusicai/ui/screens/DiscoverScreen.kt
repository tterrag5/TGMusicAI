package com.example.tgmusicai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.youtube.YouTubeAlbumRef
import com.example.tgmusicai.data.youtube.YouTubeArtistRef
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import com.example.tgmusicai.ui.components.CloudTile
import com.example.tgmusicai.ui.viewmodel.DiscoverViewModel

/** Columns in the Discover grid, matching the Library's own song grid. */
private const val DISCOVER_GRID_COLUMNS = 3

/**
 * How close to the end of the grid the user has to get before the next page is fetched. Two rows'
 * worth: enough that the page usually lands before they reach the gap, not so much that pages are
 * fetched for positions they never scroll to.
 */
private const val FEED_LOAD_AHEAD_ITEMS = 6

/**
 * Discovery for the cloud half of the library: a search for playlists and mixes, YouTube Music's
 * own recommendation shelves, the mood and genre categories it publishes, and the current chart.
 *
 * Laid out as a grid of artwork tiles rather than a list of rows, so it reads like the rest of the
 * library rather than like a separate feed. Section headings span the full width; everything under
 * them is a tile.
 *
 * The categories are fetched rather than hardcoded. Hardcoding a list of moods would be simpler,
 * but each one is addressed by an opaque token that cannot be guessed and has to come from that
 * page regardless -- and the set changes over time.
 *
 * This is a body rather than a screen: it draws no top bar and owns no `Scaffold`, because it is
 * rendered inside [LibraryScreen] as one of that screen's views. Discovery used to be its own
 * navigation-drawer destination, which put browsing the cloud catalogue somewhere other than the
 * library that browsing adds to.
 */
@Composable
fun DiscoverContent(
    discoverViewModel: DiscoverViewModel,
    onPlayTrack: (YouTubeSearchResult) -> Unit,
    onDownloadTrack: (YouTubeSearchResult) -> Unit,
    onOpenAlbum: (YouTubeAlbumRef) -> Unit,
    onOpenArtist: (YouTubeArtistRef) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val moods by discoverViewModel.moods.collectAsState()
    val homeShelves by discoverViewModel.homeShelves.collectAsState()
    val charts by discoverViewModel.charts.collectAsState()
    val selectedMood by discoverViewModel.selectedMood.collectAsState()
    val moodPlaylists by discoverViewModel.moodPlaylists.collectAsState()
    val isLoading by discoverViewModel.isLoadingDiscover.collectAsState()
    val searchQuery by discoverViewModel.searchQuery.collectAsState()
    val playlistResults by discoverViewModel.playlistResults.collectAsState()
    val artistResults by discoverViewModel.artistResults.collectAsState()
    val albumResults by discoverViewModel.albumResults.collectAsState()
    val isSearching by discoverViewModel.isSearching.collectAsState()
    val feedTracks by discoverViewModel.feedTracks.collectAsState()
    val isLoadingFeed by discoverViewModel.isLoadingFeed.collectAsState()
    val feedExhausted by discoverViewModel.feedExhausted.collectAsState()

    LaunchedEffect(Unit) { discoverViewModel.loadDiscover() }

    val searching = searchQuery.isNotBlank()
    val gridState = rememberLazyGridState()

    // Loads the next page only when the end of what is rendered comes within reach, and never for
    // anywhere else in the list. derivedStateOf is what keeps this cheap: the flag recomputes as the
    // grid scrolls but only *changes* at the threshold, so the effect below runs once per page
    // instead of once per frame. Scrolling back up changes nothing -- those tiles already exist.
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= gridState.layoutInfo.totalItemsCount - FEED_LOAD_AHEAD_ITEMS
        }
    }

    // Keyed on the feed's length as well as the flag. The flag goes true near the end and *stays*
    // true while the page loads, so on its own it fires exactly once and the feed stops growing
    // after one page. Appending a page changes the length, which re-runs this; the appended items
    // then push the end far enough away that the flag goes false on its own, so a user who stops
    // scrolling stops loading.
    LaunchedEffect(shouldLoadMore, feedTracks.size, searching) {
        if (shouldLoadMore && !searching) discoverViewModel.loadMoreFeed()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(DISCOVER_GRID_COLUMNS),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 120.dp)
    ) {
        // Playlists and mixes, searched separately from songs: the Library's own search bar
        // answers "which song", and a mix is not a song.
        fullWidth {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = discoverViewModel::onSearchQueryChanged,
                placeholder = { Text("Search mixes, artists & albums", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { discoverViewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }

        if (searching) {
            val nothingFound = playlistResults.isEmpty() && artistResults.isEmpty() && albumResults.isEmpty()
            if (isSearching && nothingFound) {
                fullWidth { LoadingRow() }
            } else if (nothingFound) {
                fullWidth { EmptyNote("Nothing on YouTube Music matched that.") }
            }

            if (playlistResults.isNotEmpty()) {
                fullWidth(key = "heading_playlists") { SectionHeading("Playlists & mixes") }
                items(playlistResults, key = { "searchplaylist_${it.browseId}" }) { playlist ->
                    CloudTile(
                        title = playlist.title,
                        subtitle = playlist.subtitle,
                        artworkUrl = playlist.thumbnailUrl,
                        onClick = { onOpenAlbum(playlist) }
                    )
                }
            }

            if (artistResults.isNotEmpty()) {
                fullWidth(key = "heading_artists") { SectionHeading("Artists") }
                items(artistResults, key = { "searchartist_${it.browseId}" }) { artist ->
                    CloudTile(
                        title = artist.name,
                        subtitle = "Artist",
                        artworkUrl = artist.thumbnailUrl,
                        onClick = { onOpenArtist(artist) }
                    )
                }
            }

            if (albumResults.isNotEmpty()) {
                fullWidth(key = "heading_albums") { SectionHeading("Albums") }
                items(albumResults, key = { "searchalbum_${it.browseId}" }) { album ->
                    CloudTile(
                        title = album.title,
                        subtitle = album.subtitle,
                        artworkUrl = album.thumbnailUrl,
                        onClick = { onOpenAlbum(album) }
                    )
                }
            }

            // Browsing sections stay out of the way while a search is open: the answer the user
            // asked for should not be followed by four screens of unrelated shelves.
            return@LazyVerticalGrid
        }

        // YouTube's own recommendations. Each shelf keeps its heading -- "Listen again" and
        // "Quick picks" mean different things, and merging them is just a pile of songs.
        if (selectedMood == null) {
            homeShelves.forEach { shelf ->
                fullWidth(key = "shelf_${shelf.title}") { SectionHeading(shelf.title) }
                items(shelf.tracks, key = { "shelftrack_${shelf.title}_${it.videoId}" }) { track ->
                    CloudTile(
                        title = track.title,
                        subtitle = track.uploader,
                        artworkUrl = track.thumbnailUri,
                        onClick = { onPlayTrack(track) },
                        showPlayOverlay = true,
                        onDownload = { onDownloadTrack(track) }
                    )
                }
                items(shelf.items, key = { "shelfitem_${shelf.title}_${it.browseId}" }) { tile ->
                    CloudTile(
                        title = tile.title,
                        subtitle = tile.subtitle,
                        artworkUrl = tile.thumbnailUrl,
                        onClick = { onOpenAlbum(tile) }
                    )
                }
            }
        }

        if (moods.isNotEmpty()) {
            fullWidth {
                Column {
                    SectionHeading("Moods & genres")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp),
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
        }

        if (selectedMood != null) {
            fullWidth { SectionHeading(selectedMood?.title.orEmpty()) }
            if (moodPlaylists.isEmpty()) {
                fullWidth { EmptyNote("Nothing in this category right now.") }
            } else {
                items(moodPlaylists, key = { "mood_${it.browseId}" }) { playlist ->
                    CloudTile(
                        title = playlist.title,
                        subtitle = playlist.subtitle,
                        artworkUrl = playlist.thumbnailUrl,
                        onClick = { onOpenAlbum(playlist) }
                    )
                }
            }
        } else {
            if (isLoading) {
                fullWidth { LoadingRow() }
            }

            // Charts are secondary and only rendered when they returned something. They are
            // region-gated and often empty, and an empty section under a heading reads as a
            // broken screen rather than as content YouTube simply does not publish here.
            if (charts.isNotEmpty()) {
                fullWidth { SectionHeading("Charts") }
                items(charts, key = { "chart_${it.videoId}" }) { track ->
                    CloudTile(
                        title = track.title,
                        subtitle = track.uploader,
                        artworkUrl = track.thumbnailUri,
                        onClick = { onPlayTrack(track) },
                        showPlayOverlay = true,
                        onDownload = { onDownloadTrack(track) }
                    )
                }
            }

            if (!isLoading && homeShelves.isEmpty() && charts.isEmpty() && moods.isEmpty()) {
                fullWidth {
                    EmptyNote("Couldn't reach YouTube Music just now. Check your connection, then switch away from this tab and back.")
                }
            }

            // The endless part, deliberately last: the sections above are finite and have a shape
            // the user can get to the bottom of. A feed that never ends has to come after them or
            // nothing above it is ever reachable again.
            if (feedTracks.isNotEmpty()) {
                fullWidth(key = "heading_for_you") { SectionHeading("For you") }
                items(feedTracks, key = { "feed_${it.videoId}" }) { track ->
                    CloudTile(
                        title = track.title,
                        subtitle = track.uploader,
                        artworkUrl = track.thumbnailUri,
                        onClick = { onPlayTrack(track) },
                        showPlayOverlay = true,
                        onDownload = { onDownloadTrack(track) }
                    )
                }
            }

            if (isLoadingFeed) {
                fullWidth(key = "feed_loading") { LoadingRow() }
            } else if (feedExhausted && feedTracks.isNotEmpty()) {
                fullWidth(key = "feed_end") { EmptyNote("That's everything for now.") }
            }
        }
    }
}

/**
 * Adds one item that spans every column -- headings, the search field and empty notes, which are
 * the things in this grid that are not tiles.
 */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.fullWidth(
    key: Any? = null,
    content: @Composable () -> Unit
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp)
    )
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/** One album, single or playlist tile rendered as a list row, for the screens that still list. */
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
