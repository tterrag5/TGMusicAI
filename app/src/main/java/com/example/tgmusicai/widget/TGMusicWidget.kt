package com.example.tgmusicai.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.action.actionStartActivity
import com.example.tgmusicai.MainActivity
import com.example.tgmusicai.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Home-screen widget showing what is playing, with transport controls.
 *
 * Two layouts, chosen by the size the user drags the widget to: a single row for a short widget,
 * and artwork above the controls once there is room for it. Both read the same
 * [NowPlayingWidgetState] snapshot, so neither touches the player to render.
 */
class TGMusicWidget : GlanceAppWidget() {

    /**
     * Responsive rather than exact sizing: Glance builds one layout per listed size and the
     * launcher picks between them without another round trip to this process, so resizing the
     * widget stays instant.
     */
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT_SIZE, EXTENDED_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = NowPlayingWidgetState.read(context)
        // Decoded here rather than inside the composition: provideGlance can suspend, composition
        // cannot, and artwork may have to come off the network for a cloud track.
        val artwork = loadArtwork(context, snapshot.artworkUri)

        provideContent {
            GlanceTheme {
                WidgetBody(snapshot, artwork)
            }
        }
    }

    @Composable
    private fun WidgetBody(snapshot: NowPlayingWidgetState.Snapshot, artwork: Bitmap?) {
        val isExtended = LocalSize.current.height >= EXTENDED_SIZE.height

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isExtended) {
                ArtworkImage(artwork, size = 72.dp)
                Spacer(GlanceModifier.size(8.dp))
                TrackText(snapshot, centered = true)
                Spacer(GlanceModifier.size(8.dp))
                TransportRow(snapshot.isPlaying)
            } else {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkImage(artwork, size = 44.dp)
                    Spacer(GlanceModifier.width(10.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        TrackText(snapshot, centered = false)
                    }
                    Spacer(GlanceModifier.width(6.dp))
                    TransportRow(snapshot.isPlaying)
                }
            }
        }
    }

    @Composable
    private fun TrackText(snapshot: NowPlayingWidgetState.Snapshot, centered: Boolean) {
        Text(
            text = snapshot.title ?: "Nothing playing",
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = if (centered) androidx.glance.text.TextAlign.Center else androidx.glance.text.TextAlign.Start
            )
        )
        val artist = snapshot.artist
        if (!artist.isNullOrBlank()) {
            Text(
                text = artist,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    textAlign = if (centered) androidx.glance.text.TextAlign.Center else androidx.glance.text.TextAlign.Start
                )
            )
        }
    }

    @Composable
    private fun ArtworkImage(artwork: Bitmap?, size: androidx.compose.ui.unit.Dp) {
        val provider = if (artwork != null) {
            ImageProvider(artwork)
        } else {
            ImageProvider(R.drawable.ic_widget_music_note)
        }
        Image(
            provider = provider,
            contentDescription = null,
            modifier = GlanceModifier.size(size).cornerRadius(8.dp)
        )
    }

    @Composable
    private fun TransportRow(isPlaying: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(R.drawable.ic_widget_previous, "Previous", actionRunCallback<SkipPreviousAction>())
            ControlButton(
                iconRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                description = if (isPlaying) "Pause" else "Play",
                action = actionRunCallback<TogglePlayPauseAction>()
            )
            ControlButton(R.drawable.ic_widget_next, "Next", actionRunCallback<SkipNextAction>())
        }
    }

    @Composable
    private fun ControlButton(
        iconRes: Int,
        description: String,
        action: androidx.glance.action.Action
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.onSurface),
            modifier = GlanceModifier
                .size(36.dp)
                .padding(6.dp)
                .clickable(action)
        )
    }

    /**
     * Loads cover art for the snapshot, downsampled to something a widget may actually hold.
     *
     * RemoteViews caps how large a bitmap may be sent across processes, and a full-resolution cover
     * will exceed it, so the image is decoded straight to [ARTWORK_PX]. Network artwork -- the case
     * for a cloud track, whose art is a YouTube thumbnail URL -- is fetched with a short timeout;
     * a widget that renders late is worse than one that renders without a picture.
     */
    private suspend fun loadArtwork(context: Context, artworkUri: String?): Bitmap? {
        if (artworkUri.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(ARTWORK_TIMEOUT_MS) {
                    val bytes = when {
                        artworkUri.startsWith("http") -> fetchBytes(artworkUri)
                        artworkUri.startsWith("content://") ->
                            context.contentResolver.openInputStream(Uri.parse(artworkUri))?.use { it.readBytes() }
                        artworkUri.startsWith("file://") ->
                            Uri.parse(artworkUri).path?.let { File(it).takeIf(File::isFile)?.readBytes() }
                        else -> File(artworkUri).takeIf(File::isFile)?.readBytes()
                    } ?: return@withTimeoutOrNull null
                    decodeDownsampled(bytes)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Could not load widget artwork from $artworkUri", e)
                null
            }
        }
    }

    private fun fetchBytes(url: String): ByteArray? {
        val client = OkHttpClient.Builder()
            .callTimeout(ARTWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    }

    /** Two-pass decode: measure first, then decode at the smallest sample size that still covers [ARTWORK_PX]. */
    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= ARTWORK_PX) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        private const val TAG = "TGMusicWidget"
        private const val ARTWORK_PX = 256
        private const val ARTWORK_TIMEOUT_MS = 3_000L

        /** One row: art, title and transport side by side. */
        private val COMPACT_SIZE = DpSize(250.dp, 60.dp)

        /** Art stacked above the title and transport controls. */
        private val EXTENDED_SIZE = DpSize(250.dp, 130.dp)
    }
}

/**
 * Registers [TGMusicWidget] with the launcher.
 *
 * Also the place the playback service reaches for when it wants every placed widget redrawn.
 */
class TGMusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TGMusicWidget()
}

/**
 * Redraws every placed widget. Safe to call from anywhere, including when no widget exists --
 * Glance treats "no instances" as a no-op rather than an error.
 */
suspend fun refreshNowPlayingWidgets(context: Context) {
    try {
        TGMusicWidget().updateAll(context.applicationContext)
    } catch (e: Throwable) {
        Log.w("TGMusicWidget", "Could not refresh widgets", e)
    }
}
