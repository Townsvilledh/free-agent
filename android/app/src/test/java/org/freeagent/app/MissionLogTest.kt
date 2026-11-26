package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direction 2 (mission log) + Direction 4 (debrief order). */
class MissionLogTest {

    @Test
    fun elapsedLabels() {
        val t0 = 1_000_000L
        assertEquals("", MissionLog.elapsedLabel(0, t0))
        assertEquals("under a minute", MissionLog.elapsedLabel(t0, t0 + 20_000))
        assertEquals("3 min", MissionLog.elapsedLabel(t0, t0 + 180_000))
        assertEquals("1 h 5 min", MissionLog.elapsedLabel(t0, t0 + 3_900_000))
    }

    @Test
    fun checklistShowsDoneStepsAndCurrentOnly() {
        val lines = MissionLog.checklist(
            listOf("search", "open_page"), running = true, steps = 2
        )
        assertEquals("✓ search", lines[0])
        assertEquals("✓ open_page", lines[1])
        assertTrue(lines[2].startsWith("● step 3"))
        // No invented pending steps beyond the current one.
        assertEquals(3, lines.size)
    }

    @Test
    fun checklistForFinishedTaskHasNoCurrentStep() {
        val lines = MissionLog.checklist(listOf("search"), running = false, steps = 1)
        assertEquals(listOf("✓ search"), lines)
    }

    @Test
    fun currentActionPrefersTheNote() {
        assertEquals(
            "Reading the second page of results.",
            MissionLog.currentAction(
                TaskStates.RUNNING, "Reading the second page of results."
            )
        )
        assertEquals(
            "Waiting — continues automatically…",
            MissionLog.currentAction(TaskStates.WAITING_ROUTE, "")
        )
        assertEquals(
            "Parked at a step only you can do.",
            MissionLog.currentAction(TaskStates.PARKED_AT_WALL, "")
        )
    }

    @Test
    fun currentActionWithholdsNarrationNotes() {
        // Seamless law: a legacy note carrying route
        // narration is never the debrief's current action —
        // the plain state fallback stands instead.
        assertEquals(
            "Working on it…",
            MissionLog.currentAction(
                TaskStates.RUNNING, "Free brain returned HTTP 429."
            )
        )
    }

    @Test
    fun debriefOrderingNeedsYouFailedFinished() {
        assertEquals(DebriefOrder.NEEDS_YOU, DebriefOrder.rank("BLOCKED"))
        assertEquals(DebriefOrder.NEEDS_YOU, DebriefOrder.rank("NEEDS ETHURAM"))
        assertEquals(DebriefOrder.FAILED, DebriefOrder.rank("FAILED"))
        assertEquals(DebriefOrder.FINISHED, DebriefOrder.rank("LANDED"))
        assertEquals(DebriefOrder.FINISHED, DebriefOrder.rank("DONE"))
        assertEquals("Needs you", DebriefOrder.sectionTitle(DebriefOrder.NEEDS_YOU))
        assertEquals("Failed", DebriefOrder.sectionTitle(DebriefOrder.FAILED))
        assertEquals("Finished", DebriefOrder.sectionTitle(DebriefOrder.FINISHED))
    }
}
