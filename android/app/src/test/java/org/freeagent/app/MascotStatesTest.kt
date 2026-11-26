package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Direction 1: the mascot IS the status system — the mapping
 *  from real app/task states to mascot states is pinned here. */
class MascotStatesTest {

    @Test
    fun parkedAndFailedTasksAreStuck() {
        assertEquals(
            MascotStates.Mascot.STUCK,
            MascotStates.forTaskState(TaskStates.PARKED_AT_WALL)
        )
        assertEquals(
            MascotStates.Mascot.STUCK,
            MascotStates.forTaskState(TaskStates.FAILED)
        )
        assertEquals(
            MascotStates.Mascot.STUCK,
            MascotStates.forTaskState(TaskStates.NEEDS_RUNNER)
        )
    }

    @Test
    fun waitingStatesAreWaiting() {
        assertEquals(
            MascotStates.Mascot.WAITING,
            MascotStates.forTaskState(TaskStates.WAITING_ROUTE)
        )
        assertEquals(
            MascotStates.Mascot.WAITING,
            MascotStates.forTaskState(TaskStates.WAITING_NETWORK)
        )
        assertEquals(
            MascotStates.Mascot.WAITING,
            MascotStates.forTaskState(TaskStates.PAUSED)
        )
    }

    @Test
    fun runningWorkingCompletedDoneRestIdle() {
        assertEquals(
            MascotStates.Mascot.WORKING,
            MascotStates.forTaskState(TaskStates.RUNNING)
        )
        assertEquals(
            MascotStates.Mascot.DONE,
            MascotStates.forTaskState(TaskStates.COMPLETED)
        )
        assertEquals(
            MascotStates.Mascot.IDLE,
            MascotStates.forTaskState(TaskStates.QUEUED)
        )
        assertEquals(
            MascotStates.Mascot.IDLE,
            MascotStates.forTaskState(TaskStates.CANCELLED)
        )
        assertEquals(
            MascotStates.Mascot.IDLE,
            MascotStates.forTaskState("some_future_state")
        )
    }

    @Test
    fun chatStates() {
        assertEquals(
            MascotStates.Mascot.IDLE,
            MascotStates.forChat(busy = false, waiting = false)
        )
        assertEquals(
            MascotStates.Mascot.WORKING,
            MascotStates.forChat(busy = true, waiting = false)
        )
        assertEquals(
            MascotStates.Mascot.WAITING,
            MascotStates.forChat(busy = true, waiting = true)
        )
    }

    @Test
    fun drawableNamesResolve() {
        assertEquals("mascot_idle", MascotStates.drawableName(MascotStates.Mascot.IDLE))
        assertEquals("mascot_stuck", MascotStates.drawableName(MascotStates.Mascot.STUCK))
        assertEquals("mascot_waiting", MascotStates.drawableName(MascotStates.Mascot.WAITING))
    }
}
