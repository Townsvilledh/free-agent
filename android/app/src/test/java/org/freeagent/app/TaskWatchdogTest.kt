package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-healing watchdog (v1.0.2): Ethuram's two tasks
 * sat unmoved 35+ minutes after he added his Groq key —
 * STUCK, not resting. These pins cover the pure selectors
 * (the WorkManager glue is compile-gated by CI):
 * expired waits get a pass on app open; stale waits are
 * force-retried despite a future nextRetryAt; Run now is
 * offered exactly for waiting states; a forced retry that
 * still ends waiting leaves a VISIBLE note, never silence.
 */
class TaskWatchdogTest {

    private val now = 10_000_000L

    private fun task(
        id: String, state: String,
        nextRetryAt: Long? = null, updatedAt: Long = now
    ): JSONObject {
        val t = JSONObject().put("id", id).put("state", state)
            .put("updatedAt", updatedAt)
        if (nextRetryAt != null) t.put("nextRetryAt", nextRetryAt)
        return t
    }

    // App-open kick: expired wait → pass enqueued.

    @Test
    fun expiredWaitsAreKickedOnAppOpen() {
        val tasks = listOf(
            task("due", TaskStates.WAITING_ROUTE, nextRetryAt = now - 1_000L),
            task("due-exact", TaskStates.WAITING_NETWORK, nextRetryAt = now),
            task("not-yet", TaskStates.WAITING_ROUTE, nextRetryAt = now + 30_000L),
            task("running", TaskStates.RUNNING, nextRetryAt = now - 1_000L),
            task("parked", TaskStates.PARKED_AT_WALL, nextRetryAt = now - 1_000L)
        )
        assertEquals(
            listOf("due", "due-exact"),
            TaskWatchdog.expiredWaitIds(tasks, now)
        )
    }

    @Test
    fun waitingTaskWithNoRetryTimeCountsAsExpired() {
        // A waiting record with no nextRetryAt is itself
        // stale state — the kick treats it as due.
        val tasks = listOf(task("lost", TaskStates.WAITING_ROUTE))
        assertEquals(
            listOf("lost"),
            TaskWatchdog.expiredWaitIds(tasks, now)
        )
    }

    // Stale-wait breaker: >10 min unmoved + route available
    // → forced retry DESPITE a future nextRetryAt.

    @Test
    fun staleWaitIsForcedDespiteFutureRetryTime() {
        val stuck = task(
            "stuck", TaskStates.WAITING_ROUTE,
            nextRetryAt = now + 3_600_000L, // an hour out — corrupt
            updatedAt = now - 35 * 60_000L // unmoved 35 minutes
        )
        assertEquals(
            listOf("stuck"),
            TaskWatchdog.staleWaitIds(listOf(stuck), now, routeAvailable = true)
        )
        // The same record is NOT expired — only the breaker
        // can move it. That is the whole point.
        assertTrue(TaskWatchdog.expiredWaitIds(listOf(stuck), now).isEmpty())
    }

    @Test
    fun staleBreakerNeedsARouteAndRealStillness() {
        val stuck = task(
            "stuck", TaskStates.WAITING_NETWORK,
            nextRetryAt = now + 3_600_000L,
            updatedAt = now - 35 * 60_000L
        )
        // No route configured/eligible: nothing to force with.
        assertTrue(
            TaskWatchdog.staleWaitIds(
                listOf(stuck), now, routeAvailable = false
            ).isEmpty()
        )
        // A fresh wait (2 min unmoved) is a rest, not stuck.
        val fresh = task(
            "fresh", TaskStates.WAITING_ROUTE,
            nextRetryAt = now + 30_000L,
            updatedAt = now - 120_000L
        )
        assertTrue(
            TaskWatchdog.staleWaitIds(
                listOf(fresh), now, routeAvailable = true
            ).isEmpty()
        )
        // Parked waits on a human — the breaker never fires.
        val parked = task(
            "parked", TaskStates.PARKED_AT_WALL,
            updatedAt = now - 3_600_000L
        )
        assertTrue(
            TaskWatchdog.staleWaitIds(
                listOf(parked), now, routeAvailable = true
            ).isEmpty()
        )
        assertEquals(600_000L, TaskWatchdog.STALE_MS)
    }

    // Run now: offered exactly for waiting states.

    @Test
    fun runNowIsOfferedExactlyForWaitingStates() {
        assertTrue(TaskWatchdog.runNowOffered(TaskStates.WAITING_ROUTE))
        assertTrue(TaskWatchdog.runNowOffered(TaskStates.WAITING_NETWORK))
        assertFalse(TaskWatchdog.runNowOffered(TaskStates.RUNNING))
        assertFalse(TaskWatchdog.runNowOffered(TaskStates.QUEUED))
        assertFalse(TaskWatchdog.runNowOffered(TaskStates.PARKED_AT_WALL))
        assertFalse(TaskWatchdog.runNowOffered(TaskStates.COMPLETED))
        assertFalse(TaskWatchdog.runNowOffered(TaskStates.FAILED))
    }

    // A forced retry that still ends waiting is VISIBLE:
    // the watchdog note renders on the status line in work
    // terms, with no mechanics vocabulary.

    @Test
    fun forcedStillWaitingLeavesAVisibleNote() {
        val t = task(
            "stuck", TaskStates.WAITING_ROUTE,
            nextRetryAt = now + 60_000L,
            updatedAt = now - 60_000L
        )
        t.put("text", "Join the Discord and note the perk codes")
        t.put("watchdogNote", TaskWatchdog.FORCED_STILL_WAITING_NOTE)
        val status = TaskTransparency.statusText(t, now)
        assertTrue(status.contains("A fresh try was made just now"))
        // Without the field, the line is absent — the note
        // is earned by an actual forced pass, not decoration.
        val plain = task(
            "plain", TaskStates.WAITING_ROUTE,
            nextRetryAt = now + 60_000L, updatedAt = now - 60_000L
        )
        assertFalse(
            TaskTransparency.statusText(plain, now)
                .contains("A fresh try was made")
        )
        // The note itself carries no banned vocabulary.
        assertEquals(
            TaskWatchdog.FORCED_STILL_WAITING_NOTE,
            NarrationGuard.cleanOrNull(TaskWatchdog.FORCED_STILL_WAITING_NOTE)
        )
    }
}
