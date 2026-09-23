package com.example.tgmusicai.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.youtube.DownloadStatus
import com.example.tgmusicai.ui.util.FormatUtils
import com.example.tgmusicai.ui.util.ShareUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Reusable list item component representing a single song in lists/searches.
 * Follows Material 3 Expressive guidelines with rounded album art,
 * clear typography hierarchy, and a contextual overflow options menu.
 *
 * @param song The [Song] metadata entity.
 * @param isPlaying Whether this song is currently playing (highlights title/icon).
 * @param onClick Callback triggered when the song row is tapped to initiate playback.
 * @param onAddToPlaylistClicked Callback triggered from the overflow menu to add to playlist.
 * @param onScrapeClicked Optional callback to fetch high-res cover art and lyrics.
 * @param onSwipeToQueue Optional callback fired when the row is swiped right far enough -- appends the track to the Up Next queue.
 * @param onSwipeToLike Optional callback fired when the row is swiped left far enough -- toggles Like / Save to Playlist.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: Song,
    onClick: () -> Unit,
    onAddToPlaylistClicked: () -> Unit,
    modifier: Modifier = Modifier,
    onScrapeClicked: (() -> Unit)? = null,
    onStartRadioClicked: (() -> Unit)? = null,
    onAnalyzeWithAiClicked: (() -> Unit)? = null,
    onSwipeToQueue: (() -> Unit)? = null,
    onSwipeToLike: (() -> Unit)? = null,
    isPlaying: Boolean = false,
    liveDownloadStatus: DownloadStatus? = null,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val swipeEnabled = onSwipeToQueue != null || onSwipeToLike != null
    val offsetX = remember { Animatable(0f) }
    val swipeScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeTriggerPx = with(density) { 88.dp.toPx() }
    val swipeMaxPx = with(density) { 96.dp.toPx() }

    Box(modifier = modifier.fillMaxWidth()) {
        if (swipeEnabled) {
            // Swipe background revealed underneath the row: green Queue icon on the left (shown
            // while dragging right), red Like icon on the right (shown while dragging left).
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        when {
                            offsetX.value > 0f -> Color(0xFF2E7D32)
                            offsetX.value < 0f -> Color(0xFFC62828)
                            else -> Color.Transparent
                        }
                    )
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (offsetX.value >= 0f) Arrangement.Start else Arrangement.End
            ) {
                when {
                    offsetX.value > 0f -> Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = "Add to queue",
                        tint = Color.White
                    )
                    offsetX.value < 0f -> Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = "Like",
                        tint = Color.White
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface)
                .then(
                    if (swipeEnabled) {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                swipeScope.launch {
                                    offsetX.snapTo((offsetX.value + delta).coerceIn(-swipeMaxPx, swipeMaxPx))
                                }
                            },
                            onDragStopped = {
                                val finalOffset = offsetX.value
                                if (finalOffset > swipeTriggerPx) {
                                    onSwipeToQueue?.invoke()
                                } else if (finalOffset < -swipeTriggerPx) {
                                    onSwipeToLike?.invoke()
                                }
                                offsetX.animateTo(0f, tween(200))
                            }
                        )
                    } else {
                        Modifier
                    }
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        // Expressive rounded album cover container with Coil AsyncImage support
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            } else if (!song.artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = FormatUtils.cacheBustedArtworkUri(song.artworkUri),
                    contentDescription = "Cover art for ${song.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (isPlaying) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Song Title and Artist info column
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitleText = if (!song.producer.isNullOrBlank()) {
                "${song.artist} • Prod. ${song.producer}"
            } else {
                "${song.artist} • ${song.album}"
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Download status: a live spinner while actively converting/downloading from the cloud
        // takes priority; otherwise a checkmark if downloaded locally, or a cloud icon if it's a
        // cloud-only (not-yet-downloaded) track. Plain local files (no youtubeId) show nothing.
        if (liveDownloadStatus == DownloadStatus.EXTRACTING || liveDownloadStatus == DownloadStatus.DOWNLOADING) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else if (!song.youtubeId.isNullOrBlank()) {
            Icon(
                imageVector = if (song.isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.CloudQueue,
                contentDescription = if (song.isDownloaded) "Downloaded" else "Cloud only, not downloaded",
                tint = if (song.isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Duration text formatted as MM:SS
        Text(
            text = FormatUtils.formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        // Contextual Overflow Menu button
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Song options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Add to Playlist") },
                    onClick = {
                        showMenu = false
                        onAddToPlaylistClicked()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        ShareUtils.shareSong(context, song)
                    }
                )
                if (onScrapeClicked != null) {
                    DropdownMenuItem(
                        text = { Text("Fetch Cover Art & Lyrics") },
                        onClick = {
                            showMenu = false
                            onScrapeClicked()
                        }
                    )
                }
                if (onStartRadioClicked != null) {
                    DropdownMenuItem(
                        text = { Text("Start Radio") },
                        onClick = {
                            showMenu = false
                            onStartRadioClicked()
                        }
                    )
                }
                if (onAnalyzeWithAiClicked != null) {
                    DropdownMenuItem(
                        text = { Text("Analyze (AI)") },
                        onClick = {
                            showMenu = false
                            onAnalyzeWithAiClicked()
                        }
                    )
                }
            }
        }
        }
    }
}

/**
 * Compact grid-cell variant of [SongItem] for the Library screen's grid layout: square cover art
 * with title/artist below, supporting the same tap-to-play / long-press-to-select / tap-to-toggle
 * interaction as the list view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongGridItem(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToPlaylistClicked: (() -> Unit)? = null,
    onScrapeClicked: (() -> Unit)? = null,
    onStartRadioClicked: (() -> Unit)? = null,
    onAnalyzeWithAiClicked: (() -> Unit)? = null,
    // Queue/Like are menu items here rather than the swipe gestures the list row uses: a swipe
    // across a one-third-width tile is both cramped and ambiguous against the grid's own scrolling.
    onAddToQueueClicked: (() -> Unit)? = null,
    onToggleLikeClicked: (() -> Unit)? = null,
    isPlaying: Boolean = false,
    liveDownloadStatus: DownloadStatus? = null,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                        isPlaying -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                } else if (!song.artworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = FormatUtils.cacheBustedArtworkUri(song.artworkUri),
                        contentDescription = "Cover art for ${song.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Same download signal the list row shows, so switching to grid view doesn't hide
            // whether a track is downloaded, cloud-only, or actively downloading right now.
            if (!isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                ) {
                    if (liveDownloadStatus == DownloadStatus.EXTRACTING ||
                        liveDownloadStatus == DownloadStatus.DOWNLOADING
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (!song.youtubeId.isNullOrBlank()) {
                        Icon(
                            imageVector = if (song.isDownloaded) {
                                Icons.Rounded.DownloadDone
                            } else {
                                Icons.Rounded.CloudQueue
                            },
                            contentDescription = if (song.isDownloaded) {
                                "Downloaded"
                            } else {
                                "Cloud only, not downloaded"
                            },
                            tint = if (song.isDownloaded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (!isSelected) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(32.dp)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Song options",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (onAddToQueueClicked != null) {
                            DropdownMenuItem(
                                text = { Text("Add to Queue") },
                                onClick = { showMenu = false; onAddToQueueClicked() }
                            )
                        }
                        if (onToggleLikeClicked != null) {
                            DropdownMenuItem(
                                text = { Text("Like / Unlike") },
                                onClick = { showMenu = false; onToggleLikeClicked() }
                            )
                        }
                        if (onAddToPlaylistClicked != null) {
                            DropdownMenuItem(
                                text = { Text("Add to Playlist") },
                                onClick = { showMenu = false; onAddToPlaylistClicked() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = { showMenu = false; ShareUtils.shareSong(context, song) }
                        )
                        if (onScrapeClicked != null) {
                            DropdownMenuItem(
                                text = { Text("Fetch Cover Art & Lyrics") },
                                onClick = { showMenu = false; onScrapeClicked() }
                            )
                        }
                        if (onStartRadioClicked != null) {
                            DropdownMenuItem(
                                text = { Text("Start Radio") },
                                onClick = { showMenu = false; onStartRadioClicked() }
                            )
                        }
                        if (onAnalyzeWithAiClicked != null) {
                            DropdownMenuItem(
                                text = { Text("Analyze (AI)") },
                                onClick = { showMenu = false; onAnalyzeWithAiClicked() }
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
