package com.example.tgmusicai.widget

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.tgmusicai.playback.PlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The transport controls on the home-screen widget.
 *
 * Each tap connects a short-lived [MediaController] to the playback session, issues one command and
 * releases. Connecting per tap looks wasteful next to firing an intent at the service, but a bare
 * intent cannot start the service from the background when nothing is playing -- which is exactly
 * the state the widget's play button exists to leave. Going through a controller makes the platform
 * start the service the supported way, so the same button works whether or not music is already on.
 */
// Opts in to Media3's unstable API surface, which PlaybackService is now marked with: referencing
// it from here counts as using it. The annotation is an acknowledgement, not a suppression -- a
// Media3 upgrade may change what it points at.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private suspend fun withController(context: Context, action: (MediaController) -> Unit) {
    val token = SessionToken(
        context.applicationContext,
        ComponentName(context.applicationContext, PlaybackService::class.java)
    )
    var controller: MediaController? = null
    try {
        controller = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            MediaController.Builder(context.applicationContext, token)
                .setApplicationLooper(context.applicationContext.mainLooper)
                .buildAsync()
                .await()
        }
        if (controller == null) {
            Log.w(TAG, "Timed out connecting to the playback session; ignoring the tap")
            return
        }
        action(controller)
    } catch (e: Exception) {
        // A widget tap must never crash the launcher's host process.
        Log.e(TAG, "Widget playback command failed", e)
    } finally {
        controller?.let { MoreExecutors.directExecutor().execute { it.release() } }
    }
}

private const val TAG = "WidgetPlaybackActions"

/**
 * How long to wait for the session. Long enough to cover a cold service start, short enough that a
 * genuinely stuck session does not leave the tap hanging.
 */
private const val CONNECT_TIMEOUT_MS = 5_000L

/** Toggles playback, resuming the last queue if the service was not running. */
class TogglePlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            if (controller.isPlaying) controller.pause() else controller.play()
        }
        TGMusicWidget().updateAll(context)
    }
}

/** Skips to the next track, if the queue has one. */
class SkipNextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
        }
        TGMusicWidget().updateAll(context)
    }
}

/**
 * Restarts the current track, or goes back a track when already near its start -- the same
 * behaviour as the previous button everywhere else, which Media3 implements for us.
 */
class SkipPreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { controller ->
            if (controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) {
                controller.seekToPrevious()
            }
        }
        TGMusicWidget().updateAll(context)
    }
}
