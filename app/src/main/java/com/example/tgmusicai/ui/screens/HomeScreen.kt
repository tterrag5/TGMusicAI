package com.example.tgmusicai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.viewmodel.HomeViewModel
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel

/** Below this many recommendations the Home row is hidden instead of shown half-empty. */
private const val MIN_RECOMMENDATIONS_TO_SHOW = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel,
    playerViewModel: PlayerViewModel,
    onPlaylistClick: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
    onSelectTheme: (String) -> Unit = {},
    currentTheme: String = "YT_DARK",
    onOpenDrawer: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val isOnline by homeViewModel.isOnline.collectAsState()
    val isDownloadedOnly by homeViewModel.isDownloadedOnly.collectAsState()
    val pinnedSongs by homeViewModel.pinnedSongs.collectAsState()
    val pinnedPlaylists by homeViewModel.pinnedPlaylists.collectAsState()
    val smartPlaylists by homeViewModel.smartPlaylists.collectAsState()
    val playlistTopArtwork by homeViewModel.playlistTopArtwork.collectAsState()
    val listenAgainSongs by homeViewModel.listenAgainSongs.collectAsState()
    val mostPlayedSongs by homeViewModel.mostPlayedSongs.collectAsState()
    val recommendedSongs by homeViewModel.recommendedSongs.collectAsState()
    val isBackupLoading by homeViewModel.isBackupLoading.collectAsState()

    var songPendingRemoveDownload by remember { mutableStateOf<Song?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TGMusic",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = "Open navigation menu"
                        )
                    }
                },
                actions = {
                    // Single Settings entry point -- Theme, AI API key, Backup/Restore, and Alarm
                    // sound settings now live together in SettingsScreen instead of as separate
                    // top-bar icons here.
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.primary
                        )
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
        ) {
            // Offline Mode Banner
            if (!isOnline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(vertical = 8.dp, horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiOff,
                        contentDescription = "Offline Mode",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Offline Mode — Internet Unavailable",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isBackupLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Processing Backup / Restore...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // Section 1: SPEED DIAL -- a 3x3 grid per page; swipe to the next page of 9 when
                // there are more than 9 pinned items instead of endless horizontal scrolling.
                item {
                    SectionTitle(title = "Speed Dial", icon = Icons.Rounded.PushPin)

                    if (pinnedSongs.isEmpty() && pinnedPlaylists.isEmpty()) {
                        Text(
                            text = "No pinned items. Pin your favorites for quick access!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    } else {
                        val speedDialItems: List<SpeedDialItem> =
                            pinnedPlaylists.map { SpeedDialItem.PlaylistItem(it) } +
                                pinnedSongs.map { SpeedDialItem.SongItem(it) }
                        val pages = speedDialItems.chunked(9)
                        val pagerState = rememberPagerState(pageCount = { pages.size })

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxWidth()
                        ) { pageIndex ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                for (rowItems in pages[pageIndex].chunked(3)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        for (cellItem in rowItems) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                when (cellItem) {
                                                    is SpeedDialItem.PlaylistItem -> PinnedPlaylistCard(
                                                        playlist = cellItem.playlist,
                                                        onClick = {
                                                            onPlaylistClick(cellItem.playlist.playlistId, cellItem.playlist.name)
                                                        },
                                                        onUnpin = { homeViewModel.togglePinPlaylist(cellItem.playlist) },
                                                        topSongArtworkUri = playlistTopArtwork[cellItem.playlist.playlistId],
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    is SpeedDialItem.SongItem -> PinnedSongCard(
                                                        song = cellItem.song,
                                                        onPlay = { playerViewModel.playSong(cellItem.song, queue = pinnedSongs) },
                                                        onUnpin = { homeViewModel.togglePinSong(cellItem.song) },
                                                        onRemoveDownload = { songPendingRemoveDownload = cellItem.song },
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                }
                                            }
                                        }
                                        repeat(3 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }
                        }

                        if (pages.size > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                repeat(pages.size) { pageIndex ->
                                    val selected = pagerState.currentPage == pageIndex
                                    Box(
                                        modifier = Modifier
                                            .padding(3.dp)
                                            .size(if (selected) 8.dp else 6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant
                                            )
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 2: LISTEN AGAIN
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionTitle(title = "Listen again", icon = Icons.Rounded.History)

                    if (listenAgainSongs.isEmpty()) {
                        Text(
                            text = "No recently played tracks.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(listenAgainSongs) { item ->
                                SongCardItem(
                                    song = item.song,
                                    subtitle = "📌 Song • ${item.song.artist}",
                                    onPlay = { playerViewModel.playSong(item.song, queue = listenAgainSongs.map { it.song }) },
                                    onPinToggle = { homeViewModel.togglePinSong(item.song) },
                                    onRemoveDownload = { songPendingRemoveDownload = item.song }
                                )
                            }
                        }
                    }
                }

                // Section 2b: MADE FOR YOU
                // Hidden rather than shown empty: with nothing played yet the engine has no taste
                // to work from, and an empty "Made for you" reads as broken.
                if (recommendedSongs.size >= MIN_RECOMMENDATIONS_TO_SHOW) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionTitle(title = "Made for you", icon = Icons.Rounded.AutoAwesome)

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(recommendedSongs) { song ->
                                SongCardItem(
                                    song = song,
                                    subtitle = "Song • ${song.artist}",
                                    onPlay = { playerViewModel.playSong(song, queue = recommendedSongs) },
                                    onPinToggle = { homeViewModel.togglePinSong(song) },
                                    onRemoveDownload = { songPendingRemoveDownload = song }
                                )
                            }
                        }
                    }
                }

                // Section 3: RECAPS / FAVORITES
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionTitle(title = "Recaps / Favorites", icon = Icons.Rounded.OfflineBolt)

                    if (mostPlayedSongs.isEmpty()) {
                        Text(
                            text = "No playback history yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(mostPlayedSongs) { item ->
                                SongCardItem(
                                    song = item.song,
                                    subtitle = "📌 Song • ${item.song.artist}",
                                    onPlay = { playerViewModel.playSong(item.song, queue = mostPlayedSongs.map { it.song }) },
                                    onPinToggle = { homeViewModel.togglePinSong(item.song) },
                                    onRemoveDownload = { songPendingRemoveDownload = item.song }
                                )
                            }
                        }
                    }
                }

                // Section 4: SMART PLAYLISTS
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionTitle(title = "Smart Playlists", icon = Icons.AutoMirrored.Rounded.QueueMusic)

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(smartPlaylists) { playlist ->
                            SmartPlaylistCard(
                                playlist = playlist,
                                topSongArtworkUri = playlistTopArtwork[playlist.playlistId],
                                onClick = { onPlaylistClick(playlist.playlistId, playlist.name) }
                            )
                        }
                    }
                }
            }
        }
    }

    songPendingRemoveDownload?.let { song ->
        AlertDialog(
            onDismissRequest = { songPendingRemoveDownload = null },
            title = { Text("Remove download of \"${song.title}\"?") },
            text = { Text("This deletes the local audio file. The song stays in your library and playlists as a cloud track, streamable and re-downloadable anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    homeViewModel.removeDownload(song.id)
                    songPendingRemoveDownload = null
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { songPendingRemoveDownload = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** A single cell in the Speed Dial's paged 3x3 grid -- either a pinned playlist or a pinned song. */
private sealed interface SpeedDialItem {
    data class PlaylistItem(val playlist: Playlist) : SpeedDialItem
    data class SongItem(val song: Song) : SpeedDialItem
}

@Composable
fun SectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun PinnedPlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    topSongArtworkUri: String? = null,
    modifier: Modifier = Modifier.width(150.dp)
) {
    val isLikedMusic = playlist.isSmart && playlist.name == MusicRepository.LIKED_MUSIC_NAME

    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isLikedMusic) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isLikedMusic) {
                    Icon(
                        imageVector = Icons.Rounded.ThumbUp,
                        contentDescription = "Liked Music",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                } else if (!topSongArtworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = topSongArtworkUri,
                        contentDescription = playlist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                }

                // Center Translucent Play Button Overlay
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    IconButton(onClick = onUnpin, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = "Unpin",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "📌 Playlist",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PinnedSongCard(
    song: Song,
    onPlay: () -> Unit,
    onUnpin: () -> Unit,
    onRemoveDownload: () -> Unit,
    modifier: Modifier = Modifier.width(150.dp)
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!song.artworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = FormatUtils.cacheBustedArtworkUri(song.artworkUri),
                        contentDescription = song.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    )
                }

                // Center Translucent Play Button Overlay
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Menu",
                            tint = Color.White
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Unpin") },
                            leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onUnpin()
                            }
                        )
                        if (song.isDownloaded && !song.youtubeId.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text("Remove Download, Keep in Playlist") },
                                leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onRemoveDownload()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "📌 Song • ${song.artist}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SongCardItem(
    song: Song,
    subtitle: String,
    onPlay: () -> Unit,
    onPinToggle: () -> Unit,
    onRemoveDownload: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!song.artworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = FormatUtils.cacheBustedArtworkUri(song.artworkUri),
                        contentDescription = song.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    )
                }

                // Center Translucent Play Button Overlay
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Menu",
                            tint = Color.White
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (song.isPinned) "Unpin" else "Pin to Speed Dial") },
                            leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onPinToggle()
                            }
                        )
                        if (song.isDownloaded && !song.youtubeId.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text("Remove Download, Keep in Playlist") },
                                leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onRemoveDownload()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SmartPlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    topSongArtworkUri: String? = null
) {
    val isLikedMusic = playlist.isSmart && playlist.name == MusicRepository.LIKED_MUSIC_NAME

    Card(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isLikedMusic) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isLikedMusic) {
                    Icon(
                        imageVector = Icons.Rounded.ThumbUp,
                        contentDescription = "Liked Music",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                } else if (!topSongArtworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = topSongArtworkUri,
                        contentDescription = playlist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }

                // Center Translucent Play Button Overlay
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "📌 Playlist • System",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
