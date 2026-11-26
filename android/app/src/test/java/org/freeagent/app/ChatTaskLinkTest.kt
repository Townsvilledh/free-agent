package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.13: the graded chat reports a chat-born task leaves in
 *  its originating thread — composed from the record alone,
 *  material states only, each state exactly once. */
class ChatTaskLinkTest {

    private fun task(
        state: String,
        chat: Boolean = true,
        reported: String = "",
        summary: String = "",
        note: String = "",
        wallStep: String = ""
    ): JSONObject {
        val t = JSONObject()
            .put("id", "task-test")
            .put("text", "do the thing")
            .put("state", state)
            .put("summary", summary)
            .put("note", note)
        if (chat) t.put(ChatTaskLink.ORIGIN_FIELD, "chat-1")
        if (reported.isNotEmpty()) {
            t.put(ChatTaskLink.REPORTED_FIELD, reported)
        }
        if (wallStep.isNotEmpty()) t.put("wallStep", wallStep)
        return t
    }

    @Test
    fun completedReportCarriesTheVerifiedSummaryAndCost() {
        val text = ChatTaskLink.reportFor(
            task(TaskStates.COMPLETED, summary = "Wrote digest.txt with 3 items."),
            TaskStates.COMPLETED
        )!!
        assertTrue(text.contains("finished"))
        assertTrue(text.contains("Wrote digest.txt with 3 items."))
        assertTrue(text.contains(ChatTaskLink.COST_LINE))
    }

    @Test
    fun failedReportNeverClaimsDone() {
        val text = ChatTaskLink.reportFor(
            task(TaskStates.FAILED, note = "Free brain returned HTTP 503."),
            TaskStates.FAILED
        )!!
        assertTrue(text.contains("failed"))
        assertTrue(text.contains("nothing about it counts as done"))
        assertTrue(text.contains("Free brain returned HTTP 503."))
    }

    @Test
    fun parkedReportNamesTheOneStep() {
        val step = "Complete the human verification on x.com in " +
            "Lille's browser, then tap 'Done — hand back to Lille'."
        val text = ChatTaskLink.reportFor(
            task(TaskStates.PARKED_AT_WALL, wallStep = step),
            TaskStates.PARKED_AT_WALL
        )!!
        assertTrue(text.contains("one step"))
        assertTrue(text.contains(step))
    }

    @Test
    fun needsRunnerReportSaysExportedNotFaked() {
        val text = ChatTaskLink.reportFor(
            task(TaskStates.NEEDS_RUNNER, note = "Needs code execution."),
            TaskStates.NEEDS_RUNNER
        )!!
        assertTrue(text.contains("exported it as a task spec"))
        assertTrue(text.contains("instead of faking it"))
    }

    @Test
    fun pausedReportPointsAtResume() {
        val text = ChatTaskLink.reportFor(
            task(TaskStates.PAUSED, note = "Step budget (8) reached — resume to continue."),
            TaskStates.PAUSED
        )!!
        assertTrue(text.contains("paused"))
        assertTrue(text.contains("resume it from the Tasks screen"))
    }

    @Test
    fun waitingAndRunningStatesComposeNothing() {
        for (state in listOf(
            TaskStates.QUEUED, TaskStates.RUNNING,
            TaskStates.WAITING_NETWORK, TaskStates.WAITING_ROUTE,
            TaskStates.CANCELLED
        )) {
            assertNull(
                "state $state must not report",
                ChatTaskLink.reportFor(task(state), state)
            )
        }
    }

    @Test
    fun alreadyReportedStateIsSilent() {
        assertNull(
            ChatTaskLink.reportFor(
                task(
                    TaskStates.COMPLETED,
                    reported = TaskStates.COMPLETED,
                    summary = "Done."
                ),
                TaskStates.COMPLETED
            )
        )
    }

    @Test
    fun intakeNoteCarriesTheFluencyStandard() {
        // The standing direction on every chat-born task:
        // gather missing pieces herself; the only stop is a
        // step that is genuinely the user's alone.
        assertTrue(ChatTaskLink.INTAKE_NOTE.contains("gather it yourself"))
        assertTrue(
            ChatTaskLink.INTAKE_NOTE.contains("genuinely the user's alone")
        )
        assertTrue(ChatTaskLink.INTAKE_NOTE.contains("do not stop to ask"))
    }

    @Test
    fun tasksScreenTasksNeverReportToChat() {
        for (state in ChatTaskLink.MATERIAL) {
            assertNull(
                ChatTaskLink.reportFor(
                    task(state, chat = false, summary = "x", note = "y"),
                    state
                )
            )
        }
    }
}
