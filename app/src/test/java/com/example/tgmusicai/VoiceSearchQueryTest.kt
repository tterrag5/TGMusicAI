package com.example.tgmusicai

import com.example.tgmusicai.playback.VoiceSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Choosing what to search for from a voice assistant's intent extras.
 *
 * The thing worth pinning down is that this returns one field rather than a joined string.
 * `PlaybackService.searchSongsAndPlaylists` matches a single string through a cascade -- exact
 * title, artist contains, title contains, then YouTube -- so a joined "Title Artist" matches no
 * title and no artist, and a track sitting on the device gets fetched over the network instead.
 */
class VoiceSearchQueryTest {

    private val audioFocus = "vnd.android.cursor.item/audio"
    private val artistFocus = "vnd.android.cursor.item/artist"
    private val albumFocus = "vnd.android.cursor.item/album"
    private val genreFocus = "vnd.android.cursor.item/genre"

    @Test
    fun aTrackRequestSearchesTheTitleAloneRatherThanTitlePlusArtist() {
        // "Hey Google, play Senbonzakura by Hatsune Miku on TGMusic"
        val query = VoiceSearchQuery.build(
            focus = audioFocus,
            title = "Senbonzakura",
            artist = "Hatsune Miku",
            album = null,
            genre = null,
            rawQuery = "Senbonzakura Hatsune Miku",
        )

        assertEquals("Senbonzakura", query)
    }

    @Test
    fun anArtistRequestSearchesTheArtist() {
        val query = VoiceSearchQuery.build(
            focus = artistFocus,
            title = null,
            artist = "MARETU",
            album = null,
            genre = null,
            rawQuery = "MARETU",
        )

        assertEquals("MARETU", query)
    }

    @Test
    fun anAlbumRequestFallsBackToTheArtistWhenNoAlbumIsGiven() {
        // Assistants routinely send a focus without the field that focus names.
        val query = VoiceSearchQuery.build(
            focus = albumFocus,
            title = null,
            artist = "Kikuo",
            album = null,
            genre = null,
            rawQuery = "that Kikuo album",
        )

        assertEquals("Kikuo", query)
    }

    @Test
    fun aGenreRequestSearchesTheGenre() {
        // "Hey Gemini, play Vocaloid songs on TGMusicAI"
        val query = VoiceSearchQuery.build(
            focus = genreFocus,
            title = null,
            artist = null,
            album = null,
            genre = "Vocaloid",
            rawQuery = "Vocaloid songs",
        )

        assertEquals("Vocaloid", query)
    }

    @Test
    fun anUnstructuredRequestUsesTheSpokenWords() {
        // The assistant could not tell what was being asked for, so what the user said is the best
        // available answer.
        val query = VoiceSearchQuery.build(
            focus = "vnd.android.cursor.item/*",
            title = null,
            artist = null,
            album = null,
            genre = null,
            rawQuery = "something upbeat",
        )

        assertEquals("something upbeat", query)
    }

    @Test
    fun noFocusAtAllStillUsesTheSpokenWords() {
        val query = VoiceSearchQuery.build(
            focus = null,
            title = null,
            artist = null,
            album = null,
            genre = null,
            rawQuery = "メズマライザー",
        )

        assertEquals("メズマライザー", query)
    }

    @Test
    fun anUnrecognisedFocusIsTreatedAsUnstructuredRatherThanGuessedAt() {
        val query = VoiceSearchQuery.build(
            focus = "vnd.android.cursor.item/playlist",
            title = "ignored",
            artist = null,
            album = null,
            genre = null,
            rawQuery = "my evening mix",
        )

        assertEquals("my evening mix", query)
    }

    @Test
    fun aBlankFieldDoesNotWinItsPositionAndThrowAwayTheFallback() {
        // The bug this guards: `?:` only catches null, so an empty-string title would be chosen and
        // then discarded, losing a usable artist and dropping the request entirely.
        val query = VoiceSearchQuery.build(
            focus = audioFocus,
            title = "   ",
            artist = "PinocchioP",
            album = null,
            genre = null,
            rawQuery = "PinocchioP",
        )

        assertEquals("PinocchioP", query)
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        val query = VoiceSearchQuery.build(
            focus = artistFocus,
            title = null,
            artist = "  DECO*27  ",
            album = null,
            genre = null,
            rawQuery = null,
        )

        assertEquals("DECO*27", query)
    }

    @Test
    fun anIntentWithNothingUsableYieldsNoQuery() {
        // The caller opens the app and stops, rather than playing something arbitrary.
        assertNull(VoiceSearchQuery.build(null, null, null, null, null, null))
        assertNull(VoiceSearchQuery.build(audioFocus, "", "  ", "", null, "   "))
    }

    @Test
    fun theFocusComparisonIgnoresCase() {
        val query = VoiceSearchQuery.build(
            focus = "VND.Android.Cursor.Item/Artist",
            title = "ignored",
            artist = "Kairiki Bear",
            album = null,
            genre = null,
            rawQuery = "ignored too",
        )

        assertEquals("Kairiki Bear", query)
    }
}
