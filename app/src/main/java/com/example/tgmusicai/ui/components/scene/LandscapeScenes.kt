package com.example.tgmusicai.ui.components.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Forest: looking out over a misty pine valley.
// ---------------------------------------------------------------------------------------------
internal class ForestScene(dark: Boolean) : ThemeScene(dark) {
    private val leafColors = if (dark) {
        listOf(Color(0xFF4ADE80), Color(0xFF86EFAC), Color(0xFFA3C76A), Color(0xFFE0B04A))
    } else {
        listOf(Color(0xFF3F9F5F), Color(0xFF6BBF59), Color(0xFFC28A2C), Color(0xFF8DBF4A))
    }
    override val layers = listOfNotNull(
        if (dark) {
            ParticleLayer(
                ParticleStyle(
                    ParticleShape.FIREFLY, 18, listOf(Color(0xFFD9F99D), Color(0xFFFDE68A)),
                    sizeMin = 1.1f, sizeMax = 2.4f, motion = ParticleMotion.HOVER, sway = 16f,
                    twinkle = true, band = 0.55f..0.95f,
                ),
                seed = 11,
            )
        } else {
            ParticleLayer(
                ParticleStyle(
                    ParticleShape.DUST, 26, listOf(Color.White, Color(0xFFFFF4C2)),
                    sizeMin = 0.7f, sizeMax = 1.7f, motion = ParticleMotion.HOVER, sway = 14f,
                    twinkle = true, wind = 0.004f, band = 0.15f..0.9f,
                ),
                seed = 11,
            )
        },
        ParticleLayer(
            ParticleStyle(
                ParticleShape.LEAF, 22, leafColors, sizeMin = 2.6f, sizeMax = 8.5f,
                speedMin = 0.025f, speedMax = 0.07f, sway = 18f, wind = -0.012f, spin = 70f,
                flutter = true, hitGlass = 0.2f, opacity = 0.92f,
            ),
            seed = 12,
        ),
    )
    override val burstShape = ParticleShape.LEAF
    override val burstColors = leafColors

    private lateinit var far: Ridge
    private val midPath = Path()
    private val nearPath = Path()
    private val frontPath = Path()

    override fun build(w: Float, h: Float) {
        far = ridge(w, h, 0.5f, 0.17f, seed = 3, segments = 9, jagged = true)
        val mid = ridge(w, h, 0.62f, 0.09f, seed = 5, segments = 7, jagged = false)
        val near = ridge(w, h, 0.75f, 0.07f, seed = 8, segments = 6, jagged = false)
        val front = ridge(w, h, 0.9f, 0.05f, seed = 13, segments = 5, jagged = false)
        midPath.reset(); midPath.addPath(mid.path); addPines(midPath, mid, w, 30, h * 0.035f, h * 0.06f, 21)
        nearPath.reset(); nearPath.addPath(near.path); addPines(nearPath, near, w, 18, h * 0.07f, h * 0.11f, 22)
        frontPath.reset(); frontPath.addPath(front.path); addPines(frontPath, front, w, 8, h * 0.13f, h * 0.22f, 23)
    }

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        sky(if (dark) listOf(0xFF06110D, 0xFF0C2018, 0xFF18372A, 0xFF2A4B3A) else listOf(0xFFBBD9C7, 0xFFD6EBDE, 0xFFECF6EF, 0xFFF6FBF5), endY = h * 0.75f)
        if (dark) starField(t, unit, 60, 0f, 0.42f, Color(0xFFE8F5E9), seed = 31)
        glowDisc(
            Offset(w * 0.7f, h * 0.25f), w * 0.075f,
            core = col(0xEEE8F5E9, 0xFFFFF8E1), glow = col(0x55B7E4C7, 0x99FFE9A8),
        )
        drawPath(far.path, col(0xFF1C392C, 0xFF9DC4AD))
        mistBand(t, 0.52f, col(0x2E9CC8B0, 0xAAFFFFFF), seed = 41, speed = 0.005f)
        flock(t, 3, 0.31f, col(0xB30B1C14, 0x992F4F3F), unit, seed = 5, path = scratch, speed = 0.012f)
        drawPath(midPath, col(0xFF12291F, 0xFF7FAE92))
        mistBand(t, 0.66f, col(0x2A9CC8B0, 0x99FFFFFF), seed = 42, speed = -0.004f)
        drawPath(nearPath, col(0xFF0A1B13, 0xFF5E8F72))
        mistBand(t, 0.82f, col(0x1A9CC8B0, 0x66FFFFFF), seed = 43, speed = 0.003f, thickness = 0.04f)
        drawPath(frontPath, col(0xFF040D09, 0xFF3F6E53))
    }
}

