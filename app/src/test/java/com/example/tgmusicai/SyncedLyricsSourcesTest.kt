package com.example.tgmusicai

import com.example.tgmusicai.data.lyrics.BetterLyricsClient
import com.example.tgmusicai.data.lyrics.KuGouLyricsClient
import com.example.tgmusicai.data.repository.LyricsRepository
import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures trimmed from live responses captured 2026-10-11. */
class SyncedLyricsSourcesTest {

    // --- YouTube Music ------------------------------------------------------------------------

    private val ytmTimed = JSONObject(
        """
        {"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{
          "timedLyricsData":[
            {"lyricLine":"♪","cueRange":{"startTimeMilliseconds":"0","endTimeMilliseconds":"26750","metadata":{"id":"0"}}},
            {"lyricLine":"Karma police","cueRange":{"startTimeMilliseconds":"26750","endTimeMilliseconds":"31190","metadata":{"id":"1"}}},
            {"lyricLine":"Arrest this man","cueRange":{"startTimeMilliseconds":"31190","endTimeMilliseconds":"33330","metadata":{"id":"2"}}}
          ],
          "sourceMessage":"Source: Musixmatch"}}}}}}}}}
        """
    )

    @Test
    fun `youtube music timed lyrics become LRC with line ends`() {
        val lyrics = YouTubeMusicBrowser.parseLyricsResponse(ytmTimed)!!
        assertTrue(lyrics.isTimed)
        assertEquals("Source: Musixmatch", lyrics.source)
        assertEquals(
            "[00:00.00]♪<00:26.75>\n[00:26.75]Karma police<00:31.19>\n[00:31.19]Arrest this man<00:33.33>",
            lyrics.text
        )
    }

    @Test
    fun `youtube music line ends survive parsing`() {
        val lines = LyricsRepository.parseLyrics(YouTubeMusicBrowser.parseLyricsResponse(ytmTimed)!!.text)
        assertEquals(listOf("♪", "Karma police", "Arrest this man"), lines.map { it.text })
        assertEquals(31_190L, lines[1].endMs)
        assertFalse(lines[1].hasRealWordTimings)
    }

    @Test
    fun `lyrics tab id is found in a next response`() {
        val next = JSONObject("""{"tabs":[{"endpoint":{"browseEndpoint":{"browseId":"FEmusic_x"}}},{"endpoint":{"browseEndpoint":{"browseId":"MPLYt_yXhSI4FCUo6-6"}}}]}""")
        assertEquals("MPLYt_yXhSI4FCUo6-6", YouTubeMusicBrowser.lyricsBrowseId(next))
        assertNull(YouTubeMusicBrowser.lyricsBrowseId(JSONObject("{}")))
    }

    @Test
    fun `no lyrics in a browse response is null`() {
        assertNull(YouTubeMusicBrowser.parseLyricsResponse(JSONObject("""{"contents":{}}""")))
    }

