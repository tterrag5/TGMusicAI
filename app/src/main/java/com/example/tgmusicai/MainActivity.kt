package com.example.tgmusicai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tgmusicai.data.google.YouTubePlaylistSyncManager
import com.example.tgmusicai.data.youtube.InnerTubeCookieManager
import com.example.tgmusicai.data.youtube.YouTubeInnerTubeClient
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.local.MediaScanner
import com.example.tgmusicai.data.network.NetworkObserver
import com.example.tgmusicai.ai.AiFeatureManager
import com.example.tgmusicai.data.repository.CoverArtScraper
import com.example.tgmusicai.data.repository.LyricsRepository
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.data.repository.FolderPathBackfill
import com.example.tgmusicai.data.repository.TagEditorManager
import com.example.tgmusicai.data.repository.RecommendationEngine
import com.example.tgmusicai.playback.MediaControllerManager
import com.example.tgmusicai.ui.onboarding.OnboardingScreen
import com.example.tgmusicai.ui.screens.MainScreen
import com.example.tgmusicai.ui.theme.TGMusicAITheme
import com.example.tgmusicai.ui.viewmodel.AlarmViewModel
import com.example.tgmusicai.ui.viewmodel.EqualizerViewModel
import com.example.tgmusicai.ui.viewmodel.GoogleSyncViewModel
import com.example.tgmusicai.ui.viewmodel.HomeViewModel
import com.example.tgmusicai.ui.viewmodel.LibraryViewModel
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel
import com.example.tgmusicai.ui.viewmodel.PlaylistViewModel
import com.example.tgmusicai.ui.viewmodel.StatsViewModel
import com.example.tgmusicai.ui.viewmodel.YouTubeViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Main Activity of TGMusicAI.
 * Sets up edge-to-edge layout, initializes database, repository layers, lyrics/cover scrapers, network observer,
 * binds to the background [MediaControllerManager], dynamically applies soft color themes, and presents the Compose UI tree.
 */
class MainActivity : ComponentActivity() {

