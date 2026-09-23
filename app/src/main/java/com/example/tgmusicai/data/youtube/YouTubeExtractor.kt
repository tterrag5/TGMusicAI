package com.example.tgmusicai.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Custom OkHttp Downloader implementation powering NewPipeExtractor network operations.
 * Enforces realistic browser User-Agent and client headers for maximum compatibility with YouTube services.
 */
class NewPipeOkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    companion object {
        const val REALISTIC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    /**
     * Executes one NewPipeExtractor [Request] over OkHttp, translating between the two libraries'
     * request/response models. Forces a realistic desktop Chrome User-Agent (unless the caller
     * already set one) plus matching Sec-CH-UA/Accept-Language headers on every call, since
     * NewPipeExtractor's default headers are recognizable as a bot and get blocked by YouTube.
     */
    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val builder = okhttp3.Request.Builder().url(url)
        var hasUserAgent = false

        headers.forEach { (key, values) ->
            if (key.equals("User-Agent", ignoreCase = true)) {
                hasUserAgent = true
            }
            values.forEach { value ->
                builder.addHeader(key, value)
            }
        }

        if (!hasUserAgent) {
            builder.header("User-Agent", REALISTIC_USER_AGENT)
        }
        builder.header("Accept-Language", "en-US,en;q=0.9")
        builder.header("Sec-CH-UA", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\", \"Google Chrome\";v=\"128\"")
        builder.header("Sec-CH-UA-Mobile", "?0")
        builder.header("Sec-CH-UA-Platform", "\"Windows\"")

        when {
            httpMethod.equals("GET", ignoreCase = true) -> builder.get()
            httpMethod.equals("POST", ignoreCase = true) -> {
                val bytes = dataToSend ?: ByteArray(0)
                val body = bytes.toRequestBody(null, 0, bytes.size)
                builder.post(body)
            }
            httpMethod.equals("HEAD", ignoreCase = true) -> builder.head()
        }

        val response = client.newCall(builder.build()).execute()
        val responseCode = response.code
        val responseMessage = response.message
        val responseHeaders = response.headers.toMultimap()
        val responseBody = response.body?.string() ?: ""

        return Response(responseCode, responseMessage, responseHeaders, responseBody, response.request.url.toString())
    }
}

/**
 * YouTube extraction service providing search and direct audio stream extraction.
 *
 * IMPORTANT -- there are TWO separate, unrelated "YouTube" code paths in this app, easy to
 * confuse because of the similar names:
 * 1. THIS package (`data.youtube`, this class + [CloudDownloadManager]) -- unofficial, no-login
 *    stream *extraction/scraping*. It never talks to Google's official API or needs the user to
 *    sign in; it pulls playable audio URLs by querying NewPipeExtractor / Piped / Invidious, and
 *    is used for actual playback and downloading of audio.
 * 2. `YouTubeInnerTubeClient`/`InnerTubeCookieManager` (this package) + `data.google`'s
 *    `YouTubePlaylistSyncManager` -- reads/imports the *signed-in* user's own YouTube Music
 *    playlists (e.g. Liked Music) into this app's local database, authenticated via a real
 *    music.youtube.com web session captured from a WebView login rather than Google OAuth. It is
 *    never used to fetch a playable stream for arbitrary audio -- that's always this class.
 * These two paths do not call each other and can fail/succeed independently.
 *
 * **Search** tries NewPipeExtractor first (talks to YouTube directly) and falls back to the
 * Piped/Invidious API cluster if it returns nothing.
 *
 * **Stream resolution** runs in tiers, and the order matters:
 * 0. NewPipeExtractor, talking to YouTube directly ([tryNewPipeStreamExtractions]). This is the
 *    tier that actually works and resolves in a few seconds. No Proof-of-Origin token is involved
 *    -- current NewPipeExtractor uses InnerTube clients that need none.
 * 1. Piped ([PIPED_ENDPOINTS]).
 * 2. Invidious ([INVIDIOUS_ENDPOINTS], plus a live instance list from
 *    `api.invidious.io/instances.json`, which makes this tier self-healing as instances rotate).
 *
 * Tiers 1 and 2 are kept only as insurance, and were measured dead: the Piped host answers HTTP
 * 500 for `/streams`, and every hardcoded Invidious host answers 401 or 403. They cost nothing
 * while tier 0 works, since they are only reached when it fails. Do not assume they work.
 *
 * An earlier design had *only* those two tiers, which is why cloud playback was completely broken
 * -- public instances rot continuously and no list of them stays working. If tier 0 ever breaks,
 * the durable fix is to update NewPipeExtractor, not to hunt for new mirrors.
 *
 * Piped's `/search` endpoint does still work even though its `/streams` endpoint does not, which
 * is why the Piped search fallback is retained while Piped stream resolution is not relied upon.
 * Piped's own instance directory (`piped-instances.kavin.rocks`) was removed: the host no longer
 * resolves at all, so that tier could never self-heal, and consulting it cost a multi-second
 * timeout on every resolution that reached it.
 *
 * Re-verify host liveness periodically with e.g.
 * `curl -o /dev/null -w '%{http_code}' https://<host>/streams/<videoId>`.
 */
