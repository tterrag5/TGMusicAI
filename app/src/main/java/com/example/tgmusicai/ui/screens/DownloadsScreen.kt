package com.example.tgmusicai.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.youtube.DownloadProgressState
import com.example.tgmusicai.data.youtube.DownloadStatus

/**
 * Shows every cloud download currently tracked in [com.example.tgmusicai.data.youtube.CloudDownloadManager],
 * with live per-track progress, and pause/resume/cancel controls. Active downloads (extracting/
 * downloading) are listed first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloadMap: Map<String, DownloadProgressState>,
    onBack: () -> Unit,
    onPause: (videoId: String) -> Unit = {},
    onResume: (videoId: String) -> Unit = {},
    onCancel: (videoId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val allEntries = downloadMap.values.sortedBy { it.title.ifBlank { it.videoId } }
    val downloadingEntries = allEntries.filter {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.EXTRACTING
    }
    val queuedEntries = allEntries.filter { it.status == DownloadStatus.IDLE }
    val pausedEntries = allEntries.filter { it.status == DownloadStatus.PAUSED }
    val doneEntries = allEntries.filter {
        it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED
    }
    val entries = downloadingEntries + queuedEntries + pausedEntries + doneEntries

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No downloads yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                if (downloadingEntries.isNotEmpty()) {
                    item(key = "header_downloading") { SectionHeader("Downloading") }
                    items(downloadingEntries, key = { it.videoId }) { entry ->
                        DownloadRow(entry, onPause, onResume, onCancel, modifier = Modifier.animateItem())
                    }
                }
                if (queuedEntries.isNotEmpty()) {
                    item(key = "header_queued") { SectionHeader("Queued") }
                    items(queuedEntries, key = { it.videoId }) { entry ->
                        DownloadRow(entry, onPause, onResume, onCancel, modifier = Modifier.animateItem())
                    }
                }
                if (pausedEntries.isNotEmpty()) {
                    item(key = "header_paused") { SectionHeader("Paused") }
                    items(pausedEntries, key = { it.videoId }) { entry ->
                        DownloadRow(entry, onPause, onResume, onCancel, modifier = Modifier.animateItem())
                    }
                }
                if (doneEntries.isNotEmpty()) {
                    item(key = "header_downloads") { SectionHeader("Downloads") }
                    items(doneEntries, key = { it.videoId }) { entry ->
                        DownloadRow(entry, onPause, onResume, onCancel, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

@Composable
private fun DownloadRow(
    entry: DownloadProgressState,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkThumbnail(entry)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { entry.videoId },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.uploader.isNotBlank()) {
                Text(
                    text = entry.uploader,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when (entry.status) {
                DownloadStatus.EXTRACTING -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Extracting stream...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                DownloadStatus.DOWNLOADING -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { entry.progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${(entry.progressFraction.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                DownloadStatus.PAUSED -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { entry.progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "Paused at ${(entry.progressFraction.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                DownloadStatus.FAILED -> {
                    Text(
                        text = entry.errorMessage ?: "Download failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DownloadStatus.COMPLETED -> {
                    Text(
                        text = "Downloaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                DownloadStatus.IDLE -> {}
            }
        }

        when (entry.status) {
            DownloadStatus.DOWNLOADING -> {
                IconButton(onClick = { onPause(entry.videoId) }) {
                    Icon(Icons.Rounded.Pause, contentDescription = "Pause")
                }
                IconButton(onClick = { onCancel(entry.videoId) }) {
                    Icon(Icons.Rounded.Cancel, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                }
            }
            DownloadStatus.PAUSED -> {
                IconButton(onClick = { onResume(entry.videoId) }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume")
                }
                IconButton(onClick = { onCancel(entry.videoId) }) {
                    Icon(Icons.Rounded.Cancel, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                }
            }
            DownloadStatus.IDLE, DownloadStatus.EXTRACTING -> {
                IconButton(onClick = { onCancel(entry.videoId) }) {
                    Icon(Icons.Rounded.Cancel, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                }
            }
            else -> {}
        }
    }
    }
}

/**
 * 48dp rounded artwork for a download entry (thumbnail from the source track, or a music-note
 * placeholder), with a small status badge overlapping its bottom-right corner.
 */
@Composable
private fun ArtworkThumbnail(entry: DownloadProgressState) {
    Box(modifier = Modifier.size(48.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!entry.thumbnailUri.isNullOrBlank()) {
                AsyncImage(
                    model = entry.thumbnailUri,
                    contentDescription = entry.title.ifBlank { entry.videoId },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            StatusIcon(entry.status, size = 14.dp)
        }
    }
}

@Composable
private fun StatusIcon(status: DownloadStatus, size: androidx.compose.ui.unit.Dp = 28.dp) {
    when (status) {
        DownloadStatus.EXTRACTING, DownloadStatus.DOWNLOADING -> {
            CircularProgressIndicator(
                modifier = Modifier.size(size),
                strokeWidth = (size.value / 9f).dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        DownloadStatus.PAUSED -> {
            Icon(
                imageVector = Icons.Rounded.Pause,
                contentDescription = "Paused",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(size)
            )
        }
        DownloadStatus.COMPLETED -> {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Completed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size)
            )
        }
        DownloadStatus.FAILED -> {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(size)
            )
        }
        DownloadStatus.IDLE -> {
            Icon(
                imageVector = Icons.Rounded.CloudDownload,
                contentDescription = "Queued",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(size)
            )
        }
    }
}
