package com.example.tgmusicai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** One row in a [QuickPicksShelf]: a song from YouTube Music or the library. */
data class QuickPick(
    val key: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val onClick: () -> Unit,
    val onDownload: (() -> Unit)? = null,
)

/** One cover in a [CoverShelf]: an album, playlist or mix. */
data class CoverItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val onClick: () -> Unit,
    val round: Boolean = false,
)

/**
 * Shelf titles kept off Home: the user wants only what is picked for them, never generic charts,
 * trending, popularity or mood lists (2026-10-12), even though YouTube Music's home includes them.
 */
private val GENERIC_SHELF = Regex(
    """\b(chart|charts|trending|popular|top\s?\d*|hits|viral|new releases?|moods?|genres?|from the community|hot)\b""",
    RegexOption.IGNORE_CASE,
)

fun isGenericShelf(title: String): Boolean = GENERIC_SHELF.containsMatchIn(title)

/** YT Music-style section heading, with an optional "Play all" pill on the right. */
@Composable
fun HomeSectionHeading(title: String, onPlayAll: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 24.dp, bottom = 10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onPlayAll != null) {
            OutlinedButton(
                onClick = onPlayAll,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) { Text("Play all", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/**
 * YT Music's "Quick picks" layout: songs in columns of four rows that scroll sideways, each column
 * a little narrower than the screen so the next one peeks in.
 */
@Composable
fun QuickPicksShelf(picks: List<QuickPick>, rowsPerColumn: Int = 4) {
    if (picks.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columnWidth = maxWidth * 0.86f
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(picks.chunked(rowsPerColumn), key = { it.first().key }) { column ->
                Column(modifier = Modifier.width(columnWidth)) {
                    column.forEach { pick -> QuickPickRow(pick) }
                }
            }
        }
    }
}

@Composable
private fun QuickPickRow(pick: QuickPick) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = pick.onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (!pick.artworkUrl.isNullOrBlank()) {
                AsyncImage(model = pick.artworkUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pick.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                pick.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (pick.onDownload != null) {
            IconButton(onClick = pick.onDownload) {
                Icon(Icons.Rounded.Download, contentDescription = "Download ${pick.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A sideways row of square covers (albums, playlists, mixes) or round ones (artists). */
@Composable
fun CoverShelf(items: List<CoverItem>) {
    if (items.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items, key = { it.key }) { item ->
            Column(
                modifier = Modifier
                    .width(150.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = item.onClick)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(if (item.round) CircleShape else RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (!item.artworkUrl.isNullOrBlank()) {
                        AsyncImage(model = item.artworkUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
