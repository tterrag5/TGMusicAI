package com.example.tgmusicai.ui.util

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a vibrant and a dark-muted swatch from [artworkUri] via the Android Palette API, for
 * the Now Playing screen's ambient gradient backdrop. Falls back to the current Material theme's
 * surface/background colors -- for a null/blank URI, a load failure, or before extraction
 * completes -- and animates between colors on every track change instead of a hard cut.
 */
@Composable
fun rememberArtworkColors(artworkUri: String?): Pair<State<Color>, State<Color>> {
    val context = LocalContext.current
    val fallbackPrimary = MaterialTheme.colorScheme.surface
    val fallbackSecondary = MaterialTheme.colorScheme.background

    var targetPrimary by remember { mutableStateOf(fallbackPrimary) }
    var targetSecondary by remember { mutableStateOf(fallbackSecondary) }

    LaunchedEffect(artworkUri) {
        if (artworkUri.isNullOrBlank()) {
            targetPrimary = fallbackPrimary
            targetSecondary = fallbackSecondary
            return@LaunchedEffect
        }
        val bitmap = loadBitmapForPalette(context, artworkUri)
        if (bitmap == null) {
            targetPrimary = fallbackPrimary
            targetSecondary = fallbackSecondary
            return@LaunchedEffect
        }
        val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).generate() }
        targetPrimary = palette.vibrantSwatch?.rgb?.let { Color(it) }
            ?: palette.mutedSwatch?.rgb?.let { Color(it) }
            ?: fallbackPrimary
        targetSecondary = palette.darkMutedSwatch?.rgb?.let { Color(it) }
            ?: palette.darkVibrantSwatch?.rgb?.let { Color(it) }
            ?: fallbackSecondary
    }

    val animatedPrimary = animateColorAsState(
        targetValue = targetPrimary,
        animationSpec = tween(durationMillis = 600),
        label = "artworkPrimaryColor"
    )
    val animatedSecondary = animateColorAsState(
        targetValue = targetSecondary,
        animationSpec = tween(durationMillis = 600),
        label = "artworkSecondaryColor"
    )

    return animatedPrimary to animatedSecondary
}

private suspend fun loadBitmapForPalette(context: android.content.Context, uri: String): Bitmap? {
    return try {
        val request = ImageRequest.Builder(context)
            .data(FormatUtils.cacheBustedArtworkUri(uri))
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        result.drawable?.let { drawable ->
            if (drawable is android.graphics.drawable.BitmapDrawable) {
                drawable.bitmap
            } else {
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
        }
    } catch (e: Exception) {
        null
    }
}

