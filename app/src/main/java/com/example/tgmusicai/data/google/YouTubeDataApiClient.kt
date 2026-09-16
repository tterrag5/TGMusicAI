package com.example.tgmusicai.data.google

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A playlist owned by the signed-in Google account, as returned by the YouTube Data API v3.
 */
data class GoogleYouTubePlaylist(
    val playlistId: String,
    val title: String,
    val itemCount: Int,
    val thumbnailUrl: String?
)

/**
 * A single video entry within a YouTube playlist, as returned by the YouTube Data API v3.
 */
data class GoogleYouTubePlaylistVideo(
    val videoId: String,
    val title: String,
    val channelTitle: String
)

/** The special playlist ID representing "Liked Videos" for the authenticated account. */
const val LIKED_VIDEOS_PLAYLIST_ID = "LL"

/**
 * Thin REST client for the parts of the YouTube Data API v3 needed to import a user's playlists
 * (including Liked Videos) — no official Google API client library dependency, consistent with
 * the rest of this app's plain-OkHttp style. All calls require an OAuth access token carrying at
 * least the `https://www.googleapis.com/auth/youtube.readonly` scope (see [GoogleAuthManager]).
 *
 * IMPORTANT -- do not confuse this with `data.youtube.YouTubeExtractor`. This class is the
 * OFFICIAL, OAuth-authenticated Google API path: it requires the user to sign into their real
 * Google account and is used ONLY for reading/importing that account's own playlists (via
 * [YouTubePlaylistSyncManager]). It never resolves a playable audio stream URL. Actual playback
 * and downloading of audio goes through the completely separate, unofficial, no-login
 * `data.youtube` package (`YouTubeExtractor`, `CloudDownloadManager`), which scrapes stream URLs
 * without using this API at all. The two paths never call each other.
 */
class YouTubeDataApiClient {

    private val TAG = "YouTubeDataApiClient"
    private val BASE_URL = "https://www.googleapis.com/youtube/v3"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Builds a GET [Request] to [url] carrying [accessToken] as a Bearer `Authorization` header.
     * Every YouTube Data API v3 call in this class goes through this helper.
     */
    private fun authorizedRequest(url: String, accessToken: String): Request {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
    }

