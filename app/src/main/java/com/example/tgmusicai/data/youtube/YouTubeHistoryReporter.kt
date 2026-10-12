package com.example.tgmusicai.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Adds a cloud track the user played to their YouTube Music watch history.
 *
 * Same mechanism the web player uses, and the one ytmusicapi's `add_history_item` uses: an
 * authenticated `/youtubei/v1/player` request returns `playbackTracking.videostatsPlaybackUrl`,
 * and a GET to that URL with a fresh client playback nonce (`cpn`) records the play. Signed out,
 * `/player` returns no tracking URL at all, so nothing can be recorded without a session.
 *
 * Opt-in and off by default: it writes to the user's real account. Never throws.
 */
class YouTubeHistoryReporter(
    private val cookieManager: InnerTubeCookieManager,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Records [videoId] in the signed-in account's history. True when YouTube accepted it. */
    suspend fun report(videoId: String): Boolean = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext false
        try {
            val cookie = cookieManager.getCookieHeader() ?: return@withContext false
            val auth = cookieManager.getAuthorizationHeader() ?: return@withContext false

            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", CLIENT_NAME)
                        put("clientVersion", CLIENT_VERSION)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
            }
            val playerRequest = authenticated(Request.Builder(), cookie, auth)
                .url("$PLAYER_URL?prettyPrint=false")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val trackingUrl = http.newCall(playerRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "player($videoId) failed: HTTP ${response.code}")
                    return@withContext false
                }
                parsePlaybackTrackingUrl(JSONObject(response.body?.string() ?: "{}"))
            } ?: run {
                Log.w(TAG, "player($videoId) returned no playback tracking URL")
                return@withContext false
            }

            val pingUrl = buildPlaybackPingUrl(trackingUrl, newCpn(Random.Default)) ?: return@withContext false
            val ping = authenticated(Request.Builder(), cookie, auth).url(pingUrl).get().build()
            http.newCall(ping).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "History ping for $videoId failed: HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not add $videoId to YouTube history: ${e.message}")
            false
        }
    }

    private fun authenticated(builder: Request.Builder, cookie: String, auth: String): Request.Builder =
        builder
            .header("Cookie", cookie)
            .header("Authorization", auth)
            .header("X-Goog-AuthUser", "0")
            .header("X-Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Origin", YOUTUBE_MUSIC_ORIGIN)
            .header("Referer", "$YOUTUBE_MUSIC_ORIGIN/")
            .header("User-Agent", NewPipeOkHttpDownloader.REALISTIC_USER_AGENT)

    companion object {
        private const val TAG = "YouTubeHistory"
        private const val PLAYER_URL = "https://music.youtube.com/youtubei/v1/player"
        const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_VERSION = "1.20240901.01.00"

        /** The alphabet the web player draws its client playback nonce from. */
        private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        const val CPN_LENGTH = 16

        /** `playbackTracking.videostatsPlaybackUrl.baseUrl`, or null when the response has none. */
        internal fun parsePlaybackTrackingUrl(root: JSONObject): String? =
            root.optJSONObject("playbackTracking")
                ?.optJSONObject("videostatsPlaybackUrl")
                ?.optString("baseUrl")
                ?.takeIf { it.startsWith("https://") }

        /** A 16-character client playback nonce, as the web player generates for each play. */
        internal fun newCpn(random: Random): String =
            buildString(CPN_LENGTH) { repeat(CPN_LENGTH) { append(CPN_ALPHABET[random.nextInt(CPN_ALPHABET.length)]) } }

        /** [baseUrl] with the parameters the history ping needs: `ver=2`, the client, and [cpn]. */
        internal fun buildPlaybackPingUrl(baseUrl: String, cpn: String): String? =
            baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.setQueryParameter("ver", "2")
                ?.setQueryParameter("c", CLIENT_NAME)
                ?.setQueryParameter("cpn", cpn)
                ?.build()
                ?.toString()
    }
}
