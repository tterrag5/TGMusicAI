package com.example.tgmusicai.ui.components.scene

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.tgmusicai.ui.theme.AppTheme

/**
 * One theme's painted backdrop: a still landscape built once per canvas size, animated details
 * drawn over it, then its particle layers.
 *
 * A scene instance belongs to one light/dark variant ([dark]) and one canvas; the backdrop and
 * each preview card keep their own, since the cached shapes are sized to the canvas they're on.
 */
internal abstract class ThemeScene(protected val dark: Boolean) {
    /** Particle families drawn over [back], nearest-last. */
    open val layers: List<ParticleLayer> = emptyList()

    /** What a tap scatters; null for a scene that shouldn't move at all. */
    abstract val burstShape: ParticleShape?
    open val burstColors: List<Color> = listOf(Color.White)

    /** Reused for anything drawn path-by-path each frame, so drawing allocates no paths. */
    protected val scratch = Path()
    private var builtFor = Size.Zero

    protected fun col(darkArgb: Long, lightArgb: Long): Color = Color(if (dark) darkArgb else lightArgb)

    /** Builds the size-dependent shapes (ridges, tree lines, skylines). Called on size change. */
    protected open fun build(w: Float, h: Float) {}

    protected abstract fun DrawScope.back(t: Float, unit: Float)

    /** Drawn over the particles: things on the glass, or foreground the particles pass behind. */
    protected open fun DrawScope.front(t: Float, unit: Float) {}

    fun draw(scope: DrawScope, t: Float) = with(scope) {
        if (size.width <= 0f || size.height <= 0f) return@with
        if (size != builtFor) {
            build(size.width, size.height)
            builtFor = size
        }
        val unit = size.width / 400f
        back(t, unit)
        layers.forEach { it.draw(this, t, unit) }
        front(t, unit)
    }

    protected fun DrawScope.sky(colors: List<Long>, endY: Float = size.height) {
        drawRect(Brush.verticalGradient(colors.map { Color(it) }, startY = 0f, endY = endY))
    }
}

/** The scene each palette paints behind the app. */
internal fun sceneFor(theme: AppTheme, dark: Boolean): ThemeScene = when (theme) {
    AppTheme.YT_DARK -> EncoreScene(dark)
    AppTheme.PASTEL_MIDNIGHT -> MidnightRainScene(dark)
    AppTheme.WARM_AMBER -> GoldenHourScene(dark)
    AppTheme.NORDIC_SLATE -> NordicSnowScene(dark)
    AppTheme.VIOLET_DUSK -> MoonlightScene(dark)
    AppTheme.FOREST -> ForestScene(dark)
    AppTheme.ROSE_QUARTZ -> RoseQuartzScene(dark)
    AppTheme.MONO -> ClassicScene(dark)
    AppTheme.SAKURA -> SakuraScene(dark)
    AppTheme.ISLAND -> TropicalScene(dark)
    AppTheme.GALAXY -> GalaxyScene(dark)
}
