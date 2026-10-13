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
     * Google account sign-in and YouTube playlist import/sync screen.
     */
    @Serializable
    data object GoogleSync : Screen

    /**
     * Shows all in-progress and recently finished cloud downloads with live progress.
     */
    @Serializable
    data object Downloads : Screen

    /**
     * Unified Settings screen consolidating theme selection, AI API key configuration,
     * backup/restore, and alarm sound settings.
     */
    @Serializable
    data object Settings : Screen

    /**
     * A YouTube Music artist's page: top tracks, albums, singles and related artists.
     *
     * Carries the artist's name alongside its id so the screen has a title to show while the page
     * is still loading, rather than appearing blank for the length of a network round trip.
     */
    @Serializable
    data class ArtistDetail(
        val browseId: String,
        val artistName: String
    ) : Screen

    /**
     * A YouTube Music album's page and its track listing. Carries the title for the same reason
     * [ArtistDetail] carries the artist's name.
     */
    @Serializable
    data class AlbumDetail(
        val browseId: String,
        val albumTitle: String
    ) : Screen
}
