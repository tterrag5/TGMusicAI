package com.example.tgmusicai.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.data.local.entity.Playlist
import com.example.tgmusicai.data.repository.MusicRepository
import com.example.tgmusicai.ui.util.FormatUtils

/**
 * Material 3 Expressive grid-cell Card representing a playlist item, for the Playlists tab's
 * grid layout. Displays playlist cover art (Thumbs Up for Liked Music, top song artwork, or
 * default icon), name, song count, and pin/delete actions (delete hidden for immutable Liked Music).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    topSongArtworkUri: String? = null,
    songCount: Int = 0,
    onPinToggleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    // Three-per-row tiles have roughly a third less width than the old two-per-row ones, so the
    // padding, icon and action-button sizes all step down rather than letting the artwork and
    // labels get squeezed. The list layout keeps the roomier sizing.
    compact: Boolean = false
) {
    val outerPadding = if (compact) 6.dp else 10.dp
    val placeholderIconSize = if (compact) 28.dp else 40.dp
    val actionButtonSize = if (compact) 28.dp else 32.dp
    val actionIconSize = if (compact) 14.dp else 16.dp
    // Must match MusicRepository.deletePlaylist()'s protection check exactly (isSmart AND exact name) -
    // a case-insensitive name-only check would also hide the delete button on a user's own playlist
    // that happens to be named "Liked Music", even though the repository would actually allow deleting it.
    val isLikedMusic = playlist.isSmart && playlist.name == MusicRepository.LIKED_MUSIC_NAME
    val isProtectedSmart = MusicRepository.isProtectedSmartPlaylist(playlist)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(modifier = Modifier.padding(outerPadding)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isLikedMusic) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isLikedMusic) {
                    Icon(
                        imageVector = Icons.Rounded.ThumbUp,
                        contentDescription = "Liked Music",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(placeholderIconSize)
                    )
                } else if (!topSongArtworkUri.isNullOrBlank()) {
                    AsyncImage(
                        model = FormatUtils.cacheBustedArtworkUri(topSongArtworkUri),
                        contentDescription = playlist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(placeholderIconSize)
                    )
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(placeholderIconSize)
                    )
                }

                if (playlist.isPinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(18.dp)
                    )
                }
            }

            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "$songCount ${if (songCount == 1) "song" else "songs"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (onPinToggleClick != null || !isProtectedSmart) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (onPinToggleClick != null) {
                        IconButton(onClick = onPinToggleClick, modifier = Modifier.size(actionButtonSize)) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = if (playlist.isPinned) "Unpin" else "Pin to top",
                                tint = if (playlist.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(actionIconSize)
                            )
                        }
                    }
                    if (!isProtectedSmart) {
                        IconButton(onClick = onDeleteClick, modifier = Modifier.size(actionButtonSize)) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Delete playlist",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(actionIconSize)
                            )
                        }
                    }
                }
            }
        }
    }
}
