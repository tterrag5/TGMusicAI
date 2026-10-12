package com.example.tgmusicai.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Text colours for content on a [LiquidGlassPanel]: light on the darkened glass, whatever the theme. */
object LiquidGlass {
    val text = Color.White
    val textDim = Color.White.copy(alpha = 0.62f)
    val textFaint = Color.White.copy(alpha = 0.4f)

    /** A soft dark halo, so lines stay legible where light parts of the artwork show through. */
    val textShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1.5f), blurRadius = 6f)
}

/**
 * A "liquid glass" surface: the cover art sits behind it, recognisable and only mildly blurred,
 * seen through a darkened, see-through pane with a light sheen across the top and a bright rim
 * around the edge -- a pane of smoked glass laid over the artwork, not a backdrop of colour.
 *
 * The blur needs Android 12 (`RenderEffect`); below that `Modifier.blur` does nothing, so the
 * pane is darkened a little more to keep the unblurred art from fighting the text.
 * Without artwork, [fallbackColors] (the artwork palette, or theme colours) make the backdrop.
 */
@Composable
fun LiquidGlassPanel(
    artworkUri: String?,
    fallbackColors: List<Color>,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    blurRadius: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        // 1. The artwork, or the palette when there is none. Scaled just past the edges so the
        // blur's soft border stays outside the pane.
        val backdrop = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.08f
                scaleY = 1.08f
            }
            .then(if (canBlur) Modifier.blur(blurRadius) else Modifier)
        if (!artworkUri.isNullOrBlank()) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = backdrop,
            )
        } else {
            Box(
                modifier = backdrop.background(
                    Brush.linearGradient(fallbackColors.ifEmpty { listOf(Color(0xFF303040), Color(0xFF101018)) })
                )
            )
        }

        // 2. Smoked glass: darkens the art enough for light text while leaving it visible.
        val tint = if (canBlur) 0.48f else 0.58f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = tint - 0.06f),
                            Color.Black.copy(alpha = tint),
                            Color.Black.copy(alpha = tint + 0.08f),
                        )
                    )
                )
        )

        // 3. Sheen: light catching the top-left of the glass.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f), Color.Transparent),
                        start = Offset.Zero,
                        end = Offset(900f, 1100f),
                    )
                )
        )

        // 4. Rim: a bright edge fading round the pane, as on thick glass.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.2.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.30f),
                        )
                    ),
                    shape = shape,
                )
        )

        content()
    }
}
