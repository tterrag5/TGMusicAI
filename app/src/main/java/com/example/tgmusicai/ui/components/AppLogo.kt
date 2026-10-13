package com.example.tgmusicai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.MaterialTheme

/**
 * The TGMusic logo (play triangle in a ring on a gradient disc) drawn in the current theme's
 * accent, so it matches whichever theme is picked (user, T040). Same design as
 * `res/drawable/ic_logo.xml`, which stays the fixed-colour launcher/notification version.
 */
@Composable
fun AppLogo(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val light = lerp(accent, Color.White, 0.35f)
    val dark = lerp(accent, Color.Black, 0.55f)
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            brush = Brush.linearGradient(listOf(light, accent, dark), start = Offset.Zero, end = Offset(size.width, size.height)),
            radius = r,
            center = c,
        )
        drawCircle(color = Color.White, radius = r * 0.5f, center = c, style = Stroke(width = r * 0.065f))
        val triangle = Path().apply {
            moveTo(c.x - r * 0.15f, c.y - r * 0.24f)
            lineTo(c.x + r * 0.25f, c.y)
            lineTo(c.x - r * 0.15f, c.y + r * 0.24f)
            close()
        }
        drawPath(triangle, color = Color.White)
    }
}
