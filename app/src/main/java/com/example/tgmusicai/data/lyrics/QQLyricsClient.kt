package com.example.tgmusicai.data.lyrics

import android.util.Log
import com.example.tgmusicai.data.repository.LyricsRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Line-synced LRC from QQ Music (`c.y.qq.com`), one of the providers in the Morphe / YT Music
 * lyrics chain. Searches, keeps the first result whose title, artist or duration matches, then
 * fetches its LRC (code -1901 means QQ has no lyrics for that song). Never throws.
 */
class QQLyricsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Synced LRC for [title] by [artist], or null. */
    fun fetch(title: String, artist: String, durationSec: Long): String? {
        if (title.isBlank()) return null
        return try {
            val search = SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("w", listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))
                .addQueryParameter("format", "json")
                .addQueryParameter("n", "10")
                .addQueryParameter("p", "1")
                .build()
            val list = getJson(search.toString(), SEARCH_REFERER)
                ?.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
            val mid = (0 until list.length()).asSequence()
                .mapNotNull { list.optJSONObject(it) }
                .firstOrNull { song ->
                    val singers = song.optJSONArray("singer")
                    val names = (0 until (singers?.length() ?: 0))
                        .joinToString(" ") { singers!!.optJSONObject(it)?.optString("name").orEmpty() }
                    LyricsRepository.isLyricsMatch(
                        title, artist, durationSec,
                        song.optString("songname"), names, song.optLong("interval"),
                    )
                }
                ?.optString("songmid")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val lyric = LYRIC_URL.toHttpUrl().newBuilder()
                .addQueryParameter("songmid", mid)
                .addQueryParameter("format", "json")
                .addQueryParameter("nobase64", "1")
                .addQueryParameter("g_tk", "5381")
                .build()
            val json = getJson(lyric.toString(), LYRIC_REFERER) ?: return null
            if (json.optInt("code") != 0) return null
            KuGouLyricsClient.cleanLrc(unescape(json.optString("lyric")))
        } catch (e: Exception) {
            Log.w(TAG, "QQ lookup failed for '$title': ${e.message}")
            null
        }
    }

    private fun getJson(url: String, referer: String): JSONObject? =
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", referer)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) null else JSONObject(response.body?.string() ?: "{}")
        }

    companion object {
        private const val TAG = "QQLyrics"
        private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        private const val SEARCH_REFERER = "https://y.qq.com"
        private const val LYRIC_REFERER = "https://y.qq.com/portal/player.html"

        /** QQ HTML-escapes some characters in the LRC body (`&#39;`, `&amp;`, ...). */
        internal fun unescape(text: String): String = text
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#10;", "\n")
            .replace("&#13;", "")
            .replace("&#32;", " ")
            .replace("&amp;", "&")
    }
}
