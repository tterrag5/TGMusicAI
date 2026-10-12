package com.example.tgmusicai.ui.components.scene

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.example.tgmusicai.ui.theme.AppTheme

/**
 * Collects taps anywhere on screen so the backdrop can scatter a few of its particles from that
 * spot. Attach [Modifier.sceneTouches] to the container the backdrop sits behind.
 */
@Stable
class SceneTouchState {
    internal val bursts = mutableStateListOf<SceneBurst>()
    internal var clock: FloatState? = null
    internal var enabled = false
    private var counter = 0

    internal fun burst(at: Offset) {
        val now = clock?.floatValue ?: return
        if (!enabled) return
        bursts.removeAll { now - it.start > BURST_SECONDS }
        if (bursts.size >= 6) bursts.removeAt(0)
        bursts += SceneBurst(at.x, at.y, now, counter++)
    }
}

@Composable
fun rememberSceneTouchState(): SceneTouchState = remember { SceneTouchState() }

/**
 * Listens for taps without consuming anything, so every button and list underneath still gets
 * its event. A drag or a long press isn't a tap and scatters nothing.
 */
fun Modifier.sceneTouches(state: SceneTouchState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val downAt = down.uptimeMillis
        val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
        val moved = (up.position - down.position).getDistance()
        if (moved < viewConfiguration.touchSlop && up.uptimeMillis - downAt < 450) {
            state.burst(up.position)
        }
    }
}

/**
 * The painted, animated scene for [theme], filling its bounds.
 *
 * Behind the app this gets a [scrim] (the theme background, part-transparent) so list text sitting
 * straight on the scene stays readable; the picker's preview cards pass none so the scene shows
 * at full strength. Motion stops when [animate] is false or the system's animator scale is 0
 * (Settings > Accessibility > Remove animations), leaving a still frame rather than a blank one.
 */
@Composable
fun ThemeBackdrop(
    theme: AppTheme,
    dark: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    scrim: Color? = null,
    touches: SceneTouchState? = null,
) {
    val context = LocalContext.current
    val systemMotionOff = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val scene = remember(theme, dark) { sceneFor(theme, dark) }
    val running = animate && !systemMotionOff && scene.burstShape != null
    val time = rememberSceneTime(running)
    val burstPath = remember { Path() }
    if (touches != null) {
        touches.clock = time
        touches.enabled = running
    }

    Canvas(modifier.fillMaxSize()) {
        val t = time.floatValue
        scene.draw(this, t)
        val shape = scene.burstShape
        if (touches != null && shape != null) {
            val unit = size.width / 400f
            touches.bursts.forEach { drawBurst(it, t, unit, shape, scene.burstColors, burstPath) }
        }
        if (scrim != null) drawRect(scrim)
    }
}
