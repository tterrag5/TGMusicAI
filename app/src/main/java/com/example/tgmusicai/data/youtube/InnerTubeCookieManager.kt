package com.example.tgmusicai.data.youtube

import android.webkit.CookieManager
import com.example.tgmusicai.data.local.AppPreferences
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/** The YouTube Music web origin every InnerTube call and cookie capture is scoped to. */
const val YOUTUBE_MUSIC_ORIGIN = "https://music.youtube.com"

/**
 * Owns the YouTube Music web session used to authenticate [YouTubeInnerTubeClient] calls, in
 * place of a Google Cloud Console OAuth client. The session is a real music.youtube.com login
 * captured from Android's [CookieManager] after the user signs in inside an in-app
 * [com.example.tgmusicai.ui.components.YouTubeLoginDialog] WebView -- exactly what the
 * music.youtube.com web client itself relies on, so it carries no OAuth scopes, no per-project
 * quota, and no test-account whitelist.
 *
 * Only two cookie values are actually needed for authenticated InnerTube requests: the raw
 * `Cookie` header (sent back verbatim) and the session's SAPISID secret, which is never sent
 * as-is but instead used to compute a fresh [generateSapisidHash] digest per request.
 */
class InnerTubeCookieManager(private val appPreferences: AppPreferences) {

    /**
     * Reads the current music.youtube.com cookies out of the WebView's [CookieManager] and
     * persists them via [AppPreferences] for use by [YouTubeInnerTubeClient]. Returns true only
     * if a usable session (one carrying a SAPISID secret) was found -- a WebView that loaded the
     * page but wasn't signed into an account has cookies too, just not that one.
     */
    suspend fun captureFromWebView(): Boolean {
        val rawCookies = CookieManager.getInstance().getCookie(YOUTUBE_MUSIC_ORIGIN) ?: return false
        val sapisid = extractCookieValue(rawCookies, "__Secure-3PAPISID")
            ?: extractCookieValue(rawCookies, "SAPISID")
        if (sapisid.isNullOrBlank()) return false
        appPreferences.setYouTubeMusicSession(rawCookies, sapisid)
        return true
    }

    /** The persisted `Cookie` header for authenticated InnerTube requests, or null if signed out. */
    suspend fun getCookieHeader(): String? =
        appPreferences.youtubeMusicCookieHeaderFlow.first()?.takeIf { it.isNotBlank() }

    /** True once a session with a usable SAPISID has been captured and not since cleared. */
    suspend fun hasValidSession(): Boolean =
        !appPreferences.youtubeMusicSapisidFlow.first().isNullOrBlank()

    /** A fresh `Authorization: SAPISIDHASH ...` header value for [origin], or null if signed out. */
    suspend fun getAuthorizationHeader(origin: String = YOUTUBE_MUSIC_ORIGIN): String? {
        val sapisid = appPreferences.youtubeMusicSapisidFlow.first()?.takeIf { it.isNotBlank() } ?: return null
        return generateSapisidHash(sapisid, origin)
    }

    /** Forgets the persisted session and clears the WebView's own YouTube cookies (sign-out). */
    suspend fun clearSession() {
        appPreferences.clearYouTubeMusicSession()
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    }

    companion object {
        /**
         * Builds the `SAPISIDHASH` value music.youtube.com's own web client sends as its
         * `Authorization` header on authenticated requests:
         * `SAPISIDHASH <unix_seconds>_<sha1("<unix_seconds> <sapisid> <origin>")>`. Must be
         * recomputed per request since it's time-scoped, not a static token.
         */
        fun generateSapisidHash(sapisid: String, origin: String = YOUTUBE_MUSIC_ORIGIN): String {
            val timestamp = System.currentTimeMillis() / 1000
            val payload = "$timestamp $sapisid $origin"
            val sha1Hex = MessageDigest.getInstance("SHA-1")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "SAPISIDHASH ${timestamp}_$sha1Hex"
        }

        private fun extractCookieValue(cookieHeader: String, name: String): String? =
            cookieHeader.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("$name=") }
                ?.substringAfter("=")
                ?.takeIf { it.isNotBlank() }
    }
}
