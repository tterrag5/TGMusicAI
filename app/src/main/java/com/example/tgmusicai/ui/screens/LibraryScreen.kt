package com.example.tgmusicai.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.ui.components.AddToPlaylistDialog
import com.example.tgmusicai.data.repository.LibraryTag
import com.example.tgmusicai.ui.components.CloudTile
import com.example.tgmusicai.ui.components.SongGridItem
import com.example.tgmusicai.ui.components.YouTubeSearchResultItem
import com.example.tgmusicai.ui.components.RecognizeSongDialog
import com.example.tgmusicai.ui.components.SongItem
import com.example.tgmusicai.ui.components.TagEditorDialog
import com.example.tgmusicai.ui.theme.TGMusicAITheme
import com.example.tgmusicai.ui.viewmodel.LibraryView
import com.example.tgmusicai.ui.viewmodel.LibraryViewModel
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Screen displaying the user's song library loaded from the Room database.
 * Supports filtering songs, playing tracks, switching between grid and list layouts,
 * adding songs to playlists, and scraping cover art & lyrics for individual tracks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    recognitionViewModel: com.example.tgmusicai.ui.viewmodel.RecognitionViewModel? = null,
    discoverViewModel: com.example.tgmusicai.ui.viewmodel.DiscoverViewModel? = null,
    playlistViewModel: com.example.tgmusicai.ui.viewmodel.PlaylistViewModel? = null,
    onPlaylistClick: (playlistId: Long, playlistName: String) -> Unit = { _, _ -> },
    onOpenYouTubeImport: (() -> Unit)? = null,
    downloadMap: Map<String, com.example.tgmusicai.data.youtube.DownloadProgressState> = emptyMap(),
    onOpenDrawer: () -> Unit = {},
    onPlayCloudResult: (com.example.tgmusicai.data.youtube.YouTubeSearchResult) -> Unit = {},
    onDownloadCloudResult: (com.example.tgmusicai.data.youtube.YouTubeSearchResult) -> Unit = {},
    onOpenArtist: (com.example.tgmusicai.data.youtube.YouTubeArtistRef) -> Unit = {},
    onOpenAlbum: (com.example.tgmusicai.data.youtube.YouTubeAlbumRef) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val songs by libraryViewModel.filteredSongs.collectAsState()
    val searchQuery by libraryViewModel.searchQuery.collectAsState()
    val songForAddToPlaylist by libraryViewModel.songForAddToPlaylist.collectAsState()
    val playlists by libraryViewModel.playlists.collectAsState()
    val currentSong by playerViewModel.currentSong.collectAsState()
    val statusMessage by libraryViewModel.statusMessage.collectAsState()
    val playerStatusMessage by playerViewModel.statusMessage.collectAsState()
    val isGridView by libraryViewModel.isGridView.collectAsState()
    val selectedSongIds by libraryViewModel.selectedSongIds.collectAsState()
    val showBulkAddToPlaylistDialog by libraryViewModel.showBulkAddToPlaylistDialog.collectAsState()
    val isSelectionMode = selectedSongIds.isNotEmpty()
    val downloadedOnly by libraryViewModel.downloadedOnly.collectAsState()
    val cloudResults by libraryViewModel.cloudResults.collectAsState()
    val isSearchingCloud by libraryViewModel.isSearchingCloud.collectAsState()
    val cloudArtists by libraryViewModel.cloudArtists.collectAsState()
    val cloudAlbums by libraryViewModel.cloudAlbums.collectAsState()
    val songForTagEdit by libraryViewModel.songForTagEdit.collectAsState()
    val libraryView by libraryViewModel.libraryView.collectAsState()
    val discoverEnabled = libraryView == LibraryView.DISCOVER
    val playlistsEnabled = libraryView == LibraryView.PLAYLISTS
    val visibleTags by libraryViewModel.visibleTags.collectAsState()
    val selectedTagFilters by libraryViewModel.selectedTagFilters.collectAsState()
    var showRecognizeDialog by remember { mutableStateOf(false) }
    val tagWriteConsentRequest by libraryViewModel.tagWriteConsentRequest.collectAsState()

    val context = LocalContext.current
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var showBulkMoreMenu by remember { mutableStateOf(false) }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            libraryViewModel.clearStatusMessage()
        }
    }

    LaunchedEffect(playerStatusMessage) {
        playerStatusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            playerViewModel.clearStatusMessage()
        }
    }

    // Rewriting a file that came from MediaStore needs the system's own per-file consent dialog.
    // It arrives as a PendingIntent the app has to launch; approving it re-runs the edit the user
    // already typed rather than making them enter it again.
    val tagWriteConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            libraryViewModel.retryPendingTagEdit()
        } else {
            libraryViewModel.cancelPendingTagEdit()
        }
    }

    LaunchedEffect(tagWriteConsentRequest) {
        tagWriteConsentRequest?.let { request ->
            tagWriteConsentLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }

    if (showRecognizeDialog && recognitionViewModel != null) {
        RecognizeSongDialog(
            recognitionViewModel = recognitionViewModel,
            onDismiss = { showRecognizeDialog = false },
            onPlaySong = { song -> playerViewModel.playSong(song = song, queue = listOf(song)) }
        )
    }

    songForTagEdit?.let { song ->
        TagEditorDialog(
            song = song,
            loadTags = { libraryViewModel.loadFileTags(it) },
            onDismiss = libraryViewModel::closeTagEditor,
            onSave = { tags -> libraryViewModel.saveTags(song, tags) }
        )
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedSongIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = libraryViewModel::clearSelection) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = libraryViewModel::openBulkAddToPlaylistDialog) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add to playlist")
                        }
                        IconButton(onClick = { showBulkDeleteConfirm = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                        }
                        Box {
                            IconButton(onClick = { showBulkMoreMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More actions")
                            }
                            DropdownMenu(expanded = showBulkMoreMenu, onDismissRequest = { showBulkMoreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Fetch Cover Art & Lyrics") },
                                    onClick = {
                                        showBulkMoreMenu = false
                                        libraryViewModel.bulkScrapeArtworkAndLyrics()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Start Radio (from first selected)") },
                                    onClick = {
                                        showBulkMoreMenu = false
                                        songs.firstOrNull { it.id in selectedSongIds }?.let { playerViewModel.startRadio(it) }
                                        libraryViewModel.clearSelection()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Analyze (AI)") },
                                    onClick = {
                                        showBulkMoreMenu = false
                                        libraryViewModel.bulkAnalyzeWithAi()
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("Music Library", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = "Open navigation menu"
                        )
                    }
                },
                    actions = {
                        if (recognitionViewModel != null) {
                            IconButton(onClick = { showRecognizeDialog = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = "Identify what's playing"
                                )
                            }
                        }
                        if (libraryView == LibraryView.SONGS) {
                            IconButton(onClick = libraryViewModel::toggleGridView) {
                                Icon(
                                    imageVector = if (isGridView) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                                    contentDescription = if (isGridView) "Switch to list view" else "Switch to grid view"
                                )
                            }
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
            // Songs / Folders / Discover. A segmented control rather than a fourth toolbar
            // icon: the bar already carries three, and an icon does not say what the current view
            // is -- with three views that matters, because two of them look nothing like a song
            // list.
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val views = listOf(
                    LibraryView.SONGS to "Songs",
                    LibraryView.PLAYLISTS to "Playlists",
                    LibraryView.DISCOVER to "Discover"
                )
                views.forEachIndexed { index, (view, label) ->
                    SegmentedButton(
                        selected = libraryView == view,
                        onClick = { libraryViewModel.setLibraryView(view) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = views.size),
                        // A view is only reachable with the model behind it; without one the
                        // segment would open a permanently empty page.
                        enabled = when (view) {
                            LibraryView.DISCOVER -> discoverViewModel != null
                            LibraryView.PLAYLISTS -> playlistViewModel != null
                            else -> true
                        },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            // Search and the downloaded-only filter act on the local song list, so they are
            // hidden in the views that have nothing for them to filter.
            if (!discoverEnabled && !playlistsEnabled) {
            // Search Input Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = libraryViewModel::onSearchQueryChanged,
                // Short, and capped to one line: `singleLine` constrains the typed value, not this
                // placeholder composable, so a long hint wrapped to a second line and made the
                // whole field noticeably taller than every other search bar in the app.
                placeholder = {
                    Text(
                        "Search",
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
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { libraryViewModel.onSearchQueryChanged("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "Clear search"
                            )
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
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Downloaded-only lives here now rather than on Home, next to the list it actually
            // filters. With it on, cloud-only tracks are hidden and no YouTube search is issued.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DownloadDone,
                    contentDescription = null,
                    tint = if (downloadedOnly) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Downloaded only",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = downloadedOnly,
                    onCheckedChange = libraryViewModel::setDownloadedOnly
                )
            }

            // Filter chips: what the on-device models heard in each track, plus the genres the
            // files declare. They narrow the list below rather than opening a section of their
            // own -- a tag is a way of asking "which of my songs are like this", which is the
            // same question the search box answers.
            if (visibleTags.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (selectedTagFilters.isNotEmpty()) {
                        item(key = "cleartags") {
                            FilterChip(
                                selected = false,
                                onClick = libraryViewModel::clearTagFilters,
                                label = { Text("Clear") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                    items(visibleTags, key = { "tag_${it.name}" }) { tag ->
                        FilterChip(
                            selected = tag.name in selectedTagFilters,
                            onClick = { libraryViewModel.toggleTagFilter(tag.name) },
                            label = { Text("${tag.name} · ${tag.songCount}") }
                        )
                    }
                }
            }
            }

            // While searching, local and cloud results share one scrolling list so YouTube hits
            // read as a continuation of the library rather than a separate place to go. Browsing
            // (no query) keeps the grid/list layout.
            if (playlistsEnabled && playlistViewModel != null) {
                PlaylistsContent(
                    playlistViewModel = playlistViewModel,
                    onPlaylistClick = onPlaylistClick,
                    onOpenYouTubeImport = onOpenYouTubeImport
                )
            } else if (discoverEnabled && discoverViewModel != null) {
                DiscoverContent(
                    discoverViewModel = discoverViewModel,
                    onPlayTrack = onPlayCloudResult,
                    onDownloadTrack = onDownloadCloudResult,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist
                )
            } else if (searchQuery.isNotBlank() && !downloadedOnly) {
                // Results are tiles, in the same three-column grid the library browses in, so a
                // search does not change the shape of the screen under the user. Local matches
                // come first, then the artists and albums that match, then YouTube's tracks.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)
                ) {
                    fun fullWidthItem(key: String, content: @Composable () -> Unit) {
                        item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
                    }

                    items(songs, key = { "local_${it.id}" }) { song ->
                        SongGridItem(
                            song = song,
                            isPlaying = currentSong?.id == song.id,
                            isSelected = song.id in selectedSongIds,
                            onClick = {
                                if (isSelectionMode) {
                                    libraryViewModel.toggleSongSelected(song.id)
                                } else {
                                    playerViewModel.playSong(song = song, queue = songs)
                                }
                            },
                            onLongClick = { libraryViewModel.startSelection(song.id) },
                            onAddToPlaylistClicked = { libraryViewModel.openAddToPlaylistDialog(song) },
                            onScrapeClicked = { libraryViewModel.scrapeArtworkAndLyrics(song) },
                            onStartRadioClicked = { playerViewModel.startRadio(song) },
                            onAnalyzeWithAiClicked = { libraryViewModel.analyzeSongWithAi(song) },
                            onAddToQueueClicked = { playerViewModel.addToQueue(song) },
                            onToggleLikeClicked = { libraryViewModel.toggleLikeSong(song) },
                            liveDownloadStatus = song.youtubeId?.let { downloadMap[it]?.status }
                        )
                    }

                    if (cloudArtists.isNotEmpty()) {
                        fullWidthItem("heading_artists") { SearchSectionHeading("Artists") }
                        items(cloudArtists, key = { "artist_${it.browseId}" }) { artist ->
                            CloudTile(
                                title = artist.name,
                                subtitle = "Artist",
                                artworkUrl = artist.thumbnailUrl,
                                onClick = { onOpenArtist(artist) }
                            )
                        }
                    }

                    if (cloudAlbums.isNotEmpty()) {
                        fullWidthItem("heading_albums") { SearchSectionHeading("Albums") }
                        items(cloudAlbums, key = { "cloudalbum_${it.browseId}" }) { album ->
                            CloudTile(
                                title = album.title,
                                subtitle = album.subtitle,
                                artworkUrl = album.thumbnailUrl,
                                onClick = { onOpenAlbum(album) }
                            )
                        }
                    }

                    if (isSearchingCloud || cloudResults.isNotEmpty()) {
                        fullWidthItem("heading_youtube") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = "From YouTube",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSearchingCloud) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                        items(cloudResults, key = { "cloud_${it.videoId}" }) { result ->
                            CloudTile(
                                title = result.title,
                                subtitle = result.uploader,
                                artworkUrl = result.thumbnailUri,
                                onClick = { onPlayCloudResult(result) },
                                showPlayOverlay = true,
                                onDownload = { onDownloadCloudResult(result) }
                            )
                        }
                    }
                }
            } else if (songs.isEmpty()) {
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
                            imageVector = Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isBlank()) "No songs found in media database." else "No songs match \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    items(songs, key = { it.id }) { song ->
                        SongGridItem(
                            song = song,
                            isPlaying = currentSong?.id == song.id,
                            isSelected = song.id in selectedSongIds,
                            onClick = {
                                if (isSelectionMode) {
                                    libraryViewModel.toggleSongSelected(song.id)
                                } else {
                                    playerViewModel.playSong(song = song, queue = songs)
                                }
                            },
                            onLongClick = { libraryViewModel.startSelection(song.id) },
                            onAddToPlaylistClicked = if (isSelectionMode) null else {
                                { libraryViewModel.openAddToPlaylistDialog(song) }
                            },
                            onScrapeClicked = if (isSelectionMode) null else {
                                { libraryViewModel.scrapeArtworkAndLyrics(song) }
                            },
                            onStartRadioClicked = if (isSelectionMode) null else {
                                { playerViewModel.startRadio(song) }
                            },
                            onAnalyzeWithAiClicked = if (isSelectionMode) null else {
                                { libraryViewModel.analyzeSongWithAi(song) }
                            },
                            onAddToQueueClicked = if (isSelectionMode) null else {
                                { playerViewModel.addToQueue(song) }
                            },
                            onToggleLikeClicked = if (isSelectionMode) null else {
                                { libraryViewModel.toggleLikeSong(song) }
                            },
                            liveDownloadStatus = song.youtubeId?.let { downloadMap[it]?.status },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(songs, key = { it.id }) { song ->
                        SongItem(
                            song = song,
                            isPlaying = currentSong?.id == song.id,
                            isSelected = song.id in selectedSongIds,
                            onClick = {
                                if (isSelectionMode) {
                                    libraryViewModel.toggleSongSelected(song.id)
                                } else {
                                    playerViewModel.playSong(song = song, queue = songs)
                                }
                            },
                            onLongClick = { libraryViewModel.startSelection(song.id) },
                            onAddToPlaylistClicked = {
                                libraryViewModel.openAddToPlaylistDialog(song)
                            },
                            onScrapeClicked = {
                                libraryViewModel.scrapeArtworkAndLyrics(song)
                            },
                            onStartRadioClicked = {
                                playerViewModel.startRadio(song)
                            },
                            onAnalyzeWithAiClicked = {
                                libraryViewModel.analyzeSongWithAi(song)
                            },
                            onEditTagsClicked = if (libraryViewModel.canEditTags(song)) {
                                { libraryViewModel.openTagEditor(song) }
                            } else {
                                null
                            },
                            onSwipeToQueue = { playerViewModel.addToQueue(song) },
                            onSwipeToLike = { libraryViewModel.toggleLikeSong(song) },
                            liveDownloadStatus = song.youtubeId?.let { downloadMap[it]?.status },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    // Add to Playlist Dialog (single song)
    songForAddToPlaylist?.let { targetSong ->
        AddToPlaylistDialog(
            promptText = "Select a playlist for \"${targetSong.title}\"",
            playlists = playlists,
            onDismissRequest = libraryViewModel::closeAddToPlaylistDialog,
            onPlaylistSelected = { playlistId ->
                libraryViewModel.addSongToPlaylist(playlistId = playlistId, songId = targetSong.id)
            },
            onCreateNewPlaylist = { newPlaylistName ->
                libraryViewModel.createPlaylistAndAddSong(playlistName = newPlaylistName, songId = targetSong.id)
            }
        )
    }

    // Add to Playlist Dialog (bulk, multi-select)
    if (showBulkAddToPlaylistDialog) {
        AddToPlaylistDialog(
            promptText = "Select a playlist for ${selectedSongIds.size} song(s)",
            playlists = playlists,
            onDismissRequest = libraryViewModel::closeBulkAddToPlaylistDialog,
            onPlaylistSelected = { playlistId ->
                libraryViewModel.addSelectedSongsToPlaylist(playlistId = playlistId)
            },
            onCreateNewPlaylist = { newPlaylistName ->
                libraryViewModel.createPlaylistAndAddSelectedSongs(playlistName = newPlaylistName)
            }
        )
    }

    // Bulk Delete Confirmation
    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete ${selectedSongIds.size} song(s)?") },
            text = { Text("This permanently deletes these songs, including any downloaded audio files, from your entire library. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    libraryViewModel.deleteSelectedSongs()
                    showBulkDeleteConfirm = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

}


@Preview(showBackground = true)
/** A section label inside the search results grid ("Artists", "Albums", "From YouTube"). */
@Composable
private fun SearchSectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
    )
}

@Composable
fun LibraryScreenEmptyPreview() {
    TGMusicAITheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Text("Library Preview")
        }
    }
}
