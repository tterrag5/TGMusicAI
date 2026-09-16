package com.example.tgmusicai.ui.util

import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Utility functions for formatting time durations and timestamps in the UI.
 */
object FormatUtils {

    /**
     * Formats a duration in milliseconds into a mm:ss string (or hh:mm:ss if >= 1 hour).
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "0:00"
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats a timestamp in milliseconds into a readable date string.
     */
    fun formatTimestamp(timestampMs: Long): String {
        if (timestampMs <= 0) return "Never"
        val date = Date(timestampMs)
        val formatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
        return formatter.format(date)
    }

    /**
     * Formats epoch timestamp in milliseconds to 12-hour clock time (e.g., "07:30 AM").
     */
    fun formatTimeOfDay(timestampMs: Long): String {
        val date = Date(timestampMs)
        val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return formatter.format(date)
    }

    /**
     * Formats a byte count into a human-readable string (e.g. "128 MB", "1.4 GB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.0f MB", mb)
            else -> String.format(Locale.getDefault(), "%.0f KB", kb)
        }
    }

    /**
     * Sanitizes track or file names by stripping illegal OS path characters (/ \ : * ? " < > |)
     * and control characters, trimming leading/trailing dots and whitespace, and bounding max length.
     */
    fun sanitizeFileName(input: String?): String {
        if (input.isNullOrBlank()) return "unnamed_track"
        val sanitized = input.replace("[\\\\/:*?\"<>|\\x00-\\x1F]".toRegex(), "_")
            .trim('.', ' ')
        val fallback = if (sanitized.isBlank()) "unnamed_track" else sanitized
        return if (fallback.length > 200) fallback.substring(0, 200) else fallback
    }

    /**
     * Appends the file's last-modified time as a query param to a local `file:` artwork URI, so
     * Coil's cache key changes when the file is overwritten in place. [CoverArtScraper] always
     * saves to the same fixed path per song (`Covers/{songId}.jpg`), so re-scraping never changes
     * the URI string itself -- without this, an `AsyncImage` pointed at that unchanged string can
     * keep showing whatever bitmap it cached from before the rescrape. Every screen that displays
     * `Song.artworkUri` should route it through this function. No-op for non-local (remote/null) URIs.
     */
    fun cacheBustedArtworkUri(artworkUri: String?): String? {
        if (artworkUri.isNullOrBlank() || !artworkUri.startsWith("file:")) return artworkUri
        return try {
            val path = Uri.parse(artworkUri).path ?: return artworkUri
            val lastModified = File(path).lastModified()
            if (lastModified > 0L) "$artworkUri?lm=$lastModified" else artworkUri
        } catch (e: Exception) {
            artworkUri
        }
    }
}
