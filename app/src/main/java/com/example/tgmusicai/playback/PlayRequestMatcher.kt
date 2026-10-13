package com.example.tgmusicai.playback

import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.youtube.YouTubeSearchResult

/**
 * Matching for spoken play requests ("play when spring comes by jamie paige") from another app
 * such as Lantern (T027): the library first, then the best YouTube Music result. Pure, so the
 * rules can be unit-tested without a database or network.
 */
object PlayRequestMatcher {

    /** Versions nobody gets by accident: only picked when the request itself asks for one. */
    private val VARIANT_WORDS = listOf("cover", "karaoke", "instrumental", "nightcore", "sped up", "slowed", "remix", "8d", "reaction", "lyrics video")

    fun normalize(text: String): String = text.lowercase()
        .replace(Regex("\\((official|lyric|lyrics|audio|music video|mv|hd)[^)]*\\)"), " ")
        .replace(Regex("\\[(official|lyric|lyrics|audio|music video|mv|hd)[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Library songs matching [title] (and [artist], when given), best first: exact title before
     * a title that merely contains it, downloaded copies before streams.
     */
    fun localMatches(songs: List<Song>, title: String, artist: String?): List<Song> {
        val wantTitle = normalize(title)
        if (wantTitle.isEmpty()) return emptyList()
        val wantArtist = artist?.let(::normalize)?.takeIf { it.isNotEmpty() }
        return songs.mapNotNull { song ->
            val songTitle = normalize(song.title)
            val titleScore = when {
                songTitle == wantTitle -> 3
                songTitle.contains(wantTitle) && wantTitle.length >= 4 -> 1
                else -> return@mapNotNull null
            }
            val artistOk = wantArtist == null || artistMatches(normalize(song.artist), wantArtist) ||
                (song.producer?.let { artistMatches(normalize(it), wantArtist) } ?: false)
            if (!artistOk) return@mapNotNull null
            song to titleScore * 10 + (if (song.isDownloaded) 1 else 0)
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** The YouTube result that best fits the request, or null when none mentions the title. */
    fun bestRemote(results: List<YouTubeSearchResult>, title: String, artist: String?): YouTubeSearchResult? {
        val wantTitle = normalize(title)
        val wantArtist = artist?.let(::normalize)?.takeIf { it.isNotEmpty() }
        val request = "$wantTitle ${wantArtist.orEmpty()}"
        return results.withIndex().map { (index, result) ->
            val resultTitle = normalize(result.title)
            val uploader = normalize(result.uploader)
            var score = when {
                resultTitle == wantTitle -> 6
                resultTitle.contains(wantTitle) -> 4
                wantTitle.contains(resultTitle) && resultTitle.length >= 4 -> 2
                else -> 0
            }
            // The artist only counts once the title matches: right artist, wrong song is still wrong.
            if (score > 0 && wantArtist != null && (artistMatches(uploader, wantArtist) || resultTitle.contains(wantArtist))) score += 3
            if (VARIANT_WORDS.any { resultTitle.contains(it) && !request.contains(it) }) score -= 5
            Triple(result, score, index)
        }
            .filter { it.second > 0 }
            // Ties keep the search's own order, which is already relevance-ranked.
            .maxWithOrNull(compareBy<Triple<YouTubeSearchResult, Int, Int>> { it.second }.thenByDescending { it.third })
            ?.first
    }

    private fun artistMatches(have: String, want: String): Boolean =
        have.isNotEmpty() && (have.contains(want) || want.contains(have) ||
            // "Jamie Paige" vs "Jamie Paige - Topic", word overlap catches the rest.
            want.split(' ').filter { it.length > 2 }.let { words -> words.isNotEmpty() && words.all { have.contains(it) } })
}
