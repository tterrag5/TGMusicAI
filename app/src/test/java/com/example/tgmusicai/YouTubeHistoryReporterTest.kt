package com.example.tgmusicai

import com.example.tgmusicai.data.youtube.YouTubeHistoryReporter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class YouTubeHistoryReporterTest {

    @Test
    fun `tracking url is read from the player response`() {
        val root = JSONObject(
            """{"playbackTracking": {"videostatsPlaybackUrl": {"baseUrl": "https://s.youtube.com/api/stats/playback?cl=1&docid=abc&ei=xyz"}}}"""
        )
        assertEquals(
            "https://s.youtube.com/api/stats/playback?cl=1&docid=abc&ei=xyz",
            YouTubeHistoryReporter.parsePlaybackTrackingUrl(root)
        )
    }

    @Test
    fun `a signed-out or unplayable response has no tracking url`() {
        assertNull(YouTubeHistoryReporter.parsePlaybackTrackingUrl(JSONObject("""{"playabilityStatus":{"status":"UNPLAYABLE"}}""")))
        assertNull(YouTubeHistoryReporter.parsePlaybackTrackingUrl(JSONObject("""{"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":""}}}""")))
    }

    @Test
    fun `ping url keeps the base parameters and adds ver, client and cpn`() {
        val url = YouTubeHistoryReporter.buildPlaybackPingUrl(
            "https://s.youtube.com/api/stats/playback?cl=1&docid=abc&ver=1",
            "ABCDEFGHIJKLMNOP"
        )!!.toHttpUrl()
        assertEquals("abc", url.queryParameter("docid"))
        assertEquals("2", url.queryParameter("ver"))
        assertEquals(1, url.queryParameterValues("ver").size)
        assertEquals("WEB_REMIX", url.queryParameter("c"))
        assertEquals("ABCDEFGHIJKLMNOP", url.queryParameter("cpn"))
    }

    @Test
    fun `cpn is 16 url-safe characters`() {
        val cpn = YouTubeHistoryReporter.newCpn(Random(42))
        assertEquals(16, cpn.length)
        assertTrue(cpn.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
