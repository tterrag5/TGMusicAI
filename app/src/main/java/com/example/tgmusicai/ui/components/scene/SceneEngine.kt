package com.example.tgmusicai.ui.components.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * The moving parts of the theme backdrops.
 *
 * Everything here is a pure function of time: a particle's position is computed from its seed and
 * the clock, never stepped frame to frame. That keeps a scene identical between the full-screen
 * backdrop and its preview card, lets "Moving backgrounds" off freeze it on a fixed instant, and
 * means a dropped frame costs nothing but that frame.
 */

/** Deterministic 0..1 noise from an index, so a scene draws the same way every time. */
internal fun rnd(i: Int, salt: Int = 0): Float {
    var x = i * 374761393 + salt * 668265263 + 1013904223
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0x7FFFFFFF) / 2147483647f
}

internal fun frac(x: Float): Float = x - floor(x)

internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal fun Color.a(alpha: Float): Color = copy(alpha = (this.alpha * alpha).coerceIn(0f, 1f))

/**
 * Scene clock in seconds. Runs on the frame clock while [running]; when stopped it holds where it
 * was (or [frozenAt] if it never ran), so switching motion off leaves a composed still frame
 * rather than every particle collapsing to its starting point.
 *
 * Read the returned state inside a draw lambda, not in composition: that way each frame only
 * invalidates drawing, and nothing recomposes 120 times a second.
 */
@Composable
internal fun rememberSceneTime(running: Boolean, frozenAt: Float = 14f): FloatState {
    val time = remember { mutableFloatStateOf(frozenAt) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val base = time.floatValue
        var start = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                time.floatValue = base + (now - start) / 1_000_000_000f
            }
        }
    }
    return time
}

enum class ParticleShape { PETAL, LEAF, SNOW, BUBBLE, SPARKLE, FIREFLY, HEART, NOTE, BOKEH, DUST }

enum class ParticleMotion { FALL, RISE, HOVER }

/**
 * How one family of particles looks and moves. Sizes and drifts are in scene units (1/400 of the
 * width), speeds in screen-heights per second.
 *
 * Every particle gets a depth from 0 (far) to 1 (near) that drives size, speed and opacity
 * together -- near ones big, quick and solid, far ones small, slow and faint -- which is what
 * makes a field of them read as space rather than as noise.
 */
data class ParticleStyle(
    val shape: ParticleShape,
    val count: Int,
    val colors: List<Color>,
    val sizeMin: Float,
    val sizeMax: Float,
    val speedMin: Float = 0.03f,
    val speedMax: Float = 0.08f,
    val motion: ParticleMotion = ParticleMotion.FALL,
    /** Side-to-side sway amplitude, in units. */
    val sway: Float = 12f,
    /** Steady sideways drift, in screen-widths per second (negative blows left). */
    val wind: Float = 0f,
    val spin: Float = 0f,
    /** Petals and leaves turning over in the air: width oscillates as if seen edge-on. */
    val flutter: Boolean = false,
    val twinkle: Boolean = false,
    /** Share of particles that sweep at the viewer and "hit the glass" before falling away. */
    val hitGlass: Float = 0f,
    val opacity: Float = 1f,
    /** Vertical band (0..1) that HOVER particles stay in. */
    val band: ClosedFloatingPointRange<Float> = 0f..1f,
)

/** A seeded set of particles for one [ParticleStyle]; cheap to keep, all state is per-index. */
class ParticleLayer(val style: ParticleStyle, seed: Int) {
    private val n = style.count
    private val depth = FloatArray(n) { rnd(it, seed) }
    private val x0 = FloatArray(n) { rnd(it, seed + 1) }
    private val y0 = FloatArray(n) { rnd(it, seed + 2) }
    private val phase = FloatArray(n) { rnd(it, seed + 3) * 6.2832f }
    private val spinDir = FloatArray(n) { if (rnd(it, seed + 4) < 0.5f) -1f else 1f }
    private val swayRate = FloatArray(n) { 0.5f + rnd(it, seed + 5) * 0.9f }
    private val hits = BooleanArray(n) { rnd(it, seed + 6) < style.hitGlass }
    private val colorIdx = IntArray(n) { (rnd(it, seed + 7) * style.colors.size).toInt().coerceAtMost(style.colors.size - 1) }
    private val path = Path()

