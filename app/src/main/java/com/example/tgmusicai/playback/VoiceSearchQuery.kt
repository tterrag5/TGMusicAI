package com.example.tgmusicai.playback

/**
 * Turns a voice assistant's search intent into the one string the player searches for.
 *
 * Assistant and Gemini describe a spoken request twice: as the raw words
 * (`SearchManager.QUERY`), and as structured fields -- title, artist, album, genre -- tagged with a
 * "focus" saying which of them the request was actually about. This picks what to search for.
 *
 * It deliberately returns the **single most specific field** rather than joining them together.
 * Joining is the obvious choice and it is wrong here, because
 * `PlaybackService.searchSongsAndPlaylists` matches one string through a cascade -- exact title,
 * then artist contains, then title contains, then a YouTube search. A joined
 * `"Senbonzakura Hatsune Miku"` matches no title and no artist, so every local match is missed and
 * a track already on the device is fetched from the network instead. The title alone matches
 * locally, and still reads as a sensible query if it has to reach YouTube.
 *
 * The raw spoken query is the fallback, since it is what the user actually said, and some assistants
 * send it with no structured fields at all.
 */
object VoiceSearchQuery {

    /** `MediaStore.EXTRA_MEDIA_FOCUS` values, which are MIME-ish type strings rather than an enum. */
    private const val FOCUS_GENRE = "vnd.android.cursor.item/genre"
    private const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
    private const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
    private const val FOCUS_AUDIO = "vnd.android.cursor.item/audio"

    /**
     * What to search for, or null when the intent carried nothing usable -- in which case the
     * caller should just open the app rather than start playing something arbitrary.
     *
     * Takes plain strings rather than a `Bundle` so the choice is testable without the Android
     * framework, which [testOptions.unitTests.isReturnDefaultValues] would otherwise stub out.
     */
    fun build(
        focus: String?,
        title: String?,
        artist: String?,
        album: String?,
        genre: String?,
        rawQuery: String?,
    ): String? {
        // Blank is the same as absent here. Normalising first matters: the fallback chains below
        // use `?:`, which only catches null, so an empty-string extra would win its position and
        // then be discarded at the end -- throwing away a perfectly usable artist or raw query.
        val title = title.orNullIfBlank()
        val artist = artist.orNullIfBlank()
        val album = album.orNullIfBlank()
        val genre = genre.orNullIfBlank()
        val rawQuery = rawQuery.orNullIfBlank()

        // Within a focus, fall through to the next-best field: assistants routinely send a focus
        // without the field that focus names (an "album" request with only an artist, say).
        val byFocus = when (focus?.trim()?.lowercase()) {
            FOCUS_GENRE -> genre ?: rawQuery
            FOCUS_ARTIST -> artist ?: rawQuery
            FOCUS_ALBUM -> album ?: artist ?: rawQuery
            FOCUS_AUDIO -> title ?: artist ?: album ?: rawQuery
            // An unstructured focus ("vnd.android.cursor.item/*") means the assistant could not
            // tell what was asked for, so the spoken words are the best available answer. Any
            // focus this does not recognise is treated the same way rather than guessed at.
            else -> rawQuery ?: title ?: artist ?: album ?: genre
        }

        return byFocus
    }

    private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
