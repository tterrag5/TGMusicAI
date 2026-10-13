package com.example.tgmusicai

import com.example.tgmusicai.data.lyrics.KuGouLyricsClient
import com.example.tgmusicai.data.lyrics.LyricsProvider
import com.example.tgmusicai.data.lyrics.LyricsProviderSetting
import com.example.tgmusicai.data.lyrics.LyricsProviderSettings
import com.example.tgmusicai.data.lyrics.QQLyricsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderSettingsTest {

    @Test
    fun defaultsFollowMorpheOrderWithCaptionsOff() {
        val defaults = LyricsProviderSettings.DEFAULT
        assertEquals(
            listOf(
                LyricsProvider.YOUTUBE_MUSIC, LyricsProvider.APPLE_MUSIC, LyricsProvider.LRCLIB,
                LyricsProvider.QQ, LyricsProvider.NETEASE, LyricsProvider.KUGOU,
                LyricsProvider.FILE_TAGS, LyricsProvider.YOUTUBE_CAPTIONS,
            ),
            defaults.map { it.provider },
        )
        assertFalse(defaults.last().enabled)
        assertTrue(defaults.dropLast(1).all { it.enabled })
    }

    @Test
    fun blankOrMissingFallsBackToDefaults() {
        assertEquals(LyricsProviderSettings.DEFAULT, LyricsProviderSettings.decode(null))
        assertEquals(LyricsProviderSettings.DEFAULT, LyricsProviderSettings.decode(""))
    }

    @Test
    fun roundTripKeepsOrderAndSwitches() {
        val custom = listOf(
            LyricsProviderSetting(LyricsProvider.NETEASE, true),
            LyricsProviderSetting(LyricsProvider.YOUTUBE_MUSIC, false),
        ) + LyricsProvider.entries
            .filter { it != LyricsProvider.NETEASE && it != LyricsProvider.YOUTUBE_MUSIC }
            .map { LyricsProviderSetting(it, true) }
        assertEquals(custom, LyricsProviderSettings.decode(LyricsProviderSettings.encode(custom)))
    }

    @Test
    fun unknownIdsAreDroppedAndNewProvidersAppended() {
        val decoded = LyricsProviderSettings.decode("kugou:1,soda:1,ytmusic:0,kugou:0")
        assertEquals(LyricsProvider.KUGOU, decoded[0].provider)
        assertTrue("first occurrence wins", decoded[0].enabled)
        assertEquals(LyricsProviderSetting(LyricsProvider.YOUTUBE_MUSIC, false), decoded[1])
        assertEquals(LyricsProvider.entries.size, decoded.size)
        assertEquals(LyricsProvider.entries.toSet(), decoded.map { it.provider }.toSet())
    }

    @Test
    fun qqEscapesAreUndone() {
        assertEquals("I'm \"here\" & <now>", QQLyricsClient.unescape("I&#39;m &quot;here&quot; &amp; &lt;now&gt;"))
    }

    @Test
    fun creditLinesFromQqAndNetEaseAreDropped() {
        val lrc = """
            [ti:Shape of You]
            [00:02.38]Lyrics by：Ed Sheeran/Steve Mac
            [00:04.77]Composed by：Ed Sheeran
            [00:07.16]Produced by：Steve Mac
            [00:00.00]作词 : 某人
            [00:12.00]The club isn't the best place to find a lover
        """.trimIndent()
        assertEquals("[00:12.00]The club isn't the best place to find a lover", KuGouLyricsClient.cleanLrc(lrc))
        assertNull(KuGouLyricsClient.cleanLrc("[00:01.00]Composed by: nobody"))
    }
}
