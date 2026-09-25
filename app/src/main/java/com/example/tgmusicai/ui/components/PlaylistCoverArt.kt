package com.example.tgmusicai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.tgmusicai.ui.util.FormatUtils

/**
 * A playlist's cover: four of its songs in a 2x2 grid, or a single cover when it does not have four
 * to show.
 *
 * Four rather than one because one song's artwork says almost nothing about a playlist -- two
 * playlists sharing a first track looked identical, and a playlist whose first track changed
 * appeared to become a different playlist. Which four is decided by the caller (see
 * `PlaylistViewModel.playlistCoverArtwork`, which ranks by plays from that playlist).
 *
 * Every tile draws the placeholder *behind* its image rather than choosing between the two. An
 * artwork URI can be missing, stale, or a cloud thumbnail that fails while a track is being
 * resolved; with the placeholder underneath, any of those reads as a plain empty tile instead of a
 * broken image, which is what Cloud Nine's cover looked like whenever a song was slow to load.
 */
@Composable
fun PlaylistCoverArt(
    artworkUris: List<String>,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.AutoMirrored.Rounded.QueueMusic,
    placeholderIconSize: Dp = 28.dp,
    /** Gap between mosaic tiles. Zero reads as one image; a hair of space reads as four. */
    tileSpacing: Dp = 1.dp
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = placeholderIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(placeholderIconSize)
        )

        if (artworkUris.size >= MOSAIC_TILE_COUNT) {
            Column(modifier = Modifier.fillMaxSize()) {
                for (row in 0 until 2) {
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        for (column in 0 until 2) {
                            CoverTile(
                                artworkUri = artworkUris[row * 2 + column],
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f)
                            )
                            if (column == 0) Box(modifier = Modifier.size(tileSpacing))
                        }
                    }
                    if (row == 0) Box(modifier = Modifier.size(tileSpacing))
                }
            }
        } else if (artworkUris.isNotEmpty()) {
            CoverTile(artworkUri = artworkUris.first(), modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CoverTile(artworkUri: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer)) {
        AsyncImage(
            model = FormatUtils.cacheBustedArtworkUri(artworkUri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** A 2x2 grid. Fewer than this and the cover shows a single image rather than gaps. */
private const val MOSAIC_TILE_COUNT = 4
