package com.example.tgmusicai.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A link to an artist page, as it appears in a list of results or related artists. */
data class YouTubeArtistRef(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String?
)

/** A link to an album or single, as it appears on an artist's page or in search results. */
data class YouTubeAlbumRef(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?
)

/** An artist's page: who they are, what to play first, and what else they have released. */
data class YouTubeArtistPage(
    val browseId: String,
    val name: String,
    val thumbnailUrl: String?,
    val description: String?,
    val topTracks: List<YouTubeSearchResult>,
    val albums: List<YouTubeAlbumRef>,
    val singles: List<YouTubeAlbumRef>,
    val relatedArtists: List<YouTubeArtistRef>
)

/** An album's page: its cover, who made it, and its track listing. */
data class YouTubeAlbumPage(
    val browseId: String,
    val title: String,
    val artist: String,
    val year: String?,
    val thumbnailUrl: String?,
    val tracks: List<YouTubeSearchResult>
)

/** One of YouTube Music's mood or genre categories, e.g. "Focus" or "Workout". */
data class YouTubeMoodCategory(
    val title: String,
    val browseId: String,
    val params: String?
)

/**
 * Browses YouTube Music's public catalogue: artist pages, album pages, mood and genre categories,
 * and charts.
 *
 * Separate from [YouTubeInnerTubeClient] because of one structural difference: that client reads
 * the *signed-in account's* own library and therefore cannot work without a session, whereas
 * everything here is public catalogue data and must keep working for a user who never signs in.
 * So requests are sent unauthenticated, and a session, when there is one, is attached only to
 * personalise the result.
 *
 * Both share the renderer-tree parsing helpers in [YouTubeInnerTubeClient]'s file. InnerTube's JSON
 * is an undocumented, unversioned tree that Google reshuffles without notice, so those helpers scan
 * for known key names rather than walking fixed paths -- and keeping one copy means one place to
 * fix when a reshuffle does break something.
 *
 * Every method returns null or an empty list on failure rather than throwing. This is a
 * best-effort view of someone else's internal API; discovery degrading to "nothing found" is
 * acceptable, discovery taking the screen down with it is not.
 */
