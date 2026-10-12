package com.example.tgmusicai.ui.components.scene

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Tropical: palms and waves under a sunset sky.
// ---------------------------------------------------------------------------------------------
internal class TropicalScene(dark: Boolean) : ThemeScene(dark) {
    override val layers = listOf(
        ParticleLayer(
            ParticleStyle(
                ParticleShape.SPARKLE, 22, listOf(Color.White, Color(0xFFFFE0A0)),
                sizeMin = 1.2f, sizeMax = 3.2f, motion = ParticleMotion.HOVER, sway = 6f,
                twinkle = true, band = 0.64f..0.95f, opacity = 0.9f,
            ),
            seed = 61,
        ),
    )
    override val burstShape = ParticleShape.BUBBLE
    override val burstColors = listOf(Color(0xFF7DD3FC), Color.White, Color(0xFF5EEAD4))

    private val horizon = 0.6f
    private val islet = Path()
    private val shore = Path()
    private val clouds = List(3) { cloudPath(it + 70) }

    override fun build(w: Float, h: Float) {
        val hy = h * horizon
        islet.reset()
        islet.moveTo(-2f, hy + 1f)
        islet.cubicTo(w * 0.04f, hy - h * 0.04f, w * 0.14f, hy - h * 0.055f, w * 0.2f, hy - h * 0.03f)
        islet.cubicTo(w * 0.24f, hy - h * 0.015f, w * 0.28f, hy - h * 0.005f, w * 0.32f, hy + 1f)
        islet.close()
        shore.reset()
        shore.moveTo(w * 0.5f, h + 2f)
        shore.cubicTo(w * 0.62f, h * 0.9f, w * 0.72f, h * 0.84f, w * 0.86f, h * 0.83f)
        shore.cubicTo(w * 0.94f, h * 0.825f, w, h * 0.83f, w + 2f, h * 0.83f)
        shore.lineTo(w + 2f, h + 2f)
        shore.close()
    }

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        val hy = h * horizon
        sky(
            if (dark) listOf(0xFF0A1236, 0xFF282666, 0xFF853D78, 0xFFEC7466, 0xFFFFB46B)
            else listOf(0xFF4FB8EC, 0xFF86D2F4, 0xFFC0E8F6, 0xFFFFE9B5),
            endY = hy,
        )
        if (dark) starField(t, unit, 40, 0f, 0.3f, Color.White, seed = 121)
        cloudDrift(t, clouds, 0.22f, col(0x66F28A96, 0xE6FFFFFF), seed = 17, speed = 0.004f, scale = 0.55f)
        val sun = Offset(w * 0.4f, hy)
        val sr = w * 0.12f
        glowDisc(sun, sr, core = col(0xFFFFD08A, 0xFFFFF4C2), glow = col(0x8CFF8A5B, 0x99FFE08A), glowScale = 2.6f)
        drawPath(islet, col(0xFF2A1F4A, 0xFF6FA8B8))
        // Sea.
        drawRect(
            Brush.verticalGradient(
                if (dark) listOf(Color(0xFF3A2C6A), Color(0xFF1A1A48), Color(0xFF0A0F2C))
                else listOf(Color(0xFF5FC0E2), Color(0xFF2E93C4), Color(0xFF1C6E9E)),
                startY = hy,
                endY = h,
            ),
            topLeft = Offset(0f, hy),
            size = Size(w, h - hy),
        )
        // The sun's path on the water.
        val glint = col(0xFFFFD08A, 0xFFFFFBE6)
        for (k in 0 until 16) {
            val f = k / 16f
            val y = hy + (h * 0.3f) * f.pow(1.4f) + 3f * unit
            val half = sr * (0.35f + f * 0.9f) * (0.55f + 0.45f * sin(t * 2.2f + k * 1.3f))
            val dx = sin(t * 1.1f + k * 0.9f) * 6f * unit
            drawLine(glint.a(0.55f * (1f - f)), Offset(sun.x - half + dx, y), Offset(sun.x + half + dx, y), (1.2f + f * 2f) * unit, StrokeCap.Round)
        }
        // Swell lines, spaced wider as they come closer.
        val swell = col(0x33FFFFFF, 0x55FFFFFF)
        for (k in 1..6) {
            val f = (k / 7f).pow(1.6f)
            val y = hy + (h - hy) * f
            val amp = (1.5f + f * 6f) * unit
            val wave = w / (2.5f - f)
            val dir = if (k % 2 == 0) 1f else -1f
            scratch.reset()
            var x = -10f
            scratch.moveTo(x, y)
            while (x < w + 10f) {
                x += 8f * unit
                scratch.lineTo(x, y + sin(x / wave * 6.2832f + t * 0.8f * dir + k) * amp)
            }
            drawPath(scratch, swell, style = Stroke((0.8f + f * 1.6f) * unit, cap = StrokeCap.Round))
        }
        flock(t, 3, 0.3f, col(0xAA1A1236, 0xAA2B4A6B), unit, seed = 7, path = scratch, speed = 0.015f)
        drawPath(shore, col(0xFF120C24, 0xFFF0D29C))
        val trunk = col(0xFF0A0716, 0xFF6B4A2F)
        val frond = col(0xFF0A0716, 0xFF2F6B3A)
        palm(t, Offset(w * 0.8f, h * 0.84f), h * 0.27f, lean = -0.18f, trunk, frond, unit, 0f)
        palm(t, Offset(w * 0.93f, h * 0.835f), h * 0.2f, lean = 0.14f, trunk, frond, unit, 1.3f)
    }

    private fun DrawScope.palm(t: Float, base: Offset, height: Float, lean: Float, trunk: Color, frond: Color, unit: Float, phase: Float) {
        val sway = sin(t * 0.8f + phase) * height * 0.025f
        val top = Offset(base.x + lean * height + sway, base.y - height)
        // Tapered, slightly curved trunk drawn as stacked segments.
        val segs = 10
        for (k in 0 until segs) {
            val a = k / segs.toFloat()
            val b = (k + 1) / segs.toFloat()
            val bowA = sin(a * PI.toFloat()) * height * 0.06f * (if (lean < 0) 1f else -1f)
            val bowB = sin(b * PI.toFloat()) * height * 0.06f * (if (lean < 0) 1f else -1f)
            val p0 = Offset(lerp(base.x, top.x, a) + bowA, lerp(base.y, top.y, a))
            val p1 = Offset(lerp(base.x, top.x, b) + bowB, lerp(base.y, top.y, b))
            drawLine(trunk, p0, p1, height * lerp(0.07f, 0.035f, a), StrokeCap.Round)
        }
        // Fronds.
        val fronds = 8
        for (k in 0 until fronds) {
            val baseAngle = lerp(-3.0f, -0.15f, k / (fronds - 1f)) + (if (k % 2 == 0) 0.08f else -0.08f)
            val a = baseAngle + sin(t * 1.2f + k + phase) * 0.06f
            val len = height * (0.42f + rnd(k, 5) * 0.14f)
            val droop = len * 0.45f
            val tip = Offset(top.x + cos(a) * len, top.y + sin(a) * len + droop)
            val ctrl = Offset(top.x + cos(a) * len * 0.55f, top.y + sin(a) * len * 0.55f - len * 0.15f)
            val thick = len * 0.13f
            scratch.reset()
            scratch.moveTo(top.x, top.y)
            scratch.cubicTo(ctrl.x, ctrl.y - thick, lerp(ctrl.x, tip.x, 0.6f), lerp(ctrl.y, tip.y, 0.6f) - thick * 0.6f, tip.x, tip.y)
            scratch.cubicTo(lerp(ctrl.x, tip.x, 0.5f), lerp(ctrl.y, tip.y, 0.5f) + thick * 0.2f, ctrl.x, ctrl.y + thick * 0.4f, top.x, top.y)
            scratch.close()
            drawPath(scratch, frond)
        }
        for (k in 0 until 3) {
            drawCircle(trunk, height * 0.028f, Offset(top.x + (k - 1) * height * 0.03f, top.y + height * 0.03f))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Galaxy: nebulae, the Milky Way, a ringed planet with a moon, shooting stars.
// ---------------------------------------------------------------------------------------------
internal class GalaxyScene(dark: Boolean) : ThemeScene(dark) {
    override val layers = listOf(
        ParticleLayer(
            ParticleStyle(
                ParticleShape.SPARKLE, 18, if (dark) listOf(Color.White, Color(0xFFE9D5FF), Color(0xFF93C5FD)) else listOf(Color(0xFF7C3AED), Color(0xFFC084FC), Color(0xFF60A5FA)),
                sizeMin = 2f, sizeMax = 5f, motion = ParticleMotion.HOVER, sway = 4f, wind = 0.002f,
                twinkle = true, opacity = 0.95f,
            ),
            seed = 71,
        ),
    )
    override val burstShape = ParticleShape.SPARKLE
    override val burstColors = listOf(Color.White, Color(0xFFE9D5FF), Color(0xFF93C5FD), Color(0xFFF0ABFC))

    private val nebulae = if (dark) {
        listOf(0x477C3AED, 0x33DB2777, 0x382563EB, 0x2006B6D4, 0x33C084FC)
    } else {
        listOf(0x40C4B5FD, 0x38F9A8D4, 0x3393C5FD, 0x2667E8F9, 0x33E9D5FF)
    }.map { Color(it) }
    private val planetClip = Path()

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        sky(if (dark) listOf(0xFF04030B, 0xFF0A0720, 0xFF120A2A) else listOf(0xFFE9E0FF, 0xFFF5EFFF, 0xFFE4D8FF))
        nebulae.forEachIndexed { k, c ->
            val cx = (0.15f + 0.7f * rnd(k, 3)) * w + sin(t * 0.03f + k * 1.7f) * w * 0.06f
            val cy = (0.1f + 0.75f * rnd(k, 4)) * h + cos(t * 0.025f + k) * h * 0.04f
            val r = w * (0.45f + 0.3f * rnd(k, 5))
            drawCircle(Brush.radialGradient(listOf(c, c.a(0.4f), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        }
        // Milky Way: a diagonal haze dense with tiny stars.
        val star = col(0xFFFFFFFF, 0xFF6B4FA0)
        rotate(-28f, Offset(w / 2, h * 0.4f)) {
            mist(w / 2, h * 0.4f, w * 0.9f, h * 0.07f, col(0x26E9D5FF, 0x40FFFFFF))
        }
        for (k in 0 until 150) {
            val u = rnd(k, 141)
            val spread = (rnd(k, 142) + rnd(k, 143) + rnd(k, 144) - 1.5f) * 0.11f
            val x = u * w * 1.3f - w * 0.15f
            val y = h * 0.4f + (x - w / 2) * -0.53f + spread * h
            val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * (1f + rnd(k, 145) * 2f) + k))
            drawCircle(star.a(tw * (0.3f + 0.5f * rnd(k, 146))), (0.4f + rnd(k, 147) * 0.9f) * unit, Offset(x, y))
        }
        starField(t, unit, 90, 0f, 1f, star, seed = 151)
        shootingStars(t, unit, col(0xFFFFFFFF, 0xFF7C3AED), slots = 3, period = 6f)
        drawPlanet(t, Offset(w * 0.76f, h * 0.7f), w * 0.16f, unit)
    }

    private fun DrawScope.drawPlanet(t: Float, c: Offset, r: Float, unit: Float) {
        val tilt = -20f + sin(t * 0.2f) * 2f
        val ringSize = Size(r * 2.5f, r * 0.62f)
        val ringTopLeft = Offset(c.x - ringSize.width / 2, c.y - ringSize.height / 2)
        val ringBrush = Brush.horizontalGradient(
            listOf(col(0xCCFDE68A, 0xCCF59E0B), col(0x99F0ABFC, 0x99C026D3), col(0xCCFDE68A, 0xCCF59E0B)),
            startX = ringTopLeft.x,
            endX = ringTopLeft.x + ringSize.width,
        )
        val moonA = t * 0.15f
        val moon = Offset(c.x + cos(moonA) * r * 2.4f, c.y + sin(moonA) * r * 0.5f)
        val moonBehind = sin(moonA) < 0f
        fun DrawScope.drawMoon() {
            drawCircle(Brush.radialGradient(listOf(col(0xFFE2E8F0, 0xFFF8FAFC), col(0xFF64748B, 0xFF94A3B8)), Offset(moon.x - r * 0.05f, moon.y - r * 0.05f), r * 0.16f), r * 0.13f, moon)
        }
        if (moonBehind) drawMoon()
        rotate(tilt, c) {
            drawArc(ringBrush, 180f, 180f, false, ringTopLeft, ringSize, style = Stroke(r * 0.1f))
            drawArc(ringBrush, 180f, 180f, false, Offset(ringTopLeft.x - r * 0.12f, ringTopLeft.y - r * 0.04f), Size(ringSize.width + r * 0.24f, ringSize.height + r * 0.08f), alpha = 0.5f, style = Stroke(r * 0.04f))
        }
        drawCircle(
            Brush.radialGradient(
                if (dark) listOf(Color(0xFFF0ABFC), Color(0xFF9333EA), Color(0xFF3B0764))
                else listOf(Color(0xFFFBCFE8), Color(0xFFC084FC), Color(0xFF7E22CE)),
                center = Offset(c.x - r * 0.35f, c.y - r * 0.35f),
                radius = r * 1.5f,
            ),
            r,
            c,
        )
        planetClip.reset()
        planetClip.addOval(Rect(c, r))
        clipPath(planetClip) {
            rotate(tilt, c) {
                for (k in 0 until 5) {
                    val y = c.y - r + (k + 0.5f) * r * 0.4f + sin(t * 0.3f + k) * r * 0.02f
                    drawRect(Color.White.a(0.07f + 0.04f * (k % 2)), Offset(c.x - r * 1.2f, y), Size(r * 2.4f, r * 0.12f))
                }
            }
        }
        rotate(tilt, c) {
            drawArc(ringBrush, 0f, 180f, false, ringTopLeft, ringSize, style = Stroke(r * 0.1f))
            drawArc(ringBrush, 0f, 180f, false, Offset(ringTopLeft.x - r * 0.12f, ringTopLeft.y - r * 0.04f), Size(ringSize.width + r * 0.24f, ringSize.height + r * 0.08f), alpha = 0.5f, style = Stroke(r * 0.04f))
        }
        if (!moonBehind) drawMoon()
    }
}

// ---------------------------------------------------------------------------------------------
// Rose quartz: glowing crystal clusters with light sweeping across them, hearts rising.
// ---------------------------------------------------------------------------------------------
internal class RoseQuartzScene(dark: Boolean) : ThemeScene(dark) {
    private val roses = if (dark) listOf(Color(0xFFF472B6), Color(0xFFFBCFE8), Color(0xFFE879F9)) else listOf(Color(0xFFEC4899), Color(0xFFF9A8D4), Color(0xFFD946EF))
    override val layers = listOf(
        ParticleLayer(
            ParticleStyle(
                ParticleShape.BOKEH, 10, listOf(Color(0xFFF9A8D4), Color(0xFFE9D5FF)),
                sizeMin = 12f, sizeMax = 28f, motion = ParticleMotion.HOVER, sway = 18f, wind = 0.002f,
                band = 0.08f..0.7f, opacity = if (dark) 0.32f else 0.45f,
            ),
            seed = 81,
        ),
        ParticleLayer(
            ParticleStyle(
                ParticleShape.HEART, 14, roses, sizeMin = 3f, sizeMax = 7f,
                speedMin = 0.015f, speedMax = 0.04f, motion = ParticleMotion.RISE, sway = 16f, spin = 15f, opacity = 0.85f,
            ),
            seed = 82,
        ),
        ParticleLayer(
            ParticleStyle(
                ParticleShape.SPARKLE, 12, listOf(Color.White, Color(0xFFFBCFE8)),
                sizeMin = 1.5f, sizeMax = 3.5f, motion = ParticleMotion.HOVER, sway = 8f,
                twinkle = true, band = 0.1f..0.9f, opacity = 0.9f,
            ),
            seed = 83,
        ),
    )
    override val burstShape = ParticleShape.HEART
    override val burstColors = roses

    private class Crystal(val left: Path, val right: Path, val apex: Offset, val mid: Offset)
    private var crystals: List<Crystal> = emptyList()
    private val all = Path()

    override fun build(w: Float, h: Float) {
        val list = ArrayList<Crystal>()
        all.reset()
        fun cluster(cx: Float, count: Int, scale: Float, seed: Int) {
            for (k in 0 until count) {
                val tilt = (k - (count - 1) / 2f) * 0.32f + (rnd(k, seed) - 0.5f) * 0.2f
                val len = h * scale * (0.55f + rnd(k, seed + 1) * 0.5f) * (1f - abs(tilt) * 0.5f)
                val wd = len * (0.24f + rnd(k, seed + 2) * 0.08f)
                val bx = cx + (k - (count - 1) / 2f) * wd * 0.55f
                val by = h * 1.02f
                fun p(x: Float, y: Float) = Offset(bx + x * cos(tilt) - y * sin(tilt), by + x * sin(tilt) + y * cos(tilt))
                val bl = p(-wd / 2, 0f)
                val br = p(wd / 2, 0f)
                val bm = p(wd * 0.05f, 0f)
                val sl = p(-wd / 2, -len * 0.8f)
                val sr = p(wd / 2, -len * 0.78f)
                val ap = p(wd * 0.08f, -len)
                val left = Path().apply { moveTo(bl.x, bl.y); lineTo(bm.x, bm.y); lineTo(ap.x, ap.y); lineTo(sl.x, sl.y); close() }
                val right = Path().apply { moveTo(bm.x, bm.y); lineTo(br.x, br.y); lineTo(sr.x, sr.y); lineTo(ap.x, ap.y); close() }
                all.addPath(left)
                all.addPath(right)
                list += Crystal(left, right, ap, bm)
            }
        }
        cluster(w * 0.14f, 5, 0.26f, 10)
        cluster(w * 0.86f, 4, 0.22f, 20)
        cluster(w * 0.52f, 3, 0.12f, 30)
        crystals = list
    }

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        sky(if (dark) listOf(0xFF130B11, 0xFF22121D, 0xFF32182A, 0xFF431F37) else listOf(0xFFFFF5F8, 0xFFFDE6EF, 0xFFF9D4E3, 0xFFF3C0D5))
        // Big soft glows drifting behind everything.
        for (k in 0 until 3) {
            val c = Offset((0.2f + 0.6f * rnd(k, 7)) * w + sin(t * 0.05f + k) * w * 0.08f, (0.2f + 0.5f * rnd(k, 8)) * h)
            val r = w * 0.55f
            val color = if (k == 1) col(0x33C084FC, 0x40E9D5FF) else col(0x40F472B6, 0x4DFBCFE8)
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), c, r), r, c)
        }
        // Glow pooled under the crystals.
        listOf(0.14f, 0.86f, 0.52f).forEach { x ->
            val c = Offset(w * x, h)
            drawCircle(Brush.radialGradient(listOf(col(0x66F472B6, 0x66FFFFFF), Color.Transparent), c, w * 0.35f), w * 0.35f, c)
        }
        val light = col(0x8CF5B8D0, 0xE6FFE3EE)
        val shade = col(0x8CB0457A, 0xCCF29BBF)
        val edge = col(0x40FFFFFF, 0x99FFFFFF)
        crystals.forEach { c ->
            drawPath(c.left, light)
            drawPath(c.right, shade)
            drawLine(edge, c.mid, c.apex, 1.2f * unit)
        }
        // A band of light sweeping across the crystals every few seconds.
        val sweep = frac(t * 0.07f) * w * 2.2f - w * 0.6f
        clipPath(all) {
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.a(if (dark) 0.35f else 0.6f), Color.Transparent),
                    start = Offset(sweep - w * 0.12f, h * 0.7f),
                    end = Offset(sweep + w * 0.12f, h * 0.55f),
                ),
            )
        }
        // Crystals catching the light at their tips.
        crystals.forEachIndexed { i, c ->
            val tw = 0.5f + 0.5f * sin(t * (1.2f + rnd(i, 9)) + i * 2.1f)
            translate(c.apex.x, c.apex.y) {
                drawShape(ParticleShape.SPARKLE, scratch, (3f + 3f * tw) * unit, Color.White, tw * 0.9f)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Midnight rain: rain on the window over a sleeping city.
// ---------------------------------------------------------------------------------------------
internal class MidnightRainScene(dark: Boolean) : ThemeScene(dark) {
    override val burstShape = ParticleShape.BUBBLE
    override val burstColors = listOf(Color(0xFFA5C8FF), Color.White, Color(0xFF4FD1C5))

    private class Building(val rect: Rect, val seed: Int)
    private val farCity = Path()
    private val nearCity = Path()
    private var near: List<Building> = emptyList()
    private val clouds = List(4) { cloudPath(it + 90) }

    override fun build(w: Float, h: Float) {
        farCity.reset()
        var x = -w * 0.02f
        var i = 0
        while (x < w) {
            val bw = w * (0.05f + rnd(i, 1) * 0.06f)
            val bh = h * (0.1f + rnd(i, 2) * 0.17f)
            farCity.rectCw(Rect(x, h * 0.8f - bh, x + bw, h + 2f))
            if (rnd(i, 3) < 0.3f) farCity.rectCw(Rect(x + bw * 0.45f, h * 0.8f - bh - h * 0.03f, x + bw * 0.52f, h * 0.8f - bh))
            x += bw * (0.85f + rnd(i, 4) * 0.2f)
            i++
        }
        nearCity.reset()
        val list = ArrayList<Building>()
        x = -w * 0.03f
        i = 0
        while (x < w) {
            val bw = w * (0.08f + rnd(i, 11) * 0.07f)
            val bh = h * (0.12f + rnd(i, 12) * 0.22f)
            val r = Rect(x, h * 0.88f - bh, x + bw, h + 2f)
            nearCity.rectCw(r)
            // Stepped crowns and antennas on a few.
            if (rnd(i, 13) < 0.35f) nearCity.rectCw(Rect(x + bw * 0.2f, r.top - h * 0.02f, x + bw * 0.8f, r.top))
            if (rnd(i, 14) < 0.25f) nearCity.rectCw(Rect(x + bw * 0.48f, r.top - h * 0.06f, x + bw * 0.52f, r.top))
            list += Building(r, i)
            x += bw + w * 0.004f
            i++
        }
        near = list
    }

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        sky(if (dark) listOf(0xFF070B14, 0xFF0D1524, 0xFF15223A, 0xFF1D3048) else listOf(0xFFA6B4C5, 0xFFBAC6D4, 0xFFCFD8E2, 0xFFDEE5EC))
        if (dark) glowDisc(Offset(w * 0.7f, h * 0.14f), w * 0.045f, core = Color(0x80DCE7F5), glow = Color(0x33DCE7F5))
        cloudDrift(t, clouds, 0.13f, col(0xE61C2638, 0x998F9DB0), seed = 23, speed = 0.006f, scale = 0.7f)
        drawPath(farCity, col(0xFF15223A, 0xFF97A6B8))
        mistBand(t, 0.8f, col(0x26FFD48A, 0x44FFFFFF), seed = 29, speed = 0.002f, thickness = 0.03f)
        drawPath(nearCity, col(0xFF0A111E, 0xFF6F8096))
        // Windows: a few lit at any time, switching on and off now and then.
        val warm = col(0xD9FFD48A, 0x99FFF1C9)
        val teal = col(0xB37FE0D4, 0x80E0FFFB)
        val cellW = 9f * unit
        val cellH = 13f * unit
        near.forEach { b ->
            val cols = ((b.rect.width - 4f * unit) / cellW).toInt()
            val rows = ((h * 0.86f - b.rect.top - 6f * unit) / cellH).toInt()
            for (r in 0 until rows) for (c in 0 until cols) {
                val id = b.seed * 997 + r * 31 + c
                val period = 9f + rnd(id, 1) * 16f
                val slot = floor(t / period + rnd(id, 2) * 7f).toInt()
                if (rnd(id, slot) > 0.24f) continue
                drawRect(
                    if (rnd(id, 3) < 0.8f) warm else teal,
                    Offset(b.rect.left + 3f * unit + c * cellW, b.rect.top + 5f * unit + r * cellH),
                    Size(cellW * 0.55f, cellH * 0.5f),
                )
            }
        }
        // Streaks.
        val rain = col(0x80A5C8FF, 0x805B7491)
        for (k in 0 until 75) {
            val d = rnd(k, 201)
            val len = (14f + 26f * d) * unit
            val p = frac(rnd(k, 202) + t * (1.2f + d) / 1.2f)
            val y = (-0.1f + p * 1.2f) * h
            val x = rnd(k, 203) * w * 1.1f - y * 0.08f
            drawLine(
                Brush.verticalGradient(listOf(Color.Transparent, rain.a(0.4f + 0.6f * d)), startY = y - len, endY = y),
                Offset(x + len * 0.08f, y - len),
                Offset(x, y),
                (0.6f + 0.8f * d) * unit,
                StrokeCap.Round,
            )
        }
        // Splashes on the street.
        for (k in 0 until 26) {
            val period = 0.55f + rnd(k, 211) * 0.7f
            val phase = t / period + rnd(k, 212)
            val q = frac(phase)
            val slot = floor(phase).toInt()
            val x = rnd(slot, k + 300) * w
            val y = h * (0.9f + rnd(slot, k + 400) * 0.09f)
            val rx = (2f + q * 9f) * unit
            drawOval(rain.a(0.7f * (1f - q)), Offset(x - rx, y - rx * 0.28f), Size(rx * 2, rx * 0.56f), style = Stroke(0.8f * unit))
            val hop = sin(q * PI.toFloat()) * 7f * unit
            drawCircle(rain.a(1f - q), 0.9f * unit, Offset(x - q * 6f * unit, y - hop))
            drawCircle(rain.a(1f - q), 0.9f * unit, Offset(x + q * 6f * unit, y - hop))
        }
    }

    override fun DrawScope.front(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        // Beads sitting on the glass.
        val bead = col(0x24FFFFFF, 0x335B7491)
        val shine = col(0x59FFFFFF, 0x80FFFFFF)
        for (k in 0 until 40) {
            val c = Offset(rnd(k, 221) * w, rnd(k, 222) * h)
            val r = (1.2f + rnd(k, 223) * 2.6f) * unit
            drawCircle(bead, r, c)
            drawCircle(shine, r * 0.35f, Offset(c.x - r * 0.3f, c.y - r * 0.3f))
        }
        // Drops running down the glass, speeding up as they go.
        for (k in 0 until 7) {
            val period = 5f + rnd(k, 231) * 4f
            val phase = t / period + rnd(k, 232)
            val q = frac(phase)
            val slot = floor(phase).toInt()
            val x = rnd(slot, k + 500) * w
            val y0 = rnd(slot, k + 600) * h * 0.5f
            val y = y0 + q * q * h * 0.5f
            val fade = if (q > 0.85f) (1f - q) / 0.15f else 1f
            drawLine(
                Brush.verticalGradient(listOf(Color.Transparent, bead.a(2.2f * fade)), startY = y0, endY = y),
                Offset(x, y0),
                Offset(x + sin(q * 9f + k) * 1.5f * unit, y),
                1.4f * unit,
                StrokeCap.Round,
            )
            drawCircle(bead.a(2.5f * fade), 2.6f * unit, Offset(x, y))
            drawCircle(shine.a(fade), 0.9f * unit, Offset(x - 0.8f * unit, y - 0.8f * unit))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Encore: stage lights sweeping over a crowd, an equalizer pulsing, notes in the air.
// ---------------------------------------------------------------------------------------------
internal class EncoreScene(dark: Boolean) : ThemeScene(dark) {
    private val noteColors = if (dark) listOf(Color(0xD9FFFFFF), Color(0xFFFCA5A5), Color(0xFFEF4444)) else listOf(Color(0xFFB91C1C), Color(0xFFEF4444), Color(0xFF7F1D1D))
    override val layers = listOf(
        ParticleLayer(
            ParticleStyle(
                ParticleShape.NOTE, 16, noteColors, sizeMin = 4f, sizeMax = 9f,
                speedMin = 0.02f, speedMax = 0.05f, motion = ParticleMotion.RISE, sway = 18f, spin = 10f, opacity = 0.8f,
            ),
            seed = 91,
        ),
        ParticleLayer(
            ParticleStyle(
                ParticleShape.SPARKLE, 10, listOf(Color.White, Color(0xFFFCA5A5)),
                sizeMin = 1.5f, sizeMax = 3.5f, motion = ParticleMotion.HOVER, sway = 10f,
                twinkle = true, band = 0.05f..0.6f, opacity = 0.9f,
            ),
            seed = 92,
        ),
    )
    override val burstShape = ParticleShape.NOTE
    override val burstColors = noteColors

    private val crowd = Path()
    private var shoulders: List<Offset> = emptyList()

    override fun build(w: Float, h: Float) {
        crowd.reset()
        val heads = 24
        val list = ArrayList<Offset>()
        for (k in 0 until heads) {
            val x = (k + rnd(k, 1) * 0.6f) / heads * w * 1.08f - w * 0.04f
            val r = w * (0.019f + rnd(k, 2) * 0.008f)
            val y = h * (0.885f + rnd(k, 3) * 0.035f)
            crowd.addOval(Rect(Offset(x, y), r))
            crowd.addOval(Rect(x - r * 2.2f, y + r * 0.9f, x + r * 2.2f, y + r * 4f))
            list += Offset(x, y + r * 1.4f)
        }
        shoulders = list
    }

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        sky(if (dark) listOf(0xFF0A0A0A, 0xFF120808, 0xFF1E0A0A, 0xFF2A0C0C) else listOf(0xFFFFF7F7, 0xFFFDEBEB, 0xFFF9DADA, 0xFFF4C8C8))
        // Spotlights.
        val beams = if (dark) listOf(0x52EF4444, 0x38FFFFFF, 0x47F97316, 0x52EF4444) else listOf(0x40F87171, 0x40FFFFFF, 0x38FB923C, 0x40F87171)
        beams.forEachIndexed { k, argb ->
            val src = Offset(w * (0.12f + k * 0.25f), -h * 0.02f)
            val a = PI.toFloat() / 2 + sin(t * 0.3f + k * 1.7f) * 0.38f
            val len = h * 1.15f
            val half = 0.12f
            val end = Offset(src.x + cos(a) * len, src.y + sin(a) * len)
            scratch.reset()
            scratch.moveTo(src.x, src.y)
            scratch.lineTo(src.x + cos(a - half) * len, src.y + sin(a - half) * len)
            scratch.lineTo(src.x + cos(a + half) * len, src.y + sin(a + half) * len)
            scratch.close()
            val c = Color(argb)
            drawPath(scratch, Brush.linearGradient(listOf(c, c.a(0.3f), Color.Transparent), start = src, end = end))
            drawCircle(Brush.radialGradient(listOf(c.a(2f), Color.Transparent), src, 30f * unit), 30f * unit, src)
        }
        mistBand(t, 0.74f, col(0x26EF4444, 0x33FFFFFF), seed = 37, speed = 0.004f, thickness = 0.06f)
        // Equalizer, with a kick on the beat.
        val bars = 32
        val beat = (0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 2f)).pow(6f)
        val slot = w / bars
        val base = h * 0.87f
        val accent = col(0xFFE53935, 0xB3E53935)
        for (k in 0 until bars) {
            val f1 = 1.6f + rnd(k, 41) * 2.4f
            val f2 = 3.1f + rnd(k, 42) * 2f
            val bassWeight = (1f - k / bars.toFloat()).pow(2f)
            val v = (0.12f + 0.7f * abs(sin(t * f1 + k * 0.7f) * 0.65f + sin(t * f2 + k * 1.9f) * 0.35f) + beat * 0.3f * bassWeight).coerceAtMost(1f)
            val bh = h * 0.2f * v
            val bw = slot * 0.6f
            drawRoundRect(
                Brush.verticalGradient(listOf(accent, accent.a(0.15f)), startY = base - bh, endY = base),
                Offset(k * slot + (slot - bw) / 2, base - bh),
                Size(bw, bh),
                CornerRadius(bw / 2),
            )
        }
        val silhouette = col(0xFF050505, 0xE63A2020)
        // Raised hands, a couple holding phone lights.
        shoulders.forEachIndexed { k, s ->
            if (k % 4 != 1) return@forEachIndexed
            val hand = Offset(s.x + sin(t * 1.6f + k) * w * 0.02f + w * 0.015f, s.y - h * (0.09f + rnd(k, 51) * 0.04f) + sin(t * 3.2f + k) * h * 0.005f)
            drawLine(silhouette, s, hand, w * 0.012f, StrokeCap.Round)
            if (k % 8 == 1) {
                drawCircle(Brush.radialGradient(listOf(Color.White.a(0.55f), Color.Transparent), hand, 16f * unit), 16f * unit, hand)
                drawRect(Color.White.a(0.9f), Offset(hand.x - 1.6f * unit, hand.y - 5.5f * unit), Size(3.2f * unit, 5f * unit))
            }
        }
        drawPath(crowd, silhouette)
        drawRect(silhouette, Offset(0f, h * 0.94f), Size(w, h * 0.06f))
    }
}

// ---------------------------------------------------------------------------------------------
// Classic: plain graphite, nothing moving.
// ---------------------------------------------------------------------------------------------
internal class ClassicScene(dark: Boolean) : ThemeScene(dark) {
    override val burstShape: ParticleShape? = null

    override fun DrawScope.back(t: Float, unit: Float) {
        sky(if (dark) listOf(0xFF181818, 0xFF121212, 0xFF0E0E0E) else listOf(0xFFFCFCFC, 0xFFF5F5F5, 0xFFEFEFEF))
        val c = Offset(size.width * 0.8f, 0f)
        drawCircle(Brush.radialGradient(listOf(col(0x14FFFFFF, 0x0A000000), Color.Transparent), c, size.width), size.width, c)
    }
}

private fun Path.rectCw(r: Rect) = rectCw(r.left, r.top, r.right, r.bottom)
