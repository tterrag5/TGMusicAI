package com.example.tgmusicai.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tgmusicai.data.local.entity.Song
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * High-resolution album cover art scraper.
 * Queries iTunes Search API, MusicBrainz / Cover Art Archive, or YouTube thumbnails,
 * saves the artwork image locally to `Android/data/com.example.tgmusicai/files/Covers/{songId}.jpg`,
 * and updates [Song.artworkUri] in the Room database.
 */
class CoverArtScraper(
    private val context: Context,
    private val musicRepository: MusicRepository,
    private val youtubeExtractor: YouTubeExtractor = YouTubeExtractor()
) {

    private val TAG = "CoverArtScraper"

    /**
     * Shared HTTP client for every scraper source (iTunes, MusicBrainz, YouTube thumbnail,
     * image download). 15s connect/read timeouts are generous enough for slow mobile
     * connections while still failing fast enough that one dead source doesn't stall the
     * whole [scrapeAndSaveArtwork] fallback chain for too long.
     */
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        /**
         * Replaces iTunes 100x100 resolution thumbnail URL with 1000x1000 high-res URL string.
         */
        fun getHighResItunesUrl(rawUrl: String): String {
            if (rawUrl.isBlank()) return rawUrl
            return rawUrl.replace("100x100bb.jpg", "1000x1000bb.jpg")
                .replace("100x100bb.png", "1000x1000bb.png")
                .replace("100x100", "1000x1000")
        }
    }

    /**
     * Scrapes cover art for a [song] and downloads it to local storage.
     * Updates Room DB with the local file URI.
     */
    suspend fun scrapeAndSaveArtwork(song: Song): String? = withContext(Dispatchers.IO) {
        var imageUrl: String? = null

        // 1. Try iTunes Search API
        try {
            imageUrl = fetchFromItunes(song)
        } catch (e: Exception) {
            Log.e(TAG, "iTunes cover art fetch failed for ${song.title}", e)
        }

        // 2. Try MusicBrainz Search API
        if (imageUrl.isNullOrBlank()) {
            try {
                imageUrl = fetchFromMusicBrainz(song)
            } catch (e: Exception) {
                Log.e(TAG, "MusicBrainz cover art fetch failed for ${song.title}", e)
            }
        }

        // 3. Try YouTube Thumbnail Fallback
        if (imageUrl.isNullOrBlank()) {
            try {
                imageUrl = fetchFromYouTubeThumbnail(song)
            } catch (e: Exception) {
                Log.e(TAG, "YouTube thumbnail fetch failed for ${song.title}", e)
            }
        }

        if (imageUrl.isNullOrBlank()) {
            return@withContext null
        }

        // Download image to local Covers directory
        val localFilePath = downloadAndSaveImage(song.id, imageUrl)
        if (localFilePath != null) {
            musicRepository.updateSongArtwork(song.id, localFilePath)
            return@withContext localFilePath
        }

        return@withContext null
    }

    /**
     * Normalizes a title/artist string for loose comparison: lowercase, drop parenthetical/
     * bracketed suffixes (remix, feat., live, etc.), strip punctuation, collapse whitespace.
     */
    private fun normalizeForMatch(text: String): String =
        text.lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Whether a search result's [candidateTitle]/[candidateArtist] plausibly refers to [song],
     * rather than an unrelated track that merely shares a keyword. Search APIs used here
     * (iTunes especially) do loose keyword matching, not artist+title pairing, so without this
     * check the very first "match" is often a same-titled song by a different artist, a cover,
     * or a remix -- i.e. exactly the wrong-cover-art bug this guards against.
     */
    private fun matchesSong(candidateTitle: String, candidateArtist: String, song: Song): Boolean {
        val songTitle = normalizeForMatch(song.title)
        val candTitle = normalizeForMatch(candidateTitle)
        if (songTitle.isBlank() || candTitle.isBlank()) return false
        if (songTitle != candTitle && !candTitle.contains(songTitle) && !songTitle.contains(candTitle)) {
            return false
        }

        val songArtist = normalizeForMatch(song.artist)
        if (songArtist.isBlank() || songArtist == "unknown artist" || songArtist == "unknown") return true
        val candArtist = normalizeForMatch(candidateArtist)
        if (candArtist.isBlank()) return false
        return candArtist.contains(songArtist) || songArtist.contains(candArtist) ||
            candArtist.split(" ").firstOrNull() == songArtist.split(" ").firstOrNull()
    }

    /**
     * Queries iTunes Search API for artwork URL, accepting only a result whose track/artist
     * name actually matches [song] (see [matchesSong]).
     */
    private fun fetchFromItunes(song: Song): String? {
        val cleanTitle = song.title.replace(Regex("(?i)\\(.*\\)|\\[.*]"), "").trim()
        val cleanArtist = song.artist.replace(Regex("(?i)unknown.*"), "").trim()
        val term = URLEncoder.encode("$cleanArtist $cleanTitle", "UTF-8")

        val url = "https://itunes.apple.com/search?entity=song&limit=5&term=$term"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TGMusicAI/1.0")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val trackName = item.optString("trackName", "")
                            val artistName = item.optString("artistName", "")
                            if (!matchesSong(trackName, artistName, song)) continue
                            val rawArtworkUrl = item.optString("artworkUrl100", "")
                            if (rawArtworkUrl.isNotBlank()) {
                                return getHighResItunesUrl(rawArtworkUrl)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Queries MusicBrainz / Cover Art Archive API for front cover artwork.
     */
    private fun fetchFromMusicBrainz(song: Song): String? {
        val cleanTitle = song.title.replace(Regex("(?i)\\(.*\\)|\\[.*\\]"), "").trim()
        val cleanArtist = song.artist.replace(Regex("(?i)unknown.*"), "").trim()

        val query = "artist:${URLEncoder.encode(cleanArtist, "UTF-8")} AND recording:${URLEncoder.encode(cleanTitle, "UTF-8")}"
        val url = "https://musicbrainz.org/ws/2/recording/?query=$query&fmt=json"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TGMusicAI/1.0 (https://github.com/tterrag5/TGMusicAI)")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val recordings = json.optJSONArray("recordings")
                    if (recordings != null) {
                        for (i in 0 until recordings.length()) {
                            val rec = recordings.getJSONObject(i)
                            val recTitle = rec.optString("title", "")
                            val artistCredit = rec.optJSONArray("artist-credit")
                            val recArtist = if (artistCredit != null) {
                                (0 until artistCredit.length()).joinToString(" ") { idx ->
                                    artistCredit.getJSONObject(idx).optString("name", "")
                                }
                            } else ""
                            if (!matchesSong(recTitle, recArtist, song)) continue

                            val releases = rec.optJSONArray("releases")
                            if (releases != null && releases.length() > 0) {
                                val releaseId = releases.getJSONObject(0).optString("id", "")
                                if (releaseId.isNotBlank()) {
                                    return "https://coverartarchive.org/release/$releaseId/front"
                                }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Queries YouTube for thumbnail image URL.
     */
    private suspend fun fetchFromYouTubeThumbnail(song: Song): String? {
        val searchResults = youtubeExtractor.search("${song.artist} ${song.title}")
        // This is the last fallback tier (after iTunes and MusicBrainz both failed), so prefer a
        // verified title/uploader match but still fall back to the top hit rather than showing
        // no art at all -- YouTube's own search ranking for an "artist title" query is already
        // reasonably relevant, unlike iTunes' loose keyword search.
        val bestMatch = searchResults.firstOrNull { matchesSong(it.title, it.uploader, song) }
            ?: searchResults.firstOrNull()
        return bestMatch?.thumbnailUri
    }

    /**
     * Downloads an image from [imageUrl] and saves it locally to
     * `Android/data/com.example.tgmusicai/files/Covers/{songId}.jpg`.
     */
    private fun downloadAndSaveImage(songId: Long, imageUrl: String): String? {
        try {
            val coversDir = context.getExternalFilesDir("Covers") ?: File(context.filesDir, "Covers")
            if (!coversDir.exists()) {
                coversDir.mkdirs()
            }

            val targetFile = File(coversDir, "$songId.jpg")

            val request = Request.Builder().url(imageUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    return Uri.fromFile(targetFile).toString()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cover art image for songId $songId", e)
        }
        return null
    }
}
