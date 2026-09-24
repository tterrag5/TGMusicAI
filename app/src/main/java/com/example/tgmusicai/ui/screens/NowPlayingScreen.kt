package com.example.tgmusicai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.example.tgmusicai.ui.components.AddToPlaylistDialog
import com.example.tgmusicai.ui.components.CastButton
import com.example.tgmusicai.ui.theme.TGMusicAITheme
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.util.rememberArtworkColors
import com.example.tgmusicai.ui.util.ShareUtils
import com.example.tgmusicai.ui.viewmodel.PlayerViewModel

/** How long playback-driven lyric auto-scroll stays paused after the user scrolls the lyrics. */
private const val LYRICS_MANUAL_SCROLL_GRACE_MS = 4000L

/**
 * How long the lyrics take to glide from one line to the next. Long enough to read as movement
 * rather than a jump, short enough that the line is in place while it is still being sung -- at
 * 650ms the highlight visibly trailed the music.
 */
private const val LYRICS_SCROLL_DURATION_MS = 420

/**
 * How far ahead of playback a line is treated as active.
 *
 * The glide takes [LYRICS_SCROLL_DURATION_MS] to finish, and LRC timestamps generally mark where a
 * line sits in the file rather than the exact instant the vocal starts, so activating a line
 * exactly on its timestamp lands it late twice over. Leading by roughly the glide duration puts
 * the line in place as it begins.
 */
private const val LYRICS_SYNC_LEAD_MS = 400L

/** Where the active lyric line comes to rest, as a fraction down the lyrics viewport. */
private const val LYRICS_ACTIVE_LINE_ANCHOR = 0.4f

