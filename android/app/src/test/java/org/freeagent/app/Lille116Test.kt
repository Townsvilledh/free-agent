package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.16 battery round 3, classes 1 + 2 (device mirror):
 * finish-vs-steps consistency and malformed-call recovery,
 * as pure halves over the runner's real record shapes. The
 * shapes replayed are round 3's failure SHAPES (a finish
 * contradicting the run's own working; a report listing
 * items no fetched source mentions; a malformed call
 * earning a repair re-ask), never its sentences as rules.
 */
class Lille116Test {

    // ---------- weekday self-contradiction ----------

    @Test
    fun weekdayMismatchIsCaughtStructurally() {
        // 8 December 2026 is a Tuesday.
        val bad = FinishChecks.weekdayContradictions(
            "The deadline is Friday 8 December 2026."
        )
        assertEquals(1, bad.size)
        assertTrue(bad[0].contains("tuesday"))
        assertTrue(
            FinishChecks.weekdayContradictions(
                "The deadline is Tuesday 8 December 2026."
            ).isEmpty()
        )
        assertTrue(
            FinishChecks.weekdayContradictions(
                "The deadline is 8 December 2026."
            ).isEmpty()
        )
    }

    // ---------- step dates ----------

    @Test
    fun finishDateContradictingStepTextsIsCaught() {
        val produced = listOf(
            "Counting working days: the 10th working day " +
                "is Friday 11 December 2026."
        )
        val contra = FinishChecks.stepDateContradiction(
            "The deadline is Tuesday 8 December 2026.",
            produced
        )
        assertNotNull(contra)
        assertTrue(contra!!.contains("2026-12-11"))
        // Agreement with her own steps is not a contradiction.
        assertNull(
            FinishChecks.stepDateContradiction(
                "The deadline is Friday 11 December 2026.",
                produced
            )
        )
        // No produced dates: nothing to judge.
        assertNull(
            FinishChecks.stepDateContradiction(
                "The deadline is Tuesday 8 December 2026.",
                listOf("no dates in this step")
            )
        )
    }

    // ---------- anchored numbers ----------

    @Test
    fun headlineFiguresContradictedByOwnWorkingAreCaught() {
        // The numbers-a shape: headline lines assert one
        // result per amount; the run's own closing note
        // asserts different results for the same anchors.
        val summary = "Calculated swap fees based on 0.5% " +
            "fee with \$2 cap:\n" +
            "- On \$30: fee = max(0.5% * 30, 2) = " +
            "max(0.15, 2) = \$2 (capped)\n" +
            "- On \$300: fee = max(0.5% * 300, 2) = " +
            "max(1.5, 2) = \$2 (capped)\n" +
            "Note: For \$30 and \$300, the 0.5% fee is " +
            "below \$2, so the actual fee would be \$0.15 " +
            "and \$1.5 respectively."
        val hit = FinishChecks.numberContradiction(
            summary, emptyList())
        assertNotNull(hit)
        assertTrue(hit!!.contains("30"))
    }

    @Test
    fun consistentFiguresAreNotAContradiction() {
        val summary = "Fees: on \$30 the fee is \$0.15; " +
            "on \$300 the fee is \$1.50; on \$3,000 the fee " +
            "is \$2 (the \$2 cap)."
        assertNull(
            FinishChecks.numberContradiction(
                summary,
                listOf("on 3000: 0.5% is 15, fee = 2")
            )
        )
    }

    // ---------- research items ----------

    @Test
    fun listedItemsTraceToFetchedSources() {
        val corpus = listOf(
            "Tool result (web_search): Open Source Summit " +
                "Lagos 2026 — applications close " +
                "20 December 2026"
        )
        val summary = "### 1. **Open Source Summit " +
            "Lagos 2026**\n" +
            "### 2. **MLH Hackathon 2024**\n" +
            "### 3. **HackMIT Spring 2024**\n"
        val unsourced = FinishChecks.unsourcedItems(
            summary, corpus, fetchDemanded = false)
        assertEquals(
            listOf("MLH Hackathon 2024", "HackMIT Spring 2024"),
            unsourced
        )
        // The combined verdict names the unsourced items.
        val verdict = FinishChecks.consistencyContradiction(
            summary, corpus, emptyList(), false)
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("MLH Hackathon 2024"))
    }

    @Test
    fun assistantTextsComeFromAssistantTurns() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "user")
                .put("content", "the task"))
            .put(JSONObject().put("role", "assistant")
                .put("content", "her working note"))
            .put(JSONObject().put("role", "user")
                .put("content", "Tool result (read_note): x"))
        assertEquals(
            listOf("her working note"),
            FinishChecks.assistantTextsOf(messages)
        )
    }

    // ---------- malformed-call recovery ----------

    @Test
    fun brokenJsonAttemptIsAMalformedShape() {
        val attempt = MalformedRepair.attempted(
            "```json\n{\"tool\": \"write_note\", \"args\": " +
                "{\"path\": \"a.txt\", \"content\": }\n```"
        )
        assertNotNull(attempt)
        assertEquals(
            "write_note|json did not parse", attempt!!.shape)
        assertTrue(attempt.reason.contains("did not parse"))
        assertTrue(
            MalformedRepair.repairSteer(attempt)
                .contains("did not parse")
        )
    }

    @Test
    fun missingToolFieldIsItsOwnShape() {
        val attempt = MalformedRepair.attempted(
            "```json\n{\"args\": {\"path\": \"a.txt\"}}\n```"
        )
        assertNotNull(attempt)
        assertEquals("unknown|no tool field", attempt!!.shape)
    }

    @Test
    fun plainTextIsNotAMalformedAttempt() {
        assertNull(
            MalformedRepair.attempted(
                "I will write the note next.")
        )
        assertNull(MalformedRepair.attempted(""))
    }
}
