package com.example.tgmusicai.data.sponsorblock

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Looks up the crowd-sourced non-music segments of a YouTube video, so playback can skip past the
 * talking, sponsor reads and long intros that are baked into a lot of YouTube-sourced audio.
 *
 * Queries the public SponsorBlock database, which needs no API key and no account.
 *
 * Lookups go through the hash-prefix endpoint rather than asking about a video by id. The client
 * sends only the first four hex characters of the video id's SHA-256, the server answers with every
 * video whose hash starts that way, and the match is made locally -- so the server never learns
 * which video is being played. It costs a slightly larger response and nothing else.
 *
 * Every failure returns an empty segment list. A track whose segments could not be fetched simply
 * plays in full, which is how it played before this existed.
 */
class SponsorBlockManager(
    private val client: OkHttpClient = defaultClient()
) {

    /** A span of a video to skip, in milliseconds from the start, plus why it is being skipped. */
    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val category: String,
        /** SponsorBlock's own id for this segment, used to avoid skipping the same one twice. */
        val id: String
    ) {
        /** Human-readable reason, shown when playback skips ahead so the jump is not a mystery. */
        val displayName: String
            get() = CATEGORY_LABELS[category] ?: "non-music section"
    }

    /**
     * Returns the segments to skip for [videoId], newest data each call.
     *
     * Results are not cached here: [com.example.tgmusicai.playback.PlaybackService] fetches once
     * per track as it starts and holds the list for that track's duration, which is the only
     * lifetime the data is needed for.
     */
    suspend fun fetchSegments(
        videoId: String,
        categories: Set<String> = DEFAULT_CATEGORIES
    ): List<Segment> = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || categories.isEmpty()) return@withContext emptyList()

        try {
            val prefix = sha256Prefix(videoId)
            // The endpoint takes its category filter as a JSON array in the query string, so the
            // value has to be both JSON-encoded and URL-encoded.
            val categoryParam = URLEncoder.encode(JSONArray(categories.toList()).toString(), "UTF-8")

            val url = "$API_BASE/api/skipSegments/$prefix?categories=$categoryParam"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 404 is the ordinary answer for "no submissions for any video in this bucket".
                    if (response.code != 404) {
                        Log.d(TAG, "SponsorBlock returned ${response.code} for prefix $prefix")
                    }
                    return@withContext emptyList()
                }
                response.body?.string()
            } ?: return@withContext emptyList()

            parseSegments(body, videoId, categories)
        } catch (e: Throwable) {
            Log.d(TAG, "Could not fetch SponsorBlock segments for $videoId", e)
            emptyList()
        }
    }

    /**
     * Pulls [videoId]'s segments out of a hash-prefix response, which carries every video sharing
     * that prefix. Split out from the network call so it can be unit-tested against real response
     * shapes without a server.
     */
    fun parseSegments(json: String, videoId: String, categories: Set<String>): List<Segment> = try {
        val videos = JSONArray(json)
        val result = mutableListOf<Segment>()
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            if (video.optString("videoID") != videoId) continue
            val segments = video.optJSONArray("segments") ?: continue
            for (j in 0 until segments.length()) {
                val entry = segments.optJSONObject(j) ?: continue
                // actionType "skip" is the only one meaningful here. "mute", "poi" and "full"
                // describe things this player does not do, and treating them as skips would cut
                // out audio the user wanted.
                if (entry.optString("actionType", "skip") != "skip") continue
                val category = entry.optString("category")
                if (category !in categories) continue
                val bounds = entry.optJSONArray("segment") ?: continue
                if (bounds.length() < 2) continue
                val startMs = (bounds.optDouble(0, -1.0) * 1000).toLong()
                val endMs = (bounds.optDouble(1, -1.0) * 1000).toLong()
                if (startMs < 0 || endMs <= startMs) continue
                // A segment shorter than this is not worth a seek: the seek itself is more
                // disruptive than the fraction of a second it would save.
                if (endMs - startMs < MIN_SEGMENT_MS) continue
                result.add(
                    Segment(
                        startMs = startMs,
                        endMs = endMs,
                        category = category,
                        id = entry.optString("UUID", "$videoId:$startMs")
                    )
                )
            }
        }
        result.sortedBy { it.startMs }
    } catch (e: Throwable) {
        Log.d(TAG, "Could not parse a SponsorBlock response", e)
        emptyList()
    }

    private fun sha256Prefix(videoId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.substring(0, HASH_PREFIX_LENGTH)
    }

    companion object {
        private const val TAG = "SponsorBlock"
        private const val API_BASE = "https://sponsor.ajay.app"

        /**
         * Four characters is what SponsorBlock's own clients use: enough buckets that responses
         * stay small, few enough that a single request covers thousands of videos and reveals
         * nothing about which one was asked for.
         */
        private const val HASH_PREFIX_LENGTH = 4

        private const val USER_AGENT = "TGMusicAI"

        /** Skipping less than a second costs more in seek disruption than it saves. */
        private const val MIN_SEGMENT_MS = 1_000L

        /**
         * Categories skipped unless the user narrows them.
         *
         * `music_offtopic` is the one that matters most for a music player -- it marks the
         * non-music parts of a music video. The rest cover the sponsor reads and channel intros
         * common on uploads that are not official releases. Deliberately omitted are `filler`,
         * which is often judgement-call editing rather than clearly unwanted, and `poi_highlight`,
         * which marks a point rather than a span to remove.
         */
        val DEFAULT_CATEGORIES = setOf(
            "music_offtopic",
            "sponsor",
            "selfpromo",
            "intro",
            "outro",
            "interaction"
        )

        /** Every category a user may switch on, with the label shown in Settings. */
        val CATEGORY_LABELS = linkedMapOf(
            "music_offtopic" to "Non-music section",
            "sponsor" to "Sponsor",
            "selfpromo" to "Self-promotion",
            "intro" to "Intro",
            "outro" to "Outro",
            "interaction" to "Subscribe reminder",
            "preview" to "Preview / recap",
            "filler" to "Filler"
        )

        private fun defaultClient() = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
