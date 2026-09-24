package com.example.tgmusicai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.playback.MediaControllerManager
import com.example.tgmusicai.ui.components.CreatePlaylistDialog
import com.example.tgmusicai.ui.components.MiniPlayer
import com.example.tgmusicai.ui.components.QueueSheet
import com.example.tgmusicai.ui.navigation.Screen
import com.example.tgmusicai.ui.viewmodel.AlarmViewModel
import com.example.tgmusicai.ui.viewmodel.GoogleSyncViewModel
import com.example.tgmusicai.ui.viewmodel.HomeViewModel
import com.example.tgmusicai.ui.viewmodel.LibraryViewModel
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel
import com.example.tgmusicai.ui.viewmodel.PlaylistViewModel
import com.example.tgmusicai.ui.viewmodel.StatsViewModel
import com.example.tgmusicai.ui.viewmodel.YouTubeViewModel
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    homeViewModel: HomeViewModel,
    libraryViewModel: LibraryViewModel,
    playlistViewModel: PlaylistViewModel,
    alarmViewModel: AlarmViewModel,
    statsViewModel: StatsViewModel,
    playerViewModel: PlayerViewModel,
    equalizerViewModel: com.example.tgmusicai.ui.viewmodel.EqualizerViewModel,
    youTubeViewModel: YouTubeViewModel,
    googleSyncViewModel: GoogleSyncViewModel,
    discoverViewModel: com.example.tgmusicai.ui.viewmodel.DiscoverViewModel,
    recognitionViewModel: com.example.tgmusicai.ui.viewmodel.RecognitionViewModel,
    mediaControllerManager: MediaControllerManager,
    modifier: Modifier = Modifier,
    currentTheme: String = "YT_DARK",
    onSelectTheme: (String) -> Unit = {}
) {
    val backStack = remember { mutableStateListOf<NavKey>(Screen.Home) }

    // Navigation3's NavDisplay does NOT wire its `onBack` parameter to the real Android system
    // back button/gesture on its own -- verified by inspecting the compiled navigation3-ui 1.0.1
    // classes, which contain no BackHandler/OnBackPressedCallback reference at all. `onBack` only
    // drives NavDisplay's own predictive-back preview animation once something else decides to
    // call it. Without a real BackHandler wrapping the same pop logic, pressing back (or the
    // gesture) inside any sub-screen fell straight through to the system, exiting/backgrounding
    // the app instead of returning to Home -- this is that real BackHandler.
    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
    }

    val currentSong by playerViewModel.currentSong.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val currentPositionMs by playerViewModel.currentPositionMs.collectAsState()
    val durationMs by playerViewModel.durationMs.collectAsState()
    val isResolving by playerViewModel.isResolving.collectAsState()
    val playbackQueue by playerViewModel.playlist.collectAsState()
    var showQueueSheet by remember { mutableStateOf(false) }

    val userPlaylists by playlistViewModel.playlists.collectAsState()
    val downloadMap by youTubeViewModel.downloadMap.collectAsState()

    val showNowPlayingFull by playerViewModel.isNowPlayingExpanded.collectAsState()
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Surfaced here rather than on Now Playing because a song that fails to resolve never gets
    // that far -- the tap would otherwise look like it did nothing at all.
    val playbackError by playerViewModel.playbackError.collectAsState()
    val mainScreenContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(playbackError) {
        playbackError?.let {
            android.widget.Toast.makeText(mainScreenContext, it, android.widget.Toast.LENGTH_LONG).show()
            playerViewModel.clearPlaybackError()
        }
    }

    val currentDestination = backStack.lastOrNull() ?: Screen.Home

    /**
     * Moves to a top-level destination without destroying history.
     *
     * Every tab and drawer entry used to do `backStack.clear()` followed by `add()`. That left the
     * stack exactly one deep, which disabled the [BackHandler] above and made the system back
     * button fall through and exit the app. Combined with the navigation drawer only being
     * reachable from Home, landing on a drawer-only screen like Stats left no way back at all.
     *
     * Popping back to an existing entry (rather than always pushing) keeps the stack from growing
     * without bound as the user bounces between tabs.
     */
    fun navigateTopLevel(screen: Screen) {
        if (backStack.lastOrNull() == screen) return
        val existingIndex = backStack.indexOfLast { it == screen }
        if (existingIndex >= 0) {
            while (backStack.size > existingIndex + 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        } else {
            backStack.add(screen)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerContentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(280.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp)
                ) {
                    // Left Sidebar Drawer Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayCircle,
                            contentDescription = "TGMusic",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "TGMusic",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // The drawer deliberately holds only destinations that are NOT bottom tabs.
                    // Home, Library and Explore used to be listed here as well, which meant three
                    // of the six entries did exactly what the bottom bar already did.

                    // Discover is not listed here: it is one of the Library screen's views now,
                    // reached from that screen's Songs / Folders / Discover switcher. Browsing the
                    // cloud catalogue belongs with the library it adds to, not in a drawer beside
                    // it.

                    // Nav item: Stats
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.BarChart, contentDescription = null) },
                        label = { Text("Stats", fontWeight = FontWeight.SemiBold) },
                        selected = currentDestination is Screen.Stats,
                        onClick = {
                            navigateTopLevel(Screen.Stats)
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            unselectedContainerColor = Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    // Nav item: Import from YouTube (Google account sync)
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.CloudSync, contentDescription = null) },
                        label = { Text("Import from YouTube", fontWeight = FontWeight.SemiBold) },
                        selected = currentDestination is Screen.GoogleSync,
                        onClick = {
                            navigateTopLevel(Screen.GoogleSync)
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            unselectedContainerColor = Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    // Nav item: Downloads (active/recent cloud download progress)
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                        label = { Text("Downloads", fontWeight = FontWeight.SemiBold) },
                        selected = currentDestination is Screen.Downloads,
                        onClick = {
                            navigateTopLevel(Screen.Downloads)
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            unselectedContainerColor = Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    // Nav item: Settings -- previously only reachable from Home's top bar, which
                    // made it unreachable from every other screen.
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                        selected = currentDestination is Screen.Settings,
                        onClick = {
                            navigateTopLevel(Screen.Settings)
                            scope.launch { drawerState.close() }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            unselectedContainerColor = Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Pill-Shaped "+ New playlist" Button
                    Surface(
                        onClick = {
                            showCreatePlaylistDialog = true
                            scope.launch { drawerState.close() }
                        },
                        shape = RoundedCornerShape(50.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "New playlist",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick access to Liked Music plus every real playlist the user has.
                    // (This used to also list "Speed Dial" / "Top 50 Most Played" / "Recently
                    // Added" shortcuts, but all three just navigated to the Home tab with no
                    // actual filtering or scrolling behind them, so they were pure dead weight
                    // duplicating the Home item already in this drawer. Removed rather than
                    // faked, since there's nothing on Home to deep-link them to yet.)
                    Text(
                        text = "YOUR PLAYLISTS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )

                    val likedPlaylist = userPlaylists.find { it.isSmart && it.name == MusicRepository.LIKED_MUSIC_NAME }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
                            DrawerPlaylistItem(
                                title = "Liked Music",
                                icon = Icons.Rounded.Favorite,
                                iconTint = MaterialTheme.colorScheme.error,
                                selected = likedPlaylist != null &&
                                    currentDestination is Screen.PlaylistDetail &&
                                    (currentDestination as Screen.PlaylistDetail).playlistId == likedPlaylist.playlistId,
                                onClick = {
                                    if (likedPlaylist != null) {
                                        backStack.add(Screen.PlaylistDetail(likedPlaylist.playlistId, likedPlaylist.name))
                                    } else {
                                        navigateTopLevel(Screen.Library)
                                    }
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                        items(
                            userPlaylists.filter { !(it.isSmart && it.name == MusicRepository.LIKED_MUSIC_NAME) },
                            key = { it.playlistId }
                        ) { playlist ->
                            DrawerPlaylistItem(
                                title = playlist.name,
                                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                selected = currentDestination is Screen.PlaylistDetail &&
                                    (currentDestination as Screen.PlaylistDetail).playlistId == playlist.playlistId,
                                onClick = {
                                    backStack.add(Screen.PlaylistDetail(playlist.playlistId, playlist.name))
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            bottomBar = {
                // The whole bar (MiniPlayer + bottom NavigationBar) is hidden entirely -- not just
                // visually covered -- while the full Now Playing screen is expanded, giving it
                // 100% screen focus. Previously this bar kept rendering underneath
                // NowPlayingScreen's content regardless of expansion state, since NowPlayingScreen
                // is a separate overlay Box inside the Scaffold's content slot and never affected
                // this bottomBar slot at all -- so the MiniPlayer (a duplicate title/progress bar/
                // controls row) and the bottom nav tabs stayed visible right below the full
                // player, which is exactly the "dual visibility" YouTube Music never allows.
                if (!showNowPlayingFull) {
                Column {
                    // YT Music Bottom Player Bar
                    MiniPlayer(
                        song = currentSong,
                        isPlaying = isPlaying,
                        currentPositionMs = currentPositionMs,
                        durationMs = durationMs,
                        onPlayPauseClick = playerViewModel::togglePlayPause,
                        onSkipNextClick = playerViewModel::skipToNext,
                        onExpandPlayer = playerViewModel::expandNowPlaying,
                        isResolving = isResolving,
                        onShowQueue = { showQueueSheet = true }
                    )

                    NavigationBar(
                        // Theme colors, not a hardcoded near-black: with light mode selectable the
                        // fixed dark bar sat under a light app as an unrelated black slab.
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        NavigationBarItem(
                            selected = currentDestination is Screen.Home,
                            onClick = {
                                navigateTopLevel(Screen.Home)
                            },
                            icon = { Icon(Icons.Rounded.Home, contentDescription = "Home") },
                            label = { Text("Home") }
                        )


                        NavigationBarItem(
                            selected = currentDestination is Screen.Library,
                            onClick = {
                                navigateTopLevel(Screen.Library)
                            },
                            icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = "Library") },
                            label = { Text("Library") }
                        )

                        NavigationBarItem(
                            selected = currentDestination is Screen.Alarms,
                            onClick = {
                                navigateTopLevel(Screen.Alarms)
                            },
                            icon = { Icon(Icons.Rounded.Alarm, contentDescription = "Alarms") },
                            label = { Text("Alarms") }
                        )
                    }
                }
                }
            },
            modifier = modifier
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Top Left Drawer Hamburger Button overlay
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = {
                            if (backStack.size > 1) {
                                backStack.removeAt(backStack.lastIndex)
                            }
                        },
                        entryProvider = { key ->
                            NavEntry(key) {
                                when (key) {
                                    is Screen.Home -> {
                                        HomeScreen(
                                            homeViewModel = homeViewModel,
                                            playerViewModel = playerViewModel,
                                            onPlaylistClick = { playlistId, playlistName ->
                                                backStack.add(Screen.PlaylistDetail(playlistId, playlistName))
                                            },
                                            currentTheme = currentTheme,
                                            onSelectTheme = onSelectTheme,
                                            onOpenDrawer = { scope.launch { drawerState.open() } },
                                            onOpenSettings = { backStack.add(Screen.Settings) },
                                            onPlayCloudResult = { result ->
                                                youTubeViewModel.playTrack(result, mediaControllerManager)
                                                playerViewModel.expandNowPlaying()
                                            },
                                            onDownloadCloudResult = { result ->
                                                youTubeViewModel.downloadTrack(result)
                                            }
                                        )
                                    }
                                    is Screen.ArtistDetail -> {
                                        ArtistDetailScreen(
                                            browseId = key.browseId,
                                            artistName = key.artistName,
                                            discoverViewModel = discoverViewModel,
                                            onBack = {
                                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                            },
                                            onPlayTrack = { result ->
                                                youTubeViewModel.playTrack(result, mediaControllerManager)
                                                playerViewModel.expandNowPlaying()
                                            },
                                            onDownloadTrack = { result -> youTubeViewModel.downloadTrack(result) },
                                            onOpenAlbum = { album ->
                                                backStack.add(Screen.AlbumDetail(album.browseId, album.title))
                                            },
                                            onOpenArtist = { related ->
                                                backStack.add(Screen.ArtistDetail(related.browseId, related.name))
                                            }
                                        )
                                    }
                                    is Screen.AlbumDetail -> {
                                        AlbumDetailScreen(
                                            browseId = key.browseId,
                                            albumTitle = key.albumTitle,
                                            discoverViewModel = discoverViewModel,
                                            onBack = {
                                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                            },
                                            onPlayTrack = { result ->
                                                youTubeViewModel.playTrack(result, mediaControllerManager)
                                                playerViewModel.expandNowPlaying()
                                            },
                                            onDownloadTrack = { result -> youTubeViewModel.downloadTrack(result) }
                                        )
                                    }
                                    is Screen.Settings -> {
                                        SettingsScreen(
                                            homeViewModel = homeViewModel,
                                            playerViewModel = playerViewModel,
                                            recognitionViewModel = recognitionViewModel,
                                            onBackClick = {
                                                if (backStack.size > 1) {
                                                    backStack.removeAt(backStack.lastIndex)
                                                }
                                            }
                                        )
                                    }
                                    is Screen.Library -> {
                                        LibraryScreen(
                                            libraryViewModel = libraryViewModel,
                                            playerViewModel = playerViewModel,
                                            recognitionViewModel = recognitionViewModel,
                                            discoverViewModel = discoverViewModel,
                                            playlistViewModel = playlistViewModel,
                                            onPlaylistClick = { playlistId, playlistName ->
                                                backStack.add(Screen.PlaylistDetail(playlistId, playlistName))
                                            },
                                            onOpenYouTubeImport = { navigateTopLevel(Screen.GoogleSync) },
                                            downloadMap = downloadMap,
                                            onOpenDrawer = { scope.launch { drawerState.open() } },
                                            onPlayCloudResult = { result ->
                                                youTubeViewModel.playTrack(result, mediaControllerManager)
                                                playerViewModel.expandNowPlaying()
                                            },
                                            onDownloadCloudResult = { result ->
                                                youTubeViewModel.downloadTrack(result)
                                            },
                                            onOpenArtist = { artist ->
                                                backStack.add(Screen.ArtistDetail(artist.browseId, artist.name))
                                            },
                                            onOpenAlbum = { album ->
                                                backStack.add(Screen.AlbumDetail(album.browseId, album.title))
                                            }
                                        )
                                    }
                                    is Screen.Alarms -> {
                                        AlarmsScreen(
                                            alarmViewModel = alarmViewModel,
                                            onOpenDrawer = { scope.launch { drawerState.open() } }
                                        )
                                    }
                                    is Screen.Stats -> {
                                        StatsScreen(
                                            statsViewModel = statsViewModel,
                                            playerViewModel = playerViewModel,
                                            onOpenDrawer = { scope.launch { drawerState.open() } }
                                        )
                                    }
                                    is Screen.PlaylistDetail -> {
                                        PlaylistDetailScreen(
                                            playlistId = key.playlistId,
                                            playlistName = key.playlistName,
                                            playlistViewModel = playlistViewModel,
                                            playerViewModel = playerViewModel,
                                            onDownloadAll = { songsToDownload ->
                                                youTubeViewModel.cloudDownloadManager.downloadPlaylist(songsToDownload)
                                            },
                                            downloadMap = downloadMap,
                                            onBackClick = {
                                                if (backStack.size > 1) {
                                                    backStack.removeAt(backStack.lastIndex)
                                                }
                                            }
                                        )
                                    }
                                    is Screen.GoogleSync -> {
                                        GoogleSyncScreen(
                                            viewModel = googleSyncViewModel,
                                            onBack = {
                                                if (backStack.size > 1) {
                                                    backStack.removeAt(backStack.lastIndex)
                                                }
                                            }
                                        )
                                    }
                                    is Screen.Downloads -> {
                                        DownloadsScreen(
                                            downloadMap = downloadMap,
                                            onBack = {
                                                if (backStack.size > 1) {
                                                    backStack.removeAt(backStack.lastIndex)
                                                }
                                            },
                                            onPause = { videoId -> youTubeViewModel.cloudDownloadManager.pauseDownload(videoId) },
                                            onResume = { videoId -> youTubeViewModel.cloudDownloadManager.resumeDownload(videoId) },
                                            onCancel = { videoId -> youTubeViewModel.cloudDownloadManager.cancelDownload(videoId) }
                                        )
                                    }
                                    else -> {
                                        HomeScreen(
                                            homeViewModel = homeViewModel,
                                            playerViewModel = playerViewModel,
                                            onPlaylistClick = { playlistId, playlistName ->
                                                backStack.add(Screen.PlaylistDetail(playlistId, playlistName))
                                            },
                                            currentTheme = currentTheme,
                                            onSelectTheme = onSelectTheme,
                                            onOpenDrawer = { scope.launch { drawerState.open() } }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                // Crossfade + slide rather than an instant cut, so expanding/collapsing the
                // player reads as one continuous motion instead of a hard pop -- matching the
                // "progressive transition" intent of a YouTube-Music-style player sheet without
                // taking on a full BottomSheetScaffold drag-gesture rewrite this app's tap-to-
                // expand (not drag-to-expand) player doesn't otherwise need.
                AnimatedVisibility(
                    visible = showNowPlayingFull,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 6 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 6 })
                ) {
                    NowPlayingScreen(
                        playerViewModel = playerViewModel,
                        equalizerViewModel = equalizerViewModel,
                        onCollapse = playerViewModel::collapseNowPlaying,
                        onOpenQueue = { showQueueSheet = true }
                    )
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            onConfirm = { playlistName, description ->
                playlistViewModel.createPlaylist(playlistName, description)
                showCreatePlaylistDialog = false
            }
        )
    }

    if (showQueueSheet) {
        QueueSheet(
            queue = playbackQueue,
            currentSong = currentSong,
            onSongClick = { index ->
                playerViewModel.skipToIndex(index)
                showQueueSheet = false
            },
            onMoveSong = playerViewModel::moveQueueItem,
            onSaveQueueAsPlaylist = playerViewModel::saveQueueAsPlaylist,
            onRemoveSongs = playerViewModel::removeQueueItems,
            onDismissRequest = { showQueueSheet = false }
        )
    }
}

/**
 * A single playlist row in the drawer's "Your Playlists" list. Styled to match the fixed
 * NavigationDrawerItem rows above it (same icon-in-a-circle + label + selected-state treatment)
 * instead of the plain unstyled text row this used to be, which looked visually disconnected from
 * the rest of the drawer.
 */
@Composable
fun DrawerPlaylistItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else iconTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
