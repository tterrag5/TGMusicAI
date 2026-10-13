package com.example.tgmusicai.data.lyrics

import android.util.Log
import com.example.tgmusicai.data.repository.LyricsRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Line-synced LRC from NetEase Cloud Music (`music.163.com/api`), one of the providers in the
 * Morphe / YT Music lyrics chain. Strong on Vocaloid, utaite and Asian releases. Searches, keeps the
 * first result whose title, artist or duration matches, then fetches its LRC. Never throws.
 */
class NetEaseLyricsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Synced LRC for [title] by [artist], or null. */
    fun fetch(title: String, artist: String, durationSec: Long): String? {
        if (title.isBlank()) return null
        return try {
            val search = SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("s", listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))
                .addQueryParameter("type", "1")
                .addQueryParameter("limit", "10")
                .build()
            val songs = getJson(search.toString())?.optJSONObject("result")?.optJSONArray("songs") ?: return null
            val id = (0 until songs.length()).asSequence()
                .mapNotNull { songs.optJSONObject(it) }
                .firstOrNull { song ->
                    val artists = song.optJSONArray("artists")
                    val names = (0 until (artists?.length() ?: 0))
                        .joinToString(" ") { artists!!.optJSONObject(it)?.optString("name").orEmpty() }
                    LyricsRepository.isLyricsMatch(
                        title, artist, durationSec,
                        song.optString("name"), names, song.optLong("duration") / 1000,
                    )
                }
                ?.optLong("id")
                ?.takeIf { it > 0 }
                ?: return null
            val lyric = LYRIC_URL.toHttpUrl().newBuilder()
                .addQueryParameter("id", id.toString())
                .addQueryParameter("lv", "1")
                .addQueryParameter("kv", "1")
                .addQueryParameter("tv", "-1")
                .build()
            val lrc = getJson(lyric.toString())?.optJSONObject("lrc")?.optString("lyric").orEmpty()
            KuGouLyricsClient.cleanLrc(lrc)
        } catch (e: Exception) {
            Log.w(TAG, "NetEase lookup failed for '$title': ${e.message}")
            null
        }
    }

    private fun getJson(url: String): JSONObject? =
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://music.163.com")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) null else JSONObject(response.body?.string() ?: "{}")
        }

    companion object {
        private const val TAG = "NetEaseLyrics"
        private const val SEARCH_URL = "https://music.163.com/api/search/get"
        private const val LYRIC_URL = "https://music.163.com/api/song/lyric"
    }
}
