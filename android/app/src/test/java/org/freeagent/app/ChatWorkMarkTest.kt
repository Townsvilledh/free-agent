package org.freeagent.app

import org.junit.Assert.assertEquals
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
}
