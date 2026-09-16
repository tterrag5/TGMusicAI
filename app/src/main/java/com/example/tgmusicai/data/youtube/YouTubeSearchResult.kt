package com.example.tgmusicai.data.youtube

/**
 * Data class representing a YouTube track or video search result.
 *
 * @param videoId Unique YouTube video ID (e.g. "dQw4w9WgXcQ")
 * @param title Track or video title
 * @param uploader Channel or artist name
 * @param durationSeconds Duration of the audio track in seconds
 * @param thumbnailUri URL pointing to the video thumbnail image
 */
data class YouTubeSearchResult(
    val videoId: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUri: String
)
