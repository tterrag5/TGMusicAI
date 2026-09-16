package com.example.tgmusicai.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation keys for Jetpack Navigation 3.
 */
sealed interface Screen : NavKey {
    /**
     * Primary Speed Dial Home tab with pinned items, Listen Again, Most Played, and Smart Playlists.
     */
    @Serializable
    data object Home : Screen

    /**
     * Library tab displaying all local songs in the database.
     */
    @Serializable
    data object Library : Screen

    /**
     * Playlists tab allowing creation, deletion, and viewing of user playlists.
     */
    @Serializable
    data object Playlists : Screen

    /**
     * YouTube Cloud search tab allowing streaming and downloading YouTube audio tracks.
     */
    @Serializable
    data object YouTube : Screen

    /**
     * Alarms tab allowing creation, viewing, editing, and toggling of musical alarms.
     */
    @Serializable
    data object Alarms : Screen

    /**
     * Statistics tab showing most played tracks and listening statistics.
     */
    @Serializable
    data object Stats : Screen

    /**
     * Playlist detail screen displaying the songs inside a selected playlist.
     */
    @Serializable
    data class PlaylistDetail(
        val playlistId: Long,
        val playlistName: String
    ) : Screen

    /**
     * Dedicated full-screen Now Playing view with expanded player controls.
     */
    @Serializable
    data object NowPlayingFull : Screen

    /**
     * Google account sign-in and YouTube playlist import/sync screen.
     */
    @Serializable
    data object GoogleSync : Screen

    /**
     * Shows all in-progress and recently finished cloud downloads with live progress.
     */
    @Serializable
    data object Downloads : Screen
}
