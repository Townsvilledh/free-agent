package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The chats-list work mark: live states mark plainly;
 *  quiet states mark nothing. */
class ChatWorkMarkTest {

    @Test
    fun liveStatesMark() {
        assertEquals("· working", ChatWorkMark.markFor(TaskStates.RUNNING))
        assertEquals("· queued", ChatWorkMark.markFor(TaskStates.QUEUED))
        assertEquals("· waiting", ChatWorkMark.markFor(TaskStates.WAITING_ROUTE))
        assertEquals("· waiting", ChatWorkMark.markFor(TaskStates.WAITING_NETWORK))
        assertEquals("· needs you", ChatWorkMark.markFor(TaskStates.PARKED_AT_WALL))
        assertEquals("· paused", ChatWorkMark.markFor(TaskStates.PAUSED))
        assertEquals("· failed", ChatWorkMark.markFor(TaskStates.FAILED))
        assertEquals(
            "· with the runner", ChatWorkMark.markFor(TaskStates.NEEDS_RUNNER)
        )
    }

    @Test
    fun quietStatesMarkNothing() {
        assertNull(ChatWorkMark.markFor(TaskStates.COMPLETED))
        assertNull(ChatWorkMark.markFor(TaskStates.CANCELLED))
        assertNull(ChatWorkMark.markFor(null))
        assertNull(ChatWorkMark.markFor(""))
    }

    /** v1.0.5: the record form — a task killed by the
     *  user's own hand marks "· stopped" (the glance form
     *  of "Stopped — you stopped this."); a plain
     *  cancelled record stays quiet, exactly like the
     *  state form. */
    @Test
    fun killedRecordsMarkStopped() {
        val killed = JSONObject()
            .put("state", TaskStates.CANCELLED)
            .put(TaskControl.KILLED_FIELD, true)
        assertEquals("· stopped", ChatWorkMark.markFor(killed))
        val plainCancelled = JSONObject()
            .put("state", TaskStates.CANCELLED)
        assertNull(ChatWorkMark.markFor(plainCancelled))
        val running = JSONObject().put("state", TaskStates.RUNNING)
        assertEquals("· working", ChatWorkMark.markFor(running))
    }

    /** The glance obeys the same law as the full status
     *  (v1.0.2): a mark is work language only — no state
     *  may surface mechanics vocabulary in the list. */
    @Test
    fun marksCarryNoMechanicsVocabulary() {
        for (state in TaskStates.ALL) {
            val mark = ChatWorkMark.markFor(state) ?: continue
            assertFalse(
                "state $state mark must be clean: $mark",
                NarrationGuard.containsNarration(mark)
            )
        }
    }
}
