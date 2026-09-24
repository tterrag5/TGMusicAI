package com.example.tgmusicai.data.repository

import com.example.tgmusicai.data.local.entity.AiSongTags
import com.example.tgmusicai.data.local.entity.Song

/**
 * One tag the library can be browsed or searched by, and the songs that carry it.
 *
 * [kind] exists so the browser can say where a tag came from. A genre was written into the file by
 * whoever tagged it and is the user's own vocabulary; an audio tag was inferred on device by the
 * tagging model and is not. Showing them without distinction would make the model's guesses look
 * like the user's metadata.
 */
data class LibraryTag(
    val name: String,
    val kind: Kind,
    val songIds: Set<Long>
) {
    val songCount: Int get() = songIds.size

    enum class Kind {
        /** From the file's own genre tag. */
        GENRE,

        /** What the on-device tagging model heard, e.g. "Electric guitar". */
        SOUND,

        /** What the lyrics are about, from on-device lyric analysis. */
        THEME
    }
}

/**
 * Groups a library into tags: the genres its files declare, plus whatever on-device analysis has
 * inferred about how the songs sound and what their lyrics are about.
 *
 * Built in memory from rows already loaded rather than by querying, for the same reason the folder
 * tree it replaces was: this is a grouping pass over data the screen is holding anyway, and
 * rebuilding it wholesale means a newly scanned or newly analysed track shows up under its tags
 * without any invalidation logic.
 *
 * A genre field frequently holds several genres at once ("Hip-Hop/Rap", "rock; alternative"), so
 * the field is split rather than treated as one opaque label -- otherwise a library ends up with a
 * long tail of one-song tags that are really combinations of a handful of real ones.
 */
object MusicTagIndex {

    /** Characters taggers use to pack several genres into one field. */
    private val SEPARATORS = Regex("[;/,|]")

    /** Values that mean "no genre" and must not become a tag of their own. */
    private val PLACEHOLDERS = setOf("unknown", "unknown genre", "other", "none", "<unknown>")

    /**
     * Every tag in [songs], most-used first, with ties broken alphabetically so the order is
     * stable between rebuilds. [aiTags] may be empty, which simply leaves out the inferred tags.
     */
    fun build(songs: List<Song>, aiTags: List<AiSongTags> = emptyList()): List<LibraryTag> {
        // Keyed by lower-case name so "Hip-Hop" and "hip-hop" are one tag, while the first spelling
        // seen is what gets displayed -- inventing a canonical casing would rewrite the user's own
        // labels back at them.
        val accumulated = LinkedHashMap<String, MutableTag>()

        fun add(rawName: String, kind: LibraryTag.Kind, songId: Long) {
            val name = rawName.trim().trim('"', '\'')
            if (name.isBlank() || name.lowercase() in PLACEHOLDERS) return
            val existing = accumulated.getOrPut(name.lowercase()) { MutableTag(name, kind) }
            existing.songIds += songId
        }

        for (song in songs) {
            song.genre?.split(SEPARATORS)?.forEach { add(it, LibraryTag.Kind.GENRE, song.id) }
        }

        val songIds = songs.mapTo(HashSet()) { it.id }
        for (row in aiTags) {
            // Analysis rows outlive the songs they describe (the table has no foreign key), so a
            // row for a deleted song must not contribute a tag with no songs behind it.
            if (row.songId !in songIds) continue
            row.tags?.split(SEPARATORS)?.forEach { add(it, LibraryTag.Kind.SOUND, row.songId) }
            row.lyricThemes?.split(SEPARATORS)?.forEach { add(it, LibraryTag.Kind.THEME, row.songId) }
        }

        return accumulated.values
            .map { LibraryTag(it.name, it.kind, it.songIds) }
            .sortedWith(compareByDescending<LibraryTag> { it.songCount }.thenBy { it.name.lowercase() })
    }

    /** The tags whose name contains [query], or all of them when the query is blank. */
    fun matching(tags: List<LibraryTag>, query: String): List<LibraryTag> =
        if (query.isBlank()) tags else tags.filter { it.name.contains(query.trim(), ignoreCase = true) }

    /**
     * Ids of every song carrying a tag whose name matches [query]. Used to fold tags into the
     * ordinary library search, so typing "jazz" finds the tracks tagged jazz as well as the ones
     * with it in their title.
     */
    fun songIdsMatching(tags: List<LibraryTag>, query: String): Set<Long> {
        if (query.isBlank()) return emptySet()
        val matches = matching(tags, query)
        if (matches.isEmpty()) return emptySet()
        return matches.flatMapTo(HashSet()) { it.songIds }
    }

    private class MutableTag(val name: String, val kind: LibraryTag.Kind) {
        val songIds = mutableSetOf<Long>()
    }
}
