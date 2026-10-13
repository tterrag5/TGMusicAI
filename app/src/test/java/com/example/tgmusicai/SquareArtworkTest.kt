package com.example.tgmusicai

import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cover sparkle upgrades YouTube Music art to 1200 px and never takes a video frame (T038). */
class SquareArtworkTest {

    @Test
    fun youtubeMusicArtIsUpsized() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            YouTubeMusicBrowser.squareArtwork("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj")
        )
        assertEquals(
            "https://yt3.googleusercontent.com/xyz=w1200-h1200",
            YouTubeMusicBrowser.squareArtwork("https://yt3.googleusercontent.com/xyz")
        )
    }

    @Test
    fun videoFramesAreRejected() {
        assertNull(YouTubeMusicBrowser.squareArtwork("https://i.ytimg.com/vi/abc/hqdefault.jpg"))
        assertNull(YouTubeMusicBrowser.squareArtwork(null))
    }
}
