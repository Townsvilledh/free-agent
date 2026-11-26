package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brain lane (multitasking): chat NEVER waits behind
 * task brain work — its wait is 0 by construction, in
 * every lane state. Task calls serialise among themselves
 * (one in flight) and yield a short beat after a chat
 * call, so the user's conversation keeps the pipe.
 */
class BrainLaneTest {

    @Test
    fun chatNeverWaitsEvenWithATaskCallInFlight() {
        val lane = BrainLane()
        lane.taskStarted("task-1")
        assertEquals(0L, lane.chatWaitMs(1_000L))
        assertEquals(0L, lane.chatWaitMs(999_999L))
    }

    @Test
    fun taskWaitsWhileChatIsInFlightThenYields() {
        val lane = BrainLane()
        lane.chatStarted()
        assertTrue(lane.taskCallWaitMs("task-1", 1_000L) > 0L)
        lane.chatFinished(2_000L)
        // The yield beat: immediately after chat, still waiting…
        assertEquals(
            BrainLane.CHAT_YIELD_MS,
            lane.taskCallWaitMs("task-1", 2_000L)
        )
        // …and clear once the beat has passed.
        assertEquals(
            0L,
            lane.taskCallWaitMs("task-1", 2_000L + BrainLane.CHAT_YIELD_MS)
        )
    }

    @Test
    fun onlyOneTaskCallFliesAtATime() {
        val lane = BrainLane()
        assertEquals(0L, lane.taskCallWaitMs("task-1", 10_000L))
        lane.taskStarted("task-1")
        assertTrue(lane.taskCallWaitMs("task-2", 10_100L) > 0L)
        // The SAME task re-checking its own call is not blocked.
        assertEquals(0L, lane.taskCallWaitMs("task-1", 10_100L))
        lane.taskFinished("task-1")
        assertEquals(0L, lane.taskCallWaitMs("task-2", 10_200L))
    }

    @Test
    fun taskMidRunChatAnsweredWithoutWaiting() {
        // The pinned scenario: a task is mid-run (its brain
        // call in flight) when the user sends a chat message.
        // The chat call's wait is zero — it fires at once;
        // the task's NEXT call is the one that yields.
        val lane = BrainLane()
        lane.taskStarted("task-1")
        val now = 50_000L
        assertEquals(0L, lane.chatWaitMs(now))
        lane.chatStarted()
        lane.chatFinished(now + 4_000L)
        assertTrue(lane.taskCallWaitMs("task-1", now + 4_000L) > 0L)
    }
}
