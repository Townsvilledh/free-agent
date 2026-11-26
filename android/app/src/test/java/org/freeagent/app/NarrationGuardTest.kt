package org.freeagent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seamless law's safety net: stored notes written by
 * older builds carry route narration verbatim — these
 * fixtures are the actual shipped shapes, and every one
 * must be withheld from any surface that echoes notes.
 * Clean working notes pass untouched.
 */
class NarrationGuardTest {

    private val legacyNarration = listOf(
        "The free brain route refused that request's shape. " +
            "Trying the slow community route next.",
        "Free route rate-limited — waiting for the free brain to free up.",
        "Brain route horde returned HTTP 503 (the routes before it in the chain also failed).",
        "The main free route is resting after repeated failures — " +
            "going straight to the slow community route.",
        "(Answered by the slow community route — the main free route is busy.)",
        "(Answered by your Groq key route.)",
        "The free model refused that phrasing, so I re-asked it as a concrete task:",
        "Every brain route is resting after repeated failures. " +
            "Your message is queued.",
        "The brain routes refused that request's shape — it can't be retried as-is.",
        "Your Groq key route hit its free-tier limit."
    )

    @Test
    fun legacyNarrationIsAlwaysFlagged() {
        for (text in legacyNarration) {
            assertTrue("must flag: $text", NarrationGuard.containsNarration(text))
            assertNull(NarrationGuard.cleanOrNull(text))
        }
    }

    @Test
    fun cleanNotesPassUntouched() {
        val clean = listOf(
            "Reading the second page of results.",
            "Stopped at a step that could not be completed.",
            "Wrote digest.txt with 3 items.",
            "The route to the venue was closed, so the summary names the detour.",
            "Waiting — continues automatically (task is waiting, not failed).",
            BrainFailure.EXHAUSTED_LINE,
            ChatOutputGuard.PLAIN_LINE
        )
        for (text in clean) {
            assertFalse("must pass: $text", NarrationGuard.containsNarration(text))
            assertTrue(NarrationGuard.cleanOrNull(text) == text)
        }
    }

    @Test
    fun blankAndNullAreNotEchoable() {
        assertNull(NarrationGuard.cleanOrNull(null))
        assertNull(NarrationGuard.cleanOrNull(""))
        assertNull(NarrationGuard.cleanOrNull("   "))
        assertFalse(NarrationGuard.containsNarration(""))
    }
}
