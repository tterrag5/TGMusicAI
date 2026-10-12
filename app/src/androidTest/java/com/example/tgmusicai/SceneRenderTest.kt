package com.example.tgmusicai

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tgmusicai.ui.components.scene.sceneFor
import com.example.tgmusicai.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders every theme scene, light and dark, at phone and preview-card proportions into PNGs under
 * the app's external files dir (`scenes/`), off screen. Lets the drawings be checked without
 * taking over the device's display; pull them with `adb pull`.
 *
 * Also a smoke test: a scene that throws while drawing fails here instead of in the app.
 */
@RunWith(AndroidJUnit4::class)
class SceneRenderTest {
    @Test
    fun renderEveryScene() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "scenes").apply { mkdirs() }
        val sizes = mapOf("phone" to (540 to 1212), "card" to (360 to 300))
        val rendered = HashMap<String, Bitmap>()
        for (theme in AppTheme.entries) for (dark in listOf(true, false)) for ((label, wh) in sizes) {
            val (w, h) = wh
            val bitmap = ImageBitmap(w, h)
            val scene = sceneFor(theme, dark)
            CanvasDrawScope().draw(Density(1.5f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
                scene.draw(this, 14f)
            }
            val file = File(out, "${theme.name.lowercase()}_${if (dark) "dark" else "light"}_$label.png")
            file.outputStream().use { bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 0)
            rendered["${theme.ordinal}_${if (dark) "dark" else "light"}_$label"] = bitmap.asAndroidBitmap()
        }
        // Contact sheets, one per variant, for reviewing everything at a glance.
        for (variant in listOf("dark_phone", "light_phone", "dark_card", "light_card")) {
            val tiles = AppTheme.entries.map { rendered.getValue("${it.ordinal}_$variant") }
            val cols = 6
            val tw = tiles[0].width / 2
            val th = tiles[0].height / 2
            val sheet = Bitmap.createBitmap(tw * cols, th * ((tiles.size + cols - 1) / cols), Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(sheet)
            tiles.forEachIndexed { i, b ->
                c.drawBitmap(b, null, android.graphics.Rect((i % cols) * tw, (i / cols) * th, (i % cols + 1) * tw, (i / cols + 1) * th), null)
            }
            File(out, "sheet_$variant.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /**
     * Rough per-frame cost of each scene at full phone resolution, logged under "SceneCost". This
     * rasterises on the CPU, which is far slower than the GPU path the app draws through, so it's
     * a ceiling for comparing scenes, not a real frame time.
     */
    @Test
    fun logSceneCost() {
        val w = 1080
        val h = 2424
        val bitmap = ImageBitmap(w, h)
        val canvas = Canvas(bitmap)
        for (theme in AppTheme.entries) {
            val scene = sceneFor(theme, dark = true)
            val draw = CanvasDrawScope()
            repeat(10) { i -> draw.draw(Density(2.6f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) { scene.draw(this, i / 60f) } }
            val frames = 60
            val start = System.nanoTime()
            repeat(frames) { i -> draw.draw(Density(2.6f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) { scene.draw(this, 1f + i / 60f) } }
            val ms = (System.nanoTime() - start) / 1e6 / frames
            android.util.Log.i("SceneCost", "${theme.name}: %.2f ms/frame".format(ms))
        }
    }
}
