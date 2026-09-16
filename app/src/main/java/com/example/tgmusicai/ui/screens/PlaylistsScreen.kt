package com.example.tgmusicai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.ui.components.CreatePlaylistDialog
import com.example.tgmusicai.ui.components.PlaylistCard
import com.example.tgmusicai.ui.viewmodel.PlaylistViewModel

/**
 * Screen displaying user-created and system playlists.
 * Allows creating new playlists, viewing artwork and descriptions, and opening playlist details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    playlistViewModel: PlaylistViewModel,
    onPlaylistClick: (playlistId: Long, playlistName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val playlistsWithSongs by playlistViewModel.playlistsWithSongs.collectAsState()
    val showCreateDialog by playlistViewModel.showCreateDialog.collectAsState()
    var playlistPendingDelete by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = playlistViewModel::openCreatePlaylistDialog) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Create playlist"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = playlistViewModel::openCreatePlaylistDialog,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Create Playlist"
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        if (playlistsWithSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
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
            val sortedPlaylists = playlistsWithSongs.sortedByDescending { it.playlist.isPinned }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                items(sortedPlaylists, key = { it.playlist.playlistId }) { pws ->
                    val topSongArtwork = pws.songs.firstOrNull()?.artworkUri
                    PlaylistCard(
                        playlist = pws.playlist,
                        topSongArtworkUri = topSongArtwork,
                        songCount = pws.songs.size,
                        onClick = {
                            onPlaylistClick(pws.playlist.playlistId, pws.playlist.name)
                        },
                        onDeleteClick = {
                            playlistPendingDelete = pws.playlist
                        },
                        onPinToggleClick = { playlistViewModel.togglePinPlaylist(pws.playlist) },
                        modifier = Modifier
                            .animateItem()
                            .padding(6.dp)
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismissRequest = playlistViewModel::closeCreatePlaylistDialog,
            onConfirm = { name, description ->
                playlistViewModel.createPlaylist(name, description)
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
