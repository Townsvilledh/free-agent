package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.15 battery round 2, classes 2 + 3 (device mirror):
 * evidence-proportionate finish gates and pseudo-tool-call
 * detection, as pure halves over the runner's real record
 * shapes. The shapes replayed are the battery's failure
 * SHAPES (zero-work finish, fabricated content values,
 * written tool calls), never its sentences as rules.
 */
class Lille115Test {

    // ---------- numeric literals ----------

    @Test
    fun numericLiteralsExtractAndNormalise() {
        val nums = FinishChecks.numericLiterals(
            "Fees were 1,234.50 and the total came to 42."
        )
        assertEquals(listOf("1234.5", "42"), nums)
    }

    @Test
    fun numericLiteralsSkipDates() {
        val nums = FinishChecks.numericLiterals(
            "On 2026-11-28 the balance was 17.5 and on " +
                "November 3, 2026 it was 9."
        )
        assertEquals(listOf("17.5", "9"), nums)
    }

    // ---------- demanded files ----------

    @Test
    fun demandedFilesAreExistingFilesNamedByTheTask() {
        val demanded = FinishChecks.demandedFiles(
            "Read alpha.txt and beta.txt and summarise them.",
            listOf("alpha.txt", "beta.txt", "gamma.txt")
        )
        assertEquals(listOf("alpha.txt", "beta.txt"), demanded)
    }

    @Test
    fun readEvidenceMatchesContentProbes() {
        val obs = listOf(
            "Tool result (read_note): FIRST FILE CONTENT here"
        )
        assertTrue(
            FinishChecks.readEvidenced(
                "alpha.txt", "FIRST FILE CONTENT here", obs
            )
        )
        assertFalse(
            FinishChecks.readEvidenced(
                "beta.txt", "UNSEEN CONTENT of beta", obs
            )
        )
    }

    // ---------- ungrounded content values ----------

    @Test
    fun fabricatedValueIsUngrounded() {
        // summary-b's shape: the report asserts a value no
        // step produced, over files that WERE read.
        val obs = listOf(
            "Tool result (read_note): alpha balance 10.25",
            "Tool result (read_note): beta balance 20.5"
        )
        val bad = FinishChecks.ungroundedContentValues(
            "Alpha holds 10.25, beta holds 20.5, and the " +
                "combined vault is 999.75.",
            obs,
            "Read alpha.txt and beta.txt and summarise.",
            listOf("alpha balance 10.25", "beta balance 20.5")
        )
        assertEquals(listOf("999.75"), bad)
    }

    @Test
    fun groundedValuesPass() {
        val obs = listOf(
            "Tool result (read_note): alpha balance 10.25"
        )
        val bad = FinishChecks.ungroundedContentValues(
            "Alpha holds 10.25 across 2 entries.",
            obs,
            "Read alpha.txt (2 files total).",
            listOf("alpha balance 10.25")
        )
        assertTrue(bad.isEmpty())
    }

    // ---------- pseudo-tool-call text ----------

    @Test
    fun pseudoCallShapesAreDetected() {
        val tools = TaskTools.ALL_TOOL_NAMES
        assertEquals(
            "browser_open",
            FinishChecks.pseudoCallName(
                "browser_open(\"https://example.com\")\n" +
                    "Lille: browser_read()",
                tools
            )
        )
        assertEquals(
            "browser_read",
            FinishChecks.pseudoCallName(
                "Step 1 done. Now:\n- browser_read()",
                tools
            )
        )
        assertEquals(
            "write_note",
            FinishChecks.pseudoCallName(
                "Lille: write_note(name=\"x\", content=\"y\")",
                tools
            )
        )
    }

    @Test
    fun proseAndMentionsAreNotPseudoCalls() {
        val tools = TaskTools.ALL_TOOL_NAMES
        assertNull(
            FinishChecks.pseudoCallName(
                "I will use browser_read() to check the page.",
                tools
            )
        )
        assertNull(
            FinishChecks.pseudoCallName(
                "The function print(x) prints values.",
                tools
            )
        )
        assertNull(
            FinishChecks.pseudoCallName(
                "All done — the notes are written.", tools
            )
        )
    }
}
