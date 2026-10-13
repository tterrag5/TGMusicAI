package com.example.tgmusicai

import com.example.tgmusicai.ui.components.isGenericShelf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home keeps YouTube Music's personal shelves and drops generic charts/trending/mood ones (user, 2026-10-12). */
class GenericShelfFilterTest {

    @Test
    fun genericShelvesAreDropped() {
        listOf(
            "Trending songs for you", "Charts", "Top 100 songs", "Popular right now", "New releases",
            "Moods & genres", "From the community", "Viral hits", "Hot list",
        ).forEach { assertTrue(it, isGenericShelf(it)) }
    }

    @Test
    fun personalShelvesAreKept() {
        listOf(
            "Quick picks", "Listen again", "Covers and remixes", "Fresh finds, old favorites",
            "Long listens", "Your daily discover", "Mixed for you", "Similar to Jamie Paige", "Stop motion vibes",
        ).forEach { assertFalse(it, isGenericShelf(it)) }
    }
}