    fun draw(scope: DrawScope, t: Float, unit: Float) = with(scope) {
        val w = size.width
        val h = size.height
        val s = style
        for (i in 0 until n) {
            val d = depth[i]
            var scale = 1f
            var alpha = s.opacity * (0.4f + 0.6f * d)
            val speed = lerp(s.speedMin, s.speedMax, d)
            val x: Float
            val y: Float
            when (s.motion) {
                ParticleMotion.FALL, ParticleMotion.RISE -> {
                    val p = frac(y0[i] + t * speed / 1.25f)
                    y = if (s.motion == ParticleMotion.FALL) (-0.12f + p * 1.25f) * h else (1.13f - p * 1.25f) * h
                    val across = frac(x0[i] + t * s.wind * (0.4f + 0.6f * d))
                    x = (-0.08f + across * 1.16f) * w + sin(t * swayRate[i] + phase[i]) * s.sway * unit * (0.5f + d)
                    if (hits[i] && p > 0.78f) {
                        // Rushes at the glass, swells, then drops away out of view.
                        val q = (p - 0.78f) / 0.22f
                        if (q < 0.35f) {
                            scale = 1f + q / 0.35f * 1.1f
                        } else {
                            val k = (q - 0.35f) / 0.65f
                            scale = 2.1f - k * 1.7f
                            alpha *= 1f - k
                        }
                    }
                }
                ParticleMotion.HOVER -> {
                    val bandY = lerp(s.band.start, s.band.endInclusive, y0[i])
                    y = bandY * h + sin(t * 0.21f * swayRate[i] + phase[i]) * s.sway * unit * 0.7f
                    x = frac(x0[i] + t * s.wind * (0.4f + 0.6f * d)) * w +
                        cos(t * 0.17f * swayRate[i] + phase[i] * 1.7f) * s.sway * unit
                }
            }
            if (s.twinkle) {
                alpha *= 0.2f + 0.8f * (0.5f + 0.5f * sin(t * (1.4f + swayRate[i] * 1.6f) + phase[i] * 3f))
            }
            if (alpha <= 0.01f) continue
            val r = lerp(s.sizeMin, s.sizeMax, d) * unit * scale
            val rot = if (s.spin != 0f) t * s.spin * spinDir[i] * (0.5f + swayRate[i]) + phase[i] * 57f else 0f
            val sx = if (s.flutter) 0.3f + 0.7f * abs(cos(t * 1.3f * swayRate[i] + phase[i])) else 1f
            val color = s.colors[colorIdx[i]]
            withTransform({
                translate(x, y)
                if (rot != 0f) rotate(rot, Offset.Zero)
                if (sx != 1f) scale(sx, 1f, Offset.Zero)
            }) {
                drawShape(s.shape, path, r, color, alpha, variant = i, near = d > 0.72f)
            }
        }
    }
}

