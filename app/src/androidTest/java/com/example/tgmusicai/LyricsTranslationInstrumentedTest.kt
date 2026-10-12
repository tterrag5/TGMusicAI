package com.example.tgmusicai

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tgmusicai.data.local.AppDatabase
import com.example.tgmusicai.data.repository.LyricsRepository
import com.example.tgmusicai.data.repository.MusicRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device lyrics translation, exercised through the real [LyricsRepository.translateLyrics].
 *
 * This is an instrumented test and not a JVM unit test because it cannot be one. ML Kit translation
 * needs the Android runtime and downloads its model through Play Services, and this module sets
 * `testOptions.unitTests.isReturnDefaultValues = true`, so under a JVM unit test every framework
 * call returns a default and the pipeline under test never runs at all -- the test would pass
 * without translating anything.
 *
 * The corpus is niche Japanese Vocaloid rather than English pop, and that choice found a real bug.
 * `translateLyrics` pinned its *source* language to English, so a Japanese song was translated
 * English-to-English and most of the text came back untouched: on screen, identical to the
 * translator doing nothing, with no error and no log line. An English-lyrics corpus cannot detect
 * that, because for English input the wrong assumption is accidentally correct. These tests now
 * cover the direction that was broken.
 *
 * Needs network access the first time, to fetch the ja/en model; it is cached on the device after
 * that. Model download failure is a skip, not a failure -- see `CLAUDE.md` on instrumented tests
 * and the environment.
 */
@RunWith(AndroidJUnit4::class)
class LyricsTranslationInstrumentedTest {

    private lateinit var database: AppDatabase
    private lateinit var lyricsRepository: LyricsRepository

    /**
     * Short excerpts in the register these songs actually use: dense kanji/kana mixtures, repeated
     * phrases, and the long-vowel and small-kana forms that a naive tokenizer mishandles. Kept to a
     * few lines each -- the subject under test is the translation pipeline, not the lyrics.
     */
    private val vocaloidJapanese = listOf(
        "愛して愛して愛して",   // "love me, love me, love me"
        "きみがすきだよ",               // "I like you"
        "夜が来る前に",                     // "before the night comes"
        "この世界は回る",               // "this world turns"
        "さようなら、またあした",  // with full-width comma
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // In-memory: translateLyrics never touches the database, but LyricsRepository needs a
        // MusicRepository to construct, and a real one keeps the test honest about the public API.
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val musicRepository = MusicRepository(
            songDao = database.songDao(),
            playlistDao = database.playlistDao(),
            songStatsDao = database.songStatsDao(),
            alarmDao = database.alarmDao(),
        )
        lyricsRepository = LyricsRepository(context, musicRepository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun japaneseVocaloidLyricsTranslateIntoEnglish() = runBlocking {
        val translated = lyricsRepository.translateLyrics(vocaloidJapanese, "english")

        assumeTrue(
            "ML Kit could not load the ja/en model on this device; environment, not a regression",
            translated != null,
        )
        val result = translated!!

        assertEquals("every line must come back, in order", vocaloidJapanese.size, result.size)

        // The regression this test exists for: with the source pinned to English, ML Kit handed the
        // Japanese straight back, so the output was character-for-character the input.
        val changedLines = result.indices.count { result[it] != vocaloidJapanese[it] }
        assertTrue(
            "no line changed at all -- the source language is being mis-assumed again, " +
                "which is what pinning it to English used to do",
            changedLines > 0,
        )

        // Translated English should actually contain Latin letters, not just rearranged kana.
        val withLatin = result.count { line -> line.any { it in 'a'..'z' || it in 'A'..'Z' } }
        assertTrue("translated output should read as English, got: $result", withLatin > 0)
    }

    @Test
    fun translatingIntoTheLyricsOwnLanguageIsANoOp() = runBlocking {
        // Japanese lyrics asked for in Japanese: there is nothing to do, and no model to fetch.
        val translated = lyricsRepository.translateLyrics(vocaloidJapanese, "japanese")

        assertNotNull("a same-language request should succeed trivially, not fail", translated)
        assertEquals("the lines must be returned untouched", vocaloidJapanese, translated)
    }

    @Test
    fun blankLinesAndMarkersSurviveTranslation() = runBlocking {
        val withGaps = listOf(
            vocaloidJapanese[0],
            "",
            "   ",
            LyricsRepository.MUSIC_MARKER,
            vocaloidJapanese[1],
        )

        val translated = lyricsRepository.translateLyrics(withGaps, "english")
        assumeTrue("ML Kit model unavailable on this device", translated != null)
        val result = translated!!

        assertEquals("line count and order must be preserved", withGaps.size, result.size)
        assertEquals("a blank line stays blank", "", result[1])
        assertEquals("a whitespace-only line is left alone", "   ", result[2])
    }

    @Test
    fun specialCharactersAndLongLinesDoNotBreakTheBatch() = runBlocking {
        val awkward = listOf(
            "〜〜〜",                                     // wave dashes only
            "！？。、「」",                   // full-width punctuation only
            "愛".repeat(400),                     // pathologically long line
            "Vocaloid ピアノ～PIANO～ mix 2024",  // mixed scripts and digits
            "🎵🎤",                               // emoji
        )

        val translated = lyricsRepository.translateLyrics(awkward, "english")
        assumeTrue("ML Kit model unavailable on this device", translated != null)

        assertEquals(
            "a batch of awkward input must still return one entry per line",
            awkward.size,
            translated!!.size,
        )
    }

    @Test
    fun aFullSongLengthBatchCompletes() = runBlocking {
        // A real Vocaloid track runs well over a hundred lines, and the lines repeat heavily. This
        // is the shape that would expose a leak or a per-line model reload: translateLyrics opens
        // one Translator for the batch and closes it in a finally block.
        val fullSong = List(FULL_SONG_LINES) { vocaloidJapanese[it % vocaloidJapanese.size] }

        val translated = lyricsRepository.translateLyrics(fullSong, "english")
        assumeTrue("ML Kit model unavailable on this device", translated != null)

        assertEquals(FULL_SONG_LINES, translated!!.size)
        assertTrue(
            "the batch should translate, not return a page of blanks",
            translated.count { it.isNotBlank() } > FULL_SONG_LINES / 2,
        )
    }

    @Test
    fun anUnsupportedTargetLanguageIsRefusedRatherThanGuessed() = runBlocking {
        // ML Kit has no on-device model for Klingon; the contract is null, not a wrong answer.
        val translated = lyricsRepository.translateLyrics(vocaloidJapanese, "tlh")
        assertEquals("an unsupported target must return null", null, translated)
    }

    @Test
    fun anEmptyLyricSetReturnsNull() = runBlocking {
        assertEquals(null, lyricsRepository.translateLyrics(emptyList(), "english"))
    }

    private companion object {
        /** Roughly the line count of a real Vocaloid track, choruses included. */
        const val FULL_SONG_LINES = 120
    }
}
