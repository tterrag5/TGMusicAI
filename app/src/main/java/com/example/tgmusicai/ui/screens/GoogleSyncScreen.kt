package com.example.tgmusicai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.data.youtube.GoogleYouTubePlaylist
import com.example.tgmusicai.data.youtube.LIKED_MUSIC_PLAYLIST_ID
import com.example.tgmusicai.ui.components.YouTubeLoginDialog
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.viewmodel.GoogleSyncViewModel

/**
 * YouTube Music sign-in and playlist import/sync screen. Lets the user sign into their YouTube
 * Music account (via an in-app [YouTubeLoginDialog] WebView, no Google Cloud Console OAuth
 * involved), see every playlist they own (plus Liked Music), pick which to import as local
 * playlists, and re-sync already-imported ones on demand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleSyncScreen(
    viewModel: GoogleSyncViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val isSignedIn by viewModel.isSignedIn.collectAsState()
    val availablePlaylists by viewModel.availablePlaylists.collectAsState()
    val syncedPlaylists by viewModel.syncedPlaylists.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val importingIds by viewModel.importingPlaylistIds.collectAsState()

    val syncedYoutubeIds = syncedPlaylists.mapNotNull { it.youtubePlaylistId }.toSet()
    var mergeLikedIntoAppLiked by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }

    if (showLoginDialog) {
        YouTubeLoginDialog(
            cookieManager = viewModel.cookieManager,
            onSignedIn = {
                showLoginDialog = false
                viewModel.onSignedIn()
            },
            onDismiss = { showLoginDialog = false }
        )
    }

    LaunchedEffect(Unit) {
        if (syncedPlaylists.isNotEmpty()) {
            viewModel.refreshAllSynced()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from YouTube", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (syncedPlaylists.isNotEmpty()) {
                        IconButton(onClick = { viewModel.refreshAllSynced() }, enabled = !isLoading) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh synced playlists")
                        }
                    }
                    if (isSignedIn) {
                        IconButton(onClick = { viewModel.signOut() }) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Sign out of YouTube Music")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            errorMessage?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            when {
                !isSignedIn -> SignInPrompt(
                    isLoading = isLoading,
                    onSignIn = { showLoginDialog = true }
                )
                isLoading && availablePlaylists.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp)
                ) {
                    if (syncedPlaylists.isNotEmpty()) {
                        item {
                            SectionHeader("Synced Playlists")
                        }
                        items(syncedPlaylists, key = { "synced-${it.playlistId}" }) { playlist ->
                            SyncedPlaylistRow(
                                name = playlist.name,
                                lastSyncedAt = playlist.lastSyncedAt
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    item {
                        SectionHeader("Available to Import")
                    }

                    item {
                        ImportableRow(
                            title = "Liked Music",
                            subtitle = "Your YouTube Music liked songs",
                            icon = Icons.Rounded.ThumbUp,
                            isImported = LIKED_MUSIC_PLAYLIST_ID in syncedYoutubeIds,
                            isImporting = LIKED_MUSIC_PLAYLIST_ID in importingIds,
                            onImport = { viewModel.importLikedMusic(mergeLikedIntoAppLiked) }
                        )
                    }

                    if (LIKED_MUSIC_PLAYLIST_ID !in syncedYoutubeIds) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .clickable { mergeLikedIntoAppLiked = !mergeLikedIntoAppLiked },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = mergeLikedIntoAppLiked,
                                    onCheckedChange = { mergeLikedIntoAppLiked = it }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Also add these to my \"Liked Music\" playlist",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    items(availablePlaylists, key = { it.playlistId }) { playlist ->
                        ImportableRow(
                            title = playlist.title,
                            subtitle = "${playlist.itemCount} videos",
                            icon = Icons.Rounded.CloudSync,
                            isImported = playlist.playlistId in syncedYoutubeIds,
                            isImporting = playlist.playlistId in importingIds,
                            onImport = { viewModel.importPlaylist(playlist) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SignInPrompt(isLoading: Boolean, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudSync,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Sign in to your YouTube Music account to import your playlists and Liked Music, and keep them in sync.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Button(onClick = onSignIn, enabled = !isLoading) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Sign in to YouTube Music")
            }
        }
    }
}

@Composable
private fun ImportableRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isImported: Boolean,
    isImporting: Boolean,
    onImport: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when {
                isImporting -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
                isImported -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Imported",
                    tint = MaterialTheme.colorScheme.primary
                )
                else -> IconButton(onClick = onImport) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Import",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncedPlaylistRow(name: String, lastSyncedAt: Long?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.CloudSync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = if (lastSyncedAt != null) "Last synced ${FormatUtils.formatTimestamp(lastSyncedAt)}" else "Not yet synced",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
