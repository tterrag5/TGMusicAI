package com.example.tgmusicai.ui.components.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Landscape pieces shared by the scenes: ridgelines, tree lines, fog, clouds, birds, aurora,
 * shooting stars. Static shapes are built once per canvas size (see [ThemeScene.ensureBuilt]) and
 * only redrawn; the animated ones are cheap functions of time.
 */

/**
 * A rectangle wound clockwise, like every hand-built outline here. [Path.addRect] winds the other
 * way, and under the non-zero fill rule an overlap of opposite windings cancels -- a tree trunk
 * added that way punched a square hole through the hill it stood on.
 */
internal fun Path.rectCw(l: Float, t: Float, r: Float, b: Float) {
    moveTo(l, t)
    lineTo(r, t)
    lineTo(r, b)
    lineTo(l, b)
    close()
}

/** A silhouette's outline plus the points it was built from, so things can stand on it. */
internal class Ridge(val path: Path, private val xs: FloatArray, private val ys: FloatArray) {
    /** Height of the ridge at [x], interpolated between its points. */
    fun yAt(x: Float): Float {
        if (x <= xs.first()) return ys.first()
        for (i in 1 until xs.size) {
            if (x <= xs[i]) {
                val k = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
                return lerp(ys[i - 1], ys[i], k)
            }
        }
        return ys.last()
    }

    /** The highest points, for snow caps and the like. */
    fun peaks(): List<Offset> = (1 until xs.size - 1)
        .filter { ys[it] < ys[it - 1] && ys[it] < ys[it + 1] }
        .map { Offset(xs[it], ys[it]) }
}

/**
 * A mountain or hill line across the full width at [base] (0..1 of height), rising up to [amp]
 * (fraction of height). Jagged gives peaks, smooth gives rolling hills.
 */
internal fun ridge(w: Float, h: Float, base: Float, amp: Float, seed: Int, segments: Int, jagged: Boolean): Ridge {
    val n = segments + 1
    val xs = FloatArray(n) { i ->
        val jitter = if (i == 0 || i == n - 1) 0f else (rnd(i, seed) - 0.5f) * 0.6f / segments
        (i / segments.toFloat() + jitter) * w
    }
    val ys = FloatArray(n) { i ->
        // Two octaves: big masses plus smaller shoulders, so it isn't a row of identical teeth.
        val big = sin(i / segments.toFloat() * PI.toFloat() * (1.3f + rnd(seed, 9) * 1.4f) + rnd(seed, 3) * 6f) * 0.5f + 0.5f
        val small = rnd(i, seed + 11)
        (base - amp * (0.65f * big + 0.35f * small)) * h
    }
    val path = Path()
    path.moveTo(-2f, h + 2f)
    path.lineTo(-2f, ys[0])
    if (jagged) {
        for (i in 1 until n) path.lineTo(xs[i], ys[i])
    } else {
        for (i in 1 until n) {
            val mx = (xs[i - 1] + xs[i]) / 2f
            path.cubicTo(mx, ys[i - 1], mx, ys[i], xs[i], ys[i])
        }
    }
    path.lineTo(w + 2f, ys[n - 1])
    path.lineTo(w + 2f, h + 2f)
    path.close()
    return Ridge(path, xs, ys)
}

/** Adds a row of layered pines standing on [ground] to [into]. Heights are in pixels. */
internal fun addPines(
    into: Path,
    ground: Ridge,
    w: Float,
    count: Int,
    minH: Float,
    maxH: Float,
    seed: Int,
    startX: Float = 0f,
    endX: Float = 1f,
    snow: Path? = null,
) {
    for (i in 0 until count) {
        val x = lerp(startX, endX, (i + rnd(i, seed) * 0.9f) / count) * w
        val th = lerp(minH, maxH, rnd(i, seed + 1))
        val base = ground.yAt(x) + th * 0.08f
        val tw = th * (0.36f + rnd(i, seed + 2) * 0.12f)
        // Three overlapping tiers, each narrower, read as a fir rather than a triangle.
        for (tier in 0 until 3) {
            val top = base - th + tier * th * 0.22f
            val bottom = base - th * 0.18f * (2 - tier)
            val half = tw * (0.55f + tier * 0.22f)
            into.moveTo(x, top)
            into.lineTo(x + half, bottom)
            into.lineTo(x - half, bottom)
            into.close()
            if (snow != null) {
                // Snow settled on the upper part of each tier.
                val drop = (bottom - top) * 0.42f
                snow.moveTo(x, top)
                snow.lineTo(x + half * 0.42f, top + drop)
                snow.lineTo(x + half * 0.12f, top + drop * 0.8f)
                snow.lineTo(x - half * 0.1f, top + drop * 0.95f)
                snow.lineTo(x - half * 0.42f, top + drop)
                snow.close()
            }
        }
        into.rectCw(x - tw * 0.07f, base - th * 0.2f, x + tw * 0.07f, base + th * 0.05f)
    }
}