    // --- Better Lyrics (TTML) -----------------------------------------------------------------

    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body dur="4:24.067"><div begin="26.786" end="46.905"><p begin="26.786" end="30.557" ttm:agent="v1"><span begin="26.786" end="28.155">Karma</span> <span begin="28.453" end="30.557">police</span></p><p begin="31.061" end="33.401"><span begin="31.061" end="31.776">Arrest</span> <span begin="31.776" end="32.459">this</span> <span begin="32.459" end="33.401">man,</span><span ttm:role="x-bg"><span begin="32.9" end="33.3">(ooh)</span></span></p><p begin="1:02.5" end="1:04"><span begin="1:02.5" end="1:03">Ka</span><span begin="1:03" end="1:04">rma</span></p></div></body></tt>"""

    @Test
    fun `ttml becomes enhanced LRC with a time on every word`() {
        assertEquals(
            "[00:26.78]<00:26.78>Karma <00:28.45>police<00:30.55>\n" +
                "[00:31.06]<00:31.06>Arrest <00:31.77>this <00:32.45>man,<00:33.40>\n" +
                "[01:02.50]<01:02.50>Ka<01:03.00>rma<01:04.00>",
            BetterLyricsClient.ttmlToEnhancedLrc(ttml)
        )
    }

    @Test
    fun `real word timings reach the line, words and syllables alike`() {
        val lines = LyricsRepository.withWordTimings(LyricsRepository.parseLyrics(BetterLyricsClient.ttmlToEnhancedLrc(ttml)))
        val karma = lines[0]
        assertTrue(karma.hasRealWordTimings)
        assertEquals("Karma police", karma.text)
        assertEquals(listOf("Karma", "police"), karma.words.map { it.text })
        assertEquals(listOf(26_780L, 28_450L), karma.words.map { it.startMs })
        assertEquals(30_550L, karma.words[1].endMs)
        val syllables = lines[2]
        assertEquals("Karma", syllables.text)
        assertEquals(listOf("Ka", "rma"), syllables.words.map { it.text })
    }

    @Test
    fun `ttml clock forms`() {
        assertEquals(12_345L, BetterLyricsClient.parseClock("12.345"))
        assertEquals(12_345L, BetterLyricsClient.parseClock("12.345s"))
        assertEquals(62_500L, BetterLyricsClient.parseClock("1:02.5"))
        assertEquals(3_723_400L, BetterLyricsClient.parseClock("1:02:03.4"))
        assertNull(BetterLyricsClient.parseClock("soon"))
    }

    // --- Enhanced LRC parsing -----------------------------------------------------------------

    @Test
    fun `estimated sweep finishes at the line end, not the next line`() {
        val lines = LyricsRepository.withWordTimings(
            LyricsRepository.parseLyrics("[00:10.00]one two three four<00:12.00>\n[00:20.00]next line")
        )
        assertFalse(lines[0].hasRealWordTimings)
        assertEquals(12_000L, lines[0].words.last().endMs)
    }

    @Test
    fun `a real last word without a line end is bounded`() {
        val lines = LyricsRepository.withWordTimings(LyricsRepository.parseLyrics("[00:10.00]<00:10.00>one <00:11.00>two\n[00:40.00]next"))
        val last = lines[0].words.last()
        assertTrue(last.durationMs in 1..2_000)
    }

    @Test
    fun `synced detection`() {
        assertTrue(LyricsRepository.isSynced("[00:01.00]hello"))
        assertFalse(LyricsRepository.isSynced("hello\nworld"))
        assertFalse(LyricsRepository.isSynced(null))
    }

    @Test
    fun `a gap after a known line end is marked as music sooner`() {
        val lines = LyricsRepository.withInstrumentalMarkers(
            LyricsRepository.parseLyrics("[00:01.00]sung<00:03.00>\n[00:10.00]again")
        )
        assertTrue(lines.any { it.isInstrumental })
        val unknownEnd = LyricsRepository.withInstrumentalMarkers(
            LyricsRepository.parseLyrics("[00:01.00]sung\n[00:10.00]again")
        )
        assertTrue(unknownEnd.none { it.isInstrumental })
    }

    // --- KuGou --------------------------------------------------------------------------------

    @Test
    fun `kugou keyword drops bracketed asides`() {
        assertEquals("ヴァンパイア - DECO*27", KuGouLyricsClient.keyword("ヴァンパイア (feat. 初音ミク)", "DECO*27"))
        assertEquals("Song - A、B", KuGouLyricsClient.keyword("Song", "A & B"))
    }

    @Test
    fun `kugou lrc keeps timed lyric lines only`() {
        val lrc = "[id:\$00000000]\n[ar:kemu]\n[00:00.00]作词 : kemu\n[00:00.50]作曲 : kemu\n[00:12.34]name mo nai\n[00:15.00]hai iro"
        assertEquals("[00:12.34]name mo nai\n[00:15.00]hai iro", KuGouLyricsClient.cleanLrc(lrc))
        assertNull(KuGouLyricsClient.cleanLrc("[ar:x]\n[ti:y]"))
    }
}

class LyricsMatchTest {
    private fun match(t: String, a: String, d: Long, ct: String, ca: String, cd: Long) =
        LyricsRepository.isLyricsMatch(t, a, d, ct, ca, cd)

    @Test
    fun `bilingual youtube titles match the library title`() {
        // Seen live: the library tag says Hiiragi Magnetite, YouTube credits 32ki & the singers.
        assertTrue(match("メズマライザー", "Hiiragi Magnetite", 156, "メズマライザー - Mesmerizer (feat. Hatsune Miku&Kasane Teto)", "32ki, Hatsune Miku & Kasane Teto", 157))
        assertTrue(match("Karma Police", "Radiohead", 0, "Karma Police (Remastered)", "Radiohead", 264))
    }

    @Test
    fun `a cover with a different length and artist is rejected`() {
        assertFalse(match("ヴァンパイア", "Kairiki bear", 230, "Vampire", "Lydia the Bard", 201))
        assertFalse(match("ヴァンパイア", "Kairiki bear", 230, "ヴァンパイア [cover]", "Someone", 199))
    }

    @Test
    fun `a different song is rejected even by the same artist`() {
        assertFalse(match("Karma Police", "Radiohead", 264, "Street Spirit", "Radiohead", 264))
    }
}
