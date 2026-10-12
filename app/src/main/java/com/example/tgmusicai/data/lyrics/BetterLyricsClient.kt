package com.example.tgmusicai.data.lyrics

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Word-synced lyrics from Better Lyrics (`lyrics-api.boidu.dev`), the source Metrolist uses for
 * word-by-word highlighting. The lyrics are Apple Music-style TTML with a time on every word (or
 * syllable).
 *
 * Without an account the service answers only from its cache (`x-auth-mode: cache`; a miss is
 * HTTP 401) and allows about 15 requests a minute, so this is a best-effort upgrade: when it has
 * the song the highlight follows the singer exactly, and when it does not the next source is
 * tried. Never throws.
 */
class BetterLyricsClient(
    private val apiRoot: String = API_ROOT,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Enhanced LRC with real word timings for [title] by [artist], or null. */
    fun fetch(title: String, artist: String, durationSec: Long): String? {
        if (title.isBlank() || artist.isBlank()) return null
        val url = apiRoot.toHttpUrl().newBuilder()
            .addPathSegment("getLyrics")
            .addQueryParameter("s", title)
            .addQueryParameter("a", artist)
            .apply { if (durationSec > 0) addQueryParameter("d", durationSec.toString()) }
            .build()
        return try {
            http.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return null
                val ttml = JSONObject(response.body?.string() ?: "{}").optString("ttml").trim()
                ttml.takeIf { it.isNotEmpty() }?.let(::ttmlToEnhancedLrc)?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Better Lyrics lookup failed for '$title': ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "BetterLyrics"
        const val API_ROOT = "https://lyrics-api.boidu.dev"

        private val P_REGEX = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        private val ATTR_BEGIN = Regex("""\bbegin="([^"]+)"""")
        private val ATTR_END = Regex("""\bend="([^"]+)"""")
        /** A background-vocal group: `<span ttm:role="x-bg">…</span>` holding its own spans. */
        private val BG_SPAN = Regex("""<span\b[^>]*ttm:role="x-bg"[^>]*>(?:(?!</span>\s*</span>).)*?</span>\s*</span>""", RegexOption.DOT_MATCHES_ALL)
        private val WORD_SPAN = Regex("""<span\b([^>]*)>([^<]*)</span>""")
        private val ANY_TAG = Regex("""<[^>]+>""")

        /**
         * TTML clock values: `12.345`, `12.345s`, `1:02.5`, `1:02:03.4`. Milliseconds, or null.
         */
        internal fun parseClock(value: String): Long? {
            val v = value.trim().removeSuffix("s")
            val parts = v.split(":")
            return try {
                when (parts.size) {
                    1 -> (parts[0].toDouble() * 1000).toLong()
                    2 -> ((parts[0].toLong() * 60 + parts[1].toDouble()) * 1000).toLong()
                    3 -> ((parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()) * 1000).toLong()
                    else -> null
                }
            } catch (e: NumberFormatException) {
                null
            }
        }

        private fun unescape(text: String): String = text
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

        /**
         * Converts Better Lyrics' TTML into enhanced LRC: `[line start]<word start>word …<line end>`.
         * Text between word spans (usually a space, nothing between syllables) stays with the word
         * before it, so words and syllables both come out right. Background vocals are dropped:
         * one highlighted line follows the lead singer.
         */
        internal fun ttmlToEnhancedLrc(ttml: String): String {
            val out = StringBuilder()
            for (p in P_REGEX.findAll(ttml)) {
                val attrs = p.groupValues[1]
                val lineStart = ATTR_BEGIN.find(attrs)?.groupValues?.get(1)?.let(::parseClock) ?: continue
                val lineEnd = ATTR_END.find(attrs)?.groupValues?.get(1)?.let(::parseClock)
                val body = BG_SPAN.replace(p.groupValues[2], "")

                val spans = WORD_SPAN.findAll(body).toList()
                val line = StringBuilder(LrcFormat.lineTag(lineStart))
                if (spans.isEmpty()) {
                    val text = unescape(ANY_TAG.replace(body, "")).trim()
                    if (text.isEmpty()) continue
                    line.append(text)
                } else {
                    spans.forEachIndexed { i, span ->
                        val start = ATTR_BEGIN.find(span.groupValues[1])?.groupValues?.get(1)?.let(::parseClock)
                        val gapEnd = spans.getOrNull(i + 1)?.range?.first ?: body.length
                        val between = unescape(ANY_TAG.replace(body.substring(span.range.last + 1, gapEnd), ""))
                        val word = unescape(span.groupValues[2]) + between
                        if (start != null) line.append(LrcFormat.wordTag(start))
                        line.append(if (i == spans.lastIndex) word.trimEnd() else word)
                    }
                }
                if (lineEnd != null) line.append(LrcFormat.wordTag(lineEnd))
                out.append(line).append('\n')
            }
            return out.toString().trimEnd()
        }
    }
}