internal fun DrawScope.glowDisc(center: Offset, r: Float, core: Color, glow: Color, glowScale: Float = 3.2f) {
    val gr = r * glowScale
    drawCircle(Brush.radialGradient(listOf(glow, glow.a(0.35f), Color.Transparent), center, gr), gr, center)
    drawCircle(core, r, center)
}

/** A soft horizontal smudge of mist, [rx] wide and [ry] tall. */
internal fun DrawScope.mist(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
    val c = Offset(cx, cy)
    withTransform({ scale(rx / ry, 1f, c) }) {
        drawCircle(Brush.radialGradient(listOf(color, color.a(0.4f), Color.Transparent), c, ry), ry, c)
    }
}

/** A band of mist at [y] (0..1) drifting sideways at [speed] screen-widths per second. */
internal fun DrawScope.mistBand(t: Float, y: Float, color: Color, seed: Int, count: Int = 5, speed: Float = 0.006f, thickness: Float = 0.05f) {
    val w = size.width
    val h = size.height
    for (k in 0 until count) {
        val cx = (frac(rnd(k, seed) + t * speed * (0.7f + 0.6f * rnd(k, seed + 1))) * 1.7f - 0.35f) * w
        val cy = (y + (rnd(k, seed + 2) - 0.5f) * thickness) * h
        mist(cx, cy, w * (0.3f + 0.25f * rnd(k, seed + 3)), h * thickness * (0.7f + 0.6f * rnd(k, seed + 4)), color)
    }
}

/** A cumulus outline at the origin, about 1 unit wide; draw with translate + scale. */
internal fun cloudPath(seed: Int): Path = Path().apply {
    val lobes = 5
    for (k in 0 until lobes) {
        val x = -0.4f + k * 0.2f + (rnd(k, seed) - 0.5f) * 0.06f
        val r = 0.12f + 0.1f * sin(k / (lobes - 1f) * PI.toFloat()) + rnd(k, seed + 1) * 0.04f
        addOval(Rect(x - r, -r * 1.1f, x + r, r * 0.6f))
    }
    addRect(Rect(-0.45f, -0.02f, 0.45f, 0.08f))
}

internal fun DrawScope.cloud(path: Path, x: Float, y: Float, width: Float, color: Color) {
    withTransform({
        translate(x, y)
        scale(width, width, Offset.Zero)
    }) { drawPath(path, color) }
}

/** Drifting clouds across [y] (0..1). */
internal fun DrawScope.cloudDrift(t: Float, paths: List<Path>, y: Float, color: Color, seed: Int, speed: Float = 0.004f, scale: Float = 0.45f) {
    val w = size.width
    val h = size.height
    paths.forEachIndexed { k, p ->
        val x = (frac(rnd(k, seed) + t * speed * (0.6f + 0.8f * rnd(k, seed + 1))) * 1.6f - 0.3f) * w
        val yy = (y + (rnd(k, seed + 2) - 0.5f) * 0.1f) * h
        cloud(p, x, yy, w * scale * (0.6f + 0.6f * rnd(k, seed + 3)), color.a(0.6f + 0.4f * rnd(k, seed + 4)))
    }
}

/** A bird in flight: two arcs whose tips rise and fall with the wingbeat. */
internal fun DrawScope.bird(x: Float, y: Float, span: Float, flap: Float, color: Color, path: Path) {
    val lift = span * (0.1f + 0.45f * flap)
    path.reset()
    path.moveTo(x - span, y - lift)
    path.cubicTo(x - span * 0.6f, y - lift * 0.4f - span * 0.25f, x - span * 0.2f, y - span * 0.18f, x, y)
    path.cubicTo(x + span * 0.2f, y - span * 0.18f, x + span * 0.6f, y - lift * 0.4f - span * 0.25f, x + span, y - lift)
    drawPath(path, color, style = Stroke(span * 0.14f, cap = StrokeCap.Round))
}

internal fun DrawScope.flock(t: Float, count: Int, y: Float, color: Color, unit: Float, seed: Int, path: Path, speed: Float = 0.02f) {
    val w = size.width
    val h = size.height
    for (k in 0 until count) {
        val x = (frac(rnd(k, seed) * 0.25f + t * speed) * 1.4f - 0.2f) * w + k * 14f * unit
        val yy = y * h + (rnd(k, seed + 1) - 0.5f) * 24f * unit + sin(t * 0.6f + k) * 4f * unit
        val flap = 0.5f + 0.5f * sin(t * (6f + rnd(k, seed + 2) * 3f) + k * 1.3f)
        bird(x, yy, (5f + rnd(k, seed + 3) * 3f) * unit, flap, color, path)
    }
}