/** Draws one particle of [shape] centred on the origin, [r] being roughly its radius. */
internal fun DrawScope.drawShape(
    shape: ParticleShape,
    path: Path,
    r: Float,
    color: Color,
    alpha: Float,
    variant: Int = 0,
    near: Boolean = false,
) {
    val c = color.a(alpha)
    when (shape) {
        ParticleShape.PETAL -> {
            // Sakura petal: rounded at the stem, notched at the tip.
            path.reset()
            path.moveTo(0f, -r)
            path.cubicTo(r * 0.95f, -r * 0.75f, r * 0.85f, r * 0.55f, r * 0.28f, r)
            path.lineTo(0f, r * 0.7f)
            path.lineTo(-r * 0.28f, r)
            path.cubicTo(-r * 0.85f, r * 0.55f, -r * 0.95f, -r * 0.75f, 0f, -r)
            path.close()
            drawPath(
                path,
                Brush.verticalGradient(
                    listOf(Color.White.a(alpha * 0.85f), c, c),
                    startY = -r,
                    endY = r,
                ),
            )
        }
        ParticleShape.LEAF -> {
            path.reset()
            path.moveTo(0f, -r)
            path.cubicTo(r * 0.85f, -r * 0.45f, r * 0.6f, r * 0.6f, 0f, r)
            path.cubicTo(-r * 0.6f, r * 0.6f, -r * 0.85f, -r * 0.45f, 0f, -r)
            path.close()
            drawPath(path, c)
            drawLine(Color.Black.a(alpha * 0.25f), Offset(0f, -r * 0.8f), Offset(0f, r * 1.25f), r * 0.09f, StrokeCap.Round)
        }
        ParticleShape.SNOW -> {
            if (near) {
                // A near flake is big enough to show its arms.
                val sw = r * 0.13f
                for (k in 0 until 3) {
                    val ang = k * 1.0472f
                    val dx = cos(ang) * r
                    val dy = sin(ang) * r
                    drawLine(c, Offset(-dx, -dy), Offset(dx, dy), sw, StrokeCap.Round)
                    for (sgn in listOf(-1f, 1f)) {
                        val bx = dx * 0.55f * sgn
                        val by = dy * 0.55f * sgn
                        val a1 = ang + 0.6f
                        val a2 = ang - 0.6f
                        val l = r * 0.32f * sgn
                        drawLine(c, Offset(bx, by), Offset(bx + cos(a1) * l, by + sin(a1) * l), sw * 0.8f, StrokeCap.Round)
                        drawLine(c, Offset(bx, by), Offset(bx + cos(a2) * l, by + sin(a2) * l), sw * 0.8f, StrokeCap.Round)
                    }
                }
            } else {
                drawCircle(Brush.radialGradient(listOf(c, c.a(0.35f), Color.Transparent), Offset.Zero, r), r)
            }
        }
        ParticleShape.BUBBLE -> {
            drawCircle(c.a(0.12f), r)
            drawCircle(c, r, style = Stroke(r * 0.1f))
            drawCircle(Color.White.a(alpha * 0.8f), r * 0.2f, Offset(-r * 0.38f, -r * 0.38f))
        }
        ParticleShape.SPARKLE -> {
            drawCircle(Brush.radialGradient(listOf(c.a(0.45f), Color.Transparent), Offset.Zero, r * 1.1f), r * 1.1f)
            path.reset()
            path.moveTo(0f, -r)
            path.lineTo(r * 0.18f, -r * 0.18f)
            path.lineTo(r, 0f)
            path.lineTo(r * 0.18f, r * 0.18f)
            path.lineTo(0f, r)
            path.lineTo(-r * 0.18f, r * 0.18f)
            path.lineTo(-r, 0f)
            path.lineTo(-r * 0.18f, -r * 0.18f)
            path.close()
            drawPath(path, c)
        }
        ParticleShape.FIREFLY -> {
            drawCircle(Brush.radialGradient(listOf(c.a(0.7f), c.a(0.18f), Color.Transparent), Offset.Zero, r * 2.4f), r * 2.4f)
            drawCircle(Color.White.a(alpha * 0.9f), r * 0.35f)
        }
        ParticleShape.HEART -> {
            path.reset()
            path.moveTo(0f, r * 0.9f)
            path.cubicTo(-r * 1.35f, r * 0.05f, -r * 0.75f, -r * 1.1f, 0f, -r * 0.35f)
            path.cubicTo(r * 0.75f, -r * 1.1f, r * 1.35f, r * 0.05f, 0f, r * 0.9f)
            path.close()
            drawPath(path, Brush.verticalGradient(listOf(Color.White.a(alpha * 0.5f), c, c), startY = -r, endY = r))
        }
        ParticleShape.NOTE -> {
            val sw = r * 0.16f
            if (variant % 3 == 0) {
                // Beamed pair.
                drawOval(c, Offset(-r * 0.95f, r * 0.45f), Size(r * 0.7f, r * 0.5f))
                drawOval(c, Offset(r * 0.15f, r * 0.25f), Size(r * 0.7f, r * 0.5f))
                drawLine(c, Offset(-r * 0.3f, r * 0.65f), Offset(-r * 0.3f, -r * 0.8f), sw)
                drawLine(c, Offset(r * 0.8f, r * 0.45f), Offset(r * 0.8f, -r), sw)
                drawLine(c, Offset(-r * 0.3f, -r * 0.8f), Offset(r * 0.8f, -r), sw * 2.2f)
            } else {
                drawOval(c, Offset(-r * 0.6f, r * 0.35f), Size(r * 0.75f, r * 0.55f))
                drawLine(c, Offset(r * 0.1f, r * 0.6f), Offset(r * 0.1f, -r), sw)
                path.reset()
                path.moveTo(r * 0.1f, -r)
                path.cubicTo(r * 0.75f, -r * 0.7f, r * 0.8f, -r * 0.25f, r * 0.45f, r * 0.05f)
                drawPath(path, c, style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        ParticleShape.BOKEH -> {
            drawCircle(Brush.radialGradient(listOf(c.a(0.55f), c.a(0.4f), c.a(0.08f), Color.Transparent), Offset.Zero, r), r)
            drawCircle(c.a(0.3f), r * 0.92f, style = Stroke(r * 0.05f))
        }
        ParticleShape.DUST -> drawCircle(c, r)
    }
}

/** A spray of themed particles where the user touched, flying out and fading (≈0.9s). */
internal class SceneBurst(val x: Float, val y: Float, val start: Float, val seed: Int)

internal const val BURST_SECONDS = 0.9f

internal fun DrawScope.drawBurst(
    burst: SceneBurst,
    t: Float,
    unit: Float,
    shape: ParticleShape,
    colors: List<Color>,
    path: Path,
) {
    val q = ((t - burst.start) / BURST_SECONDS).coerceIn(0f, 1f)
    if (q >= 1f) return
    val ease = 1f - (1f - q) * (1f - q)
    val count = 9
    for (k in 0 until count) {
        val ang = (k / count.toFloat()) * 6.2832f + rnd(k, burst.seed) * 0.6f
        val dist = (22f + rnd(k, burst.seed + 1) * 30f) * unit
        val x = burst.x + cos(ang) * dist * ease
        val y = burst.y + sin(ang) * dist * ease - 10f * unit * ease
        val r = (4f + rnd(k, burst.seed + 2) * 4f) * unit * (0.5f + ease * 0.6f)
        val color = colors[k % colors.size]
        withTransform({
            translate(x, y)
            rotate(ease * 120f * (if (k % 2 == 0) 1f else -1f), Offset.Zero)
        }) {
            drawShape(shape, path, r, color, 1f - q, variant = k)
        }
    }
}
