package com.voiceprompter.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are the cases that decide whether the prompter feels right. If you
 * change the weights or thresholds in [PhraseMatcher] / [MatchMode], this is
 * the file that tells you what you broke.
 */
class PhraseMatcherTest {

    private val expected = "Great leaders inspire people through vision and action."

    private fun advances(heard: String, mode: MatchMode = MatchMode.BALANCED): Boolean =
        PhraseMatcher.score(expected, heard).advances(mode)

    @Test
    fun `exact repeat advances in every mode`() {
        val heard = "Great leaders inspire people through vision and action"
        for (mode in MatchMode.entries) {
            assertTrue("$mode should advance on an exact repeat", advances(heard, mode))
        }
    }

    @Test
    fun `fillers are ignored`() {
        assertTrue(advances("uh great leaders um inspire people through vision and action"))
    }

    @Test
    fun `stutters are ignored`() {
        assertTrue(advances("great great leaders inspire people through vision and action"))
    }

    @Test
    fun `one wrong word advances on balanced but not strict`() {
        val heard = "great leaders inspire people through vision in action"
        assertTrue(advances(heard, MatchMode.BALANCED))
        assertFalse(advances(heard, MatchMode.STRICT))
    }

    @Test
    fun `a couple of skipped words still advance on balanced`() {
        assertTrue(advances("great leaders inspire vision and action"))
    }

    @Test
    fun `heavily skipped phrase holds in every mode`() {
        val heard = "great leaders action"
        for (mode in MatchMode.entries) {
            assertFalse("$mode should hold on a heavy skip", advances(heard, mode))
        }
    }

    @Test
    fun `ad-lib does not advance the script`() {
        assertFalse(advances("Let me share a personal experience about leadership"))
        assertFalse(advances("and you know i was thinking about this the other day"))
    }

    @Test
    fun `unrelated text scores zero`() {
        val result = PhraseMatcher.score(expected, "The Lord is my shepherd I shall not want")
        assertEquals(0.0, result.score, 0.001)
    }

    @Test
    fun `the phrase said inside a longer ad-lib still counts`() {
        // Coverage alone would give this 1.0; the edit term pulls it down to
        // just above the balanced threshold, which is the intended behaviour.
        assertTrue(
            advances(
                "so what i want to say is great leaders inspire people through " +
                    "vision and action and that is really the heart of it",
            ),
        )
    }

    @Test
    fun `empty speech never advances`() {
        assertFalse(advances(""))
    }

    @Test
    fun `progress grows as the speaker works through the phrase`() {
        val tokens = Normalizer.tokenize(expected)
        val quarter = PhraseMatcher.progress(tokens, Normalizer.tokenize("great leaders"))
        val half = PhraseMatcher.progress(tokens, Normalizer.tokenize("great leaders inspire people"))
        assertTrue(half > quarter)
        assertEquals(1.0, PhraseMatcher.progress(tokens, tokens), 0.001)
    }

    @Test
    fun `resync finds the phrase the speaker returned to`() {
        val phrases = listOf(
            "Today we will discuss effective leadership.",
            "Great leaders inspire people through vision and action.",
            "They listen before they speak.",
            "And they carry the weight of the decision.",
        ).map { Normalizer.tokenize(it) }

        val heard = Normalizer.tokenize("they listen before they speak")
        val resync = PhraseMatcher.resync(
            phrases = phrases,
            heard = heard,
            currentIndex = 1,
            mode = MatchMode.BALANCED,
        )

        assertNotNull(resync)
        assertEquals(2, resync!!.index)
    }

    @Test
    fun `resync returns null while the speaker is still ad-libbing`() {
        val phrases = listOf(
            "Today we will discuss effective leadership.",
            "Great leaders inspire people through vision and action.",
        ).map { Normalizer.tokenize(it) }

        val heard = Normalizer.tokenize("my youngest daughter asked me about this last tuesday")
        assertNull(
            PhraseMatcher.resync(phrases, heard, currentIndex = 1, mode = MatchMode.BALANCED),
        )
    }
}
