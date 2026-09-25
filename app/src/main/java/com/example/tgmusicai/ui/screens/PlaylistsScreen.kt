package com.example.tgmusicai.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.local.entity.PlaylistWithSongs
import com.example.tgmusicai.ui.components.CreatePlaylistDialog
import com.example.tgmusicai.ui.components.PlaylistCard
import com.example.tgmusicai.ui.viewmodel.PlaylistViewModel

/**
 * Screen displaying user-created and system playlists.
 * Allows creating new playlists, viewing artwork and descriptions, and opening playlist details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsContent(
    playlistViewModel: PlaylistViewModel,
    onPlaylistClick: (playlistId: Long, playlistName: String) -> Unit,
    onOpenYouTubeImport: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val playlistsWithSongs by playlistViewModel.playlistsWithSongs.collectAsState()
    val coverArtwork by playlistViewModel.playlistCoverArtwork.collectAsState()
    val showCreateDialog by playlistViewModel.showCreateDialog.collectAsState()
    var playlistPendingDelete by remember { mutableStateOf<Playlist?>(null) }
    val context = LocalContext.current

    val selectedPlaylistIds by playlistViewModel.selectedPlaylistIds.collectAsState()
    val isSelectionMode = selectedPlaylistIds.isNotEmpty()
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    val importProgress by playlistViewModel.importProgress.collectAsState()
    val importResultMessage by playlistViewModel.importResultMessage.collectAsState()
    var pendingImportCsv by remember { mutableStateOf<String?>(null) }
    var pendingImportName by remember { mutableStateOf("") }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val csvText = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() }
            if (!csvText.isNullOrBlank()) {
                pendingImportCsv = csvText
                pendingImportName = queryDisplayName(context, it)
                    ?.substringBeforeLast('.')
                    ?.ifBlank { "Imported Playlist" }
                    ?: "Imported Playlist"
            } else {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(importResultMessage) {
        importResultMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            playlistViewModel.clearImportResult()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Playlist-only controls live here rather than in the Library's top bar. That bar is
            // shared with the Songs and Discover views, and piling view-specific actions
            // into it was how it ended up crowded enough to need this switcher in the first place.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                if (isSelectionMode) {
                    IconButton(onClick = playlistViewModel::clearPlaylistSelection) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                    }
                    Text(
                        text = "${selectedPlaylistIds.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showBulkDeleteConfirm = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete selected playlists")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { importFileLauncher.launch("text/*") }) {
                        Icon(
                            imageVector = Icons.Rounded.FileUpload,
                            contentDescription = "Import playlist from file"
                        )
                    }
                    // Importing from a YouTube account produces playlists, so it belongs next to
                    // them rather than only in the navigation drawer. The drawer entry stays: it
                    // is the way in for someone who is not already looking at their playlists.
                    if (onOpenYouTubeImport != null) {
                        IconButton(onClick = onOpenYouTubeImport) {
                            Icon(
                                imageVector = Icons.Rounded.CloudSync,
                                contentDescription = "Import playlists from YouTube"
                            )
                        }
                    }
                }
            }

            if (playlistsWithSongs.isEmpty()) {
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
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No playlists yet. Create one to organize your music!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Pinned playlists stay on top; the flow underneath already returns the user's own
                // order. Dragging writes positions for the whole visible list, so an item dragged
                // across the pinned boundary settles at the edge of its own group rather than
                // jumping the boundary -- the pin is the stronger statement of the two.
                val sortedPlaylists = playlistsWithSongs.sortedByDescending { it.playlist.isPinned }

                // The extra bottom padding keeps the last row of cards clear of the FAB and of the
                // mini player MainScreen draws over the bottom of this screen -- without it the
                // final row's song count was clipped and the FAB sat on top of a card.
                val listContentPadding =
                    PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 108.dp)

                // Shared per-card wiring so the grid and list layouts can't drift apart.
                @Composable
                fun playlistCardFor(pws: PlaylistWithSongs, compact: Boolean) {
                    val playlist = pws.playlist
                    // Protected smart playlists can't be deleted, so letting them be selected would
                    // show a count that doesn't match what bulk delete actually removes.
                    val isSelectable = !MusicRepository.isProtectedSmartPlaylist(playlist)
                    PlaylistCard(
                        playlist = playlist,
                        coverArtworkUris = coverArtwork[playlist.playlistId].orEmpty(),
                        songCount = pws.songs.size,
                        isSelected = playlist.playlistId in selectedPlaylistIds,
                        compact = compact,
                        onClick = {
                            if (isSelectionMode) {
                                if (isSelectable) playlistViewModel.togglePlaylistSelected(playlist.playlistId)
                            } else {
                                onPlaylistClick(playlist.playlistId, playlist.name)
                            }
                        },
                        onLongClick = if (isSelectable) {
                            { playlistViewModel.startPlaylistSelection(playlist.playlistId) }
                        } else {
                            null
                        },
                        onDeleteClick = { playlistPendingDelete = playlist },
                        onPinToggleClick = { playlistViewModel.togglePinPlaylist(playlist) },
                        modifier = Modifier.padding(if (compact) 4.dp else 6.dp)
                    )
                }

                // Always a grid. Artwork is how a playlist is recognised, and a row of text
                // with a thumbnail made every playlist look the same; there is no reason to offer
                // the weaker of the two.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding
                ) {
                    items(sortedPlaylists, key = { it.playlist.playlistId }) { pws ->
                        Box(modifier = Modifier.animateItem()) {
                            playlistCardFor(pws, compact = true)
                        }
                    }
                }
            }
        }

        // Labelled rather than icon-only: it is the single entry point for creating a playlist, so
        // it should say what it does. The bottom padding lifts it clear of the mini player that
        // MainScreen overlays on top of every screen.
        ExtendedFloatingActionButton(
            onClick = playlistViewModel::openCreatePlaylistDialog,
            // Full-strength accent, not the softer container role: this is the view's single
            // primary action and it sits on top of busy playlist artwork, so it has to read
            // clearly against whatever is behind it.
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null
                )
            },
            text = { Text("New playlist") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 72.dp)
        )
    }

    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete ${selectedPlaylistIds.size} playlist(s)?") },
            text = { Text("This deletes the playlists. Songs in them stay in your library and other playlists.") },
            confirmButton = {
                TextButton(onClick = {
                    playlistViewModel.deleteSelectedPlaylists()
                    showBulkDeleteConfirm = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismissRequest = playlistViewModel::closeCreatePlaylistDialog,
            onConfirm = { name, description ->
                playlistViewModel.createPlaylist(name, description)
            }
        )
    }

    pendingImportCsv?.let { csvText ->
        AlertDialog(
            onDismissRequest = { pendingImportCsv = null },
            title = { Text("Import Playlist") },
            text = {
                Column {
                    Text(
                        "Anything not already in your library is looked up on YouTube. This can take a moment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pendingImportName,
                        onValueChange = { pendingImportName = it },
                        label = { Text("Playlist Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playlistViewModel.importPlaylistFromCsv(context, csvText, pendingImportName.trim())
                        pendingImportCsv = null
                    },
                    enabled = pendingImportName.isNotBlank()
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportCsv = null }) { Text("Cancel") }
            }
        )
    }

    importProgress?.let { (processed, total) ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Importing Playlist") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(if (total > 0) "Matching track $processed of $total..." else "Starting import...")
                }
            }
        )
    }

    playlistPendingDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistPendingDelete = null },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("This deletes the playlist. Songs in it stay in your library and other playlists.") },
            confirmButton = {
                TextButton(onClick = {
                    playlistViewModel.deletePlaylist(playlist)
                    playlistPendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Resolves [uri]'s human-readable file name via [OpenableColumns.DISPLAY_NAME]. A SAF/MediaStore
 * content URI's own [Uri.lastPathSegment] is an opaque document id (e.g. `msf:1000000057`), not a
 * filename, so that can't be used to pre-fill a sensible default playlist name.
 */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
}