class YouTubeMusicBrowser(
    private val cookieManager: InnerTubeCookieManager? = null
) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Loads the artist page for [browseId] -- the `UC...`/`MPAD...` id that search results and
     * album pages link to. Returns null if the page could not be fetched or parsed.
     */
    suspend fun fetchArtist(browseId: String): YouTubeArtistPage? {
        val root = browse(browseId) ?: return null
        return try {
            val name = findHeaderTitle(root) ?: return null
            val shelves = sectionShelves(root)

            YouTubeArtistPage(
                browseId = browseId,
                name = name,
                thumbnailUrl = findThumbnailUrl(root),
                description = findFirstString(root, "description")?.takeIf { it.isNotBlank() },
                topTracks = shelves.firstOrNull { it.isTrackShelf }?.tracks.orEmpty(),
                // "Albums" and "Singles" are separate shelves on the page, distinguished only by
                // their heading, so they are matched by title rather than by position.
                albums = shelves.firstOrNull { it.title.contains("album", ignoreCase = true) }?.items.orEmpty(),
                singles = shelves.firstOrNull { it.title.contains("single", ignoreCase = true) }?.items.orEmpty(),
                relatedArtists = shelves
                    .firstOrNull { it.title.contains("fans might also like", ignoreCase = true) || it.title.contains("similar", ignoreCase = true) }
                    ?.items
                    ?.map { YouTubeArtistRef(it.browseId, it.title, it.thumbnailUrl) }
                    .orEmpty()
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse the artist page for $browseId", e)
            null
        }
    }

    /** Loads the album page for [browseId] (an `MPRE...` id). Returns null on failure. */
    suspend fun fetchAlbum(browseId: String): YouTubeAlbumPage? {
        val root = browse(browseId) ?: return null
        return try {
            val title = findHeaderTitle(root) ?: return null
            val subtitleTexts = mutableListOf<String>()
            collectAllRunTexts(root, subtitleTexts)
            YouTubeAlbumPage(
                browseId = browseId,
                title = title,
                artist = subtitleTexts.firstOrNull { it.isNotBlank() && it != title && it != "•" } ?: "Unknown Artist",
                year = subtitleTexts.firstOrNull { Regex("^(19|20)\\d{2}$").matches(it.trim()) },
                thumbnailUrl = findThumbnailUrl(root),
                tracks = parseTrackRows(root)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse the album page for $browseId", e)
            null
        }
    }

    /**
     * The mood and genre categories YouTube Music itself offers ("Focus", "Workout", "Chill" and
     * so on), rather than a hardcoded list.
     *
     * Hardcoding them would be simpler but wrong in two ways: the set changes over time, and each
     * one is addressed by an opaque `params` token that cannot be guessed and has to come from
     * this page anyway.
     */
    suspend fun fetchMoodCategories(): List<YouTubeMoodCategory> {
        val root = browse(BROWSE_ID_MOODS) ?: return emptyList()
        return try {
            val chips = mutableListOf<JSONObject>()
            collectRenderers(root, "musicNavigationButtonRenderer", chips)
            chips.mapNotNull { chip ->
                val title = runsText(chip.optJSONObject("buttonText")).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val endpoint = chip.optJSONObject("clickCommand")?.optJSONObject("browseEndpoint")
                    ?: return@mapNotNull null
                val id = endpoint.optString("browseId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                YouTubeMoodCategory(
                    title = title,
                    browseId = id,
                    params = endpoint.optJSONObject("params")?.toString()
                        ?: endpoint.optString("params").takeIf { it.isNotBlank() }
                )
            }.distinctBy { it.title }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse the mood and genre categories", e)
            emptyList()
        }
    }

    /** The playlists inside one mood or genre category. */
    suspend fun fetchMoodPlaylists(category: YouTubeMoodCategory): List<YouTubeAlbumRef> {
        val root = browse(category.browseId, params = category.params) ?: return emptyList()
        return try {
            parseTileItems(root)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse the '${category.title}' category", e)
            emptyList()
        }
    }

    /**
     * Artists matching [query], so a search can lead to an artist's page rather than only to
     * individual tracks.
     *
     * The existing track search goes through NewPipeExtractor, which returns videos and has no
     * notion of a YouTube Music artist page. This is a separate, narrower call for that one thing.
     */
    suspend fun searchArtists(query: String): List<YouTubeArtistRef> {
        val root = search(query, SEARCH_PARAMS_ARTISTS) ?: return emptyList()
        return try {
            parseSearchRows(root).mapNotNull { (browseId, title, _) ->
                // Artist browse ids all start with UC; anything else in this shelf is a different
                // kind of result that slipped through the filter.
                if (!browseId.startsWith("UC")) null else YouTubeArtistRef(browseId, title, null)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse artist search results", e)
            emptyList()
        }
    }

    /** Albums matching [query]. */
    suspend fun searchAlbums(query: String): List<YouTubeAlbumRef> {
        val root = search(query, SEARCH_PARAMS_ALBUMS) ?: return emptyList()
        return try {
            parseSearchRows(root).mapNotNull { (browseId, title, subtitle) ->
                if (!browseId.startsWith("MPRE")) null else YouTubeAlbumRef(browseId, title, subtitle, null)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse album search results", e)
            emptyList()
        }
    }

    /** Pulls (browseId, title, subtitle) out of each result row in a search response. */
    private fun parseSearchRows(root: JSONObject): List<Triple<String, String, String>> {
        val rows = mutableListOf<JSONObject>()
        collectRenderers(root, "musicResponsiveListItemRenderer", rows)
        val seen = mutableSetOf<String>()
        return rows.mapNotNull { row ->
            val browseId = row.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
                ?.takeIf { it.isNotBlank() }
                ?: findFirstString(row, "browseId")
                ?: return@mapNotNull null
            if (!seen.add(browseId)) return@mapNotNull null
            val title = flexColumnText(row, 0).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(browseId, title, flexColumnText(row, 1))
        }
    }

    /** Issues one `POST /youtubei/v1/search`, filtered to a single result type by [params]. */
    private suspend fun search(query: String, params: String): JSONObject? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", query)
            put("params", params)
        }

        val request = Request.Builder()
            .url("$SEARCH_URL?key=$INNERTUBE_API_KEY&prettyPrint=false")
            .header("X-Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Referer", "$YOUTUBE_MUSIC_ORIGIN/")
            .header("User-Agent", NewPipeOkHttpDownloader.REALISTIC_USER_AGENT)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "search failed: HTTP ${response.code}")
                    return@withContext null
                }
                JSONObject(response.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "search failed: ${e.message}")
            null
        }
    }

    /** The current top-songs chart. Empty if charts are unavailable in the user's region. */
    suspend fun fetchTopChart(): List<YouTubeSearchResult> {
        val root = browse(BROWSE_ID_CHARTS) ?: return emptyList()
        return try {
            parseTrackRows(root)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse the charts page", e)
            emptyList()
        }
    }

    // --- Parsing ------------------------------------------------------------------------------

    /** One horizontal row on a browse page, which holds either tracks or tiles, never both. */
    private data class Shelf(
        val title: String,
        val tracks: List<YouTubeSearchResult>,
        val items: List<YouTubeAlbumRef>
    ) {
        val isTrackShelf: Boolean get() = tracks.isNotEmpty()
    }

    private fun sectionShelves(root: JSONObject): List<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val carousels = mutableListOf<JSONObject>()
        collectRenderers(root, "musicCarouselShelfRenderer", carousels)
        collectRenderers(root, "musicShelfRenderer", carousels)

        for (shelf in carousels) {
            val title = findHeaderTitle(shelf).orEmpty()
            shelves.add(
                Shelf(
                    title = title,
                    tracks = parseTrackRows(shelf),
                    items = parseTileItems(shelf)
                )
            )
        }
        return shelves
    }

    /** Reads every `musicResponsiveListItemRenderer` row under [node] as a playable track. */
    private fun parseTrackRows(node: JSONObject): List<YouTubeSearchResult> {
        val rows = mutableListOf<JSONObject>()
        collectRenderers(node, "musicResponsiveListItemRenderer", rows)
        val seen = mutableSetOf<String>()
        return rows.mapNotNull { row ->
            val videoId = row.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
                ?: findFirstString(row, "videoId")
                ?: return@mapNotNull null
            if (!seen.add(videoId)) return@mapNotNull null
            val title = flexColumnText(row, 0).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = flexColumnText(row, 1)
                .split("•")
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: "Unknown Artist"
            YouTubeSearchResult(
                videoId = videoId,
                title = title,
                uploader = artist,
                durationSeconds = extractDurationSeconds(row),
                thumbnailUri = findThumbnailUrl(row).orEmpty()
            )
        }
    }

    /** Reads every `musicTwoRowItemRenderer` tile under [node] as an album, single or playlist. */
    private fun parseTileItems(node: JSONObject): List<YouTubeAlbumRef> {
        val tiles = mutableListOf<JSONObject>()
        collectRenderers(node, "musicTwoRowItemRenderer", tiles)
        val seen = mutableSetOf<String>()
        return tiles.mapNotNull { tile ->
            val browseId = tile.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (!seen.add(browseId)) return@mapNotNull null
            val title = runsText(tile.optJSONObject("title")).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            YouTubeAlbumRef(
                browseId = browseId,
                title = title,
                subtitle = runsText(tile.optJSONObject("subtitle")),
                thumbnailUrl = findThumbnailUrl(tile)
            )
        }
    }

    /**
     * The heading of a page or shelf.
     *
     * InnerTube uses several different header renderers depending on the page type, so this tries
     * each rather than assuming one. Falls back to any `title` holder found, which is right often
     * enough to be worth having and never worse than showing nothing.
     */
    private fun findHeaderTitle(node: JSONObject): String? {
        for (key in HEADER_RENDERER_KEYS) {
            val headers = mutableListOf<JSONObject>()
            collectRenderers(node, key, headers)
            headers.firstOrNull()?.let { header ->
                runsText(header.optJSONObject("title")).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        val titles = mutableListOf<JSONObject>()
        collectRenderers(node, "title", titles)
        return titles.firstNotNullOfOrNull { runsText(it).takeIf { text -> text.isNotBlank() } }
    }

    /**
     * Issues one `POST /youtubei/v1/browse`.
     *
     * Unlike [YouTubeInnerTubeClient.browse], a missing session is not an error: the catalogue
     * pages this reads are public, and a user who never signs in still gets artist pages, albums
     * and charts. Session headers are attached only when there is a session, which personalises
     * the response rather than enabling it.
     */
    private suspend fun browse(
        browseId: String,
        continuation: String? = null,
        params: String? = null
    ): JSONObject? = withContext(Dispatchers.IO) {
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
            if (!params.isNullOrBlank()) put("params", params)
        }

        val builder = Request.Builder()
            .url("$BROWSE_URL?key=$INNERTUBE_API_KEY&prettyPrint=false")
            .header("X-Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Referer", "$YOUTUBE_MUSIC_ORIGIN/")
            .header("User-Agent", NewPipeOkHttpDownloader.REALISTIC_USER_AGENT)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))

        val cookieHeader = cookieManager?.getCookieHeader()
        val authHeader = cookieManager?.getAuthorizationHeader()
        if (cookieHeader != null && authHeader != null) {
            builder.header("Cookie", cookieHeader)
                .header("Authorization", authHeader)
                .header("X-Goog-AuthUser", "0")
        }

        try {
            okHttpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "browse($browseId) failed: HTTP ${response.code}")
                    return@withContext null
                }
                JSONObject(response.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "browse($browseId) failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "YouTubeMusicBrowser"
        private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse"
        private const val CLIENT_VERSION = "1.20240901.01.00"

        /** Same public client-identifying key [YouTubeInnerTubeClient] uses; it identifies the client type, not a user. */
        private const val INNERTUBE_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

        private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"

        /** YouTube Music's own well-known browse ids for its moods/genres and charts pages. */
        const val BROWSE_ID_MOODS = "FEmusic_moods_and_genres"
        const val BROWSE_ID_CHARTS = "FEmusic_charts"

        /**
         * Opaque tokens that restrict a search to one kind of result. They are protobuf filter
         * values the web client sends verbatim -- not constructible from anything meaningful on
         * this side, which is why they are literals rather than something built at call time.
         */
        private const val SEARCH_PARAMS_ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA=="
        private const val SEARCH_PARAMS_ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA=="

        private val HEADER_RENDERER_KEYS = listOf(
            "musicImmersiveHeaderRenderer",
            "musicDetailHeaderRenderer",
            "musicResponsiveHeaderRenderer",
            "musicVisualHeaderRenderer",
            "musicCarouselShelfBasicHeaderRenderer"
        )
    }
}