// ---------------------------------------------------------------------------------------------
// Sakura: a setting sun behind a snow-capped peak, a torii on the hill, blossoms overhead.
// ---------------------------------------------------------------------------------------------
internal class SakuraScene(dark: Boolean) : ThemeScene(dark) {
    private val pinks = if (dark) {
        listOf(Color(0xFFF9A8D4), Color(0xFFFBCFE8), Color(0xFFF472B6))
    } else {
        listOf(Color(0xFFF9A8D4), Color(0xFFF472B6), Color(0xFFFBCFE8))
    }
    override val layers = listOf(
        ParticleLayer(
            ParticleStyle(
                ParticleShape.PETAL, 32, pinks, sizeMin = 2.6f, sizeMax = 7.5f,
                speedMin = 0.03f, speedMax = 0.075f, sway = 20f, wind = 0.01f, spin = 60f,
                flutter = true, hitGlass = 0.22f, opacity = 0.95f,
            ),
            seed = 21,
        ),
    )
    override val burstShape = ParticleShape.PETAL
    override val burstColors = pinks

    private class Seg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float)
    private class Branch(val rootX: Float, val rootY: Float, val segs: List<Seg>, val flowers: List<Offset>, val phase: Float)

    private val fuji = Path()
    private val cap = Path()
    private lateinit var mid: Ridge
    private lateinit var near: Ridge
    private val torii = Path()
    private var branches: List<Branch> = emptyList()
    private var flowerR = 0f

    override fun build(w: Float, h: Float) {
        fuji.reset()
        fuji.moveTo(-2f, h * 0.66f)
        fuji.cubicTo(w * 0.12f, h * 0.62f, w * 0.26f, h * 0.44f, w * 0.31f, h * 0.38f)
        fuji.lineTo(w * 0.37f, h * 0.38f)
        fuji.cubicTo(w * 0.42f, h * 0.44f, w * 0.56f, h * 0.62f, w * 0.78f, h * 0.66f)
        fuji.lineTo(w + 2f, h * 0.67f)
        fuji.lineTo(w + 2f, h)
        fuji.lineTo(-2f, h)
        fuji.close()
        cap.reset()
        cap.moveTo(w * 0.31f, h * 0.38f)
        cap.lineTo(w * 0.37f, h * 0.38f)
        cap.cubicTo(w * 0.39f, h * 0.405f, w * 0.405f, h * 0.425f, w * 0.415f, h * 0.44f)
        cap.lineTo(w * 0.39f, h * 0.43f)
        cap.lineTo(w * 0.37f, h * 0.45f)
        cap.lineTo(w * 0.345f, h * 0.43f)
        cap.lineTo(w * 0.32f, h * 0.45f)
        cap.lineTo(w * 0.3f, h * 0.43f)
        cap.lineTo(w * 0.27f, h * 0.44f)
        cap.cubicTo(w * 0.285f, h * 0.42f, w * 0.3f, h * 0.395f, w * 0.31f, h * 0.38f)
        cap.close()
        mid = ridge(w, h, 0.72f, 0.06f, seed = 17, segments = 6, jagged = false)
        near = ridge(w, h, 0.84f, 0.05f, seed = 19, segments = 5, jagged = false)

        // Torii on the near hill.
        torii.reset()
        val tx = w * 0.74f
        val ground = near.yAt(tx)
        val th = h * 0.1f
        val tw = th * 1.15f
        val post = tw * 0.07f
        torii.rectCwR(Rect(tx - tw * 0.36f - post / 2, ground - th * 0.86f, tx - tw * 0.36f + post / 2, ground + 2f))
        torii.rectCwR(Rect(tx + tw * 0.36f - post / 2, ground - th * 0.86f, tx + tw * 0.36f + post / 2, ground + 2f))
        torii.rectCwR(Rect(tx - tw * 0.46f, ground - th * 0.72f, tx + tw * 0.46f, ground - th * 0.65f))
        torii.rectCwR(Rect(tx - post * 0.4f, ground - th * 0.86f, tx + post * 0.4f, ground - th * 0.68f))
        // Top beam with upswept ends.
        torii.moveTo(tx - tw * 0.6f, ground - th * 1.02f)
        torii.cubicTo(tx - tw * 0.3f, ground - th * 0.9f, tx + tw * 0.3f, ground - th * 0.9f, tx + tw * 0.6f, ground - th * 1.02f)
        torii.lineTo(tx + tw * 0.52f, ground - th * 0.86f)
        torii.lineTo(tx - tw * 0.52f, ground - th * 0.86f)
        torii.close()

        flowerR = w * 0.013f
        branches = listOf(
            grow(-w * 0.02f, h * 0.05f, angle = 0.38f, length = w * 0.42f, width = w * 0.026f, seed = 1, phase = 0f),
            grow(w * 1.02f, h * 0.12f, angle = PI.toFloat() - 0.32f, length = w * 0.34f, width = w * 0.022f, seed = 2, phase = 1.7f),
        )
    }

    /** A branch that forks a few times, with blossom clusters along its outer twigs. */
    private fun grow(x: Float, y: Float, angle: Float, length: Float, width: Float, seed: Int, phase: Float): Branch {
        val segs = ArrayList<Seg>()
        val flowers = ArrayList<Offset>()
        fun recurse(x0: Float, y0: Float, a: Float, len: Float, wd: Float, depth: Int, id: Int) {
            // Each limb bends slightly midway, so it doesn't read as a ruler.
            val bend = (rnd(id, seed) - 0.5f) * 0.5f
            val xm = x0 + cos(a) * len * 0.5f
            val ym = y0 + sin(a) * len * 0.5f
            val x1 = xm + cos(a + bend) * len * 0.5f
            val y1 = ym + sin(a + bend) * len * 0.5f
            segs += Seg(x0, y0, xm, ym, wd)
            segs += Seg(xm, ym, x1, y1, wd * 0.82f)
            if (depth >= 2) {
                for (k in 0 until 3) {
                    val f = 0.35f + k * 0.3f
                    flowers += Offset(
                        lerp(x0, x1, f) + (rnd(id * 7 + k, seed + 3) - 0.5f) * len * 0.35f,
                        lerp(y0, y1, f) + (rnd(id * 7 + k, seed + 4) - 0.5f) * len * 0.35f,
                    )
                }
            }
            if (depth >= 4) {
                flowers += Offset(x1, y1)
                return
            }
            val kids = if (depth == 0) 3 else 2
            for (k in 0 until kids) {
                val spread = (k - (kids - 1) / 2f) * 0.75f + (rnd(id * 3 + k, seed + 1) - 0.5f) * 0.3f
                recurse(
                    if (k == 0 && depth == 0) xm else x1,
                    if (k == 0 && depth == 0) ym else y1,
                    a + bend + spread,
                    len * (0.55f + rnd(id * 3 + k, seed + 2) * 0.15f),
                    wd * 0.6f,
                    depth + 1,
                    id * 4 + k + 1,
                )
            }
        }
        recurse(x, y, angle, length * 0.45f, width, 0, 1)
        return Branch(x, y, segs, flowers, phase)
    }

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        sky(
            if (dark) listOf(0xFF1E0B24, 0xFF461536, 0xFF85294C, 0xFFD15E5A, 0xFFF29A62)
            else listOf(0xFFFFE6EF, 0xFFFFD6E2, 0xFFFFC9C4, 0xFFFFD2A8, 0xFFFFE3B8),
            endY = h * 0.7f,
        )
        if (dark) starField(t, unit, 35, 0f, 0.3f, Color(0xFFFFE4F1), seed = 61)
        glowDisc(
            Offset(w * 0.56f, h * 0.53f), w * 0.15f,
            core = col(0xFFFFB27A, 0xFFFFF1D6), glow = col(0x88FF7A6B, 0x99FFC2A0), glowScale = 2.6f,
        )
        cloudDrift(t, cloudPaths, 0.3f, col(0x55F59AB0, 0xCCFFFFFF), seed = 7, speed = 0.003f, scale = 0.5f)
        drawPath(fuji, col(0xFF3A1735, 0xFFE3A3BC))
        drawPath(cap, col(0xBBF7D7E6, 0xF2FFFFFF))
        mistBand(t, 0.64f, col(0x40F9A8C8, 0xAAFFFFFF), seed = 71, speed = 0.004f)
        drawPath(mid.path, col(0xFF2A0F28, 0xFFD88AA8))
        flock(t, 3, 0.36f, col(0x991A0918, 0x99883355), unit, seed = 9, path = scratch, speed = 0.01f)
        drawPath(near.path, col(0xFF180816, 0xFFC4688C))
        drawPath(torii, col(0xFF0E040C, 0xE0B5363F))
        drawBranches(t)
    }

    private val cloudPaths = List(3) { cloudPath(it + 40) }

    private fun DrawScope.drawBranches(t: Float) {
        val bark = col(0xFF13050E, 0xFF5B2A3A)
        branches.forEach { b ->
            rotate(sin(t * 0.45f + b.phase) * 1.4f, Offset(b.rootX, b.rootY)) {
                b.segs.forEach { s ->
                    drawLine(bark, Offset(s.x1, s.y1), Offset(s.x2, s.y2), s.width, StrokeCap.Round)
                }
                b.flowers.forEachIndexed { i, f ->
                    val r = flowerR * (0.7f + rnd(i, 91) * 0.6f)
                    val color = pinks[i % pinks.size].a(if (dark) 0.92f else 1f)
                    val spin = rnd(i, 92) * 1.2566f
                    for (p in 0 until 5) {
                        val a = spin + p * 1.2566f
                        drawCircle(color, r * 0.52f, Offset(f.x + cos(a) * r * 0.5f, f.y + sin(a) * r * 0.5f))
                    }
                    drawCircle(col(0xFFBE185D, 0xFFDB2777), r * 0.22f, f)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Nordic snow: snow falling over quiet peaks and pines.
// ---------------------------------------------------------------------------------------------
internal class NordicSnowScene(dark: Boolean) : ThemeScene(dark) {
    private val flakes = if (dark) listOf(Color.White, Color(0xFFDBEAFE)) else listOf(Color(0xFF9FB3C8), Color(0xFFB8C7D9), Color.White)
    override val layers = listOf(
        ParticleLayer(
            ParticleStyle(
                ParticleShape.SNOW, 75, flakes, sizeMin = 1.2f, sizeMax = 5f,
                speedMin = 0.02f, speedMax = 0.06f, sway = 14f, wind = -0.006f, spin = 20f, opacity = 0.95f,
            ),
            seed = 31,
        ),
    )
    override val burstShape = ParticleShape.SNOW
    override val burstColors = flakes

    private lateinit var far: Ridge
    private val caps = Path()
    private val midPath = Path()
    private val midSnow = Path()
    private lateinit var ground: Ridge
    private val frontTrees = Path()
    private val frontSnow = Path()

    override fun build(w: Float, h: Float) {
        far = ridge(w, h, 0.56f, 0.21f, seed = 4, segments = 8, jagged = true)
        caps.reset()
        val capW = w / 8f * 0.38f
        far.peaks().forEach { p ->
            val l = Offset(p.x - capW, far.yAt(p.x - capW))
            val r = Offset(p.x + capW, far.yAt(p.x + capW))
            caps.moveTo(p.x, p.y)
            caps.lineTo(r.x, r.y)
            caps.lineTo(lerp(p.x, r.x, 0.55f), lerp(p.y, r.y, 0.7f))
            caps.lineTo(p.x + capW * 0.1f, lerp(p.y, r.y, 0.85f))
            caps.lineTo(lerp(p.x, l.x, 0.5f), lerp(p.y, l.y, 0.65f))
            caps.lineTo(l.x, l.y)
            caps.close()
        }
        val mid = ridge(w, h, 0.71f, 0.08f, seed = 6, segments = 7, jagged = false)
        midPath.reset(); midSnow.reset()
        midPath.addPath(mid.path)
        addPines(midPath, mid, w, 24, h * 0.04f, h * 0.07f, 27, snow = midSnow)
        ground = ridge(w, h, 0.87f, 0.035f, seed = 9, segments = 5, jagged = false)
        frontTrees.reset(); frontSnow.reset()
        addPines(frontTrees, ground, w, 4, h * 0.14f, h * 0.22f, 28, startX = 0f, endX = 0.32f, snow = frontSnow)
        addPines(frontTrees, ground, w, 3, h * 0.12f, h * 0.2f, 29, startX = 0.72f, endX = 1.02f, snow = frontSnow)
    }

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        sky(if (dark) listOf(0xFF0B111A, 0xFF141F2E, 0xFF203045, 0xFF314458) else listOf(0xFFC6D4E3, 0xFFDAE4EE, 0xFFECF1F6, 0xFFF6F8FB), endY = h * 0.72f)
        if (dark) {
            starField(t, unit, 70, 0f, 0.45f, Color(0xFFE0EAFF), seed = 81)
            aurora(t, 0.06f, 0.18f, Color(0x555EEAD4), Color(0x2260A5FA), phase = 0.6f, path = scratch, unit = unit)
        } else {
            glowDisc(Offset(w * 0.24f, h * 0.2f), w * 0.055f, core = Color(0xE6FFFFFF), glow = Color(0x88FFFFFF))
        }
        drawPath(far.path, col(0xFF2A3B50, 0xFFAFC0D2))
        drawPath(caps, col(0xB3E2E8F0, 0xFFFFFFFF))
        mistBand(t, 0.6f, col(0x22CBD5E1, 0x99FFFFFF), seed = 85, speed = 0.004f)
        drawPath(midPath, col(0xFF182434, 0xFF8EA4BB))
        drawPath(midSnow, col(0x99CBD5E1, 0xEEFFFFFF))
        mistBand(t, 0.78f, col(0x1ACBD5E1, 0x77FFFFFF), seed = 86, speed = -0.003f, thickness = 0.04f)
        drawPath(ground.path, col(0xFF56687F, 0xFFF8FAFC))
        drawPath(frontTrees, col(0xFF0D1520, 0xFF4B6077))
        drawPath(frontSnow, col(0xCCDCE4EE, 0xFFFFFFFF))
    }
}

// ---------------------------------------------------------------------------------------------
// Golden hour: low sun over rolling hills, long grass in the wind, warm light drifting.
// ---------------------------------------------------------------------------------------------
internal class GoldenHourScene(dark: Boolean) : ThemeScene(dark) {
    private val golds = listOf(Color(0xFFFFC46B), Color(0xFFFFE08A), Color(0xFFFF9D5C))
    override val layers = listOf(
        ParticleLayer(
            ParticleStyle(
                ParticleShape.BOKEH, 14, golds, sizeMin = 9f, sizeMax = 24f,
                speedMin = 0.006f, speedMax = 0.016f, motion = ParticleMotion.RISE, sway = 22f,
                opacity = if (dark) 0.42f else 0.5f,
            ),
            seed = 41,
        ),
        ParticleLayer(
            ParticleStyle(
                ParticleShape.SPARKLE, 24, listOf(Color(0xFFFFE7A0), Color.White, Color(0xFFFFC46B)),
                sizeMin = 1.4f, sizeMax = 4f, motion = ParticleMotion.HOVER, sway = 14f, wind = 0.003f,
                twinkle = true, band = 0.08f..0.85f, opacity = 0.95f,
            ),
            seed = 42,
        ),
    )
    override val burstShape = ParticleShape.SPARKLE
    override val burstColors = golds

    private lateinit var far: Ridge
    private val midPath = Path()
    private val oak = Path()
    private lateinit var near: Ridge
    private val grass = Path()

    override fun build(w: Float, h: Float) {
        far = ridge(w, h, 0.67f, 0.08f, seed = 2, segments = 6, jagged = false)
        val mid = ridge(w, h, 0.77f, 0.06f, seed = 7, segments = 5, jagged = false)
        midPath.reset()
        midPath.addPath(mid.path)
        // A lone oak on the middle hill.
        val ox = w * 0.24f
        val oy = mid.yAt(ox)
        val s = h * 0.06f
        midPath.rectCw(ox - s * 0.07f, oy - s * 0.9f, ox + s * 0.07f, oy + 2f)
        oak.reset()
        for (k in 0 until 6) {
            val a = k / 6f * 6.2832f
            val r = s * (0.38f + rnd(k, 14) * 0.12f)
            val cx = ox + cos(a) * s * 0.42f
            val cy = oy - s * 1.15f + sin(a) * s * 0.25f
            oak.addOval(Rect(cx - r, cy - r * 0.85f, cx + r, cy + r * 0.85f))
        }
        near = ridge(w, h, 0.89f, 0.04f, seed = 12, segments = 5, jagged = false)
    }

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        sky(if (dark) listOf(0xFF130B07, 0xFF2C190D, 0xFF5A3215, 0xFFA2581E) else listOf(0xFFFFF4DC, 0xFFFFE4B0, 0xFFFFCD7C, 0xFFFFB45E), endY = h * 0.7f)
        val sun = Offset(w * 0.62f, h * 0.63f)
        // Slowly turning sunbeams.
        val ray = col(0x26FFB347, 0x33FFE0A0)
        for (k in 0 until 10) {
            val a = (k * 36f + t * 1.5f) * PI.toFloat() / 180f
            val half = 0.07f + 0.03f * sin(t * 0.4f + k)
            val len = w * 1.3f
            scratch.reset()
            scratch.moveTo(sun.x, sun.y)
            scratch.lineTo(sun.x + cos(a - half) * len, sun.y + sin(a - half) * len)
            scratch.lineTo(sun.x + cos(a + half) * len, sun.y + sin(a + half) * len)
            scratch.close()
            drawPath(scratch, Brush.radialGradient(listOf(ray, Color.Transparent), sun, len * 0.8f))
        }
        glowDisc(sun, w * 0.13f, core = col(0xFFFFD27A, 0xFFFFF6D8), glow = col(0x80FF9D3C, 0x99FFC46B), glowScale = 2.8f)
        drawPath(far.path, col(0xFF4A2812, 0xFFE8A35C))
        mistBand(t, 0.68f, col(0x33FFB86B, 0x66FFF1D0), seed = 47, speed = 0.004f)
        flock(t, 4, 0.36f, col(0x992E180A, 0x99804515), unit, seed = 3, path = scratch, speed = 0.014f)
        drawPath(midPath, col(0xFF2E180A, 0xFFC97F3A))
        drawPath(oak, col(0xFF2E180A, 0xFFC97F3A))
        drawPath(near.path, col(0xFF190D05, 0xFF9A5A22))
        drawGrass(t, unit)
    }

    private fun DrawScope.drawGrass(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        val color = col(0xFF190D05, 0xFF9A5A22)
        grass.reset()
        val blades = 110
        for (k in 0 until blades) {
            val x = (k + rnd(k, 3)) / blades * w
            val y = near.yAt(x) + 2f
            val hh = h * (0.035f + rnd(k, 4) * 0.075f)
            val gust = sin(t * 0.9f + x / w * 5f) * 0.6f + sin(t * 2.3f + k * 0.7f) * 0.18f + 0.3f
            val bend = gust * hh * 0.35f
            grass.moveTo(x, y)
            grass.cubicTo(x, y - hh * 0.5f, x + bend * 0.5f, y - hh * 0.85f, x + bend, y - hh)
            if (k % 6 == 0) {
                grass.addOval(Rect(x + bend - 1.6f * unit, y - hh - 4f * unit, x + bend + 1.6f * unit, y - hh + 1f * unit))
            }
        }
        drawPath(grass, color, style = Stroke(1.3f * unit, cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------------------------------------
// Moonlight: aurora, a cratered moon, clouds passing, a cabin with its window lit.
// ---------------------------------------------------------------------------------------------
internal class MoonlightScene(dark: Boolean) : ThemeScene(dark) {
    override val layers = listOf(
        ParticleLayer(
            ParticleStyle(
                ParticleShape.SPARKLE, 14, listOf(Color.White, Color(0xFFC7D2FE)),
                sizeMin = 1.5f, sizeMax = 4f, motion = ParticleMotion.HOVER, sway = 6f,
                twinkle = true, band = 0f..0.55f, opacity = if (dark) 0.95f else 0.8f,
            ),
            seed = 51,
        ),
    )
    override val burstShape = ParticleShape.SPARKLE
    override val burstColors = listOf(Color.White, Color(0xFFC7D2FE), Color(0xFF86EFAC))

    private lateinit var far: Ridge
    private lateinit var near: Ridge
    private val nearPath = Path()
    private val cabin = Path()
    private var window = Rect.Zero
    private var chimney = Offset.Zero
    private val clouds = List(3) { cloudPath(it + 60) }

    override fun build(w: Float, h: Float) {
        far = ridge(w, h, 0.73f, 0.1f, seed = 15, segments = 6, jagged = false)
        near = ridge(w, h, 0.85f, 0.06f, seed = 18, segments = 5, jagged = false)
        nearPath.reset()
        nearPath.addPath(near.path)
        addPines(nearPath, near, w, 5, h * 0.06f, h * 0.1f, 33, startX = 0.04f, endX = 0.2f)
        addPines(nearPath, near, w, 4, h * 0.05f, h * 0.09f, 34, startX = 0.42f, endX = 0.56f)
        val cx = w * 0.3f
        val gy = near.yAt(cx) + 2f
        val cw = w * 0.1f
        val ch = cw * 0.55f
        cabin.reset()
        cabin.rectCw(cx - cw / 2, gy - ch, cx + cw / 2, gy + 4f)
        cabin.moveTo(cx - cw * 0.62f, gy - ch)
        cabin.lineTo(cx, gy - ch - cw * 0.45f)
        cabin.lineTo(cx + cw * 0.62f, gy - ch)
        cabin.close()
        cabin.rectCw(cx + cw * 0.18f, gy - ch - cw * 0.42f, cx + cw * 0.3f, gy - ch - cw * 0.12f)
        chimney = Offset(cx + cw * 0.24f, gy - ch - cw * 0.44f)
        window = Rect(cx - cw * 0.28f, gy - ch * 0.66f, cx - cw * 0.06f, gy - ch * 0.3f)
    }

    override fun DrawScope.back(t: Float, unit: Float) {
        val w = size.width
        val h = size.height
        sky(if (dark) listOf(0xFF06071A, 0xFF0F1136, 0xFF1C1948, 0xFF2C2256) else listOf(0xFFC6BFF0, 0xFFDAD3F9, 0xFFEFE4FA, 0xFFFFE5EE), endY = h * 0.8f)
        if (dark) starField(t, unit, 120, 0f, 0.7f, Color.White, seed = 101)
        if (dark) {
            aurora(t, 0.07f, 0.2f, Color(0x8C34D399), Color(0x408B5CF6), phase = 0f, path = scratch, unit = unit)
            aurora(t, 0.13f, 0.15f, Color(0x5922D3EE), Color(0x33C084FC), phase = 2.1f, path = scratch, unit = unit)
        } else {
            aurora(t, 0.08f, 0.18f, Color(0x55F0ABFC), Color(0x3393C5FD), phase = 0.4f, path = scratch, unit = unit)
        }
        val moon = Offset(w * 0.78f, h * 0.2f)
        val mr = w * 0.07f
        glowDisc(moon, mr, core = col(0xFFF5F3FF, 0xEEFFFFFF), glow = col(0x5CA5B4FC, 0x88FFFFFF))
        val crater = col(0x66C7C2E6, 0x33B8B0E0)
        drawCircle(crater, mr * 0.22f, Offset(moon.x - mr * 0.3f, moon.y - mr * 0.2f))
        drawCircle(crater, mr * 0.14f, Offset(moon.x + mr * 0.35f, moon.y + mr * 0.25f))
        drawCircle(crater, mr * 0.1f, Offset(moon.x + mr * 0.1f, moon.y - mr * 0.45f))
        drawCircle(crater, mr * 0.17f, Offset(moon.x - mr * 0.15f, moon.y + mr * 0.45f))
        if (dark) shootingStars(t, unit, Color.White)
        cloudDrift(t, clouds, 0.26f, col(0xB32B2660, 0xBBFFFFFF), seed = 13, speed = 0.005f, scale = 0.5f)
        drawPath(far.path, col(0xFF1A1740, 0xFFA9A2DA))
        mistBand(t, 0.76f, col(0x229C94E0, 0x88FFFFFF), seed = 103, speed = 0.003f)
        drawPath(nearPath, col(0xFF0C0A22, 0xFF7E77BC))
        drawPath(cabin, col(0xFF0C0A22, 0xFF6B64A8))
        // Window light, with a slight hearth flicker.
        val flicker = 0.85f + 0.15f * sin(t * 7f) * sin(t * 3.1f)
        val lamp = col(0xFFFFC56B, 0xFFFFE2A8).a(flicker)
        drawCircle(Brush.radialGradient(listOf(lamp.a(0.45f), Color.Transparent), window.center, window.width * 2.2f), window.width * 2.2f, window.center)
        drawRect(lamp, window.topLeft, window.size)
        // Chimney smoke.
        val smoke = col(0xFFB8B3E0, 0xFFFFFFFF)
        for (k in 0 until 7) {
            val q = frac(t * 0.1f + k / 7f)
            val x = chimney.x + q * 26f * unit + sin(q * 4f + k) * 4f * unit
            val y = chimney.y - q * h * 0.14f
            drawCircle(smoke.a(0.28f * (1f - q)), (2.5f + q * 9f) * unit, Offset(x, y))
        }
    }
}

private fun Path.rectCwR(r: Rect) = rectCw(r.left, r.top, r.right, r.bottom)
