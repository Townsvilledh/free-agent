package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STATUS FROM STATE: a bare probe in a chat with work is
 * answered from the record with no brain call. Pinned
 * both ways — probes recognised, lookalikes carrying an
 * object NOT intercepted, and the composed answer built
 * only from record fields (with narration notes vetted).
 */
class StatusProbeTest {

    private val now = 61_000L

    private fun task(
        state: String,
        chatId: String = "chat-1",
        steps: Int = 0,
        summary: String = "",
        note: String = "",
        wallStep: String = "",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        nextRetryAt: Long = 0L
    ): JSONObject {
        val t = JSONObject()
            .put("id", "task-x")
            .put("text", "Do the thing")
            .put("state", state)
            .put("steps", steps)
            .put("summary", summary)
            .put("note", note)
            .put("nextRetryAt", nextRetryAt)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
        if (chatId.isNotEmpty()) t.put(ChatTaskLink.ORIGIN_FIELD, chatId)
        if (wallStep.isNotEmpty()) t.put("wallStep", wallStep)
        return t
    }

    @Test
    fun bareProbesAreRecognised() {
        for (probe in listOf(
            "update", "Update", "update?", "UPDATE!", "status",
            "progress", "how far", "How far?", "what's happening",
            "any update", "update please", "is it done", "done yet",
            "still working", "give me an update", "how's it going"
        )) {
            assertTrue("probe: $probe", StatusProbe.isProbe(probe))
        }
    }

    @Test
    fun lookalikesAreNotProbes() {
        for (notProbe in listOf(
            "update my bio",
            "check the status of bitcoin",
            "update the document with the new totals",
            "what's happening in Lagos this weekend",
            "how far is the airport from Ikeja",
            "progress on my loan application with the bank",
            "is it done downloading the file I sent you yesterday morning",
            "",
            "hello"
        )) {
            assertFalse("not a probe: $notProbe", StatusProbe.isProbe(notProbe))
        }
    }

    @Test
    fun latestTaskIsTheChatsNewestWork() {
        val older = task(TaskStates.COMPLETED, createdAt = 100L, updatedAt = 100L)
        val newer = task(TaskStates.RUNNING, createdAt = 200L, updatedAt = 200L)
        val otherChat = task(TaskStates.RUNNING, chatId = "chat-2",
            createdAt = 300L, updatedAt = 300L)
        val found = StatusProbe.latestTaskForChat(
            listOf(older, newer, otherChat), "chat-1"
        )
        assertTrue(found === newer)
        assertNull(StatusProbe.latestTaskForChat(listOf(otherChat), "chat-1"))
        assertNull(StatusProbe.latestTaskForChat(emptyList(), "chat-1"))
    }

    @Test
    fun runningAnswerComesFromTheRecord() {
        // v1.0.2: the answer is the rich rendering — title,
        // state in work terms, last-movement time — not the
        // bare step count 1.0.1 shipped (steps here carry
        // no recorded messages, so no step can be named;
        // named-step pins live in TaskTransparencyTest).
        val answer = StatusProbe.answerFor(
            task(TaskStates.RUNNING, steps = 3), now
        )
        assertTrue(answer.contains("Working on it here"))
        assertTrue(answer.contains("Do the thing"))
        assertTrue(answer.contains("Last movement"))
    }

    @Test
    fun waitingAnswerCarriesTheReasonAndRetryTime() {
        val answer = StatusProbe.answerFor(
            task(
                TaskStates.WAITING_ROUTE, steps = 4,
                updatedAt = 1_000L, nextRetryAt = 181_000L
            ),
            now
        )
        assertTrue(answer.contains("Taking a short pause before the next step"))
        assertTrue(answer.contains("she continues on her own"))
        assertTrue(
            answer.contains(
                "Next try around " + TaskTransparency.clockLabel(181_000L) + "."
            )
        )
    }

    @Test
    fun probeAnswerMarksNoChangeHonestly() {
        val t = task(TaskStates.RUNNING, steps = 2, updatedAt = 1_000L)
        val answer = StatusProbe.answerFor(t, now, noChangeSince = 1_000L)
        assertTrue(
            answer.contains(
                "No change since " + TaskTransparency.clockLabel(1_000L) + "."
            )
        )
        // Without the no-change stamp the same block
        // carries no such line.
        assertFalse(
            StatusProbe.answerFor(t, now).contains("No change since")
        )
    }

    @Test
    fun parkedAnswerNamesTheStep() {
        val answer = StatusProbe.answerFor(
            task(
                TaskStates.PARKED_AT_WALL, steps = 5,
                wallStep = "Complete the human check on x.com."
            ),
            now
        )
        assertTrue(answer.contains("Waiting on one step from you"))
        assertTrue(answer.contains("Complete the human check on x.com."))
    }

    @Test
    fun completedAnswerCarriesTheSummary() {
        val answer = StatusProbe.answerFor(
            task(TaskStates.COMPLETED, steps = 4, summary = "Wrote digest.txt."),
            now
        )
        assertTrue(answer.contains("Finished"))
        assertTrue(answer.contains("Wrote digest.txt."))
    }

    @Test
    fun narrationNotesAreWithheldFromAnswers() {
        val answer = StatusProbe.answerFor(
            task(
                TaskStates.RUNNING, steps = 1,
                note = "Free route rate-limited — retrying on the community route."
            ),
            now
        )
        assertFalse(answer.contains("rate-limited"))
        assertFalse(answer.contains("community route"))
        assertTrue(answer.contains("Working on it here"))
    }

    @Test
    fun statusLinesArePlainForEveryState() {
        for (state in TaskStates.ALL) {
            val line = StatusProbe.statusLineFor(task(state, steps = 2))
            assertFalse(
                "state $state line must be clean: $line",
                NarrationGuard.containsNarration(line)
            )
        }
        assertEquals(
            "Queued here — starts on its own.",
            StatusProbe.statusLineFor(task(TaskStates.QUEUED))
        )
    }
}
