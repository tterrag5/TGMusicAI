package com.example.tgmusicai.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import com.example.tgmusicai.data.local.entity.Song
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Clear
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.ui.util.ShareUtils
import coil.compose.AsyncImage
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.ui.components.SongItem
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel
import com.example.tgmusicai.ui.viewmodel.PlaylistViewModel

/**
 * Detailed view of a single playlist displaying cover artwork, title, description, and songs.
 * Displays Top Song album cover art (or Thumbs Up for Liked Music), allows editing playlist description,
 * and includes a "Play All" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playlistName: String,
    playlistViewModel: PlaylistViewModel,
    playerViewModel: PlayerViewModel,
    onDownloadAll: (List<Song>) -> Unit = {},
    downloadMap: Map<String, com.example.tgmusicai.data.youtube.DownloadProgressState> = emptyMap(),
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(playlistId) {
        playlistViewModel.selectPlaylist(playlistId)
    }

    val playlistWithSongs by playlistViewModel.selectedPlaylistWithSongs.collectAsState()
    val songs = playlistWithSongs?.songs ?: emptyList()

    // Search within this playlist. Matches the fields LibraryViewModel.filteredSongs searches, so
    // the two search boxes behave the same way.
    var songQuery by remember { mutableStateOf("") }
    val visibleSongs = remember(songs, songQuery) {
        if (songQuery.isBlank()) {
            songs
        } else {
            songs.filter { song ->
                song.title.contains(songQuery, ignoreCase = true) ||
                    song.artist.contains(songQuery, ignoreCase = true) ||
                    song.album.contains(songQuery, ignoreCase = true) ||
                    (song.producer?.contains(songQuery, ignoreCase = true) == true)
            }
        }
    }
    val playlist = playlistWithSongs?.playlist
    val context = LocalContext.current

    // Export goes through the system save dialog: writing to a self-chosen path in public storage
    // needs a permission this app doesn't hold, so it always failed.
    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: android.net.Uri? ->
        uri?.let { playlistViewModel.exportPlaylist(context, it, playlistName, songs) }
    }
    val currentSong by playerViewModel.currentSong.collectAsState()
    val exportStatusMessage by playlistViewModel.exportStatusMessage.collectAsState()

    LaunchedEffect(exportStatusMessage) {
        exportStatusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            playlistViewModel.clearExportStatus()
        }
    }

    // Prefer the loaded Playlist's isSmart+exact-name check (matches MusicRepository's real
    // protected-playlist definition); fall back to the nav-arg name only before it has loaded.
    val isLikedMusic = playlist?.let { it.isSmart && it.name == MusicRepository.LIKED_MUSIC_NAME }
        ?: playlistName.equals(MusicRepository.LIKED_MUSIC_NAME, ignoreCase = true)
    val topSongArtwork = songs.firstOrNull()?.artworkUri

    // "Top 50 Most Played", "Recently Added", "Unplayed", "Downloads", and "Cloud Nine" are all
    // computed on the fly from other tables, not backed by real playlist_song_cross_ref rows -
    // removing a "song from playlist" there would delete a cross-ref that was never created
    // (silent no-op, song just reappears next recomposition). Only real cross-ref-backed
    // playlists (Liked Music and user-created ones) support a meaningful removal action.
    val isReadOnlyComputedPlaylist = playlist?.isSmart == true &&
        playlist.name in MusicRepository.COMPUTED_SMART_PLAYLIST_NAMES

    var showEditDescriptionDialog by remember { mutableStateOf(false) }
    var descriptionText by remember { mutableStateOf(playlist?.description ?: "") }
    var songPendingRemoval by remember { mutableStateOf<Song?>(null) }
    var songPendingDelete by remember { mutableStateOf<Song?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val selectedSongIds by playlistViewModel.selectedSongIds.collectAsState()
    val isSelectionMode = selectedSongIds.isNotEmpty()

    LaunchedEffect(playlist?.description) {
        descriptionText = playlist?.description ?: ""
    }

    if (showEditDescriptionDialog) {
        AlertDialog(
            onDismissRequest = { showEditDescriptionDialog = false },
            title = { Text("Edit Playlist Description") },
            text = {
                Column {
                    OutlinedTextField(
                        value = descriptionText,
                        onValueChange = { descriptionText = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playlistViewModel.updatePlaylistDescription(
                            playlistId = playlistId,
                            description = if (descriptionText.isBlank()) null else descriptionText.trim()
                        )
                        showEditDescriptionDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDescriptionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedSongIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = playlistViewModel::clearSelection) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = playlistViewModel::likeSelectedSongs) {
                            Icon(Icons.Rounded.ThumbUp, contentDescription = "Like selected")
                        }
                        IconButton(onClick = playlistViewModel::unlikeSelectedSongs) {
                            Icon(Icons.Rounded.HeartBroken, contentDescription = "Remove liked tag from selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            } else {
                TopAppBar(
                    title = { Text(playlistName) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showEditDescriptionDialog = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Edit Description"
                            )
                        }
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Like all songs") },
                                leadingIcon = { Icon(Icons.Rounded.ThumbUp, contentDescription = null) },
                                onClick = {
                                    playlistViewModel.likeAllSongs(songs.map { it.id })
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove liked tag from all") },
                                leadingIcon = { Icon(Icons.Rounded.HeartBroken, contentDescription = null) },
                                onClick = {
                                    playlistViewModel.unlikeAllSongs(songs.map { it.id })
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    ShareUtils.sharePlaylist(context, playlistName, songs)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export to file") },
                                leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    exportCsvLauncher.launch(
                                        playlistViewModel.suggestedExportFileName(playlistName)
                                    )
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Header Section with Cover Artwork & Description
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isLikedMusic) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLikedMusic) {
                        Icon(
                            imageVector = Icons.Rounded.ThumbUp,
                            contentDescription = "Liked Music",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    } else if (!topSongArtwork.isNullOrBlank()) {
                        AsyncImage(
                            model = FormatUtils.cacheBustedArtworkUri(topSongArtwork),
                            contentDescription = playlistName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = playlistName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = playlist?.description ?: if (isLikedMusic) "Your liked tracks collection" else "No description",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${songs.size} ${if (songs.size == 1) "song" else "songs"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (songs.isNotEmpty()) {
                // Playlist Play All Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            playerViewModel.playQueue(queue = songs, startIndex = 0)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Play All (${songs.size})")
                    }

                    val undownloadedCount = songs.count { !it.isDownloaded }
                    if (undownloadedCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { onDownloadAll(songs.filter { !it.isDownloaded }) }) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Download all ($undownloadedCount)",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Filters only what this list renders. Everything else on the screen -- Play All,
                // the per-song queue, Like all, Share, Export, the song count -- deliberately keeps
                // using the unfiltered `songs`, so searching never silently narrows those actions.
                OutlinedTextField(
                    value = songQuery,
                    onValueChange = { songQuery = it },
                    placeholder = {
                        Text(
                            "Search in playlist",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search icon"
                        )
                    },
                    trailingIcon = {
                        if (songQuery.isNotEmpty()) {
                            IconButton(onClick = { songQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(visibleSongs, key = { it.id }) { song ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                SongItem(
                                    song = song,
                                    isPlaying = currentSong?.id == song.id,
                                    isSelected = song.id in selectedSongIds,
                                    onClick = {
                                        if (isSelectionMode) {
                                            playlistViewModel.toggleSongSelected(song.id)
                                        } else {
                                            playerViewModel.playSong(song = song, queue = songs)
                                        }
                                    },
                                    onLongClick = { playlistViewModel.startSelection(song.id) },
                                    onAddToPlaylistClicked = {},
                                    onStartRadioClicked = { playerViewModel.startRadio(song) },
                                    liveDownloadStatus = song.youtubeId?.let { downloadMap[it]?.status }
                                )
                            }
                            if (!isSelectionMode) {
                                if (isReadOnlyComputedPlaylist) {
                                    // No real cross-ref backs this playlist, so there's nothing
                                    // to "remove" -- offer full library deletion instead so a
                                    // song doesn't have to be hunted down in Library just to get
                                    // rid of it here.
                                    IconButton(
                                        onClick = { songPendingDelete = song },
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = "Delete song",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = { songPendingRemoval = song },
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.RemoveCircleOutline,
                                            contentDescription = "Remove from playlist",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "This playlist is empty. Add songs from your Library!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    songPendingRemoval?.let { song ->
        AlertDialog(
            onDismissRequest = { songPendingRemoval = null },
            title = { Text("Remove \"${song.title}\"?") },
            text = { Text("This removes it from this playlist only. It stays in your library and any other playlists.") },
            confirmButton = {
                TextButton(onClick = {
                    playlistViewModel.removeSongFromPlaylist(playlistId = playlistId, songId = song.id)
                    songPendingRemoval = null
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { songPendingRemoval = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    songPendingDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { songPendingDelete = null },
            title = { Text("Delete \"${song.title}\"?") },
            text = { Text("This permanently deletes it from your library, including any downloaded file. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    playlistViewModel.deleteSongCompletely(song.id)
                    songPendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { songPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
