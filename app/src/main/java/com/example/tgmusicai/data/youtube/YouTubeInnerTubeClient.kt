package com.example.tgmusicai.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import java.util.concurrent.TimeUnit

/** A YouTube Music playlist owned by (or saved to the library by) the signed-in account. */
data class GoogleYouTubePlaylist(
    val playlistId: String,
    val title: String,
    val itemCount: Int,
    val thumbnailUrl: String?
)

/** A single track within a YouTube Music playlist. */
data class GoogleYouTubePlaylistVideo(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val durationSeconds: Long = 0L
)

/** Sentinel playlist ID for the signed-in account's YouTube Music "Liked Music" auto-playlist. */
const val LIKED_MUSIC_PLAYLIST_ID = "LM"

/**
 * Talks to YouTube Music's own internal "InnerTube" API -- the same one music.youtube.com's web
 * client itself uses -- instead of the official, quota-capped, OAuth-gated YouTube Data API v3.
 * Replaces `YouTubeDataApiClient`.
 *
 * Authentication is a real, signed-in music.youtube.com web session captured by
 * [InnerTubeCookieManager] (via [com.example.tgmusicai.ui.components.YouTubeLoginDialog]) rather
 * than a Google Cloud Console OAuth client: no client ID, no API key registration, no per-project
 * quota, no test-account whitelist. Every authenticated call carries the session's `Cookie`
 * header plus a `SAPISIDHASH` `Authorization` header computed fresh per request.
 *
 * InnerTube's response JSON is not a documented, versioned API -- it's the same nested renderer
 * tree music.youtube.com's own web client renders from, and Google reshuffles it without notice.
 * Rather than hardcoding exact nested paths (which breaks on every such reshuffle), the parsing
 * helpers below recursively scan the tree for known renderer/field keys, which tolerates
 * additions/reorderings as long as the key names themselves stay put -- the same tolerant
 * approach reverse-engineered YouTube Music clients (e.g. ytmusicapi) use.
 *
 * For a playlist URL/ID pasted without ever signing in, [fetchPublicPlaylistTracks] falls back to
 * the same unauthenticated NewPipeExtractor path [YouTubeExtractor] uses for search -- public/
 * unlisted playlists only, since InnerTube requires a real session to read a private one.
 */
