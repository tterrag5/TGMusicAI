package com.example.tgmusicai.data.scrobble

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A track Last.fm considers similar, by name only -- it has no YouTube id. */
data class LastFmTrack(val title: String, val artist: String, val match: Float)

/**
 * Reads Last.fm's similarity data (`track.getSimilar`, `artist.getSimilar`) with an API key the
 * **user** supplies. The app ships no key of its own (see "No API keys" in CLAUDE.md); without a
 * key, nothing here is called.
 *
 * Read-only: nothing is sent to Last.fm beyond the seed's artist and title. Never throws. A key
 * Last.fm rejects is remembered for the life of this instance, since every later call would be
 * rejected too.
 */
class LastFmSimilarClient(
    private val apiRoot: String = API_ROOT,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var rejectedKey: String? = null

    /** Outcome of [verifyKey], so Settings can say why a key was not accepted. */
    enum class KeyCheck { VALID, REJECTED, UNREACHABLE }

    /** Tracks similar to [title] by [artist], best match first. Empty on any failure. */
    suspend fun similarTracks(apiKey: String, artist: String, title: String, limit: Int): List<LastFmTrack> {
        val root = call(
            apiKey,
            "track.getsimilar",
            mapOf("artist" to artist, "track" to title, "limit" to limit.toString(), "autocorrect" to "1"),
        ) ?: return emptyList()
        return parseSimilarTracks(root)
    }

    /** Artists similar to [artist], best match first. Empty on any failure. */
    suspend fun similarArtists(apiKey: String, artist: String, limit: Int): List<String> {
        val root = call(
            apiKey,
            "artist.getsimilar",
            mapOf("artist" to artist, "limit" to limit.toString(), "autocorrect" to "1"),
        ) ?: return emptyList()
        return parseSimilarArtists(root)
    }

    /** Checks [apiKey] with one cheap request, for Settings to confirm before saving it. */
    suspend fun verifyKey(apiKey: String): KeyCheck = withContext(Dispatchers.IO) {
        try {
            http.newCall(request(apiKey, "artist.getsimilar", mapOf("artist" to "Radiohead", "limit" to "1")))
                .execute().use { response ->
                    val body = JSONObject(response.body?.string()?.ifBlank { "{}" } ?: "{}")
                    when {
                        isKeyRejection(body) -> KeyCheck.REJECTED
                        response.isSuccessful && body.has("similarartists") -> KeyCheck.VALID
                        else -> KeyCheck.UNREACHABLE
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Key check failed: ${e.message}")
            KeyCheck.UNREACHABLE
        }
    }

    private suspend fun call(apiKey: String, method: String, params: Map<String, String>): JSONObject? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || apiKey == rejectedKey) return@withContext null
            try {
                http.newCall(request(apiKey, method, params)).execute().use { response ->
                    val body = JSONObject(response.body?.string()?.ifBlank { "{}" } ?: "{}")
                    if (isKeyRejection(body)) {
                        Log.w(TAG, "Last.fm rejected the API key; not retrying this session")
                        rejectedKey = apiKey
                        return@withContext null
                    }
                    if (!response.isSuccessful || body.has("error")) {
                        Log.w(TAG, "$method failed: HTTP ${response.code} ${body.optString("message")}")
                        return@withContext null
                    }
                    body
                }
            } catch (e: Exception) {
                Log.w(TAG, "$method failed: ${e.message}")
                null
            }
        }

    private fun request(apiKey: String, method: String, params: Map<String, String>): Request {
        val url = apiRoot.toHttpUrl().newBuilder().apply {
            addQueryParameter("method", method)
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("api_key", apiKey)
            addQueryParameter("format", "json")
        }.build()
        return Request.Builder().url(url).get().build()
    }

    companion object {
        private const val TAG = "LastFmSimilar"
        const val API_ROOT = "https://ws.audioscrobbler.com/2.0/"

        /** Last.fm error codes that mean the key itself is bad: invalid (10) or suspended (26). */
        private val KEY_ERROR_CODES = setOf(10, 26)

        internal fun isKeyRejection(body: JSONObject): Boolean =
            body.has("error") && body.optInt("error") in KEY_ERROR_CODES

        /**
         * Reads `similartracks.track[]`. Last.fm returns a single object rather than an array
         * when there is exactly one result, so both shapes are accepted.
         */
        internal fun parseSimilarTracks(root: JSONObject): List<LastFmTrack> {
            val holder = root.optJSONObject("similartracks") ?: return emptyList()
            return objects(holder, "track").mapNotNull { track ->
                val title = track.optString("name").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = track.optJSONObject("artist")?.optString("name")?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LastFmTrack(title, artist, track.optString("match").toFloatOrNull() ?: 0f)
            }.sortedByDescending { it.match }
        }

        /** Reads `similarartists.artist[]`, best match first. */
        internal fun parseSimilarArtists(root: JSONObject): List<String> {
            val holder = root.optJSONObject("similarartists") ?: return emptyList()
            return objects(holder, "artist")
                .mapNotNull { artist ->
                    val name = artist.optString("name").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    name to (artist.optString("match").toFloatOrNull() ?: 0f)
                }
                .sortedByDescending { it.second }
                .map { it.first }
                .distinct()
        }

        private fun objects(holder: JSONObject, key: String): List<JSONObject> {
            holder.optJSONArray(key)?.let { array ->
                return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }
            return listOfNotNull(holder.optJSONObject(key))
        }
    }
}
