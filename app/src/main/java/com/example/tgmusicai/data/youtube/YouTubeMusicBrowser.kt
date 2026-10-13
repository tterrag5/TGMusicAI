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

/**
 * One row of YouTube Music's home feed, keeping its own heading.
 *
 * A shelf holds either tracks or tiles (albums, playlists), never both, so whichever list is
 * non-empty is the one to render.
 */
data class YouTubeHomeShelf(
    val title: String,
    val tracks: List<YouTubeSearchResult>,
    val items: List<YouTubeAlbumRef>
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
     * Songs matching [query], best first -- YouTube Music's "Songs" search filter, which returns
     * official audio tracks rather than videos. Used to turn a track known only by name (from
     * Last.fm) into a playable video id.
     */
    suspend fun searchSongs(query: String): List<YouTubeSearchResult> {
        val root = search(query, SEARCH_PARAMS_SONGS) ?: return emptyList()
        return try {
            parseTrackRows(root)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse song search for '$query'", e)
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
            parseSearchRows(root).mapNotNull { row ->
                // Artist browse ids all start with UC; anything else in this shelf is a different
                // kind of result that slipped through the filter.
                if (!row.browseId.startsWith("UC")) {
                    null
                } else {
                    YouTubeArtistRef(row.browseId, row.title, row.thumbnailUrl)
                }
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
            parseSearchRows(root).mapNotNull { row ->
                if (!row.browseId.startsWith("MPRE")) {
                    null
                } else {
                    YouTubeAlbumRef(row.browseId, row.title, row.subtitle, row.thumbnailUrl)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse album search results", e)
            emptyList()
        }
    }

    /**
     * Playlists and mixes matching [query] -- YouTube Music's own "Mix", "Radio" and user
     * playlists, not albums.
     *
     * Results are kept by browse id prefix rather than trusted from the search filter. A playlist
     * of any kind is addressed by a `VL`-prefixed browse id, so that prefix is what actually
     * distinguishes one from the album and artist rows YouTube mixes into the same response when
     * it decides the filter token is stale.
     */
    suspend fun searchPlaylists(query: String): List<YouTubeAlbumRef> {
        val root = search(query, SEARCH_PARAMS_PLAYLISTS) ?: return emptyList()
        return try {
            parseSearchRows(root).mapNotNull { row ->
                if (!row.browseId.startsWith("VL")) {
                    null
                } else {
                    YouTubeAlbumRef(row.browseId, row.title, row.subtitle, row.thumbnailUrl)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse playlist search results", e)
            emptyList()
        }
    }

    /** One row of a search response: what it is, what it is called, and what it looks like. */
    private data class SearchRow(
        val browseId: String,
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?
    )

    /** Pulls the identifying fields out of each result row in a search response. */
    private fun parseSearchRows(root: JSONObject): List<SearchRow> {
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
            // Taken per row rather than per page: results are drawn as artwork tiles now, and a
            // tile with no artwork is a grey square with no way to tell one apart from the next.
            SearchRow(browseId, title, flexColumnText(row, 1), findThumbnailUrl(row))
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

    /**
     * YouTube Music's own home feed: the "Quick picks", "Listen again" and "Mixed for you" rows.
     *
     * The better primary source for a discovery screen than the charts are. Charts are region-gated
     * and frequently return nothing at all, which leaves the screen looking broken through no fault
     * of the user's. The home feed always returns something: recommendations tailored to the
     * account when a session exists, and generally-popular material when it does not.
     *
     * Shelves are returned with their own headings rather than flattened into one list, because
     * "Listen again" and "Quick picks" mean different things and a merged list of both is just a
     * pile of songs.
     */
    suspend fun fetchHomeFeed(): List<YouTubeHomeShelf> {
        val root = browse(BROWSE_ID_HOME) ?: return emptyList()
        return try {
            sectionShelves(root)
                .filterNot { PODCAST_SHELF.containsMatchIn(it.title) }
                .filter { it.tracks.isNotEmpty() || it.items.isNotEmpty() }
                .map { shelf ->
                    YouTubeHomeShelf(
                        // A shelf with no heading still holds usable content, so it gets a generic
                        // one rather than being dropped.
                        title = shelf.title.ifBlank { "Recommended" },
                        tracks = shelf.tracks,
                        items = shelf.items
                    )
                }
                // The feed repeats an item across shelves fairly often; distinct titles keep the
                // screen from showing the same row twice under two names.
                .distinctBy { it.title }
                .take(MAX_HOME_SHELVES)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse the home feed", e)
            emptyList()
        }
    }

    /**
     * YouTube Music's own radio for [videoId]: the "up next" mix the web player builds when a track
     * is started, which is the `RDAMVM` + id playlist served through `/youtubei/v1/next`.
     *
     * This is the closest thing to "more like this one track" that the catalogue offers, and it is
     * tuned by YouTube to flow on from the seed, which taste-seeded artist lookups cannot do. The
     * seed itself (always the first entry) is left out. Empty on any failure.
     */
    suspend fun fetchRadio(videoId: String): List<YouTubeSearchResult> {
        if (videoId.isBlank()) return emptyList()
        val payload = JSONObject().apply {
            put("context", webRemixContext())
            put("videoId", videoId)
            put("playlistId", RADIO_PLAYLIST_PREFIX + videoId)
            put("isAudioOnly", true)
        }
        val root = post(NEXT_URL, payload, "next($videoId)") ?: return emptyList()
        return try {
            parseRadioTracks(root, videoId)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse the radio for $videoId", e)
            emptyList()
        }
    }

    /**
     * Square, high-resolution album art for [videoId], the way YouTube Music shows it (and the way
     * Discover gets its covers): the track's own thumbnail from `/next`, upscaled. A music video's
     * thumbnail is a 16:9 frame (`i.ytimg.com`), so for those the official song version found by
     * [searchSongs] with [title] and [artist] is used instead. Null when neither has square art.
     */
    suspend fun fetchSongArtwork(videoId: String, title: String = "", artist: String = ""): String? {
        if (videoId.isBlank()) return null
        val payload = JSONObject().apply {
            put("context", webRemixContext())
            put("videoId", videoId)
            put("isAudioOnly", true)
        }
        val own = post(NEXT_URL, payload, "next-art($videoId)")?.let { root ->
            val rows = mutableListOf<JSONObject>()
            collectRenderers(root, "playlistPanelVideoRenderer", rows)
            rows.firstOrNull { it.optString("videoId") == videoId }?.let { findThumbnailUrl(it.optJSONObject("thumbnail")) }
        }
        own?.let(::squareArtwork)?.let { return it }
        if (title.isBlank()) return null
        return searchSongs("$title $artist".trim()).firstNotNullOfOrNull { squareArtwork(it.thumbnailUri) }
    }

    /** Lyrics for one track as YouTube Music shows them: timed per line, or plain text. */
    data class TrackLyrics(
        /** LRC with each line's end as a trailing `<mm:ss.xx>` tag when timed; plain text otherwise. */
        val text: String,
        val isTimed: Boolean,
        /** The credit YouTube Music shows under them, e.g. "Source: Musixmatch". */
        val source: String?,
    )

    /**
     * The lyrics YouTube Music itself shows for [videoId]: the Lyrics tab's browse id comes from
     * `/next`, and `/browse` on it returns the lyrics. Only the Android/iOS Music clients get the
     * timed version (`timedLyricsData`, one cue per line with start and end in ms, licensed from
     * Musixmatch/LyricFind); the web client gets plain text for the same id, which is why the
     * browse call names [LYRICS_CLIENT_NAME]. Null when the track has no lyrics tab.
     *
     * Music videos often have no tab while the official audio ("song") version does, so callers
     * holding a video id should also try the song found by [searchSongs].
     */
    suspend fun fetchLyrics(videoId: String): TrackLyrics? {
        if (videoId.isBlank()) return null
        val next = post(
            NEXT_URL,
            JSONObject().apply {
                put("context", webRemixContext())
                put("videoId", videoId)
            },
            "next($videoId) for lyrics"
        ) ?: return null
        val browseId = lyricsBrowseId(next) ?: return null
        val root = post(
            BROWSE_URL,
            JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", LYRICS_CLIENT_NAME)
                        put("clientVersion", LYRICS_CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", browseId)
            },
            "browse($browseId) for lyrics"
        ) ?: return null
        return try {
            parseLyricsResponse(root)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not parse lyrics for $videoId", e)
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
            // Podcast episodes and other non-music audio don't resolve to a playable stream here.
            if (isUnplayableTrack(row)) return@mapNotNull null
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

    /**
     * True for a track row that is a podcast episode or other non-music audio (T035). Only those
     * two markers: a row's subtitle links to its uploader's channel, so a channel page type there
     * says nothing about the row itself.
     */
    private fun isUnplayableTrack(row: JSONObject): Boolean =
        findFirstString(row, "musicVideoType") == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" ||
            findFirstString(row, "pageType") == "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"

    /**
     * True for a tile that opens a podcast show or a user channel (T035), read from the tile's own
     * navigation target. Its subtitle runs link elsewhere (a playlist's author channel), so the
     * first page type found anywhere in the tile is not the tile's.
     */
    private fun isUnplayableTile(tile: JSONObject): Boolean {
        val pageType = tile.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
        return pageType in UNPLAYABLE_PAGE_TYPES
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
            // Podcast shows and user channels open pages this app has no screen for.
            if (browseId.startsWith("MPSP") || isUnplayableTile(tile)) return@mapNotNull null
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
    ): JSONObject? {
        val payload = JSONObject().apply {
            put("context", webRemixContext())
            put("browseId", browseId)
            if (!continuation.isNullOrBlank()) put("continuation", continuation)
            if (!params.isNullOrBlank()) put("params", params)
        }
        return post(BROWSE_URL, payload, "browse($browseId)")
    }

    private fun webRemixContext(): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", CLIENT_VERSION)
            put("hl", "en")
            put("gl", "US")
        })
    }

    /**
     * POSTs [payload] to an InnerTube endpoint, attaching session headers when there is a session
     * (to personalise, never to enable -- see [browse]). Null on any failure; [label] names the
     * call in the log.
     */
    private suspend fun post(url: String, payload: JSONObject, label: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url("$url?key=$INNERTUBE_API_KEY&prettyPrint=false")
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
                        Log.w(TAG, "$label failed: HTTP ${response.code}")
                        return@withContext null
                    }
                    JSONObject(response.body?.string() ?: "{}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "$label failed: ${e.message}")
                null
            }
        }

    companion object {
        private val UNPLAYABLE_PAGE_TYPES = setOf(
            "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE",
            "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE",
            "MUSIC_PAGE_TYPE_USER_CHANNEL",
        )
        private val PODCAST_SHELF = Regex("""\b(podcasts?|episodes?)\b""", RegexOption.IGNORE_CASE)
        private const val TAG = "YouTubeMusicBrowser"
        private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse"
        private const val CLIENT_VERSION = "1.20240901.01.00"

        /** Same public client-identifying key [YouTubeInnerTubeClient] uses; it identifies the client type, not a user. */
        private const val INNERTUBE_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

        private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
        private const val NEXT_URL = "https://music.youtube.com/youtubei/v1/next"

        /**
         * The client the lyrics browse call identifies as. Pinned on purpose: the web client gets
         * untimed text for the same browse id, and Music clients older than 6.x are refused (400).
         * Version as used by sydlexius/canticle and verified live on 2026-10-11.
         */
        const val LYRICS_CLIENT_NAME = "ANDROID_MUSIC"
        const val LYRICS_CLIENT_VERSION = "7.03.52"

        /** The Lyrics tab's browse id in a `/next` response (they start with `MPLY`). */
        internal fun lyricsBrowseId(next: JSONObject): String? {
            val ids = mutableListOf<String>()
            collectStrings(next, "browseId", ids)
            return ids.firstOrNull { it.startsWith("MPLY") }
        }

        private fun collectStrings(node: Any?, key: String, out: MutableList<String>) {
            when (node) {
                is JSONObject -> {
                    (node.opt(key) as? String)?.let { out.add(it) }
                    val keys = node.keys()
                    while (keys.hasNext()) collectStrings(node.opt(keys.next()), key, out)
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) collectStrings(node.opt(i), key, out)
                else -> Unit
            }
        }

        /**
         * Reads a lyrics browse response: `timedLyricsData` (each entry `lyricLine` plus
         * `cueRange.startTimeMilliseconds`/`endTimeMilliseconds`) into LRC with line-end tags, or
         * the plain `description` text when that is all there is.
         */
        internal fun parseLyricsResponse(root: JSONObject): TrackLyrics? {
            val sources = mutableListOf<String>()
            val footers = mutableListOf<JSONObject>()
            collectRenderers(root, "sourceMessage", footers)
            footers.firstOrNull()?.let { runsText(it).takeIf { t -> t.isNotBlank() }?.let(sources::add) }
            if (sources.isEmpty()) collectStrings(root, "sourceMessage", sources)
            val source = sources.firstOrNull()

            val timed = findArray(root, "timedLyricsData")
            if (timed != null && timed.length() > 0) {
                val out = StringBuilder()
                for (i in 0 until timed.length()) {
                    val cue = timed.optJSONObject(i) ?: continue
                    val text = cue.optString("lyricLine").trim()
                    val range = cue.optJSONObject("cueRange") ?: continue
                    val start = range.optString("startTimeMilliseconds").toLongOrNull() ?: continue
                    val end = range.optString("endTimeMilliseconds").toLongOrNull()
                    if (text.isEmpty()) continue
                    out.append(com.example.tgmusicai.data.lyrics.LrcFormat.lineTag(start)).append(text)
                    if (end != null && end > start) out.append(com.example.tgmusicai.data.lyrics.LrcFormat.wordTag(end))
                    out.append('\n')
                }
                if (out.isNotEmpty()) return TrackLyrics(out.toString().trimEnd(), true, source)
            }

            val shelves = mutableListOf<JSONObject>()
            collectRenderers(root, "musicDescriptionShelfRenderer", shelves)
            val plain = shelves.firstNotNullOfOrNull { runsText(it.optJSONObject("description")).takeIf { t -> t.isNotBlank() } }
            if (plain != null) {
                val footer = shelves.firstNotNullOfOrNull { runsText(it.optJSONObject("footer")).takeIf { t -> t.isNotBlank() } }
                return TrackLyrics(plain.trim(), false, source ?: footer)
            }
            return null
        }

        private fun findArray(node: Any?, key: String): org.json.JSONArray? {
            when (node) {
                is JSONObject -> {
                    (node.opt(key) as? org.json.JSONArray)?.let { return it }
                    val keys = node.keys()
                    while (keys.hasNext()) findArray(node.opt(keys.next()), key)?.let { return it }
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) findArray(node.opt(i), key)?.let { return it }
                else -> Unit
            }
            return null
        }

        /** Prefix that turns a video id into the id of YouTube Music's radio seeded from it. */
        const val RADIO_PLAYLIST_PREFIX = "RDAMVM"

        /**
         * Reads the tracks of a `/next` radio response, skipping [seedVideoId].
         *
         * Each entry is a `playlistPanelVideoRenderer`. Its `shortBylineText` holds the artist
         * alone; `longBylineText` adds view and like counts, which is why it is not used.
         */
        /**
         * [url] as large square album art, or null when it is a video frame. YouTube Music's art
         * (lh3 / yt3.googleusercontent.com) carries its size in the URL (`=w60-h60-l90-rj`), so
         * asking for 1200 px is a matter of rewriting that suffix.
         */
        internal fun squareArtwork(url: String?): String? {
            if (url.isNullOrBlank() || "googleusercontent.com" !in url) return null
            val sized = Regex("""=w\d+-h\d+""")
            return if (sized.containsMatchIn(url)) url.replace(sized, "=w1200-h1200") else "$url=w1200-h1200"
        }

        internal fun parseRadioTracks(root: JSONObject, seedVideoId: String): List<YouTubeSearchResult> {
            val rows = mutableListOf<JSONObject>()
            collectRenderers(root, "playlistPanelVideoRenderer", rows)
            val seen = mutableSetOf(seedVideoId)
            return rows.mapNotNull { row ->
                val videoId = row.optString("videoId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!seen.add(videoId)) return@mapNotNull null
                val title = runsText(row.optJSONObject("title")).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val artist = runsText(row.optJSONObject("shortBylineText"))
                    .split("•")
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?: "Unknown Artist"
                YouTubeSearchResult(
                    videoId = videoId,
                    title = title,
                    uploader = artist,
                    durationSeconds = extractDurationSeconds(row),
                    thumbnailUri = findThumbnailUrl(row.optJSONObject("thumbnail")).orEmpty()
                )
            }
        }

        /** YouTube Music's own well-known browse ids for its home, moods/genres and charts pages. */
        const val BROWSE_ID_HOME = "FEmusic_home"
        const val BROWSE_ID_MOODS = "FEmusic_moods_and_genres"
        const val BROWSE_ID_CHARTS = "FEmusic_charts"

        /**
         * Home-feed rows to keep. The feed is long and paginated; this is roughly what fits before
         * a user stops scrolling, and it bounds how much parsing one screen load costs.
         */
        private const val MAX_HOME_SHELVES = 8

        /**
         * Opaque tokens that restrict a search to one kind of result. They are protobuf filter
         * values the web client sends verbatim -- not constructible from anything meaningful on
         * this side, which is why they are literals rather than something built at call time.
         */
        private const val SEARCH_PARAMS_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
        private const val SEARCH_PARAMS_ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA=="
        private const val SEARCH_PARAMS_ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA=="

        /**
         * The "playlists" search filter. Longer than the others because the playlist filter is
         * expressed as a nested option group rather than a single type byte.
         */
        private const val SEARCH_PARAMS_PLAYLISTS = "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo="

        private val HEADER_RENDERER_KEYS = listOf(
            "musicImmersiveHeaderRenderer",
            "musicDetailHeaderRenderer",
            "musicResponsiveHeaderRenderer",
            "musicVisualHeaderRenderer",
            "musicCarouselShelfBasicHeaderRenderer"
        )
    }
}