class YouTubeInnerTubeClient(private val cookieManager: InnerTubeCookieManager) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Every playlist owned by or saved to the signed-in account's YouTube Music library.
     * Empty (not an error) if the session is missing/expired.
     */
    suspend fun fetchMyPlaylists(): List<GoogleYouTubePlaylist> {
        val root = browse("FEmusic_liked_playlists") ?: return emptyList()
        val tiles = mutableListOf<JSONObject>()
        collectRenderers(root, "musicTwoRowItemRenderer", tiles)
        return tiles.mapNotNull { tile ->
            val browseId = tile.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
                ?.takeIf { it.isNotBlank() }
                ?: findFirstString(tile, "browseId")
                ?: return@mapNotNull null
            // Playlist tiles' browseIds start with "VL"; this page also mixes in a "New playlist"
            // creation tile (no browseId, already filtered above) and can include podcast/album
            // tiles under other prefixes -- skip anything that isn't actually a playlist.
            if (!browseId.startsWith("VL")) return@mapNotNull null
            val title = runsText(tile.optJSONObject("title"))
            if (title.isBlank()) return@mapNotNull null
            val subtitle = runsText(tile.optJSONObject("subtitle"))
            val itemCount = Regex("(\\d+)").find(subtitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            GoogleYouTubePlaylist(
                playlistId = browseId.removePrefix("VL"),
                title = title,
                itemCount = itemCount,
                thumbnailUrl = findThumbnailUrl(tile)
            )
        }
    }

    /**
     * Every track in [playlistId] (accepts either a bare ID or one already prefixed with the
     * `VL` browse-id prefix), including private playlists, paginated internally via InnerTube
     * continuation tokens. Empty (not an error) if the session is missing/expired or the playlist
     * has no tracks.
     */
    suspend fun fetchPlaylistTracks(playlistId: String): List<GoogleYouTubePlaylistVideo> {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val results = mutableListOf<GoogleYouTubePlaylistVideo>()
        val seenVideoIds = mutableSetOf<String>()

        var root = browse(browseId)
        var pages = 0
        while (root != null && pages < MAX_CONTINUATION_PAGES) {
            pages++
            val rows = mutableListOf<JSONObject>()
            collectRenderers(root, "musicResponsiveListItemRenderer", rows)
            for (row in rows) {
                val videoId = row.optJSONObject("playlistItemData")?.optString("videoId")
                    ?.takeIf { it.isNotBlank() }
                    ?: findFirstString(row, "videoId")
                    ?: continue
                if (!seenVideoIds.add(videoId)) continue
                val title = flexColumnText(row, 0)
                if (title.isBlank()) continue
                val artist = flexColumnText(row, 1)
                    .split("•")
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?: "Unknown Artist"
                results.add(GoogleYouTubePlaylistVideo(videoId, title, artist, extractDurationSeconds(row)))
            }
            val continuation = findContinuationToken(root)
            root = continuation?.let { browse(browseId, it) }
        }
        return results
    }

    /** The signed-in account's YouTube Music "Liked Music" tracks. */
    suspend fun fetchLikedSongs(): List<GoogleYouTubePlaylistVideo> = fetchPlaylistTracks(LIKED_MUSIC_PLAYLIST_ID)

    /**
     * Best-effort, no-login extraction of a *public* YouTube/YouTube Music playlist given its
     * URL (either host) or bare playlist ID, for users who don't want to sign in at all. Reuses
     * the same NewPipeExtractor path [YouTubeExtractor] already relies on for search rather than
     * a second hand-rolled unauthenticated InnerTube parser. Only the first page of results is
     * returned -- adequate for the vast majority of playlists; very large ones may be truncated.
     */
    suspend fun fetchPublicPlaylistTracks(urlOrId: String): List<GoogleYouTubePlaylistVideo> = withContext(Dispatchers.IO) {
        val listId = extractPlaylistId(urlOrId) ?: return@withContext emptyList()
        try {
            val extractor = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/playlist?list=$listId")
            extractor.fetchPage()
            extractor.initialPage.items.mapNotNull { item ->
                val videoId = extractVideoId(item.url) ?: return@mapNotNull null
                GoogleYouTubePlaylistVideo(
                    videoId = videoId,
                    title = item.name ?: "",
                    channelTitle = item.uploaderName ?: "Unknown Artist",
                    durationSeconds = item.duration.coerceAtLeast(0L)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPublicPlaylistTracks($urlOrId) failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Issues one authenticated `POST /youtubei/v1/browse` call. Returns null (and logs) if there
     * is no signed-in session, or on any request failure/non-2xx response -- callers treat that
     * uniformly as "no data available" rather than distinguishing the reason.
     */
    private suspend fun browse(browseId: String, continuation: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        val cookieHeader = cookieManager.getCookieHeader() ?: return@withContext null
        val authHeader = cookieManager.getAuthorizationHeader() ?: return@withContext null

        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("browseId", browseId)
            if (!continuation.isNullOrBlank()) put("continuation", continuation)
        }

        val request = Request.Builder()
            .url("$BROWSE_URL?key=$INNERTUBE_API_KEY&prettyPrint=false")
            .header("Cookie", cookieHeader)
            .header("Authorization", authHeader)
            .header("X-Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Referer", "$YOUTUBE_MUSIC_ORIGIN/")
            .header("User-Agent", NewPipeOkHttpDownloader.REALISTIC_USER_AGENT)
            .header("X-Goog-AuthUser", "0")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "browse($browseId) failed: HTTP ${response.code}")
                    return@withContext null
                }
                JSONObject(response.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "browse($browseId) failed: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "YouTubeInnerTubeClient"
        private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse"
        private const val CLIENT_VERSION = "1.20240901.01.00"
        private const val MAX_CONTINUATION_PAGES = 20

        // Public, non-secret InnerTube API key that music.youtube.com's own web client embeds in
        // every page it serves. It identifies the client *type*, not the user -- the signed-in
        // user's own data is gated entirely by the Cookie + SAPISIDHASH Authorization header sent
        // alongside it, not by this key.
        private const val INNERTUBE_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

        private fun extractPlaylistId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return null
            return Regex("[?&]list=([^&]+)").find(trimmed)?.groupValues?.get(1) ?: trimmed
        }

        private fun extractVideoId(watchUrl: String?): String? {
            if (watchUrl.isNullOrBlank()) return null
            Regex("[?&]v=([^&]+)").find(watchUrl)?.groupValues?.get(1)?.let { return it }
            Regex("youtu\\.be/([^?&]+)").find(watchUrl)?.groupValues?.get(1)?.let { return it }
            return null
        }
    }
}

// --- InnerTube renderer-tree parsing helpers -------------------------------------------------
// InnerTube's response is an unversioned, deeply-nested tree of "renderer" objects. These walk
// the whole tree looking for known keys rather than hardcoding exact paths, so minor reshuffles
// in Google's own markup don't silently break every field at once.

/** Collects every [JSONObject] value found anywhere in [node] under a property named [key]. */
private fun collectRenderers(node: Any?, key: String, out: MutableList<JSONObject>) {
    when (node) {
        is JSONObject -> {
            (node.opt(key) as? JSONObject)?.let { out.add(it) }
            val keys = node.keys()
            while (keys.hasNext()) collectRenderers(node.opt(keys.next()), key, out)
        }
        is JSONArray -> for (i in 0 until node.length()) collectRenderers(node.opt(i), key, out)
        else -> Unit
    }
}

/** Depth-first search for the first String value found anywhere under a property named [key]. */
private fun findFirstString(node: Any?, key: String): String? {
    when (node) {
        is JSONObject -> {
            (node.opt(key) as? String)?.let { return it }
            val keys = node.keys()
            while (keys.hasNext()) {
                findFirstString(node.opt(keys.next()), key)?.let { return it }
            }
        }
        is JSONArray -> for (i in 0 until node.length()) {
            findFirstString(node.opt(i), key)?.let { return it }
        }
        else -> Unit
    }
    return null
}

/** Depth-first search for the highest-resolution thumbnail URL anywhere under [node]. */
private fun findThumbnailUrl(node: Any?): String? {
    when (node) {
        is JSONObject -> {
            val thumbs = node.optJSONArray("thumbnails")
            if (thumbs != null && thumbs.length() > 0) {
                thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val keys = node.keys()
            while (keys.hasNext()) {
                findThumbnailUrl(node.opt(keys.next()))?.let { return it }
            }
        }
        is JSONArray -> for (i in 0 until node.length()) {
            findThumbnailUrl(node.opt(i))?.let { return it }
        }
        else -> Unit
    }
    return null
}

/** Concatenates every run's `text` inside a `{ runs: [...] }` holder, e.g. a title or subtitle field. */
private fun runsText(runsHolder: JSONObject?): String {
    val runs = runsHolder?.optJSONArray("runs") ?: return ""
    return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }
}

/** Text of the Nth `flexColumns` entry of a `musicResponsiveListItemRenderer` row. */
private fun flexColumnText(item: JSONObject, index: Int): String {
    val renderer = item.optJSONArray("flexColumns")
        ?.optJSONObject(index)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        ?: return ""
    return runsText(renderer.optJSONObject("text"))
}

private val DURATION_TEXT_REGEX = Regex("^\\d{1,2}(:\\d{2}){1,2}$")

/**
 * Finds a track's duration by scanning every run of text in [item] for one that looks like a
 * clock duration (e.g. "3:45"), taking the last match -- InnerTube's row layout conventionally
 * places the duration in the last text column, after title/artist/album.
 */
private fun extractDurationSeconds(item: JSONObject): Long {
    val texts = mutableListOf<String>()
    collectAllRunTexts(item, texts)
    val durationText = texts.lastOrNull { DURATION_TEXT_REGEX.matches(it.trim()) } ?: return 0L
    val parts = durationText.trim().split(":").mapNotNull { it.toLongOrNull() }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> 0L
    }
}

private fun collectAllRunTexts(node: Any?, out: MutableList<String>) {
    when (node) {
        is JSONObject -> {
            node.optJSONArray("runs")?.let { runs ->
                for (i in 0 until runs.length()) {
                    runs.optJSONObject(i)?.optString("text")?.let { out.add(it) }
                }
            }
            val keys = node.keys()
            while (keys.hasNext()) collectAllRunTexts(node.opt(keys.next()), out)
        }
        is JSONArray -> for (i in 0 until node.length()) collectAllRunTexts(node.opt(i), out)
        else -> Unit
    }
}

/** Finds the next page's continuation token, if [root]'s renderer tree includes one. */
private fun findContinuationToken(root: JSONObject): String? {
    val holders = mutableListOf<JSONObject>()
    collectRenderers(root, "nextContinuationData", holders)
    return holders.firstOrNull()?.optString("continuation")?.takeIf { it.isNotBlank() }
}