/**
 * Full-screen Now Playing view: YouTube-Music-style hero artwork, pill action bar (Like/Dislike,
 * AI scrape, Lyrics toggle, Save to Playlist), circular hero play/pause button, 3-state repeat,
 * and synced/plain Lyrics transcript. Sleep Timer, Playback Speed, Equalizer, and Start Radio live
 * in the overflow menu -- this app has no video playback or Cast support, so (unlike a literal
 * YouTube Music clone) there's no Audio/Video mode pill or Cast button here; adding controls for
 * capabilities the app doesn't have would just be dead UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerViewModel: PlayerViewModel,
    equalizerViewModel: com.example.tgmusicai.ui.viewmodel.EqualizerViewModel,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentSong by playerViewModel.currentSong.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val currentPositionMs by playerViewModel.currentPositionMs.collectAsState()
    val durationMs by playerViewModel.durationMs.collectAsState()
    val shuffleMode by playerViewModel.shuffleMode.collectAsState()
    val repeatMode by playerViewModel.repeatMode.collectAsState()

    val parsedLyrics by playerViewModel.parsedLyrics.collectAsState()
    val isScraping by playerViewModel.isScraping.collectAsState()
    val isTranscribing by playerViewModel.isTranscribing.collectAsState()
    val transcribeError by playerViewModel.transcribeError.collectAsState()
    val translatedLyrics by playerViewModel.translatedLyrics.collectAsState()
    val isTranslatingLyrics by playerViewModel.isTranslatingLyrics.collectAsState()
    val translationLanguage by playerViewModel.translationLanguage.collectAsState()
    val translationError by playerViewModel.translationError.collectAsState()
    val remainingSleepTimeMs by playerViewModel.remainingSleepTimeMs.collectAsState()
    val isLiked by playerViewModel.isCurrentSongLiked.collectAsState()
    val playbackSpeed by playerViewModel.playbackSpeed.collectAsState()
    val equalizerEnabled by equalizerViewModel.enabled.collectAsState()
    val playlists by playerViewModel.playlists.collectAsState()
    val context = LocalContext.current

    var showLyricsTab by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showTranslateMenu by remember { mutableStateOf(false) }
    var isDisliked by remember(currentSong?.mediaUri) { mutableStateOf(false) }

    LaunchedEffect(translationError) {
        translationError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            playerViewModel.clearTranslationError()
        }
    }

    // Dominant/muted colors sampled from the current track's artwork, crossfading between tracks;
    // drive a slow breathing pulse on top so the ambient backdrop reads as alive, not a static tint.
    val artworkColors = rememberArtworkColors(currentSong?.artworkUri)
    val ambientPrimary by artworkColors.first
    val ambientSecondary by artworkColors.second
    val ambientPulse by rememberInfiniteTransition(label = "ambientPulse").animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientPulseFraction"
    )

    // Priority 1 of the back-navigation architecture: while this screen is composed (i.e. the
    // player is expanded), back must collapse it back to the MiniPlayer rather than falling
    // through to whatever's underneath (a sub-screen's back navigation, or exiting the app).
    // Scoping the handler to this composable's own lifecycle -- rather than a top-level
    // `enabled` flag in MainScreen -- means it's registered only while actually expanded, which
    // is exactly when it should take priority over any other active BackHandler.
    BackHandler {
        onCollapse()
    }

    if (showEqualizerDialog) {
        EqualizerDialog(
            equalizerViewModel = equalizerViewModel,
            onDismiss = { showEqualizerDialog = false }
        )
    }

    if (showInfoDialog && currentSong != null) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Track Information") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Title: ${currentSong?.title}")
                    Text(text = "Artist: ${currentSong?.artist}")
                    if (!currentSong?.producer.isNullOrBlank()) {
                        Text(text = "Producer: ${currentSong?.producer}")
                    }
                    Text(text = "Duration: ${FormatUtils.formatDuration(durationMs)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("Close") }
            }
        )
    }

    if (showSaveDialog && currentSong != null) {
        AddToPlaylistDialog(
            promptText = "Select a playlist for \"${currentSong?.title}\"",
            playlists = playlists,
            onDismissRequest = { showSaveDialog = false },
            onPlaylistSelected = { playlistId ->
                playerViewModel.addCurrentSongToPlaylist(playlistId)
                showSaveDialog = false
            },
            onCreateNewPlaylist = { newPlaylistName ->
                playerViewModel.createPlaylistAndAddCurrentSong(newPlaylistName)
                showSaveDialog = false
            }
        )
    }

    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer") },
            text = {
                Column {
                    if (remainingSleepTimeMs != null) {
                        val remainingSec = (remainingSleepTimeMs!! / 1000L)
                        val mins = remainingSec / 60
                        val secs = remainingSec % 60
                        Text(
                            text = "Timer active: ${mins}m ${secs}s remaining",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    } else {
                        Text(
                            text = "Select duration to stop playback automatically:",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    listOf(15, 30, 45, 60).forEach { minutes ->
                        TextButton(
                            onClick = {
                                playerViewModel.startSleepTimer(minutes)
                                showSleepTimerDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("$minutes Minutes")
                        }
                    }

                    if (remainingSleepTimeMs != null) {
                        TextButton(
                            onClick = {
                                playerViewModel.cancelSleepTimer()
                                showSleepTimerDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Turn Off Timer", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Collapse Player",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                },
                actions = {
                    // Renders nothing where Cast is unusable, so a device without Play Services
                    // sees no dead control.
                    CastButton(
                        modifier = Modifier
                            .size(48.dp)
                            .padding(12.dp)
                    )
                    IconButton(onClick = onOpenQueue) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Up Next"
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Track Info") },
                                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                                onClick = { showOverflowMenu = false; showInfoDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    currentSong?.let { ShareUtils.shareSong(context, it) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Start Radio") },
                                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    currentSong?.let { playerViewModel.startRadio(it) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (remainingSleepTimeMs != null) "Sleep Timer (active)" else "Sleep Timer") },
                                leadingIcon = { Icon(Icons.Rounded.Bedtime, contentDescription = null) },
                                onClick = { showOverflowMenu = false; showSleepTimerDialog = true }
                            )
                            Box {
                                DropdownMenuItem(
                                    text = { Text("Playback Speed: ${playbackSpeed}x") },
                                    leadingIcon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
                                    onClick = { showSpeedMenu = true }
                                )
                                DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
                                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "${speed}x",
                                                    fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (speed == playbackSpeed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                playerViewModel.setPlaybackSpeed(speed)
                                                showSpeedMenu = false
                                                showOverflowMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(if (equalizerEnabled) "Equalizer (on)" else "Equalizer") },
                                leadingIcon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    equalizerViewModel.ensureAttached()
                                    showEqualizerDialog = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        var lyricsSwipeDrag by remember { mutableStateOf(0f) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { lyricsSwipeDrag = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            lyricsSwipeDrag += dragAmount
                            change.consume()
                        },
                        onDragEnd = {
                            // Swipe right -> show lyrics, swipe left -> back to cover art.
                            if (lyricsSwipeDrag > 80f) {
                                showLyricsTab = true
                            } else if (lyricsSwipeDrag < -80f) {
                                showLyricsTab = false
                            }
                            lyricsSwipeDrag = 0f
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Album Artwork / Lyrics Transcript Area (toggled by the Lyrics pill below, or a
            // horizontal swipe -- see pointerInput above)
            if (showLyricsTab) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (parsedLyrics.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                            ) {
                                IconButton(onClick = { showTranslateMenu = true }) {
                                    if (isTranslatingLyrics) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Translate,
                                            contentDescription = "Translate lyrics",
                                            tint = if (translatedLyrics != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                DropdownMenu(expanded = showTranslateMenu, onDismissRequest = { showTranslateMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (translatedLyrics != null) "Hide translation" else "Translate to $translationLanguage") },
                                        onClick = {
                                            showTranslateMenu = false
                                            playerViewModel.toggleLyricsTranslation()
                                        }
                                    )
                                    HorizontalDivider()
                                    listOf("Spanish", "French", "German", "Portuguese", "Italian", "Japanese", "Korean", "Chinese (Simplified)", "Arabic", "Hindi").forEach { language ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = language,
                                                    fontWeight = if (language == translationLanguage) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (language == translationLanguage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                showTranslateMenu = false
                                                // Persists the pick and, if a translation is already showing,
                                                // re-translates into it immediately (see PlayerViewModel).
                                                playerViewModel.setTranslationLanguage(language)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        if (parsedLyrics.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = when {
                                        isScraping -> "Fetching transcript/lyrics..."
                                        isTranscribing -> "Transcribing audio on-device..."
                                        else -> "No lyrics available for this song"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                if (transcribeError != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = transcribeError.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        currentSong?.let { playerViewModel.fetchLyrics(it, forceFetch = true) }
                                    },
                                    enabled = !isScraping && !isTranscribing,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text("Refetch Lyrics")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        playerViewModel.clearTranscribeError()
                                        currentSong?.let { playerViewModel.transcribeLyricsWithAi(it) }
                                    },
                                    enabled = !isScraping && !isTranscribing
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("AI Transcribe Lyrics")
                                }
                            }
                        } else {
                            val activeIndex = remember(currentPositionMs, parsedLyrics) {
                                val cursorMs = currentPositionMs + LYRICS_SYNC_LEAD_MS
                                var idx = -1
                                for (i in parsedLyrics.indices) {
                                    if (parsedLyrics[i].timestampMs >= 0 && parsedLyrics[i].timestampMs <= cursorMs) {
                                        idx = i
                                    } else if (parsedLyrics[i].timestampMs > cursorMs) {
                                        break
                                    }
                                }
                                idx
                            }

                            val listState = rememberLazyListState()

                            // Tracks when the user last dragged the lyrics themselves. Checking
                            // isScrollInProgress alone only paused auto-scroll during the drag
                            // itself, so letting go snapped the view straight back to the active
                            // line -- you could never actually read ahead. Auto-scroll now stays
                            // out of the way for a few seconds after a manual scroll.
                            var lastManualScrollMs by remember { mutableStateOf(0L) }
                            LaunchedEffect(listState.isScrollInProgress) {
                                if (listState.isScrollInProgress) {
                                    lastManualScrollMs = System.currentTimeMillis()
                                }
                            }

                            LaunchedEffect(activeIndex) {
                                val sinceManualScroll = System.currentTimeMillis() - lastManualScrollMs
                                if (activeIndex >= 0 &&
                                    !listState.isScrollInProgress &&
                                    sinceManualScroll > LYRICS_MANUAL_SCROLL_GRACE_MS
                                ) {
                                    // Glide by an exact pixel distance rather than calling
                                    // animateScrollToItem, which lands on an item boundary using a
                                    // spec this code cannot choose -- with lines of unequal height
                                    // that produced the jerk between lines. Measuring where the
                                    // active line actually sits and easing that exact delta gives
                                    // one continuous movement instead.
                                    val info = listState.layoutInfo
                                    val active = info.visibleItemsInfo.firstOrNull { it.index == activeIndex }
                                    if (active != null) {
                                        // Rest the active line a little above centre so the lines
                                        // coming next stay on screen, the way YouTube Music does.
                                        val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
                                        val restingPoint = info.viewportStartOffset + viewportHeight * LYRICS_ACTIVE_LINE_ANCHOR
                                        val delta = (active.offset + active.size / 2f) - restingPoint
                                        listState.animateScrollBy(
                                            delta,
                                            animationSpec = tween(
                                                durationMillis = LYRICS_SCROLL_DURATION_MS,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        )
                                    } else {
                                        // Off screen entirely (a seek, or returning to the tab),
                                        // so there is no distance to ease -- just get there.
                                        listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                                    }
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                itemsIndexed(parsedLyrics) { index, line ->
                                    val isActive = index == activeIndex
                                    // Color/scale animate smoothly; the text's own style/size never
                                    // changes. Sizing the active line up via titleMedium used to
                                    // reflow this item's height the instant it activated -- while
                                    // the list was also mid-animateScrollToItem -- which is what
                                    // read as choppy/stuttery scrolling rather than a clean
                                    // line-by-line highlight.
                                    val animatedColor by animateColorAsState(
                                        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        animationSpec = tween(250),
                                        label = "lyricLineColor"
                                    )
                                    val animatedScale by animateFloatAsState(
                                        targetValue = if (isActive) 1.08f else 1f,
                                        animationSpec = tween(250),
                                        label = "lyricLineScale"
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                scaleX = animatedScale
                                                scaleY = animatedScale
                                            }
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (line.timestampMs >= 0) {
                                                    playerViewModel.seekTo(line.timestampMs)
                                                }
                                            }
                                            .padding(vertical = 8.dp, horizontal = 12.dp)
                                    ) {
                                        Text(
                                            text = line.text,
                                            // A music marker is a symbol standing in for singing,
                                            // not a lyric: sized up so it reads as one, and never
                                            // bolded, so real words still stand out when active.
                                            style = if (line.isInstrumental) {
                                                MaterialTheme.typography.titleMedium
                                            } else {
                                                MaterialTheme.typography.bodyMedium
                                            },
                                            fontWeight = if (isActive && !line.isInstrumental) FontWeight.Bold else FontWeight.Normal,
                                            color = if (line.isInstrumental) animatedColor.copy(alpha = 0.7f) else animatedColor,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                        // Markers hold no words, so there is nothing to translate.
                                        translatedLyrics?.takeIf { !line.isInstrumental }?.getOrNull(index)?.let { translatedText ->
                                            Text(
                                                text = translatedText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = animatedColor.copy(alpha = 0.75f),
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    ambientPrimary.copy(alpha = 0.45f * ambientPulse),
                                    ambientSecondary.copy(alpha = 0.25f * ambientPulse),
                                    Color.Transparent
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .aspectRatio(1f),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!currentSong?.artworkUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = FormatUtils.cacheBustedArtworkUri(currentSong?.artworkUri),
                                    contentDescription = "Album Art",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.fillMaxSize(0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Title + Artist (+ Producer)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = currentSong?.title ?: "No Song Selected",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentSong?.artist ?: "Unknown Artist",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!currentSong?.producer.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Prod. ${currentSong?.producer}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Interactive Action Pill Bar: Like/Dislike, AI scrape, Lyrics toggle, Save
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    playerViewModel.toggleLikeCurrentSong()
                                    if (!isLiked) isDisliked = false
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (isLiked) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt,
                                contentDescription = "Like",
                                tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isLiked) "Liked" else "Like",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight(0.5f)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        IconButton(
                            onClick = {
                                // No per-song "disliked" data model exists yet -- matches the
                                // MiniPlayer's existing dislike behavior of just skipping ahead.
                                isDisliked = true
                                playerViewModel.skipToNext()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ThumbDownOffAlt,
                                contentDescription = "Dislike",
                                tint = if (isDisliked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Surface(
                    onClick = {
                        currentSong?.let { playerViewModel.scrapeArtworkAndLyrics(it) }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isScraping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = "Fetch cover art & lyrics",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Surface(
                    onClick = { showLyricsTab = !showLyricsTab },
                    shape = CircleShape,
                    color = if (showLyricsTab) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.ChatBubbleOutline,
                            contentDescription = "Lyrics / Transcript",
                            tint = if (showLyricsTab) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Surface(
                    onClick = { showSaveDialog = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.height(38.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlaylistAdd,
                            contentDescription = "Save to Playlist",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Progress Seek Bar (isolated drag state -- doesn't fight the position ticker)
            var dragPosition by remember { mutableStateOf<Float?>(null) }
            val safeDuration = maxOf(1L, durationMs).toFloat()
            val currentPosFloat = currentPositionMs.toFloat().coerceIn(0f, safeDuration)
            val sliderValue = (dragPosition ?: currentPosFloat).coerceIn(0f, safeDuration)
            val displayPositionMs = (dragPosition ?: currentPosFloat).toLong()

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = { newPosition ->
                        dragPosition = newPosition
                    },
                    onValueChangeFinished = {
                        dragPosition?.let { playerViewModel.seekTo(it.toLong()) }
                        dragPosition = null
                    },
                    valueRange = 0f..safeDuration,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = MaterialTheme.colorScheme.onSurface,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = FormatUtils.formatDuration(displayPositionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = FormatUtils.formatDuration(durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 5-Button Playback Control Bar: Shuffle, Previous, Hero Play/Pause, Next, 3-State Repeat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = playerViewModel::toggleShuffle) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleMode) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = playerViewModel::restartOrPrevious,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Skip Previous",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Hero Play/Pause button -- theme-aware high-contrast circle (not a hardcoded
                // black-on-white swatch), so it still reads correctly in every selectable theme.
                Surface(
                    onClick = playerViewModel::togglePlayPause,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                IconButton(
                    onClick = playerViewModel::skipToNext,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Skip Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = playerViewModel::toggleRepeat) {
                    Icon(
                        imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

/**
 * 5-band equalizer + bass boost settings dialog. Sliders act on the live audio session
 * immediately via [EqualizerViewModel]; nothing shows if no playback session exists yet (i.e.
 * nothing has ever played this app run), since the system Equalizer/BassBoost effects need a
 * real audio session id to attach to.
 */
