package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded task concurrency + the exclusive browser lane.
 * The gate is admission only — a denied run retries later
 * with its record untouched, so serialisation never fails
 * a task. These tests simulate the interleavings the
 * workers will produce.
 */
class TaskGateTest {

    @Test
    fun twoTasksInterleaveToCompletion() {
        val gate = TaskGate()
        val progress = mutableMapOf("a" to 0, "b" to 0)
        assertTrue(gate.tryAcquire("a", needsBrowser = false))
        assertTrue(gate.tryAcquire("b", needsBrowser = false))
        // Interleaved steps: each run advances while the
        // other holds its slot — neither waits for the
        // other's whole run to finish.
        repeat(3) {
            progress["a"] = progress.getValue("a") + 1
            progress["b"] = progress.getValue("b") + 1
        }
        assertEquals(3, progress.getValue("a"))
        assertEquals(3, progress.getValue("b"))
        gate.release("a")
        gate.release("b")
        assertEquals(0, gate.runningCount())
    }

    @Test
    fun thirdTaskWaitsForASlot() {
        val gate = TaskGate()
        assertTrue(gate.tryAcquire("a", needsBrowser = false))
        assertTrue(gate.tryAcquire("b", needsBrowser = false))
        assertFalse(gate.tryAcquire("c", needsBrowser = false))
        gate.release("a")
        assertTrue(gate.tryAcquire("c", needsBrowser = false))
    }

    @Test
    fun browserAndBrainTasksOverlap() {
        val gate = TaskGate()
        assertTrue(gate.tryAcquire("browser-task", needsBrowser = true))
        // A brain/file/search task never touches the lane.
        assertTrue(gate.tryAcquire("brain-task", needsBrowser = false))
        assertEquals("browser-task", gate.browserOwnerTask())
    }

    @Test
    fun twoBrowserTasksSerialiseWithoutFailing() {
        val gate = TaskGate()
        assertTrue(gate.tryAcquire("browser-1", needsBrowser = true))
        // The second browser run is DENIED admission — not
        // failed: its worker retries, its record untouched.
        assertFalse(gate.tryAcquire("browser-2", needsBrowser = true))
        assertFalse(gate.isRunning("browser-2"))
        // A brain task still fits beside the lane holder.
        assertTrue(gate.tryAcquire("brain-1", needsBrowser = false))
        gate.release("browser-1")
        assertNull(gate.browserOwnerTask())
        // Now the second browser run is admitted and owns it.
        gate.release("brain-1")
        assertTrue(gate.tryAcquire("browser-2", needsBrowser = true))
        assertEquals("browser-2", gate.browserOwnerTask())
        gate.release("browser-2")
        assertEquals(0, gate.runningCount())
    }
}