class YouTubeExtractor {

    private val TAG = "YouTubeExtractor"

    companion object {
        const val REALISTIC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        // Verified live and returning real, playable audio streams at rebuild time (see
        // YT_REBUILD_PROGRESS.md for the verification transcript). Public instances rotate/die
        // frequently -- re-check periodically.
        val PIPED_ENDPOINTS = listOf(
            "https://pipedapi.wireway.ch"
        )

        // Sourced from the official https://api.invidious.io/instances.json directory at rebuild
        // time. None responded successfully from the sandboxed dev environment used to build this
        // (401/403 -- likely IP-based throttling of that specific outbound IP), but they are real,
        // currently-listed public instances and may work from a real device's network.
        val INVIDIOUS_ENDPOINTS = listOf(
            "https://invidious.nerdvpn.de",
            "https://inv.nadeko.net",
            "https://invidious.tiekoetter.com"
        )

        // Fallback TTL for a resolved stream whose URL doesn't carry a parseable expire= param.
        private const val DEFAULT_STREAM_CACHE_TTL_MS = 4L * 60 * 60 * 1000

        /**
         * Parses a duration string into whole seconds. Accepts a plain integer (already-seconds),
         * or `mm:ss` / `hh:mm:ss` clock-style text as returned by some search APIs. Returns 0 for
         * blank or unparseable input rather than throwing.
         */
        fun parseDurationTextToSeconds(text: String): Long {
            if (text.isBlank()) return 0L
            text.toLongOrNull()?.let { return it }

            val parts = text.trim().split(":")
            return try {
                when (parts.size) {
                    1 -> parts[0].toLong()
                    2 -> parts[0].toLong() * 60 + parts[1].toLong()
                    3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                    else -> 0L
                }
            } catch (e: Exception) {
                0L
            }
        }

        /**
         * Heuristically decides whether a stream entry is audio-only (not a combined video+audio
         * track), from its MIME type and/or format/container name. Needed because backends
         * (Piped, Invidious) report audio-ness inconsistently -- sometimes via `mimeType`,
         * sometimes only via a bare format string like "m4a" -- so both are checked together.
         */
        fun isAudioMimeOrFormat(mimeType: String, formatName: String): Boolean {
            val mime = mimeType.lowercase()
            val fmt = formatName.lowercase()
            if (mime.contains("video/") && !mime.contains("audio/")) {
                return false
            }
            val combined = "$mime $fmt"
            return mime.startsWith("audio/") ||
                   combined.contains("audio/mp4") ||
                   combined.contains("audio/m4a") ||
                   combined.contains("audio/webm") ||
                   combined.contains("audio/aac") ||
                   combined.contains("audio/") ||
                   fmt == "m4a" ||
                   fmt == "opus" ||
                   fmt == "aac" ||
                   (fmt == "webm" && mime.contains("audio")) ||
                   (fmt == "mp4" && mime.contains("audio"))
        }

        /**
         * Maps a raw mime type/format pair from a Piped/Invidious response onto one of this app's
         * three canonical audio format tags ("webm", "aac", "m4a"), defaulting to "m4a" so
         * downstream code (file naming, playback) always gets a recognized extension.
         */
        fun normalizeAudioFormat(mimeType: String, formatName: String): String {
            val combined = "$mimeType $formatName".lowercase()
            return when {
                combined.contains("webm") || combined.contains("opus") -> "webm"
                combined.contains("aac") -> "aac"
                combined.contains("m4a") || combined.contains("mp4") -> "m4a"
                else -> "m4a"
            }
        }

        /**
         * Parses a Piped `audioStreams` JSON array into [YouTubeAudioStream] candidates. For each
         * audio entry, emits both the direct CDN URL (fastest, but sometimes geo/IP-restricted)
         * and a same-instance `/proxy?url=` fallback (slower, routed through the Piped instance,
         * but works even when the direct URL is blocked) -- callers try candidates in order until
         * one actually downloads.
         */
        fun parsePipedProxyAudioStreams(jsonArray: JSONArray, baseUrl: String = PIPED_ENDPOINTS.first()): List<YouTubeAudioStream> {
            val streams = mutableListOf<YouTubeAudioStream>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                val directUrl = item.optString("url", "")
                val proxyUrlField = item.optString("proxyUrl", "")
                val bitrate = item.optInt("bitrate", 0)
                val mimeType = item.optString("mimeType", "").lowercase()
                val format = item.optString("format", "").lowercase()

                if (isAudioMimeOrFormat(mimeType, format)) {
                    if (directUrl.isNotBlank()) {
                        streams.add(
                            YouTubeAudioStream(
                                url = directUrl,
                                format = normalizeAudioFormat(mimeType, format),
                                bitrate = bitrate
                            )
                        )
                    }
                    val fallbackProxyUrl = if (proxyUrlField.isNotBlank()) {
                        proxyUrlField
                    } else if (directUrl.isNotBlank()) {
                        val encoded = try { URLEncoder.encode(directUrl, "UTF-8") } catch (e: Exception) { directUrl }
                        "$baseUrl/proxy?url=$encoded"
                    } else ""

                    if (fallbackProxyUrl.isNotBlank() && fallbackProxyUrl != directUrl) {
                        streams.add(
                            YouTubeAudioStream(
                                url = fallbackProxyUrl,
                                format = normalizeAudioFormat(mimeType, format),
                                bitrate = bitrate
                            )
                        )
                    }
                }
            }
            return streams
        }