@Composable
private fun EqualizerDialog(
    equalizerViewModel: com.example.tgmusicai.ui.viewmodel.EqualizerViewModel,
    onDismiss: () -> Unit
) {
    val enabled by equalizerViewModel.enabled.collectAsState()
    val bandLevels by equalizerViewModel.bandLevels.collectAsState()
    val presetName by equalizerViewModel.presetName.collectAsState()
    val bassBoostStrength by equalizerViewModel.bassBoostStrength.collectAsState()
    val isSessionAvailable by equalizerViewModel.isSessionAvailable.collectAsState()
    var showPresetMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Equalizer") },
        text = {
            if (!isSessionAvailable) {
                Text(
                    "Play a song first, then open the equalizer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enabled", fontWeight = FontWeight.SemiBold)
                        Switch(checked = enabled, onCheckedChange = equalizerViewModel::setEnabled)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box {
                        androidx.compose.material3.TextButton(onClick = { showPresetMenu = true }) {
                            Text("Preset: $presetName")
                        }
                        DropdownMenu(expanded = showPresetMenu, onDismissRequest = { showPresetMenu = false }) {
                            equalizerViewModel.presetNames.forEachIndexed { index, name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        equalizerViewModel.applyPreset(index.toShort(), name)
                                        showPresetMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val range = equalizerViewModel.bandLevelRange
                    bandLevels.forEachIndexed { band, level ->
                        val freqHz = equalizerViewModel.centerFreqHz(band)
                        val freqLabel = if (freqHz >= 1000) "${freqHz / 1000}kHz" else "${freqHz}Hz"
                        Text(
                            text = freqLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = level.toFloat(),
                            valueRange = range[0].toFloat()..range[1].toFloat(),
                            onValueChange = { equalizerViewModel.setBandLevel(band, it.toInt().toShort()) },
                            enabled = enabled
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bass Boost",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = bassBoostStrength.toFloat(),
                        valueRange = 0f..1000f,
                        onValueChange = { equalizerViewModel.setBassBoostStrength(it.toInt()) },
                        enabled = enabled
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun NowPlayingScreenPreview() {
    TGMusicAITheme {
        Box(modifier = Modifier.fillMaxSize())
    }
}
