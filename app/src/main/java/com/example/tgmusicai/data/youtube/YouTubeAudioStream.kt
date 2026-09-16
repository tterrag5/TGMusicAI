package com.example.tgmusicai.data.youtube

/**
 * Represents an extracted direct audio stream URL and its metadata.
 *
 * @param url Direct HTTPS URL to stream or download audio content
 * @param format Audio format/extension (e.g. "m4a", "webm", "opus", "aac")
 * @param bitrate Audio stream quality bitrate in bits per second
 */
data class YouTubeAudioStream(
    val url: String,
    val format: String,
    val bitrate: Int
)
