package com.example.tgmusicai

import com.example.tgmusicai.data.sponsorblock.SponsorBlockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers parsing of the SponsorBlock hash-prefix response.
 *
 * That endpoint answers with every video sharing a hash prefix, not just the one being played, so
 * the filtering is the part that matters: getting it wrong means seeking past a chunk of the wrong
 * song, which sounds like the player skipping at random.
 */
class SponsorBlockTest {

    private val manager = SponsorBlockManager()
    private val categories = SponsorBlockManager.DEFAULT_CATEGORIES

    @Test
    fun `segments belonging to another video in the same hash bucket are ignored`() {
        // The whole point of the prefix endpoint is that the response covers other videos too.
        val json = """
            [
              {"videoID":"otherVideo","segments":[
                {"segment":[0.0,30.0],"category":"sponsor","UUID":"a","actionType":"skip"}
              ]},
              {"videoID":"wantedVideo","segments":[
                {"segment":[10.0,20.0],"category":"intro","UUID":"b","actionType":"skip"}
              ]}
            ]
        """.trimIndent()

        val segments = manager.parseSegments(json, "wantedVideo", categories)

        assertEquals(1, segments.size)
        assertEquals("b", segments.first().id)
        assertEquals(10_000L, segments.first().startMs)
        assertEquals(20_000L, segments.first().endMs)
    }

    @Test
    fun `categories the user turned off are not skipped`() {
        val json = """
            [{"videoID":"v","segments":[
              {"segment":[5.0,15.0],"category":"sponsor","UUID":"a","actionType":"skip"},
              {"segment":[20.0,30.0],"category":"intro","UUID":"b","actionType":"skip"}
            ]}]
        """.trimIndent()

        val segments = manager.parseSegments(json, "v", setOf("intro"))

        assertEquals(1, segments.size)
        assertEquals("intro", segments.first().category)
    }

    @Test
    fun `non-skip action types are ignored`() {
        // "mute" and "poi" describe things this player does not implement. Treating them as skips
        // would cut out audio the user expects to hear.
        val json = """
            [{"videoID":"v","segments":[
              {"segment":[5.0,15.0],"category":"sponsor","UUID":"a","actionType":"mute"},
              {"segment":[20.0,30.0],"category":"sponsor","UUID":"b","actionType":"poi"}
            ]}]
        """.trimIndent()

        assertTrue(manager.parseSegments(json, "v", categories).isEmpty())
    }

    @Test
    fun `a segment missing an action type is treated as a skip`() {
        // Older submissions predate the field entirely.
        val json = """
            [{"videoID":"v","segments":[
              {"segment":[5.0,15.0],"category":"sponsor","UUID":"a"}
            ]}]
        """.trimIndent()

        assertEquals(1, manager.parseSegments(json, "v", categories).size)
    }

    @Test
    fun `segments too short to be worth a seek are dropped`() {
        val json = """
            [{"videoID":"v","segments":[
              {"segment":[5.0,5.2],"category":"sponsor","UUID":"a","actionType":"skip"}
            ]}]
        """.trimIndent()

        assertTrue(manager.parseSegments(json, "v", categories).isEmpty())
    }

    @Test
    fun `malformed segments are skipped without losing the good ones`() {
        // Real responses do contain junk; one bad entry must not cost the whole track its segments.
        val json = """
            [{"videoID":"v","segments":[
              {"segment":[30.0,10.0],"category":"sponsor","UUID":"backwards","actionType":"skip"},
              {"segment":[5.0],"category":"sponsor","UUID":"truncated","actionType":"skip"},
              {"category":"sponsor","UUID":"noBounds","actionType":"skip"},
              {"segment":[40.0,60.0],"category":"sponsor","UUID":"good","actionType":"skip"}
            ]}]
        """.trimIndent()

        val segments = manager.parseSegments(json, "v", categories)

        assertEquals(1, segments.size)
        assertEquals("good", segments.first().id)
    }

    @Test
    fun `segments come back in playback order`() {
        // The skip check walks the list looking for the first match, which only behaves if the
        // list is ordered by position.
        val json = """
            [{"videoID":"v","segments":[
              {"segment":[90.0,100.0],"category":"outro","UUID":"c","actionType":"skip"},
              {"segment":[5.0,15.0],"category":"intro","UUID":"a","actionType":"skip"},
              {"segment":[40.0,50.0],"category":"sponsor","UUID":"b","actionType":"skip"}
            ]}]
        """.trimIndent()

        val segments = manager.parseSegments(json, "v", categories)

        assertEquals(listOf("a", "b", "c"), segments.map { it.id })
    }

    @Test
    fun `an empty or malformed response yields no segments rather than throwing`() {
        assertTrue(manager.parseSegments("[]", "v", categories).isEmpty())
        assertTrue(manager.parseSegments("not json at all", "v", categories).isEmpty())
        assertTrue(manager.parseSegments("""{"unexpected":"shape"}""", "v", categories).isEmpty())
    }

    @Test
    fun `every default category has a label for the settings screen`() {
        // A category with no label would render as a blank switch row.
        SponsorBlockManager.DEFAULT_CATEGORIES.forEach { category ->
            assertTrue(
                "No label defined for default category '$category'",
                SponsorBlockManager.CATEGORY_LABELS.containsKey(category)
            )
        }
    }
}