        /**
         * Convenience overload of [parsePipedProxyAudioStreams] using the first configured
         * [PIPED_ENDPOINTS] host as the proxy base URL.
         */
        fun parsePipedAudioStreams(jsonArray: JSONArray): List<YouTubeAudioStream> {
            return parsePipedProxyAudioStreams(jsonArray, PIPED_ENDPOINTS.first())
        }

        /**
         * Parses an Invidious `adaptiveFormats` JSON array into [YouTubeAudioStream] candidates,
         * keeping only entries that look like audio (per [isAudioMimeOrFormat]).
         */
        fun parseInvidiousAdaptiveFormats(jsonArray: JSONArray): List<YouTubeAudioStream> {
            val streams = mutableListOf<YouTubeAudioStream>()
            for (i in 0 until jsonArray.length()) {
                val stream = jsonArray.optJSONObject(i) ?: continue
                val type = stream.optString("type", "").lowercase()
                val container = stream.optString("container", "").lowercase()
                val encoding = stream.optString("encoding", "").lowercase()

                if (isAudioMimeOrFormat(type, "$container $encoding")) {
                    val streamUrl = stream.optString("url", "")
                    val bitrateStr = stream.optString("bitrate", "0")
                    val bitrate = stream.optInt("bitrate", bitrateStr.toIntOrNull() ?: 0)

                    if (streamUrl.isNotBlank()) {
                        streams.add(
                            YouTubeAudioStream(
                                url = streamUrl,
                                format = normalizeAudioFormat(type, container),
                                bitrate = bitrate
                            )
                        )
                    }
                }
            }
            return streams
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        // Connect timeout is deliberately much shorter than the read timeout. Resolution walks a
        // list of public instances, most of which are dead at any given moment, and a dead host
        // burns the full connect timeout before the next one is tried -- at 15s each that added up
        // to well over a minute of apparently-nothing before the user saw any result. Reaching a
        // host that is actually up takes a fraction of this; only dead ones pay it. The read
        // timeout stays long because that one covers real transfers.
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", REALISTIC_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-CH-UA", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\", \"Google Chrome\";v=\"128\"")
                .header("Sec-CH-UA-Mobile", "?0")
                .header("Sec-CH-UA-Platform", "\"Windows\"")
                .build()
            chain.proceed(req)
        }
        .build()

    // The Invidious directory lookup is a best-effort self-heal, not a critical-path fetch, so it
    // must never hang for the full 15s connect/read timeout used for real stream requests. An
    // unreachable directory host used to cost a 15s stall per song, which made "Play All" on a
    // cloud playlist look completely broken: every song whose primary endpoint failed paid that
    // cost serially before falling through to the next tier.
    private val directoryLookupClient = okHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // The pre-flight check (a tiny 1KB Range GET) is a liveness probe, not a real download -- it
    // has no business waiting up to 15s. A slow/dead candidate used to stall "Play All" on a cloud
    // playlist for up to 15s per candidate per song before falling through to the next one, which
    // made queue resolution (and therefore the risk of the player timing out ahead of it) far
    // worse than it needed to be.
    private val preflightClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** A resolved stream URL cached until [expiresAtMs] -- Google Video CDN URLs embed their own `expire=<epoch>` param, typically ~6h out. */
    private data class CachedStream(val stream: YouTubeAudioStream, val expiresAtMs: Long)

    // Keyed by videoId. Avoids re-resolving (a multi-network-round-trip operation) the same video
    // every time it's replayed within its CDN URL's real validity window -- e.g. replaying a
    // cloud song, or it reappearing later in a shuffled/looped queue.
    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    /**
     * Computes when a resolved stream URL should be evicted from [streamCache]. Google's CDN URLs
     * carry their own `expire=<unix-seconds>` query param (~6h validity); when present, this
     * trusts it (minus a safety margin, see below) instead of the fixed [DEFAULT_STREAM_CACHE_TTL_MS],
     * so cached URLs are dropped right before they'd actually stop working rather than on a guess.
     */
    private fun expiryFromStreamUrl(url: String): Long {
        val expireParam = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        // expire= is a Unix epoch in seconds; back off 5 minutes from the real expiry as a safety
        // margin so a cached URL is never handed out right as it's about to stop working.
        return if (expireParam != null) (expireParam * 1000L) - (5 * 60 * 1000L)
        else System.currentTimeMillis() + DEFAULT_STREAM_CACHE_TTL_MS
    }

    // In-memory cache for the live Invidious instance directory, so we don't hit
    // api.invidious.io on every single stream resolution -- refreshed once per process lifetime.
    // Caches a failed attempt too (as an empty list), not just a successful one -- otherwise an
    // unreachable directory host gets re-attempted (and re-times-out) for every single song that
    // needs it instead of just once per process.
    private var liveInvidiousInstancesCache: List<String>? = null
    private var liveInvidiousInstancesAttempted = false

    // Same idea for Piped's own public instance directory.

    // Registers this class's OkHttp-backed Downloader as NewPipeExtractor's global HTTP client;
    // must happen before any NewPipe.* call (e.g. search()) is made.
    init {
        try {
            NewPipe.init(NewPipeOkHttpDownloader(okHttpClient))
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing NewPipeExtractor", e)
        }
    }

    /**
     * Resolves audio streams by talking to YouTube directly through NewPipeExtractor.
     *
     * This is the primary resolution path, and the reason cloud playback works at all: the
     * Piped/Invidious tiers that used to be the only path depend on public instances that rot
     * continuously, and every one of them was measured dead or blocking. This path depends on
     * nothing but YouTube itself.
     *
     * No Proof-of-Origin token is involved. Current NewPipeExtractor resolves through InnerTube
     * clients that do not require one -- in practice the visionOS client, visible as `c=VISIONOS`
     * in the resulting stream URLs -- and its own `YoutubeStreamExtractor.setPoTokenProvider` is
     * documented as a no-op "until SABR support is added to the extractor". An earlier revision of
     * this work included a full BotGuard/WebView poToken generator; it was removed once it was
     * shown to never be consulted. If YouTube forces poTokens again, recover it from git history
     * rather than rewriting it.
     *
     * Returns an empty list rather than throwing, so callers can simply fall through to the
     * remaining tiers.
     */
    private suspend fun tryNewPipeStreamExtractions(videoId: String): List<YouTubeAudioStream> =
        withContext(Dispatchers.IO) {
            try {
                val info = StreamInfo.getInfo(
                    ServiceList.YouTube,
                    "https://www.youtube.com/watch?v=$videoId",
                )
                info.audioStreams
                    // Highest bitrate wins regardless of container. Android Auto is unaffected by
                    // the choice: it renders this app's browse tree while audio plays through the
                    // app's own ExoPlayer, so the container never reaches the head unit.
                    .sortedByDescending { it.averageBitrate }
                    .mapNotNull { stream ->
                        val url = stream.content ?: return@mapNotNull null
                        if (url.isBlank()) return@mapNotNull null
                        YouTubeAudioStream(
                            url = url,
                            format = normalizeAudioFormat(
                                stream.format?.mimeType.orEmpty(),
                                stream.format?.suffix.orEmpty(),
                            ),
                            bitrate = stream.averageBitrate,
                        )
                    }
            } catch (e: Exception) {
                Log.e("TGMusicCloud", "Tier 0 NewPipe extraction failed for $videoId: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * Fetches the current list of public HTTPS Invidious instances from the official
     * `api.invidious.io/instances.json` directory. Unlike Piped (which has no equivalent live,
     * reachable directory as of this writing), Invidious publishes one, so this makes the
     * Invidious fallback tier self-healing as instances rotate/die, instead of relying solely on
     * the hardcoded [INVIDIOUS_ENDPOINTS] list. Cached in-memory after the first attempt --
     * success or failure -- so a network that can't reach this host only pays that cost once per
     * process, not once per song. Uses [directoryLookupClient]'s short timeout since this is a
     * best-effort self-heal, not a critical-path fetch. Returns empty (never throws) on any
     * failure so callers can just fall through.
     */
    private suspend fun fetchLiveInvidiousInstances(): List<String> = withContext(Dispatchers.IO) {
        liveInvidiousInstancesCache?.let { return@withContext it }
        if (liveInvidiousInstancesAttempted) return@withContext emptyList()
        liveInvidiousInstancesAttempted = true
        try {
            val request = okhttp3.Request.Builder()
                .url("https://api.invidious.io/instances.json")
                .get()
                .build()
            directoryLookupClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val array = JSONArray(bodyStr)
                val hosts = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val pair = array.optJSONArray(i) ?: continue
                    val name = pair.optString(0, "")
                    val info = pair.optJSONObject(1) ?: continue
                    if (name.isNotBlank() && info.optString("type") == "https") {
                        hosts.add("https://$name")
                    }
                }
                val limited = hosts.take(8)
                liveInvidiousInstancesCache = limited
                Log.d("TGMusicCloud", "Fetched ${limited.size} live Invidious instance(s) from api.invidious.io")
                limited
            }
        } catch (e: Exception) {
            Log.e("TGMusicCloud", "Failed to fetch live Invidious instance list: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Searches YouTube for [query]. Tries NewPipeExtractor first (direct, most reliable), then
     * falls back to the Piped/Invidious API cluster if NewPipe returns nothing.
     */
    suspend fun search(query: String): List<YouTubeSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val service = ServiceList.YouTube
            val searchExtractor = service.getSearchExtractor(query)
            searchExtractor.fetchPage()

            val results = mutableListOf<YouTubeSearchResult>()
            searchExtractor.initialPage.items.forEach { item ->
                if (item is StreamInfoItem) {
                    val videoId = extractVideoId(item.url) ?: item.url
                    results.add(
                        YouTubeSearchResult(
                            videoId = videoId,
                            title = item.name ?: "Unknown Title",
                            uploader = item.uploaderName ?: "Unknown Artist",
                            durationSeconds = item.duration,
                            thumbnailUri = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        )
                    )
                }
            }

            if (results.isNotEmpty()) {
                Log.d("TGMusicCloud", "NewPipe search resolved ${results.size} result(s) for '$query'")
                return@withContext results
            }
            Log.d("TGMusicCloud", "NewPipe search returned no results for '$query', falling back")
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe search failed for '$query', attempting fallback search engines", e)
        }

        return@withContext fallbackSearch(query)
    }

    /**
     * Extracts the bare YouTube video ID from a URL, or returns the input unchanged if it already
     * looks like a bare ID (no scheme/slash/query). Handles `watch?v=`, `/v/`, `/embed/`, and
     * `youtu.be/` URL shapes via regex, since different sources (search results, Piped, Invidious)
     * hand back the ID in different URL forms.
     */
    fun extractVideoId(url: String): String? {
        if (url.isBlank()) return null
        if (!url.contains("http://") && !url.contains("https://") && !url.contains("/") && !url.contains("?")) {
            return url
        }
        val regex = "(?:v=|/v/|embed/|youtu\\.be/)([^&?#/]+)".toRegex()
        val match = regex.find(url)
        return match?.groupValues?.get(1) ?: url
    }

    /**
     * Fallback search path used when NewPipeExtractor's direct search returns nothing. Tries every
     * Piped host (hardcoded plus live-fetched instances) in order first, then every Invidious
     * host, returning as soon as one endpoint yields a non-empty result list.
     */
    private suspend fun fallbackSearch(query: String): List<YouTubeSearchResult> {
        val encodedQuery = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (e: Exception) {
            query
        }

        for (base in PIPED_ENDPOINTS) {
            try {
                val url = "$base/search?q=$encodedQuery&filter=music_songs"
                val request = okhttp3.Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    Log.d("TGMusicCloud", "Piped search request to $url returned HTTP $code")
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        val items = JSONObject(bodyStr).optJSONArray("items") ?: JSONArray()
                        val results = parsePipedSearchItems(items)
                        if (results.isNotEmpty()) return results
                    }
                }
            } catch (e: Exception) {
                Log.e("TGMusicCloud", "Piped search failed at $base for query '$query': ${e.message}", e)
            }
        }

        for (base in INVIDIOUS_ENDPOINTS) {
            try {
                val url = "$base/api/v1/search?q=$encodedQuery&type=video"
                val request = okhttp3.Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    Log.d("TGMusicCloud", "Invidious search request to $url returned HTTP $code")
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        val items = JSONArray(bodyStr)
                        val results = parseInvidiousSearchItems(items)
                        if (results.isNotEmpty()) return results
                    }
                }
            } catch (e: Exception) {
                Log.e("TGMusicCloud", "Invidious search failed at $base for query '$query': ${e.message}", e)
            }
        }

        return emptyList()
    }

    /**
     * Parses a Piped `/search` response's `items` array into [YouTubeSearchResult]s, deriving the
     * video ID from the `/watch?v=`-prefixed item URL.
     */
    fun parsePipedSearchItems(jsonArray: JSONArray): List<YouTubeSearchResult> {
        val results = mutableListOf<YouTubeSearchResult>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            val itemUrl = item.optString("url", "")
            val videoId = itemUrl.removePrefix("/watch?v=")
            val title = item.optString("title", "Unknown")
            val uploader = item.optString("uploaderName", "Unknown Artist")
            val duration = item.optLong("duration", 0L)
            val thumbnail = item.optString("thumbnail", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")

            if (videoId.isNotBlank()) {
                results.add(
                    YouTubeSearchResult(
                        videoId = videoId,
                        title = title,
                        uploader = uploader,
                        durationSeconds = duration,
                        thumbnailUri = thumbnail
                    )
                )
            }
        }
        return results
    }

    /**
     * Parses an Invidious `/api/v1/search` JSON array into [YouTubeSearchResult]s.
     */
    fun parseInvidiousSearchItems(jsonArray: JSONArray): List<YouTubeSearchResult> {
        val results = mutableListOf<YouTubeSearchResult>()
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.optJSONObject(i) ?: continue
            val videoId = item.optString("videoId", "")
            val title = item.optString("title", "Unknown")
            val uploader = item.optString("author", "Unknown Artist")
            val duration = item.optLong("lengthSeconds", 0L)
            val thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            if (videoId.isNotBlank()) {
                results.add(
                    YouTubeSearchResult(
                        videoId = videoId,
                        title = title,
                        uploader = uploader,
                        durationSeconds = duration,
                        thumbnailUri = thumbnail
                    )
                )
            }
        }
        return results
    }

    /**
     * Extracts all candidate audio stream URLs across all fallback engines, for callers (like
     * [CloudDownloadManager]) that want to try multiple candidates in sequence until one actually
     * downloads successfully. Execution order: Piped, then Invidious.
     */
    suspend fun extractAudioStreams(videoId: String): List<YouTubeAudioStream> = withContext(Dispatchers.IO) {
        val cleanId = extractVideoId(videoId) ?: videoId
        val candidateStreams = mutableListOf<YouTubeAudioStream>()

        // Tier 0 first, so downloads try the direct YouTube streams before the public-instance
        // fallbacks. CloudDownloadManager attempts candidates in order and names the file from the
        // chosen stream's format, so order here decides both which source is used and the on-disk
        // extension.
        try {
            candidateStreams.addAll(tryNewPipeStreamExtractions(cleanId))
        } catch (e: Exception) {
            Log.e("TGMusicCloud", "Tier 0 NewPipe extractions failed for $cleanId: ${e.message}", e)
        }

        try {
            candidateStreams.addAll(tryPipedStreamExtractions(cleanId))
        } catch (e: Exception) {
            Log.e("TGMusicCloud", "Piped stream proxy extractions failed for $cleanId: ${e.message}", e)
        }

        try {
            candidateStreams.addAll(tryInvidiousStreamExtractions(cleanId))
        } catch (e: Exception) {
            Log.e("TGMusicCloud", "Invidious stream extractions failed for $cleanId: ${e.message}", e)
        }

        candidateStreams
            .filter { it.url.isNotBlank() && isAudioMimeOrFormat(it.format, it.format) }
            .distinctBy { it.url }
    }

    /**
     * Extracts a single direct playable audio stream URL for [videoId], for callers that need a
     * fast answer (e.g. direct playback). Tries each tier in order and returns as soon as a
     * candidate passes the [verifyStreamUrl] pre-flight check.
     */
    suspend fun extractAudioStream(videoId: String): YouTubeAudioStream? = withContext(Dispatchers.IO) {
        val cleanId = extractVideoId(videoId) ?: videoId

        streamCache[cleanId]?.let { cached ->
            if (cached.expiresAtMs > System.currentTimeMillis()) {
                Log.d("TGMusicCloud", "Using cached stream for $cleanId (expires in ${(cached.expiresAtMs - System.currentTimeMillis()) / 1000}s)")
                return@withContext cached.stream
            }
            streamCache.remove(cleanId)
        }

        try {
            val newPipeStreams = tryNewPipeStreamExtractions(cleanId)
            for (candidate in newPipeStreams) {
                if (verifyStreamUrl(candidate.url)) {
                    Log.d("TGMusicCloud", "Tier 0 NewPipe resolved verified stream for $cleanId: ${candidate.url}")
                    streamCache[cleanId] = CachedStream(candidate, expiryFromStreamUrl(candidate.url))
                    return@withContext candidate
                }
            }
        } catch (e: Exception) {
            Log.e("TGMusicCloud", "Tier 0 NewPipe extraction failed for $cleanId: ${e.message}", e)
        }

        try {
            val pipedStreams = tryPipedStreamExtractions(cleanId)
            for (candidate in pipedStreams) {
                if (verifyStreamUrl(candidate.url)) {
                    Log.d("TGMusicCloud", "Tier 1 Piped resolved verified stream for $cleanId: ${candidate.url}")
                    streamCache[cleanId] = CachedStream(candidate, expiryFromStreamUrl(candidate.url))
                    return@withContext candidate
                }
            }
        } catch (e: Exception) {
            Log.e("TGMusicCloud", "Tier 1 Piped extraction failed for $cleanId: ${e.message}", e)
        }

        try {
            val invidiousStreams = tryInvidiousStreamExtractions(cleanId)
            for (candidate in invidiousStreams) {
                if (verifyStreamUrl(candidate.url)) {
                    Log.d("TGMusicCloud", "Tier 2 Invidious resolved verified stream for $cleanId: ${candidate.url}")
                    streamCache[cleanId] = CachedStream(candidate, expiryFromStreamUrl(candidate.url))
                    return@withContext candidate
                }
            }
        } catch (e: Exception) {
            Log.e("TGMusicCloud", "Tier 2 Invidious extraction failed for $cleanId: ${e.message}", e)
        }

        Log.e("TGMusicCloud", "No candidate audio streams found across all tiers for video ID: $videoId")
        return@withContext null
    }

    /**
     * Verifies whether a given stream URL is accessible and returns HTTP 200 or 206 status code.
     * Uses [preflightClient]'s short timeout -- this is a liveness probe, not a real download, and
     * has no business tying up a resolution attempt for as long as an actual stream request would.
     */
    fun verifyStreamUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", REALISTIC_USER_AGENT)
                .header("Range", "bytes=0-1023")
                .get()
                .build()
            preflightClient.newCall(request).execute().use { response ->
                val code = response.code
                Log.d("TGMusicCloud", "Pre-flight HTTP check for $url -> HTTP $code")
                val isOk = code == 200 || code == 206
                if (!isOk) {
                    Log.e("TGMusicCloud", "Pre-flight HTTP check failed for $url -> HTTP $code ${response.message}")
                }
                isOk
            }
        } catch (e: Exception) {
            Log.e("TGMusicCloud", "Pre-flight HTTP check exception for $url: ${e.message}", e)
            false
        }
    }

    /**
     * Resolves audio stream candidates for [videoId] from the Piped tier. Tries the hardcoded
     * [PIPED_ENDPOINTS] host(s) first; only falls through to the live instance directory (an extra
     * network round-trip) if those produced nothing, since the hardcoded host succeeding is the
     * common case.
     */
    private suspend fun tryPipedStreamExtractions(videoId: String): List<YouTubeAudioStream> = withContext(Dispatchers.IO) {
        val streams = mutableListOf<YouTubeAudioStream>()
        // Try the hardcoded, known-good host(s) first without paying for a live-directory fetch
        addPipedStreamsFromHosts(PIPED_ENDPOINTS, videoId, streams)
        return@withContext streams
    }

    /**
     * Queries each Piped [hosts] entry's `/streams/{videoId}` endpoint in turn and appends any
     * parsed audio streams into [streams]. Does not stop at the first success -- every reachable
     * host's streams are collected so the caller has multiple candidates to try if one later
     * fails to actually download.
     */
    private suspend fun addPipedStreamsFromHosts(
        hosts: List<String>,
        videoId: String,
        streams: MutableList<YouTubeAudioStream>
    ) {
        for (base in hosts) {
            try {
                val url = "$base/streams/$videoId"
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", REALISTIC_USER_AGENT)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    Log.d("TGMusicCloud", "Piped API request to $url for $videoId returned HTTP $code")
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        if (bodyStr.isNotBlank()) {
                            val json = JSONObject(bodyStr)
                            val audioStreams = json.optJSONArray("audioStreams") ?: JSONArray()
                            val parsed = parsePipedProxyAudioStreams(audioStreams, base)
                            streams.addAll(parsed)
                        }
                    } else {
                        Log.e("TGMusicCloud", "Piped API endpoint $url failed for $videoId: HTTP $code ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("TGMusicCloud", "Piped stream resolution failed at $base for $videoId: ${e.message}", e)
            }
        }
    }

    /**
     * Resolves audio stream candidates for [videoId] from the Invidious tier, mirroring
     * [tryPipedStreamExtractions]'s lazy fallback to the live instance directory.
     */
    private suspend fun tryInvidiousStreamExtractions(videoId: String): List<YouTubeAudioStream> = withContext(Dispatchers.IO) {
        val streams = mutableListOf<YouTubeAudioStream>()
        // Same lazy-fetch pattern as tryPipedStreamExtractions: only consult the live instance
        // directory if the hardcoded hosts didn't already produce a working candidate.
        addInvidiousStreamsFromHosts(INVIDIOUS_ENDPOINTS, videoId, streams)
        if (streams.isEmpty()) {
            val liveHosts = fetchLiveInvidiousInstances().filterNot { it in INVIDIOUS_ENDPOINTS }
            addInvidiousStreamsFromHosts(liveHosts, videoId, streams)
        }
        return@withContext streams
    }

    /**
     * Queries each Invidious [hosts] entry's `/api/v1/videos/{videoId}` endpoint in turn and
     * appends any parsed `adaptiveFormats` audio streams into [streams].
     */
    private suspend fun addInvidiousStreamsFromHosts(
        hosts: List<String>,
        videoId: String,
        streams: MutableList<YouTubeAudioStream>
    ) {
        for (base in hosts) {
            try {
                val url = "$base/api/v1/videos/$videoId"
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", REALISTIC_USER_AGENT)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    Log.d("TGMusicCloud", "Invidious API request to $url for $videoId returned HTTP $code")
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        if (bodyStr.isNotBlank()) {
                            val json = JSONObject(bodyStr)
                            val adaptiveFormats = json.optJSONArray("adaptiveFormats") ?: JSONArray()
                            streams.addAll(parseInvidiousAdaptiveFormats(adaptiveFormats))
                        }
                    } else {
                        Log.e("TGMusicCloud", "Invidious API endpoint $url failed for $videoId: HTTP $code ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("TGMusicCloud", "Invidious stream resolution failed at $base for $videoId: ${e.message}", e)
            }
        }
    }
}