/**
 * An aurora curtain: a wavering ribbon bright at its lower hem and fading upwards, with faint
 * vertical rays shimmering through it.
 */
internal fun DrawScope.aurora(t: Float, top: Float, height: Float, hem: Color, crown: Color, phase: Float, path: Path, unit: Float) {
    val w = size.width
    val h = size.height
    val steps = 48
    fun hemY(x: Float) = (top + height) * h +
        sin(x / w * 6.2832f * 1.1f + t * 0.22f + phase) * h * 0.035f +
        sin(x / w * 6.2832f * 2.7f - t * 0.15f + phase * 2f) * h * 0.012f
    fun crownY(x: Float) = top * h + sin(x / w * 6.2832f * 0.8f + t * 0.18f + phase * 1.3f) * h * 0.03f
    path.reset()
    path.moveTo(0f, crownY(0f))
    for (i in 1..steps) {
        val x = w * i / steps
        path.lineTo(x, crownY(x))
    }
    // The polygon runs a little past the hem so the glow can fade out below it instead of
    // stopping on a hard edge, which read as a dark ridge in the sky.
    val fall = h * 0.06f
    for (i in steps downTo 0) {
        val x = w * i / steps
        path.lineTo(x, hemY(x) + fall)
    }
    path.close()
    drawPath(
        path,
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.45f to crown,
            0.78f to hem,
            1f to Color.Transparent,
            startY = (top - 0.03f) * h,
            endY = (top + height + 0.03f) * h + fall,
        ),
    )
    // Rays.
    val rays = 36
    for (k in 0 until rays) {
        val x = w * (k + rnd(k, 51)) / rays
        val shimmer = 0.5f + 0.5f * sin(t * (0.6f + rnd(k, 52)) + k * 1.9f + phase)
        val y1 = hemY(x)
        val y0 = lerp(crownY(x), y1, 0.35f * rnd(k, 53))
        drawLine(
            Brush.verticalGradient(listOf(Color.Transparent, hem.a(0.55f * shimmer)), startY = y0, endY = y1),
            Offset(x, y0),
            Offset(x, y1),
            strokeWidth = (2f + rnd(k, 54) * 3f) * unit,
        )
    }
}

/** Occasional meteors in the upper part of the sky, each lasting about a second. */
internal fun DrawScope.shootingStars(t: Float, unit: Float, color: Color, slots: Int = 2, period: Float = 7f) {
    val w = size.width
    val h = size.height
    for (s in 0 until slots) {
        val tt = t + s * period * 0.47f
        val n = floor(tt / period).toInt()
        val local = tt - n * period
        val life = 1.1f
        if (local > life) continue
        val q = local / life
        val fromLeft = rnd(n, s * 13 + 1) < 0.5f
        val sx = (0.15f + rnd(n, s * 13 + 2) * 0.7f) * w
        val sy = (0.05f + rnd(n, s * 13 + 3) * 0.25f) * h
        val dx = if (fromLeft) 1f else -1f
        val dy = 0.45f + rnd(n, s * 13 + 4) * 0.25f
        val norm = sqrt(dx * dx + dy * dy)
        val len = w * 0.4f
        val head = Offset(sx + dx / norm * len * q, sy + dy / norm * len * q)
        val tailLen = len * 0.35f * (1f - (q - 0.6f).coerceAtLeast(0f) * 2f)
        val tail = Offset(head.x - dx / norm * tailLen, head.y - dy / norm * tailLen)
        val fade = if (q < 0.15f) q / 0.15f else 1f - (q - 0.15f) / 0.85f
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, color.a(fade)), start = tail, end = head),
            tail,
            head,
            strokeWidth = 1.8f * unit,
            cap = StrokeCap.Round,
        )
        drawCircle(Brush.radialGradient(listOf(color.a(fade), Color.Transparent), head, 5f * unit), 5f * unit, head)
    }
}

/** A still, twinkling field of tiny stars in the band [top]..[bottom]. */
internal fun DrawScope.starField(t: Float, unit: Float, count: Int, top: Float, bottom: Float, color: Color, seed: Int) {
    val w = size.width
    val h = size.height
    for (k in 0 until count) {
        val x = rnd(k, seed) * w
        val y = lerp(top, bottom, rnd(k, seed + 1) * rnd(k, seed + 5)) * h
        val d = rnd(k, seed + 2)
        val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (0.8f + d * 2.2f) + k * 2.3f))
        drawCircle(color.a(tw * (0.35f + 0.65f * d)), (0.5f + d * 1.1f) * unit, Offset(x, y))
    }
}
