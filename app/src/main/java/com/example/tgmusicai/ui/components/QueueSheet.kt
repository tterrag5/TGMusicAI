package com.example.tgmusicai.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.ui.util.FormatUtils

/**
 * Bottom sheet showing the currently playing song and everything queued up after it. Scrolls to
 * and highlights the current song on open, supports tap-to-jump, and supports dragging a track's
 * handle to reorder the live queue. Opened from the mini player's queue icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<Song>,
    currentSong: Song?,
    playingFromSource: String = "Your Queue",
    onSongClick: (index: Int) -> Unit,
    onMoveSong: (from: Int, to: Int) -> Unit,
    onSaveQueueAsPlaylist: (name: String) -> Unit = {},
    onRemoveSongs: (indices: List<Int>) -> Unit = {},
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val currentIndex = queue.indexOfFirst { it.mediaUri == currentSong?.mediaUri }
    val listState = rememberLazyListState()
    var showSaveNameDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    // Tracked by media URI rather than index so a selection stays correct even if the queue
    // itself changes (e.g. autoplay appending more songs) while the user is still selecting.
    var selectedUris by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedUris.isNotEmpty()

    LaunchedEffect(currentSong?.mediaUri, queue.size) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    if (showSaveNameDialog) {
        AlertDialog(
            onDismissRequest = { showSaveNameDialog = false },
            title = { Text("Save Queue as Playlist") },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (saveName.isNotBlank()) {
                            onSaveQueueAsPlaylist(saveName.trim())
                            showSaveNameDialog = false
                            saveName = ""
                        }
                    },
                    enabled = saveName.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveNameDialog = false }) { Text("Cancel") }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        if (isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedUris = emptySet() }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                    }
                    Text(
                        text = "${selectedUris.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = {
                        val indices = queue.withIndex()
                            .filter { (_, song) -> song.mediaUri in selectedUris }
                            .map { (index, _) -> index }
                        onRemoveSongs(indices)
                        selectedUris = emptySet()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Remove selected from queue",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Playing from",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = playingFromSource,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (queue.isNotEmpty()) {
                    Surface(
                        onClick = { showSaveNameDialog = true },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistAdd,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
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
            }
        }

        if (queue.isEmpty()) {
            Text(
                text = "Nothing queued.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(queue, key = { it.mediaUri }) { song ->
                    val index = queue.indexOf(song)
                    QueueRow(
                        song = song,
                        index = index,
                        isCurrent = index == currentIndex,
                        queueSize = queue.size,
                        isSelectionMode = isSelectionMode,
                        isSelected = song.mediaUri in selectedUris,
                        onClick = {
                            if (isSelectionMode) {
                                selectedUris = if (song.mediaUri in selectedUris) {
                                    selectedUris - song.mediaUri
                                } else {
                                    selectedUris + song.mediaUri
                                }
                            } else {
                                onSongClick(index)
                            }
                        },
                        onLongClick = { selectedUris = selectedUris + song.mediaUri },
                        onMove = onMoveSong
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    queueSize: Int,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit
) {
    // These let the drag gesture (which must not restart mid-drag -- see the stable
    // song.mediaUri pointerInput key below) always read this row's latest position, even as
    // reordering moves it to a different index in the list out from under the running gesture.
    val latestIndex = rememberUpdatedState(index)
    val latestQueueSize = rememberUpdatedState(queueSize)

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val rowHeightPx = remember(density) { with(density) { 64.dp.toPx() } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffsetY }
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    isDragging -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .combinedClickable(enabled = !isDragging, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            } else if (!song.artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = FormatUtils.cacheBustedArtworkUri(song.artworkUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Icon(
                    imageVector = if (isCurrent) Icons.Rounded.Equalizer else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
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
        Spacer(modifier = Modifier.width(8.dp))
        // Hidden during multi-select: dragging to reorder and selecting rows at once would be a
        // confusing pair of gestures to support simultaneously on the same row.
        if (!isSelectionMode) {
            Icon(
                imageVector = Icons.Rounded.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                // Keyed on the song's stable media URI, not its list index, so the gesture isn't
                // cancelled mid-drag just because reordering moved this row to a different index.
                modifier = Modifier
                    .padding(start = 4.dp)
                    .pointerInput(song.mediaUri) {
                        detectDragGestures(
                            onDragStart = { isDragging = true; dragOffsetY = 0f },
                            onDragEnd = { isDragging = false; dragOffsetY = 0f },
                            onDragCancel = { isDragging = false; dragOffsetY = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                                val curIndex = latestIndex.value
                                val size = latestQueueSize.value
                                if (dragOffsetY > rowHeightPx / 2 && curIndex < size - 1) {
                                    onMove(curIndex, curIndex + 1)
                                    dragOffsetY -= rowHeightPx
                                } else if (dragOffsetY < -rowHeightPx / 2 && curIndex > 0) {
                                    onMove(curIndex, curIndex - 1)
                                    dragOffsetY += rowHeightPx
                                }
                            }
                        )
                    }
            )
        }
    }
}
