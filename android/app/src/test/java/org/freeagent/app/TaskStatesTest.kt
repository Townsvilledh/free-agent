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
}
