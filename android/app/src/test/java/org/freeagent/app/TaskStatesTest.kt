package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the durable-task state machine (v0.4). */
class TaskStatesTest {

    @Test
    fun happyPathTransitions() {
        assertTrue(TaskStates.canTransition(TaskStates.QUEUED, TaskStates.RUNNING))
        assertTrue(TaskStates.canTransition(TaskStates.RUNNING, TaskStates.COMPLETED))
        assertTrue(TaskStates.canTransition(TaskStates.RUNNING, TaskStates.PAUSED))
        assertTrue(TaskStates.canTransition(TaskStates.PAUSED, TaskStates.RUNNING))
    }

    @Test
    fun waitingIsNotFailure() {
        assertTrue(TaskStates.canTransition(TaskStates.RUNNING, TaskStates.WAITING_NETWORK))
        assertTrue(TaskStates.canTransition(TaskStates.RUNNING, TaskStates.WAITING_ROUTE))
        assertTrue(TaskStates.canTransition(TaskStates.WAITING_NETWORK, TaskStates.RUNNING))
        assertTrue(TaskStates.canTransition(TaskStates.WAITING_ROUTE, TaskStates.RUNNING))
        assertTrue(TaskStates.isWaiting(TaskStates.WAITING_NETWORK))
        assertFalse(TaskStates.isTerminal(TaskStates.WAITING_ROUTE))
    }

    @Test
    fun terminalStatesAreFinal() {
        assertFalse(TaskStates.canTransition(TaskStates.COMPLETED, TaskStates.RUNNING))
        assertFalse(TaskStates.canTransition(TaskStates.CANCELLED, TaskStates.RUNNING))
        assertTrue(TaskStates.isTerminal(TaskStates.COMPLETED))
        assertTrue(TaskStates.isTerminal(TaskStates.CANCELLED))
    }

    @Test
    fun recoverySetCoversUnfinishedStates() {
        assertTrue(TaskStates.isResumable(TaskStates.QUEUED))
        assertTrue(TaskStates.isResumable(TaskStates.RUNNING))
        assertTrue(TaskStates.isResumable(TaskStates.WAITING_NETWORK))
        assertTrue(TaskStates.isResumable(TaskStates.WAITING_ROUTE))
        assertFalse(TaskStates.isResumable(TaskStates.COMPLETED))
        assertFalse(TaskStates.isResumable(TaskStates.PAUSED))
    }

    @Test
    fun needsRunnerIsAndroidOnlyEscapeHatch() {
        assertTrue(TaskStates.canTransition(TaskStates.RUNNING, TaskStates.NEEDS_RUNNER))
        assertTrue(TaskStates.canTransition(TaskStates.NEEDS_RUNNER, TaskStates.QUEUED))
        assertEquals(true, TaskStates.ALL.contains(TaskStates.NEEDS_RUNNER))
    }

    @Test
    fun failedTasksMayBeRequeued() {
        assertTrue(TaskStates.canTransition(TaskStates.FAILED, TaskStates.QUEUED))
        assertFalse(TaskStates.canTransition(TaskStates.FAILED, TaskStates.COMPLETED))
    }

    @Test
    fun parkedAtWallLifecycle() {
        // A running browser task parks at an observed wall...
        assertTrue(TaskStates.canTransition(
            TaskStates.RUNNING, TaskStates.PARKED_AT_WALL))
        // ...the human handback resumes it (park -> resume)...
        assertTrue(TaskStates.canTransition(
            TaskStates.PARKED_AT_WALL, TaskStates.RUNNING))
        // ...and from running it can complete (park -> resume -> complete).
        assertTrue(TaskStates.canTransition(
            TaskStates.RUNNING, TaskStates.COMPLETED))
        // ...or the user gives up on it (park -> cancel).
        assertTrue(TaskStates.canTransition(
            TaskStates.PARKED_AT_WALL, TaskStates.CANCELLED))
        // A parked task cannot skip the human step: no direct
        // completion, no parking from queued, no un-parking to queued.
        assertFalse(TaskStates.canTransition(
            TaskStates.PARKED_AT_WALL, TaskStates.COMPLETED))
        assertFalse(TaskStates.canTransition(
            TaskStates.QUEUED, TaskStates.PARKED_AT_WALL))
        assertFalse(TaskStates.canTransition(
            TaskStates.PARKED_AT_WALL, TaskStates.QUEUED))
    }

    @Test
    fun parkedIsNeitherTerminalNorAutoResumable() {
        // Parked waits on a HUMAN: not terminal, and deliberately
        // NOT in the boot/WorkManager recovery set — only the
        // handback resumes it.
        assertFalse(TaskStates.isTerminal(TaskStates.PARKED_AT_WALL))
        assertFalse(TaskStates.isResumable(TaskStates.PARKED_AT_WALL))
        assertFalse(TaskStates.isWaiting(TaskStates.PARKED_AT_WALL))
        assertTrue(TaskStates.isParked(TaskStates.PARKED_AT_WALL))
        assertTrue(TaskStates.ALL.contains(TaskStates.PARKED_AT_WALL))
    }
}
