package com.example.tgmusicai.data.scrobble

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Submits listening history to ListenBrainz, so a play here counts toward the user's own listening
 * record rather than being visible only inside this app.
 *
 * ListenBrainz rather than Last.fm, deliberately. Last.fm's API requires an application key and
 * secret registered by the app's developer and embedded in the build, which this project has a
 * standing rule against -- an earlier feature was removed for exactly that reason. ListenBrainz
 * needs only a token the user generates on their own profile page, which keeps the app free of
 * embedded credentials and keeps the user's account in their own hands.
 *
 * The server address is configurable because the same API is spoken by self-hosted
 * ListenBrainz-compatible servers; pointing at one costs nothing here and makes the feature useful
 * to people who do not want a hosted account at all.
 */
class ListenBrainzScrobbler(
    private val client: OkHttpClient = defaultClient()
) {

    /** What a listen submission carries. Mirrors the fields ListenBrainz actually stores. */
    data class Listen(
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long,
        /** Seconds since the epoch at which the track *started*, which is what the API wants. */
        val startedAtEpochSeconds: Long
    )

    /** Why a submission did not go through, so callers can tell a retry-worthy failure from a permanent one. */
    sealed interface Result {
        data object Success : Result

        /** The token was rejected. Retrying will not help until the user fixes it. */
        data object InvalidToken : Result

        /** A network or server problem. Worth retrying later. */
        data class Transient(val reason: String) : Result
    }

    /**
     * Records that a track finished, which is the submission that becomes permanent history.
     *
     * Submitted as a `single` listen rather than batched. Batching would cut request count, but a
     * batch lost to a process death loses every listen in it, and the request volume here -- one
     * per completed track -- was never a problem worth trading that for.
     */
    suspend fun submitListen(token: String, server: String, listen: Listen): Result =
        post(token, server, buildPayload("single", listen, includeTimestamp = true))

    /**
     * Tells the server what is playing right now. Purely a "now playing" indicator: it is not
     * stored as history, and failing to send it costs nothing, which is why callers ignore the
     * result.
     */
    suspend fun submitNowPlaying(token: String, server: String, listen: Listen): Result =
        post(token, server, buildPayload("playing_now", listen, includeTimestamp = false))

    private fun buildPayload(type: String, listen: Listen, includeTimestamp: Boolean): JSONObject {
        val trackMetadata = JSONObject().apply {
            put("track_name", listen.title)
            put("artist_name", listen.artist)
            listen.album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
            put(
                "additional_info",
                JSONObject().apply {
                    // Identifying the submitting client is expected of API consumers and is what
                    // lets a user see where a listen came from in their own history.
                    put("media_player", "TGMusic")
                    put("submission_client", "TGMusic")
                    if (listen.durationMs > 0) put("duration_ms", listen.durationMs)
                }
            )
        }

        val payload = JSONObject().apply {
            put("track_metadata", trackMetadata)
            // A "playing_now" submission must not carry a timestamp; the server rejects it if it
            // does, since nothing has finished yet.
            if (includeTimestamp) put("listened_at", listen.startedAtEpochSeconds)
        }

        return JSONObject().apply {
            put("listen_type", type)
            put("payload", JSONArray().put(payload))
        }
    }

    private suspend fun post(token: String, server: String, body: JSONObject): Result =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext Result.InvalidToken
            val base = server.trim().trimEnd('/').ifBlank { DEFAULT_SERVER }

            val request = Request.Builder()
                .url("$base/1/submit-listens")
                .header("Authorization", "Token $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> Result.Success
                        response.code == 401 -> Result.InvalidToken
                        else -> Result.Transient("HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Listen submission failed: ${e.message}")
                Result.Transient(e.message ?: "network error")
            }
        }

    /** Checks a token by asking the server who it belongs to, so Settings can confirm it works. */
    suspend fun validateToken(token: String, server: String): String? = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext null
        val base = server.trim().trimEnd('/').ifBlank { DEFAULT_SERVER }
        val request = Request.Builder()
            .url("$base/1/validate-token")
            .header("Authorization", "Token $token")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string() ?: "{}")
                if (!json.optBoolean("valid")) return@withContext null
                json.optString("user_name").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Token validation failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ListenBrainzScrobbler"

        /** The hosted instance, which is what almost everyone will use. */
        const val DEFAULT_SERVER = "https://api.listenbrainz.org"

        /**
         * A track counts as listened to once it has played for half its length, or four minutes,
         * whichever comes first -- the threshold ListenBrainz and every scrobbler before it uses.
         * Matching it means history submitted here looks like history from anywhere else.
         */
        const val SCROBBLE_FRACTION = 0.5
        const val SCROBBLE_CEILING_MS = 4 * 60 * 1000L

        /** Tracks shorter than this are not submitted; the API rejects them as not real listens. */
        const val MIN_TRACK_LENGTH_MS = 30_000L

        /** Returns how long [durationMs] of audio must play before it counts as listened to. */
        fun scrobbleThresholdMs(durationMs: Long): Long =
            if (durationMs <= 0) SCROBBLE_CEILING_MS
            else minOf((durationMs * SCROBBLE_FRACTION).toLong(), SCROBBLE_CEILING_MS)

        private fun defaultClient() = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
