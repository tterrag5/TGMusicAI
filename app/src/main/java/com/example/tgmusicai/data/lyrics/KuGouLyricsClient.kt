package com.example.tgmusicai.data.lyrics

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Line-synced LRC from KuGou's lyrics library, which is strongest exactly where the others are
 * weakest: Chinese and Japanese releases, Vocaloid included. Same endpoints ViMusic and Metrolist
 * use (`lyrics.kugou.com/search`, then `/download`, base64 LRC). Never throws.
 */
class KuGouLyricsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Synced LRC for [title] by [artist], or null. [durationSec] narrows the match when known. */
    fun fetch(title: String, artist: String, durationSec: Long): String? {
        if (title.isBlank()) return null
        return try {
            val search = SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("ver", "1")
                .addQueryParameter("man", "yes")
                .addQueryParameter("client", "pc")
                .addQueryParameter("keyword", keyword(title, artist))
                .apply { if (durationSec > 0) addQueryParameter("duration", (durationSec * 1000).toString()) }
                .build()
            val candidate = getJson(search.toString())?.optJSONArray("candidates")?.optJSONObject(0) ?: return null
            val id = candidate.optString("id").takeIf { it.isNotBlank() } ?: return null
            val accessKey = candidate.optString("accesskey").takeIf { it.isNotBlank() } ?: return null
            val download = DOWNLOAD_URL.toHttpUrl().newBuilder()
                .addQueryParameter("fmt", "lrc")
                .addQueryParameter("charset", "utf8")
                .addQueryParameter("client", "pc")
                .addQueryParameter("ver", "1")
                .addQueryParameter("id", id)
                .addQueryParameter("accesskey", accessKey)
                .build()
            val content = getJson(download.toString())?.optString("content")?.takeIf { it.isNotBlank() } ?: return null
            cleanLrc(String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "KuGou lookup failed for '$title': ${e.message}")
            null
        }
    }

    private fun getJson(url: String): JSONObject? =
        http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { response ->
            if (!response.isSuccessful) null else JSONObject(response.body?.string() ?: "{}")
        }

    companion object {
        private const val TAG = "KuGouLyrics"
        private const val SEARCH_URL = "https://lyrics.kugou.com/search"
        private const val DOWNLOAD_URL = "https://lyrics.kugou.com/download"

        private val TIMED_LINE = Regex("""^\[\d{2}:\d{2}[.:]\d{2,3}].*""")

        /**
         * Credit lines providers put at the top, e.g. "[00:00.00]作词 : …", "作曲", "编曲" (KuGou,
         * NetEase) or "Lyrics by：…", "Produced by：…" (QQ). They are not lyrics.
         */
        private val CREDIT_LINE = Regex(
            """^\[[^]]+]\s*(作词|作曲|编曲|制作人|词|曲|Lyrics|Composer|Arranger|(Lyrics|Composed|Produced|Written|Arranged|Mixed|Mastered|Music) by)\s*[:：].*""",
            RegexOption.IGNORE_CASE,
        )

        /** "title - artist", with bracketed asides removed, as KuGou's search expects. */
        internal fun keyword(title: String, artist: String): String {
            val cleanTitle = title.replace(Regex("""[(（「『《〈<].*?[)）」』》〉>]"""), " ").trim().ifBlank { title }
            val cleanArtist = artist.replace(Regex("""[(（].*?[)）]"""), " ").replace(" & ", "、").replace(", ", "、").trim()
            return if (cleanArtist.isBlank()) cleanTitle else "$cleanTitle - $cleanArtist"
        }

        /** Keeps timed lyric lines only, dropping tags and credits; null when nothing is left. */
        internal fun cleanLrc(lrc: String): String? =
            lrc.lines()
                .map { it.trim() }
                .filter { TIMED_LINE.matches(it) && !CREDIT_LINE.matches(it) }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
    }
}
