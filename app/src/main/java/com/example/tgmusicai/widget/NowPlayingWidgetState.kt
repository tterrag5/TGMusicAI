package com.example.tgmusicai.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.tgmusicai.data.local.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The snapshot of what is playing that the home-screen widget renders from.
 *
 * A widget is drawn by the launcher's process, not this app's, so it cannot read the player
 * directly. It could connect a `MediaController` on every render, but that means binding to the
 * playback service each time the launcher decides to redraw -- slow, and it starts the service for
 * something as incidental as scrolling the home screen. Writing a small snapshot whenever playback
 * changes, and having the widget read only that, keeps rendering to a file read.
 *
 * Stored in the app's existing settings DataStore rather than Glance's own per-widget state,
 * because this is one piece of app-wide state shared by every placed widget, not per-instance
 * configuration.
 */
object NowPlayingWidgetState {

    private val TITLE = stringPreferencesKey("widget_now_playing_title")
    private val ARTIST = stringPreferencesKey("widget_now_playing_artist")
    private val ARTWORK_URI = stringPreferencesKey("widget_now_playing_artwork")
    private val IS_PLAYING = booleanPreferencesKey("widget_now_playing_is_playing")

    /** What the widget shows. A null [title] means nothing has played yet, which renders as an empty state. */
    data class Snapshot(
        val title: String? = null,
        val artist: String? = null,
        val artworkUri: String? = null,
        val isPlaying: Boolean = false
    )

    /** Reads the current snapshot, falling back to the empty state if nothing was ever written. */
    suspend fun read(context: Context): Snapshot =
        context.applicationContext.dataStore.data.map { preferences ->
            Snapshot(
                title = preferences[TITLE],
                artist = preferences[ARTIST],
                artworkUri = preferences[ARTWORK_URI],
                isPlaying = preferences[IS_PLAYING] ?: false
            )
        }.first()

    /** Records what is playing. Called from the playback service on every track and play-state change. */
    suspend fun write(
        context: Context,
        title: String?,
        artist: String?,
        artworkUri: String?,
        isPlaying: Boolean
    ) {
        context.applicationContext.dataStore.edit { preferences ->
            if (title != null) preferences[TITLE] = title else preferences.remove(TITLE)
            if (artist != null) preferences[ARTIST] = artist else preferences.remove(ARTIST)
            if (artworkUri != null) preferences[ARTWORK_URI] = artworkUri else preferences.remove(ARTWORK_URI)
            preferences[IS_PLAYING] = isPlaying
        }
    }
}
