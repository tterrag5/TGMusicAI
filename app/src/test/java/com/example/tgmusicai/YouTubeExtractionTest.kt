package com.example.tgmusicai

import com.example.tgmusicai.data.youtube.DownloadProgressState
import com.example.tgmusicai.data.youtube.DownloadStatus
import com.example.tgmusicai.data.youtube.NewPipeOkHttpDownloader
import com.example.tgmusicai.data.youtube.YouTubeAudioStream
import com.example.tgmusicai.data.youtube.YouTubeExtractor
import com.example.tgmusicai.data.youtube.YouTubeSearchResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying YouTube cloud models, fallback endpoint configurations, stream format
 * filtering, and Piped/Invidious JSON response parsing. NewPipeExtractor is only used for search
 * (verified separately via live testing on a physical device); stream extraction goes through the
 * Piped/Invidious tiers tested here, since NewPipeExtractor's client-side stream resolution was
 * removed after being confirmed non-functional (blocked by YouTube's anti-bot measures).
 */
class YouTubeExtractionTest {

    @Test
    fun testYouTubeSearchResultDataClass() {
        val result = YouTubeSearchResult(
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            uploader = "Rick Astley",
            durationSeconds = 213,
            thumbnailUri = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        )

        assertEquals("dQw4w9WgXcQ", result.videoId)
        assertEquals("Never Gonna Give You Up", result.title)
        assertEquals("Rick Astley", result.uploader)
        assertEquals(213L, result.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", result.thumbnailUri)
    }

    @Test
    fun testYouTubeAudioStreamDataClass() {
        val stream = YouTubeAudioStream(
            url = "https://googlevideo.com/videoplayback?id=123",
            format = "m4a",
            bitrate = 128000
        )

        assertEquals("https://googlevideo.com/videoplayback?id=123", stream.url)
        assertEquals("m4a", stream.format)
        assertEquals(128000, stream.bitrate)
    }

    @Test
    fun testDownloadProgressStateDataClass() {
        val state = DownloadProgressState(
            videoId = "dQw4w9WgXcQ",
            status = DownloadStatus.DOWNLOADING,
            progressFraction = 0.45f
        )

        assertEquals("dQw4w9WgXcQ", state.videoId)
        assertEquals(DownloadStatus.DOWNLOADING, state.status)
        assertEquals(0.45f, state.progressFraction, 0.001f)
        assertNotNull(state)
    }

    @Test
    fun testDownloaderRealisticUserAgent() {
        assertTrue(
            NewPipeOkHttpDownloader.REALISTIC_USER_AGENT.contains("Mozilla/5.0")
        )
        assertTrue(
            YouTubeExtractor.REALISTIC_USER_AGENT.contains("Chrome")
        )
    }

    @Test
    fun testYouTubeAudioStreamFormatNormalization() {
        val streamM4a = YouTubeAudioStream(url = "https://example.com/audio.m4a", format = "m4a", bitrate = 128000)
        val streamWebm = YouTubeAudioStream(url = "https://example.com/audio.webm", format = "webm", bitrate = 160000)

        assertEquals("m4a", streamM4a.format)
        assertEquals("webm", streamWebm.format)
        assertTrue(streamWebm.bitrate > streamM4a.bitrate)
    }

    @Test
    fun testDownloadStatusEnumValues() {
        val statuses = DownloadStatus.entries
        assertTrue(statuses.contains(DownloadStatus.IDLE))
        assertTrue(statuses.contains(DownloadStatus.EXTRACTING))
        assertTrue(statuses.contains(DownloadStatus.DOWNLOADING))
        assertTrue(statuses.contains(DownloadStatus.COMPLETED))
        assertTrue(statuses.contains(DownloadStatus.FAILED))
    }

    @Test
    fun testParseDurationTextToSeconds() {
        assertEquals(225L, YouTubeExtractor.parseDurationTextToSeconds("3:45"))
        assertEquals(225L, YouTubeExtractor.parseDurationTextToSeconds("03:45"))
        assertEquals(3735L, YouTubeExtractor.parseDurationTextToSeconds("1:02:15"))
        assertEquals(180L, YouTubeExtractor.parseDurationTextToSeconds("180"))
        assertEquals(0L, YouTubeExtractor.parseDurationTextToSeconds(""))
    }

    @Test
    fun testPipedEndpointsCluster() {
        val endpoints = YouTubeExtractor.PIPED_ENDPOINTS
        assertTrue(endpoints.isNotEmpty())
        assertTrue(endpoints.all { it.startsWith("https://") })
    }

    @Test
    fun testInvidiousEndpointsCluster() {
        val endpoints = YouTubeExtractor.INVIDIOUS_ENDPOINTS
        assertTrue(endpoints.isNotEmpty())
        assertTrue(endpoints.all { it.startsWith("https://") })
    }

    @Test
    fun testAudioMimeTypeAndFormatFiltering() {
        assertTrue(YouTubeExtractor.isAudioMimeOrFormat("audio/mp4; codecs=\"mp4a.40.2\"", "m4a"))
        assertTrue(YouTubeExtractor.isAudioMimeOrFormat("audio/webm; codecs=\"opus\"", "webm"))
        assertTrue(YouTubeExtractor.isAudioMimeOrFormat("audio/aac", "aac"))
        assertTrue(YouTubeExtractor.isAudioMimeOrFormat("audio/m4a", "m4a"))

        assertFalse(YouTubeExtractor.isAudioMimeOrFormat("video/mp4", "mp4"))
        assertFalse(YouTubeExtractor.isAudioMimeOrFormat("video/webm", "webm"))
    }

    @Test
    fun testNormalizeAudioFormat() {
        assertEquals("webm", YouTubeExtractor.normalizeAudioFormat("audio/webm; codecs=\"opus\"", "webm"))
        assertEquals("m4a", YouTubeExtractor.normalizeAudioFormat("audio/mp4", "m4a"))
        assertEquals("aac", YouTubeExtractor.normalizeAudioFormat("audio/aac", "aac"))
    }

    @Test
    fun testPipedAudioStreamParsing() {
        val jsonArray = JSONArray().apply {
            put(JSONObject().apply {
                put("url", "https://piped.proxy/audio1.m4a")
                put("bitrate", 128000)
                put("mimeType", "audio/mp4")
                put("format", "m4a")
            })
            put(JSONObject().apply {
                put("url", "https://piped.proxy/audio2.webm")
                put("bitrate", 160000)
                put("mimeType", "audio/webm")
                put("format", "webm")
            })
            put(JSONObject().apply {
                put("url", "https://piped.proxy/video.mp4")
                put("bitrate", 500000)
                put("mimeType", "video/mp4")
                put("format", "mp4")
            })
        }

        // Each audio item yields both its direct URL and a synthesized proxy-fallback URL
        // (used when the direct googlevideo URL returns 403), so 2 audio items produce 4 streams.
        // The video/mp4 item must never be included.
        val streams = YouTubeExtractor.parsePipedAudioStreams(jsonArray)
        assertEquals(4, streams.size)
        assertEquals("m4a", streams[0].format)
        assertEquals(128000, streams[0].bitrate)
        assertEquals("https://piped.proxy/audio1.m4a", streams[0].url)
        assertEquals("m4a", streams[1].format)
        assertEquals(128000, streams[1].bitrate)
        assertTrue(streams[1].url.startsWith("${YouTubeExtractor.PIPED_ENDPOINTS.first()}/proxy?url="))
        assertEquals("webm", streams[2].format)
        assertEquals(160000, streams[2].bitrate)
        assertEquals("https://piped.proxy/audio2.webm", streams[2].url)
        assertEquals("webm", streams[3].format)
        assertEquals(160000, streams[3].bitrate)
        assertTrue(streams[3].url.startsWith("${YouTubeExtractor.PIPED_ENDPOINTS.first()}/proxy?url="))
        assertTrue(streams.none { it.url.contains("video.mp4") })
    }

    @Test
    fun testInvidiousAdaptiveFormatsParsing() {
        val jsonArray = JSONArray().apply {
            put(JSONObject().apply {
                put("url", "https://invidious.proxy/audio.m4a")
                put("type", "audio/mp4; codecs=\"mp4a.40.2\"")
                put("container", "m4a")
                put("bitrate", "128000")
            })
            put(JSONObject().apply {
                put("url", "https://invidious.proxy/video.mp4")
                put("type", "video/mp4")
                put("container", "mp4")
                put("bitrate", "800000")
            })
        }

        val streams = YouTubeExtractor.parseInvidiousAdaptiveFormats(jsonArray)
        assertEquals(1, streams.size)
        assertEquals("https://invidious.proxy/audio.m4a", streams[0].url)
        assertEquals("m4a", streams[0].format)
        assertEquals(128000, streams[0].bitrate)
    }

    @Test
    fun testVideoIdExtraction() {
        val extractor = YouTubeExtractor()

        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("dQw4w9WgXcQ"))
    }

    @Test
    fun testCandidateStreamSortingByBitrate() {
        val s1 = YouTubeAudioStream("https://example.com/1", "m4a", 128000)
        val s2 = YouTubeAudioStream("https://example.com/2", "webm", 160000)
        val s3 = YouTubeAudioStream("https://example.com/3", "m4a", 96000)

        val list = listOf(s1, s2, s3).sortedByDescending { it.bitrate }

        assertEquals(160000, list[0].bitrate)
        assertEquals(128000, list[1].bitrate)
        assertEquals(96000, list[2].bitrate)
    }
}
