package com.example.tgmusicai.data.repository

/**
 * Breaks a line of lyrics into the units a karaoke highlight advances through.
 *
 * A whitespace split is the obvious implementation and it is wrong for a large part of this
 * library. Japanese is written without spaces, so `"愛して愛して"` is one
 * single "word" to a whitespace splitter -- the highlight would cover the entire line in one step
 * and karaoke would visibly do nothing for exactly the tracks it was asked for. Every spaceless
 * script needs splitting by character instead, and the two cases can appear in the same line
 * (`"カレンダー girl"`), so the segmentation walks script runs rather than
 * choosing one strategy per line.
 *
 * Within Japanese the unit is the mora rather than the character, because that is what a singer
 * actually lands on: `"きょ"` (ki + small yo) is sung as one beat, as is a vowel held with
 * `"ー"`. Splitting those apart makes the highlight stutter mid-syllable. Kanji get one segment
 * each -- `"愛"` is really two morae ("a-i"), but knowing that needs a reading dictionary, and
 * guessing wrong is worse than being uniformly approximate.
 */
object LyricSegmenter {

    /**
     * One highlight unit, with the relative share of its line's duration it should occupy.
     *
     * [weight] is in "character units": a CJK character counts [CJK_WEIGHT] because a sung kana or
     * kanji holds roughly that much longer than one Latin letter. It only ever matters as a ratio
     * against the other segments on the same line, so the absolute scale is arbitrary -- it exists
     * so a mixed-script line divides sensibly instead of giving a four-letter English word the same
     * time as a four-mora Japanese phrase.
     */
    data class Segment(val text: String, val weight: Int)

    /**
     * Splits [text] into highlight units, or returns an empty list for a line with nothing to
     * highlight (blank, or punctuation only). Joining every [Segment.text] in order reproduces
     * [text] exactly apart from the whitespace that separated the segments, so a caller can render
     * segments in place of the original line without the words changing.
     */
    fun segment(text: String): List<Segment> {
        if (text.isBlank()) return emptyList()

        val segments = mutableListOf<Segment>()
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch.isWhitespace() -> index++

                isSpaceless(ch) -> {
                    val end = endOfMora(text, index)
                    segments.add(Segment(text.substring(index, end), weightOf(text, index, end)))
                    index = end
                }

                else -> {
                    // A run of a space-delimited script (Latin, Cyrillic, Hangul, digits). It ends
                    // at whitespace or at the point the line switches into a spaceless script, so
                    // "Hello世界" yields "Hello" and then the CJK run separately rather
                    // than one inseparable blob.
                    var end = index
                    while (end < text.length && !text[end].isWhitespace() && !isSpaceless(text[end])) end++
                    segments.add(Segment(text.substring(index, end), weightOf(text, index, end)))
                    index = end
                }
            }
        }
        return segments
    }

    /**
     * Where the mora starting at [start] ends.
     *
     * Grows past the base character to swallow the marks that are sung as part of it: a small kana
     * (`"きょ"`), a long-vowel mark (`"ラー"`), a combining voicing mark, or a
     * repeat mark. A sokuon (`"っ"`) is the mirror image -- it belongs to the mora that
     * *follows* it (`"った"` is sung "tta"), so it absorbs the next character instead.
     */
    private fun endOfMora(text: String, start: Int): Int {
        var end = start + 1
        if (text[start] in SOKUON && end < text.length && isSpaceless(text[end])) {
            // Take the following base character too, then let it collect its own trailing marks.
            end++
        }
        while (end < text.length && text[end] in TRAILING_MARKS) end++
        return end
    }

    /** Summed character weight of `text[start until end]`. */
    private fun weightOf(text: String, start: Int, end: Int): Int {
        var weight = 0
        for (i in start until end) weight += if (isSpaceless(text[i])) CJK_WEIGHT else 1
        return weight.coerceAtLeast(1)
    }

    /**
     * Whether [ch] belongs to a script written without spaces between words.
     *
     * Hangul is deliberately absent: Korean is written in Hangul but does use spaces, so it
     * segments correctly as a space-delimited script and splitting it per character would break
     * words apart.
     */
    private fun isSpaceless(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3040..0x30FF ||   // Hiragana + Katakana
            code in 0x31F0..0x31FF ||      // Katakana phonetic extensions
            code in 0x3400..0x4DBF ||      // CJK unified ideographs extension A
            code in 0x4E00..0x9FFF ||      // CJK unified ideographs
            code in 0xF900..0xFAFF         // CJK compatibility ideographs
    }

    /** Small kana, long-vowel and voicing marks, which are sung as part of the preceding mora. */
    private val TRAILING_MARKS = setOf(
        'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', // small hiragana a i u e o
        'ゃ', 'ゅ', 'ょ',                      // small hiragana ya yu yo
        'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', // small katakana a i u e o
        'ャ', 'ュ', 'ョ',                      // small katakana ya yu yo
        'ー',                                          // long vowel mark
        '゙', '゚', '゛', '゜',           // voicing marks, combining and spacing
        'ヽ', 'ヾ', 'ゝ', 'ゞ',           // iteration marks
    )

    /** Sokuon ("small tsu"), which attaches to the mora after it rather than the one before. */
    private val SOKUON = setOf('っ', 'ッ')

    /**
     * How much longer one CJK character is held than one Latin character. Approximate by design;
     * see [Segment.weight].
     */
    private const val CJK_WEIGHT = 3
}