    /**
     * Fetches every playlist owned by the signed-in account (paginated internally).
     */
    suspend fun fetchMyPlaylists(accessToken: String): List<GoogleYouTubePlaylist> = withContext(Dispatchers.IO) {
        val results = mutableListOf<GoogleYouTubePlaylist>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$BASE_URL/playlists?part=snippet,contentDetails&mine=true&maxResults=50")
                if (pageToken != null) append("&pageToken=$pageToken")
            }
            val request = authorizedRequest(url, accessToken)
            okHttpClient.newCall(request).execute().use { response ->
                val code = response.code
                Log.d(TAG, "fetchMyPlaylists page request returned HTTP $code")
                if (!response.isSuccessful) {
                    Log.e(TAG, "fetchMyPlaylists failed: HTTP $code ${response.body?.string()}")
                    return@withContext results
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val items = json.optJSONArray("items") ?: org.json.JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val snippet = item.optJSONObject("snippet") ?: continue
                    val contentDetails = item.optJSONObject("contentDetails")
                    val thumbnails = snippet.optJSONObject("thumbnails")
                    val thumbUrl = thumbnails?.optJSONObject("medium")?.optString("url")
                        ?: thumbnails?.optJSONObject("default")?.optString("url")
                    results.add(
                        GoogleYouTubePlaylist(
                            playlistId = item.optString("id"),
                            title = snippet.optString("title", "Untitled Playlist"),
                            itemCount = contentDetails?.optInt("itemCount", 0) ?: 0,
                            thumbnailUrl = thumbUrl
                        )
                    )
                }
                pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            }
        } while (pageToken != null)
        results
    }

    /**
     * Resolves the playlist ID for the account's Liked Videos. Google deprecated exposing
     * `relatedPlaylists.likes` for arbitrary channels in 2016, but the literal special ID `LL`
     * still resolves correctly for the *authenticated* user's own liked videos, so that's used
     * as the reliable default; `channels.list` is tried first in case it still returns a real ID.
     */
    suspend fun resolveLikedVideosPlaylistId(accessToken: String): String = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/channels?part=contentDetails&mine=true"
            val request = authorizedRequest(url, accessToken)
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val items = json.optJSONArray("items")
                    val likes = items?.optJSONObject(0)
                        ?.optJSONObject("contentDetails")
                        ?.optJSONObject("relatedPlaylists")
                        ?.optString("likes")
                    if (!likes.isNullOrBlank()) return@withContext likes
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveLikedVideosPlaylistId failed, falling back to '$LIKED_VIDEOS_PLAYLIST_ID': ${e.message}", e)
        }
        LIKED_VIDEOS_PLAYLIST_ID
    }

    /**
     * Fetches every video in [playlistId] (paginated internally). Entries whose video was deleted
     * or made private (no longer resolvable) are skipped.
     */
    suspend fun fetchPlaylistVideos(accessToken: String, playlistId: String): List<GoogleYouTubePlaylistVideo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<GoogleYouTubePlaylistVideo>()
        var pageToken: String? = null
        val encodedPlaylistId = URLEncoder.encode(playlistId, "UTF-8")
        do {
            val url = buildString {
                append("$BASE_URL/playlistItems?part=snippet,contentDetails&playlistId=$encodedPlaylistId&maxResults=50")
                if (pageToken != null) append("&pageToken=$pageToken")
            }
            val request = authorizedRequest(url, accessToken)
            okHttpClient.newCall(request).execute().use { response ->
                val code = response.code
                Log.d(TAG, "fetchPlaylistVideos($playlistId) page request returned HTTP $code")
                if (!response.isSuccessful) {
                    Log.e(TAG, "fetchPlaylistVideos failed for $playlistId: HTTP $code ${response.body?.string()}")
                    return@withContext results
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val items = json.optJSONArray("items") ?: org.json.JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val snippet = item.optJSONObject("snippet") ?: continue
                    val videoId = item.optJSONObject("contentDetails")?.optString("videoId")
                        ?: snippet.optJSONObject("resourceId")?.optString("videoId")
                    val title = snippet.optString("title", "")
                    // Deleted/private videos surface with these placeholder titles and no real content.
                    if (videoId.isNullOrBlank() || title == "Deleted video" || title == "Private video") continue
                    results.add(
                        GoogleYouTubePlaylistVideo(
                            videoId = videoId,
                            title = title,
                            channelTitle = snippet.optString("videoOwnerChannelTitle", snippet.optString("channelTitle", "Unknown Artist"))
                        )
                    )
                }
                pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            }
        } while (pageToken != null)
        results
    }

    /**
     * Fetches real durations (in seconds) for up to 50 video IDs per underlying API call
     * (`videos.list` is batched automatically for larger lists).
     */
    suspend fun fetchVideoDurations(accessToken: String, videoIds: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        val durations = mutableMapOf<String, Long>()
        for (batch in videoIds.chunked(50)) {
            if (batch.isEmpty()) continue
            val encodedIds = URLEncoder.encode(batch.joinToString(","), "UTF-8")
            val url = "$BASE_URL/videos?part=contentDetails&id=$encodedIds"
            val request = authorizedRequest(url, accessToken)
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "fetchVideoDurations batch failed: HTTP ${response.code}")
                        return@use
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val items = json.optJSONArray("items") ?: org.json.JSONArray()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        val iso8601Duration = item.optJSONObject("contentDetails")?.optString("duration")
                        if (id.isNotBlank() && !iso8601Duration.isNullOrBlank()) {
                            durations[id] = parseIso8601DurationToSeconds(iso8601Duration)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchVideoDurations batch failed: ${e.message}", e)
            }
        }
        durations
    }

    companion object {
        private val ISO8601_DURATION_REGEX =
            "PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?".toRegex()

        /**
         * Parses a YouTube API ISO 8601 duration string (e.g. "PT4M13S") into total seconds.
         * Returns 0 for a blank or unparseable input rather than throwing.
         */
        fun parseIso8601DurationToSeconds(duration: String): Long {
            val match = ISO8601_DURATION_REGEX.find(duration) ?: return 0L
            val (hours, minutes, seconds) = match.destructured
            return (hours.toLongOrNull() ?: 0L) * 3600L +
                (minutes.toLongOrNull() ?: 0L) * 60L +
                (seconds.toLongOrNull() ?: 0L)
        }
    }
}
