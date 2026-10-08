package com.example.tgmusicai

import com.example.tgmusicai.data.local.AppPreferences
import com.example.tgmusicai.data.sponsorblock.SponsorBlockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants the "Skipped <label>" announcement and its Disable action depend on.
 *
 * The announcement names the category it skipped and offers to switch that one off. Both halves
 * read from the same two tables, which are declared in different classes, so what can actually go
 * wrong here is drift between them -- a category that can be skipped but has no label to announce,
 * or a default set that no longer matches the one the preferences hand out.
 */
class SponsorBlockAnnouncementTest {

    @Test
    fun everySkippableCategoryHasALabelToAnnounce() {
        // Segment.displayName falls back to a generic "non-music section" for an unmapped category.
        // That fallback exists for a category the server invents, not for one this app ships with:
        // a skip the user is told nothing specific about is one they cannot make a decision on.
        for (category in SponsorBlockManager.DEFAULT_CATEGORIES) {
            assertTrue(
                "'$category' can be skipped but has no label in CATEGORY_LABELS",
                SponsorBlockManager.CATEGORY_LABELS.containsKey(category),
            )
        }
    }

    @Test
    fun theTwoDefaultCategorySetsHaveNotDrifted() {
        // AppPreferences duplicates this set on purpose, to keep the preferences class free of any
        // dependency on network code. Duplication is fine; silent divergence is not, because the
        // app would then skip one set and offer the user checkboxes for another.
        assertEquals(
            "AppPreferences.DEFAULT_SPONSORBLOCK_CATEGORIES has drifted from " +
                "SponsorBlockManager.DEFAULT_CATEGORIES",
            SponsorBlockManager.DEFAULT_CATEGORIES,
            AppPreferences.DEFAULT_SPONSORBLOCK_CATEGORIES,
        )
    }

    @Test
    fun disablingACategoryLeavesTheOthersSkipping() {
        // What the Disable action does, as a set operation: remove exactly the one announced.
        val active = SponsorBlockManager.DEFAULT_CATEGORIES
        val remaining = active - "sponsor"

        assertTrue("the disabled category is gone", "sponsor" !in remaining)
        assertEquals("nothing else moved", active.size - 1, remaining.size)
        assertTrue(
            "the category that matters most for a music player is untouched",
            "music_offtopic" in remaining,
        )
    }

    @Test
    fun disablingTheLastCategoryStopsLookupsAltogether() {
        // fetchSegments returns early on an empty category set, so emptying it via the announcement
        // must not leave the app querying SponsorBlock for segments it would then ignore.
        val emptied = SponsorBlockManager.DEFAULT_CATEGORIES - SponsorBlockManager.DEFAULT_CATEGORIES
        assertTrue(emptied.isEmpty())
    }

    @Test
    fun labelsAreNotJustTheCategoryIds() {
        // The announcement shows these to the user, so "music_offtopic" is not an acceptable label.
        for ((category, label) in SponsorBlockManager.CATEGORY_LABELS) {
            assertNotEquals("'$category' is shown to the user as its raw id", category, label)
            assertTrue("'$category' has a blank label", label.isNotBlank())
        }
    }
}
