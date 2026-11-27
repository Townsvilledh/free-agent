package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
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
            task(
                TaskStates.FAILED,
                note = "Stopped at a step that could not be completed."
            ),
            TaskStates.FAILED
        )!!
        assertTrue(text.contains("failed"))
        assertTrue(text.contains("nothing about it counts as done"))
        assertTrue(
            text.contains("Stopped at a step that could not be completed.")
        )
    }

    @Test
    fun failedReportWithholdsNarrationNotes() {
        // A legacy record's note carries route narration
        // (written by the shipped build): the report must
        // stand on the state and NEVER echo it (seamless law).
        val text = ChatTaskLink.reportFor(
            task(TaskStates.FAILED, note = "Free brain returned HTTP 503."),
            TaskStates.FAILED
        )!!
        assertTrue(text.contains("nothing about it counts as done"))
        assertFalse(text.contains("Free brain"))
        assertFalse(text.contains("HTTP"))
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
        // CHAT IS THE ENGINE: resume happens HERE, in the
        // thread — no screen is named, no hand-off exists.
        assertTrue(text.contains("Say the word here"))
        assertFalse(text.contains("Tasks screen"))
        assertFalse(text.contains("task screen"))
    }

    @Test
    fun startAckStaysInTheThread() {
        assertTrue(ChatTaskLink.START_ACK.contains("work on it here"))
        assertFalse(ChatTaskLink.START_ACK.contains("Tasks screen"))
        assertFalse(ChatTaskLink.START_ACK.contains("taken it on as a task"))
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
    fun partialCompletionIsLabelledPartialNeverFullDone() {
        // v1.0.3: a finish with computer-lane classes
        // outstanding is labelled partial — the headline
        // scopes itself, the not-done classes are named,
        // and the plain full-done headline never appears.
        val t = task(
            TaskStates.COMPLETED,
            summary = "Joined the Discord server and read the page."
        )
        t.put("partialCompletion", true)
        TaskTools.stampPlan(
            t,
            TaskTools.planFor(
                "Join the Discord server, read my Gmail inbox, " +
                    "and record a demo video"
            )
        )
        val text = ChatTaskLink.reportFor(t, TaskStates.COMPLETED)!!
        assertTrue(text.contains("(partial)"))
        // v1.0.4: the not-done parts are named by shape —
        // routed work carries its hand-off receipt, and the
        // email part names the one Settings step.
        assertTrue(text.contains("Handed to the computer lane"))
        assertTrue(text.contains("receipt:"))
        assertTrue(text.contains("video work"))
        assertTrue(text.contains("The email part is not done"))
        assertFalse(text.contains("Done — that work is finished."))
        assertTrue(text.contains(ChatTaskLink.COST_LINE))
    }

    @Test
    fun nonChatTasksNeverReportToChat() {
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
