package com.example.tgmusicai

import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of YouTube Music's `/next` radio response. The fixture keeps the real response's nesting
 * (captured 2026-10-11 for `RDAMVMdQw4w9WgXcQ`), trimmed to a few entries.
 */
class YouTubeRadioParseTest {

    private fun entry(videoId: String, title: String, artist: String, length: String): String = """
        {"playlistPanelVideoRenderer": {
          "videoId": "$videoId",
          "title": {"runs": [{"text": "$title"}]},
          "shortBylineText": {"runs": [{"text": "$artist"}]},
          "longBylineText": {"runs": [{"text": "$artist"}, {"text": " • "}, {"text": "1.8B views"}]},
          "lengthText": {"runs": [{"text": "$length"}]},
          "thumbnail": {"thumbnails": [
            {"url": "https://i.ytimg.com/vi/$videoId/default.jpg"},
            {"url": "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"}
          ]}
        }}
    """

    private fun response(vararg entries: String) = JSONObject("""
        {"contents": {"singleColumnMusicWatchNextResultsRenderer": {"tabbedRenderer": {
          "watchNextTabbedResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
            "musicQueueRenderer": {"content": {"playlistPanelRenderer": {
              "contents": [${entries.joinToString(",")}]
            }}}
          }}}]}
        }}}}
    """)

    @Test
    fun `seed is skipped and the rest keep their order`() {
        val root = response(
            entry("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "3:34"),
            entry("BeyEGebJ1l4", "Whenever You Need Somebody (Radio Edit)", "Rick Astley", "3:28"),
            entry("6RUIeX6UCT8", "The Boys Of Summer", "Don Henley", "4:46"),
        )
        val tracks = YouTubeMusicBrowser.parseRadioTracks(root, "dQw4w9WgXcQ")
        assertEquals(listOf("BeyEGebJ1l4", "6RUIeX6UCT8"), tracks.map { it.videoId })
        val first = tracks.first()
        assertEquals("Whenever You Need Somebody (Radio Edit)", first.title)
        assertEquals("Rick Astley", first.uploader)
        assertEquals(208L, first.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/BeyEGebJ1l4/hqdefault.jpg", first.thumbnailUri)
    }

    @Test
    fun `duplicates and entries without an id or title are dropped`() {
        val root = response(
            entry("aaaaaaaaaaa", "One", "A", "3:00"),
            entry("aaaaaaaaaaa", "One again", "A", "3:00"),
            entry("", "No id", "B", "3:00"),
            entry("bbbbbbbbbbb", "", "C", "3:00"),
            entry("ccccccccccc", "Two", "D", "1:02:03"),
        )
        val tracks = YouTubeMusicBrowser.parseRadioTracks(root, "seed")
        assertEquals(listOf("aaaaaaaaaaa", "ccccccccccc"), tracks.map { it.videoId })
        assertEquals(3723L, tracks[1].durationSeconds)
    }

    @Test
    fun `an unrelated response yields nothing`() {
        assertTrue(YouTubeMusicBrowser.parseRadioTracks(JSONObject("{}"), "seed").isEmpty())
    }
}