    private lateinit var mediaControllerManager: MediaControllerManager
    private lateinit var networkObserver: NetworkObserver
    private val openNowPlayingRequest = mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == com.example.tgmusicai.playback.PlaybackService.ACTION_OPEN_NOW_PLAYING) {
            openNowPlayingRequest.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.action == com.example.tgmusicai.playback.PlaybackService.ACTION_OPEN_NOW_PLAYING) {
            openNowPlayingRequest.intValue++
        }

        val appPreferences = AppPreferences(this)
        val database = AppDatabase.getDatabase(this)
        // Blends acoustic, lyrical, behavioural and metadata signals for Start Radio and the
        // Home screen's recommendations row. Reads only; it can never touch playback state.
        val recommendationEngine = RecommendationEngine(
            songDao = database.songDao(),
            songStatsDao = database.songStatsDao(),
            aiSongTagsDao = database.aiSongTagsDao(),
            listeningHistoryDao = database.listeningHistoryDao()
        )
        val repository = MusicRepository(
            songDao = database.songDao(),
            playlistDao = database.playlistDao(),
            songStatsDao = database.songStatsDao(),
            alarmDao = database.alarmDao(),
            listeningHistoryDao = database.listeningHistoryDao(),
            database = database,
            recommendationEngine = recommendationEngine
        )

        networkObserver = NetworkObserver(this)
        val lyricsRepository = LyricsRepository(this, repository)
        val coverArtScraper = CoverArtScraper(this, repository)
        // Fully optional on-device AI container (audio tagging + lyrics embeddings): every call
        // into it is self-contained and catches its own failures, so constructing it here can
        // never affect app startup even if the AI deps/models turn out to be broken on a device.
        val aiFeatureManager = AiFeatureManager(this, database.aiSongTagsDao())
        val tagEditorManager = TagEditorManager(this, database.songDao())
        // Fills in where each already-known track lives on disk, so the Library's folder browser
        // works for a library that predates the column rather than only for newly scanned tracks.
        val folderPathBackfill = FolderPathBackfill(this, database.songDao())

        mediaControllerManager = MediaControllerManager(this)

        // Shared by the Library's cloud-search section (the old Explore tab) so searching doesn't
        // spin up a second extractor with its own connection pool.
        val youTubeExtractor = com.example.tgmusicai.data.youtube.YouTubeExtractor()

        // YouTube Music sync: authenticated via a captured music.youtube.com web session
        // (InnerTubeCookieManager) rather than a Google Cloud Console OAuth client -- see
        // YouTubeInnerTubeClient's doc comment.
        val innerTubeCookieManager = InnerTubeCookieManager(appPreferences)
        val youTubeInnerTubeClient = YouTubeInnerTubeClient(innerTubeCookieManager)
        val youTubePlaylistSyncManager = YouTubePlaylistSyncManager(
            songDao = database.songDao(),
            playlistDao = database.playlistDao(),
            musicRepository = repository,
            innerTubeClient = youTubeInnerTubeClient
        )
        val playlistImportExportManager = com.example.tgmusicai.data.local.PlaylistImportExportManager(
            songDao = database.songDao(),
            musicRepository = repository
        )

        setContent {
            val selectedTheme by appPreferences.selectedThemeFlow.collectAsState(initial = "YT_DARK")
            val themeModeName by appPreferences.themeModeFlow.collectAsState(initial = "DARK")
            val dynamicColor by appPreferences.dynamicColorFlow.collectAsState(initial = false)

            TGMusicAITheme(
                themeName = selectedTheme,
                themeMode = com.example.tgmusicai.ui.theme.ThemeMode.fromName(themeModeName),
                systemInDarkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                dynamicColor = dynamicColor
            ) {
                val onboardingCompleted by appPreferences.onboardingCompletedFlow.collectAsState(initial = null)

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (onboardingCompleted == null) {
                        // Loading state checking DataStore preferences
                    } else if (onboardingCompleted == true) {
                        val homeViewModel: HomeViewModel = viewModel(
                            factory = remember {
                                HomeViewModel.Factory(
                                    repository = repository,
                                    networkObserver = networkObserver,
                                    appPreferences = appPreferences,
                                    recommendationEngine = recommendationEngine
                                )
                            }
                        )
                        val libraryViewModel: LibraryViewModel = viewModel(
                            factory = remember {
                                LibraryViewModel.Factory(
                                    repository = repository,
                                    lyricsRepository = lyricsRepository,
                                    coverArtScraper = coverArtScraper,
                                    aiFeatureManager = aiFeatureManager,
                                    appPreferences = appPreferences,
                                    youtubeExtractor = youTubeExtractor,
                                    tagEditorManager = tagEditorManager
                                )
                            }
                        )
                        val playlistViewModel: PlaylistViewModel = viewModel(
                            factory = remember {
                                PlaylistViewModel.Factory(
                                    repository,
                                    playlistImportExportManager,
                                    appPreferences
                                )
                            }
                        )
                        val alarmViewModel: AlarmViewModel = viewModel(
                            factory = remember { AlarmViewModel.Factory(repository) }
                        )
                        val statsViewModel: StatsViewModel = viewModel(
                            factory = remember { StatsViewModel.Factory(repository) }
                        )
                        val playerViewModel: PlayerViewModel = viewModel(
                            factory = remember {
                                PlayerViewModel.Factory(
                                    mediaControllerManager = mediaControllerManager,
                                    lyricsRepository = lyricsRepository,
                                    coverArtScraper = coverArtScraper,
                                    repository = repository,
                                    appPreferences = appPreferences
                                )
                            }
                        )

                        // Tapping the media notification opens Now Playing directly (works both
                        // for a cold start and while the app is already running, since the
                        // Activity is singleTop and re-delivers via onNewIntent).
                        val openNowPlayingTick = openNowPlayingRequest.intValue
                        LaunchedEffect(openNowPlayingTick) {
                            if (openNowPlayingTick > 0) {
                                playerViewModel.expandNowPlaying()
                            }
                        }

                        val equalizerViewModel: EqualizerViewModel = viewModel(
                            factory = remember { EqualizerViewModel.Factory(mediaControllerManager, appPreferences) }
                        )

                        val youTubeViewModel: YouTubeViewModel = viewModel()
                        val googleSyncViewModel: GoogleSyncViewModel = viewModel(
                            factory = remember {
                                GoogleSyncViewModel.Factory(
                                    cookieManager = innerTubeCookieManager,
                                    syncManager = youTubePlaylistSyncManager,
                                    innerTubeClient = youTubeInnerTubeClient,
                                    playlistDao = database.playlistDao()
                                )
                            }
                        )

                        MainScreen(
                            homeViewModel = homeViewModel,
                            libraryViewModel = libraryViewModel,
                            playlistViewModel = playlistViewModel,
                            alarmViewModel = alarmViewModel,
                            statsViewModel = statsViewModel,
                            playerViewModel = playerViewModel,
                            equalizerViewModel = equalizerViewModel,
                            youTubeViewModel = youTubeViewModel,
                            googleSyncViewModel = googleSyncViewModel,
                            mediaControllerManager = mediaControllerManager,
                            currentTheme = selectedTheme,
                            onSelectTheme = { themeKey ->
                                lifecycleScope.launch {
                                    appPreferences.setSelectedTheme(themeKey)
                                }
                            },
                            modifier = Modifier.padding(innerPadding)
                        )

                        // Trigger media scan when main UI opens
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                MediaScanner.scanMediaStore(this@MainActivity, database.songDao())
                                // Runs after the scan, not alongside it: the scan is what gives
                                // newly-found tracks their folder, so starting the backfill first
                                // would just mean two passes over the same rows.
                                folderPathBackfill.runToCompletion()
                            }
                        }

                        // One-time cleanup: merge any duplicate Song rows created before
                        // addCloudTrackToPlaylist checked for an existing song first (same
                        // YouTube ID, or same title+artist). Gated by a persisted flag so it only
                        // ever runs once per install, never touches audio files on disk, and is a
                        // safe no-op for a library with no duplicates.
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                val alreadyDone = appPreferences.hasDeduplicatedLibraryV1Flow.first()
                                if (!alreadyDone) {
                                    val merged = repository.deduplicateLibrary()
                                    android.util.Log.d("LibraryDedup", "One-time dedup merged $merged duplicate song row(s)")
                                    appPreferences.setHasDeduplicatedLibraryV1(true)
                                }
                            }
                        }

                        // V2 cleanup pass: merges duplicates that differ only by a YouTube
                        // auto-generated "- Topic" channel suffix on the artist (e.g. "Artist" vs
                        // "Artist - Topic"), which the V1 pass's plain title+artist match didn't
                        // catch. Separately gated so it runs once more even on installs that
                        // already completed V1.
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                val alreadyDone = appPreferences.hasDeduplicatedLibraryV2Flow.first()
                                if (!alreadyDone) {
                                    val merged = repository.deduplicateLibrary()
                                    android.util.Log.d("LibraryDedup", "V2 dedup merged $merged duplicate song row(s)")
                                    appPreferences.setHasDeduplicatedLibraryV2(true)
                                }
                            }
                        }

                        // V3 cleanup pass: broadens artist matching (strips a "/alias" tail, e.g.
                        // "Jamie Paige / JamieP") on top of the V2 Topic-suffix strip, and re-runs
                        // regardless of V1/V2 completion since new duplicates can appear anytime
                        // (new downloads, playlist syncs) after those already ran once.
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                val alreadyDone = appPreferences.hasDeduplicatedLibraryV3Flow.first()
                                if (!alreadyDone) {
                                    val merged = repository.deduplicateLibrary()
                                    android.util.Log.d("LibraryDedup", "V3 dedup merged $merged duplicate song row(s)")
                                    appPreferences.setHasDeduplicatedLibraryV3(true)
                                }
                            }
                        }

                        // V4 cleanup pass: re-runs merge after fixing YouTubePlaylistSyncManager
                        // (it only checked for an existing song by exact YouTube video ID, so a
                        // synced playlist listing both an artist's real-channel and Topic-channel
                        // upload of the same song recreated a "merged away" duplicate on every
                        // sync). That insert path is now fixed, but the batch it already
                        // recreated before the fix still needs one more merge.
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                val alreadyDone = appPreferences.hasDeduplicatedLibraryV4Flow.first()
                                if (!alreadyDone) {
                                    val merged = repository.deduplicateLibrary()
                                    android.util.Log.d("LibraryDedup", "V4 dedup merged $merged duplicate song row(s)")
                                    appPreferences.setHasDeduplicatedLibraryV4(true)
                                }
                            }
                        }

                        // V5 cleanup pass: same as V4, but now that CloudDownloadManager,
                        // YouTubeViewModel, and YouTubePlaylistSyncManager all share one
                        // normalized-artist matching definition (SongDao.findByTitleAndNormalizedArtist),
                        // catches variants like "Jamie Paige" vs "Jamie Paige / JamieP" that V4's
                        // exact-match-only insert check still let through.
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                val alreadyDone = appPreferences.hasDeduplicatedLibraryV5Flow.first()
                                if (!alreadyDone) {
                                    val merged = repository.deduplicateLibrary()
                                    android.util.Log.d("LibraryDedup", "V5 dedup merged $merged duplicate song row(s)")
                                    appPreferences.setHasDeduplicatedLibraryV5(true)
                                }
                            }
                        }

                        // V6 cleanup pass: deduplicateLibrary now also strips a lingering
                        // "- Topic"/"/alias" suffix from EVERY song's artist credit, not just ones
                        // that collided with another row -- V5 only fixed songs that had an exact
                        // duplicate; standalone Topic-channel imports (e.g. "ewe - Topic") kept the
                        // raw suffix forever since they never matched anything to merge with.
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                val alreadyDone = appPreferences.hasDeduplicatedLibraryV6Flow.first()
                                if (!alreadyDone) {
                                    val merged = repository.deduplicateLibrary()
                                    android.util.Log.d("LibraryDedup", "V6 dedup merged $merged duplicate song row(s)")
                                    appPreferences.setHasDeduplicatedLibraryV6(true)
                                }
                            }
                        }

                        // Cleans up any song_stats row left over from the old play-tracking bug
                        // (bogus songId, e.g. 0, with no matching song). Cheap and safe to run
                        // every launch.
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                repository.cleanupOrphanedStats()
                                android.util.Log.d("LibraryDedup", "Orphaned stats cleanup ran")
                            }
                        }

                        // One-time AI tag backfill: analyzes every already-downloaded song that
                        // hasn't been tagged yet. Runs on AiFeatureManager's own IO scope (not
                        // lifecycleScope) so it isn't cancelled if this composable leaves
                        // composition, and skips songs it already has a cached result for, so
                        // this is safe to leave gated behind a "done" flag rather than a
                        // per-launch cost. A crash here is caught inside AiFeatureManager itself
                        // and can't affect startup.
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                val alreadyDone = appPreferences.hasBackfilledAiTagsV1Flow.first()
                                if (!alreadyDone) {
                                    val downloadedSongs = repository.downloadedSongs.first()
                                    aiFeatureManager.backfillAll(downloadedSongs)
                                    appPreferences.setHasBackfilledAiTagsV1(true)
                                    android.util.Log.d("AiBackfill", "AI tag backfill finished for ${downloadedSongs.size} song(s)")
                                }
                            }
                        }

                        // Keep any already-imported YouTube playlists up to date on every launch.
                        // A safe no-op for users who have never used the feature (see
                        // GoogleSyncViewModel.refreshAllSynced -- never prompts for sign-in here).
                        LaunchedEffect(Unit) {
                            googleSyncViewModel.refreshAllSynced()
                        }

                        // Automatic cover-art backfill: scrape artwork for any song that still
                        // doesn't have any, a few seconds after launch (after the media scan
                        // above has had a chance to run) and only while online. Runs one song at
                        // a time with a small delay between each, so it never floods the cover
                        // art APIs or competes noticeably with normal app usage.
                        LaunchedEffect(Unit) {
                            lifecycleScope.launch {
                                kotlinx.coroutines.delay(5000)
                                if (!networkObserver.isOnline.value) return@launch
                                val missingArtwork = database.songDao().getSongsMissingArtwork()
                                for (song in missingArtwork) {
                                    if (!networkObserver.isOnline.value) break
                                    try {
                                        coverArtScraper.scrapeAndSaveArtwork(song)
                                    } catch (e: Exception) {
                                        android.util.Log.e("CoverArtBackfill", "Failed for '${song.title}'", e)
                                    }
                                    kotlinx.coroutines.delay(1500)
                                }
                            }
                        }
                    } else {
                        OnboardingScreen(
                            onPermissionsGranted = {
                                lifecycleScope.launch {
                                    appPreferences.setOnboardingCompleted(true)
                                }
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::networkObserver.isInitialized) {
            networkObserver.unregister()
        }
        if (::mediaControllerManager.isInitialized) {
            mediaControllerManager.release()
        }
    }
}
