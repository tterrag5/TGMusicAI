package com.example.tgmusicai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.tgmusicai.data.lyrics.KuGouLyricsClient
import com.example.tgmusicai.data.repository.CloudRecommendationSource
import com.example.tgmusicai.data.repository.LyricsRepository
import com.example.tgmusicai.data.youtube.YouTubeMusicBrowser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The synced lyric sources against the real services, on niche Vocaloid (where LrcLib found 1 of
 * 10 synced on 2026-10-11) plus mainstream controls. Needs network access.
 */
@RunWith(AndroidJUnit4::class)
class SyncedLyricsSourcesIntegrationTest {

    private val vocaloid = listOf(
        "DECO*27" to "ヴァンパイア",
        "Kikuo" to "愛して愛して愛して",
        "PinocchioP" to "ノンセンス文学",
        "Hiiragi Magnetite" to "メズマライザー",
        "Nanahoshi Kangengakudan" to "ロキ",
        "Kairiki bear" to "ヴァンパイア",
        "MARETU" to "ハートアラモード",
        "Kemu" to "六兆年と一夜物語",
    )

    @Test
    fun youTubeMusicTimedLyricsCoverMostOfANicheSet() = runBlocking {
        val browser = YouTubeMusicBrowser()
        var timed = 0
        for ((artist, title) in vocaloid) {
            val match = browser.searchSongs("$title $artist").firstOrNull()
            val lyrics = match?.let { browser.fetchLyrics(it.videoId) }
            val lines = lyrics?.takeIf { it.isTimed }?.let { LyricsRepository.parseLyrics(it.text) }.orEmpty()
            Log.i(TAG, "$artist - $title -> ${match?.title} / ${match?.uploader}: ${lines.size} timed lines (${lyrics?.source})")
            if (lines.size >= 10) {
                timed++
                // Line times must run forwards and each line must end after it starts.
                assertTrue(lines.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs })
                assertTrue(lines.all { line -> line.endMs == null || line.endMs!! >= line.timestampMs })
            }
            delay(1_000)
        }
        Log.i(TAG, "YouTube Music timed: $timed / ${vocaloid.size}")
        assertTrue("Expected YouTube Music timed lyrics for most of the set, got $timed", timed >= 5)
    }

    @Test
    fun mainstreamControlHasTimedLyricsWithLineEnds() = runBlocking {
        val browser = YouTubeMusicBrowser()
        val match = browser.searchSongs("Karma Police Radiohead")
            .first { CloudRecommendationSource.isSameTrack("Karma Police", "Radiohead", it) }
        val lyrics = browser.fetchLyrics(match.videoId)
        assertTrue("No timed lyrics for a mainstream control", lyrics?.isTimed == true)
        val lines = LyricsRepository.parseLyrics(lyrics!!.text)
        assertTrue(lines.count { it.endMs != null } >= lines.size / 2)
    }

    @Test
    fun kuGouFindsAJapaneseTrack() {
        val lrc = KuGouLyricsClient().fetch("六兆年と一夜物語", "kemu", 0)
        Log.i(TAG, "KuGou: ${lrc?.lines()?.size} lines")
        assertTrue("KuGou returned nothing for a catalogue track", LyricsRepository.isSynced(lrc))
    }

    private companion object {
        const val TAG = "SyncedLyricsSources"
    }
}
