package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for the planning interval + verify-state +
 * final-answer checks (v1.0.4, steals #8 + #2): cadence,
 * plan-note merging, probe selection, verdicts, and the
 * deterministic finish gate (bounce once, then honest).
 */
class TaskPlanningTest {

    @Test
    fun planningCadenceAlignsToTheBudgets() {
        assertTrue(TaskPlanning.planDue(10, browserActive = true))
        assertFalse(TaskPlanning.planDue(8, browserActive = true))
        assertTrue(TaskPlanning.planDue(8, browserActive = false))
        assertTrue(TaskPlanning.planDue(16, browserActive = false))
        assertFalse(TaskPlanning.planDue(0, browserActive = false))
    }

    @Test
    fun planNoteMergesSubgoalStates() {
        val task = JSONObject()
        TaskPlanning.applyPlanNote(
            task,
            JSONObject()
                .put("facts", "found the page")
                .put(
                    "subgoals",
                    JSONArray().put("open the page").put("post the reply")
                ),
            step = 10
        )
        var goals = TaskPlanning.subgoals(task)
        assertEquals(2, goals.size)
        assertTrue(goals.all { it.status == TaskPlanning.Subgoal.OPEN })

        TaskPlanning.applyPlanNote(
            task,
            JSONObject().put(
                "done", JSONArray().put("Open the page")
            ),
            step = 12
        )
        goals = TaskPlanning.subgoals(task)
        assertEquals(
            TaskPlanning.Subgoal.DONE,
            goals.first { it.text == "open the page" }.status
        )
        // The transparency block renders the standing.
        val block = TaskPlanning.planBlock(task)!!
        assertTrue(block.contains("✓ open the page"))
        assertTrue(block.contains("● post the reply"))
    }

    @Test
    fun probeSelectionPrefersCalendarThenNoteThenBrowser() {
        val cal = JSONObject().put("calendarCreatedTitle", "Dentist")
        assertEquals(
            FinishVerifier.ProbeKind.CALENDAR,
            FinishVerifier.probeFor(cal, browserActive = true)
        )
        val note = JSONObject().put("lastNotePath", "a.txt")
        assertEquals(
            FinishVerifier.ProbeKind.NOTE,
            FinishVerifier.probeFor(note, browserActive = true)
        )
        assertEquals(
            FinishVerifier.ProbeKind.BROWSER,
            FinishVerifier.probeFor(JSONObject(), browserActive = true)
        )
        assertEquals(
            FinishVerifier.ProbeKind.NONE,
            FinishVerifier.probeFor(JSONObject(), browserActive = false)
        )
    }

    @Test
    fun verdictsGradeTheReRead() {
        assertTrue(FinishVerifier.noteVerdict("a.txt", true, 42).ok)
        assertFalse(FinishVerifier.noteVerdict("a.txt", false, 0).ok)
        assertTrue(
            FinishVerifier.browserVerdict("https://x.com", "X", true).ok
        )
        assertFalse(
            FinishVerifier.browserVerdict("https://evil.com", "", false).ok
        )
        assertTrue(FinishVerifier.calendarVerdict("Dentist", true).ok)
        assertFalse(FinishVerifier.calendarVerdict("Dentist", false).ok)
        // A lying finish's discrepancy names the gap.
        assertTrue(
            FinishVerifier.discrepancyLine(
                FinishVerifier.noteVerdict("a.txt", false, 0)
            ).contains("a.txt")
        )
    }

    @Test
    fun finalChecksCatchOpenSubgoalsAndLyingState() {
        val task = JSONObject()
        TaskPlanning.applyPlanNote(
            task,
            JSONObject().put(
                "subgoals", JSONArray().put("still to do")
            ),
            step = 1
        )
        val failures = FinalChecks.evaluate(
            task, null, "All done.",
            FinishVerifier.noteVerdict("a.txt", false, 0)
        )
        val names = failures.map { it.check }
        assertTrue("subgoals_open" in names)
        assertTrue("state_unverified" in names)
        // The bounce note names both, in words.
        val note = FinalChecks.bounceNote(failures)
        assertTrue(note.contains("subgoals_open"))
        assertTrue(note.contains("state_unverified"))
    }

    @Test
    fun finalChecksPassACleanVerifiedFinish() {
        val task = JSONObject()
        TaskPlanning.applyPlanNote(
            task,
            JSONObject().put(
                "done", JSONArray().put("the thing")
            ),
            step = 1
        )
        val failures = FinalChecks.evaluate(
            task, null, "Done.",
            FinishVerifier.noteVerdict("a.txt", true, 10)
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun finalChecksNameAnUnnamedPartial() {
        val plan = TaskTools.planFor(
            "write a poem and check my email for the invoice",
            TaskTools.DeviceCaps(gmailConnected = false)
        )
        assertTrue(plan.partial)
        val vague = FinalChecks.evaluate(
            JSONObject(), plan, "Poem written, all done!",
            FinishVerifier.noteVerdict("poem.txt", true, 10)
        )
        assertTrue(vague.any { it.check == "partial_unnamed" })
        val honest = FinalChecks.evaluate(
            JSONObject(), plan,
            "Poem written. The email part is not done — Gmail " +
                "is not connected.",
            FinishVerifier.noteVerdict("poem.txt", true, 10)
        )
        assertFalse(honest.any { it.check == "partial_unnamed" })
    }
}
