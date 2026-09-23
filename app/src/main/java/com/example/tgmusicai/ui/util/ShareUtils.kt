package com.example.tgmusicai.ui.util

import android.content.Context
import android.content.Intent
import com.example.tgmusicai.data.local.entity.Song

/**
 * Builds and launches the system share sheet for a [Song] or a playlist. A song with a
 * [Song.youtubeId] shares a real `music.youtube.com` link alongside its title/artist; a
 * purely-local song (no YouTube origin) has no public URL to share, so it falls back to plain
 * "Title - Artist" text. Playlists never have a public URL of their own (there is no backend --
 * see [com.example.tgmusicai.data.local.entity.Playlist]), so they always share as a formatted
 * track list.
 */
object ShareUtils {
    private const val MAX_PLAYLIST_TRACKS_LISTED = 50

    fun shareSong(context: Context, song: Song) {
        val text = buildString {
            append(song.title)
            append(" - ")
            append(song.artist)
            val youtubeId = song.youtubeId
            if (!youtubeId.isNullOrBlank()) {
                append("\nhttps://music.youtube.com/watch?v=")
                append(youtubeId)
            }
        }
        launchShareSheet(context, subject = song.title, text = text)
    }

    fun sharePlaylist(context: Context, playlistName: String, songs: List<Song>) {
        val text = buildString {
            append(playlistName)
            append(" (")
            append(songs.size)
            append(if (songs.size == 1) " song)" else " songs)")
            append("\n\n")
            songs.take(MAX_PLAYLIST_TRACKS_LISTED).forEachIndexed { index, song ->
                append(index + 1)
                append(". ")
                append(song.title)
                append(" - ")
                append(song.artist)
                append("\n")
            }
            val remaining = songs.size - MAX_PLAYLIST_TRACKS_LISTED
            if (remaining > 0) {
                append("...and ")
                append(remaining)
                append(" more")
            }
        }
        launchShareSheet(context, subject = playlistName, text = text.trim())
    }

    private fun launchShareSheet(context: Context, subject: String, text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(sendIntent, null)
        if (context !is android.app.Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
