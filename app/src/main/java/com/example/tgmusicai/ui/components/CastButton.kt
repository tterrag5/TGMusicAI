package com.example.tgmusicai.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.example.tgmusicai.cast.CastAvailability
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * The Cast device picker.
 *
 * Wraps the platform's own [MediaRouteButton] rather than drawing a custom one. The button is not
 * just an icon: it hides itself when no Cast device is on the network, shows connection state, and
 * opens the system device picker. Reimplementing that in Compose would mean reimplementing device
 * discovery, and users recognise this exact button from every other app.
 *
 * Renders nothing at all where Cast is unusable -- a device without Play Services -- rather than
 * showing a control that cannot do anything.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val castAvailable = remember(context) { CastAvailability.isAvailable(context) }
    if (!castAvailable) return

    AndroidView(
        modifier = modifier,
        factory = { viewContext -> createMediaRouteButton(viewContext) }
    )
}

/**
 * Builds the button and hands it to the Cast framework, which is what wires it to discovery.
 *
 * Failures are swallowed and an inert button returned: Cast reported itself available a moment
 * ago, but this call still reaches into Play Services, and a device picker that fails to attach is
 * not a reason to take the Now Playing screen down.
 */
private fun createMediaRouteButton(context: Context): MediaRouteButton {
    val button = MediaRouteButton(context)
    try {
        CastButtonFactory.setUpMediaRouteButton(context.applicationContext, button)
    } catch (e: Throwable) {
        Log.d("CastButton", "Could not attach the Cast button", e)
    }
    return button
}
