package com.example.tgmusicai

import com.example.tgmusicai.ai.LyricThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the theme taxonomy that lyric tagging is built on.
 *
 * Tagging is zero-shot: a theme is recognised purely by how close a song's lyrics sit to the
 * theme's written description. That makes the descriptions load-bearing text rather than
 * documentation, and a bare or duplicated one quietly produces a theme nothing ever matches, or
 * one that matches everything.
 */
class LyricThemesTest {

    @Test
    fun everyThemeHasADescriptivePhraseNotALabel() {
        LyricThemes.DESCRIPTIONS.forEach { (name, description) ->
            assertTrue("Theme '$name' has a blank description", description.isNotBlank())
            // A sentence-embedding model places a phrase far more usefully than a single word, so
            // a description that collapsed to a bare label would be a silent quality regression.
            assertTrue(
                "Theme '$name' is described by too few words to embed well: '$description'",
                description.split(" ").size >= 5,
            )
        }
    }

    @Test
    fun themeNamesAndDescriptionsAreUnique() {
        val descriptions = LyricThemes.DESCRIPTIONS.values
        assertEquals(
            "Two themes share a description, so they can never be told apart",
            descriptions.size,
            descriptions.toSet().size,
        )
    }

    @Test
    fun coversTheSocialAndPoliticalGround() {
        // The whole reason themes exist: nothing about a song's sound, genre or artist reveals
        // that it is about work, class or protest, so those have to be nameable.
        val names = LyricThemes.DESCRIPTIONS.keys
        listOf("class and labour", "socialism and collective politics", "protest and resistance")
            .forEach { assertTrue("Missing theme '$it'", it in names) }
    }

    @Test
    fun taggingLimitsAreSane() {
        // Too high a threshold silently tags nothing and leaves the signal dead; too many themes
        // per song makes every song overlap every other.
        assertTrue(LyricThemes.STANDOUT_THRESHOLD > 0.5f)
        assertTrue(LyricThemes.STANDOUT_THRESHOLD < 3f)
        assertTrue(LyricThemes.MAX_THEMES_PER_SONG in 3..8)
    }
}
