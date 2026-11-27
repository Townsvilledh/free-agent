package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NO SLEEPING WHILE A ROUTE LIVES (v1.0.2, Ethuram's hard
 * requirement): a task's rest is the shortest PER-ROUTE
 * cooldown, never an escalating task-level backoff; pacing
 * floors bind per route only; new capacity wakes waiting
 * tasks at once. The arithmetic lives in BrainPacing +
 * TaskStates (pure); the WorkManager/Settings glue is thin
 * and compile-gated by CI.
 */
class RouteRestTest {

    // (b) rest = shortest route cooldown, never escalating
    // at task level across repeated failures.

    @Test
    fun restIsTheShortestRouteCooldown() {
        assertEquals(
            45_000L,
            BrainPacing.restMs(listOf(300_000L, 45_000L, 900_000L))
        )
        assertEquals(30_000L, BrainPacing.restMs(listOf(30_000L)))
    }

    @Test
    fun restNeverEscalatesAtTaskLevel() {
        // The same per-route state yields the same rest on
        // the first failure and the tenth: restMs takes no
        // streak, so there is nothing at task level TO
        // escalate. (Each route's own cooldown may escalate
        // via cooldownMs — the shortest still governs.)
        val state = listOf(120_000L, 45_000L)
        val first = BrainPacing.restMs(state)
        repeat(9) {
            assertEquals(first, BrainPacing.restMs(state))
        }
        // Even when one route's own cooldown HAS escalated
        // hard, the rest tracks the shortest route, capped.
        assertEquals(
            45_000L,
            BrainPacing.restMs(listOf(1_800_000L, 45_000L))
        )
    }

    @Test
    fun restIsCappedAtSixtySecondsWhileRoutesAreConfigured() {
        assertEquals(
            BrainPacing.MAX_REST_MS,
            BrainPacing.restMs(listOf(900_000L, 300_000L))
        )
        assertTrue(BrainPacing.MAX_REST_MS <= 60_000L)
        // No route configured at all (unreachable in the
        // app — the keyless pair always exists): still one
        // capped stretch, never a spin and never a blowout.
        assertEquals(
            BrainPacing.MAX_REST_MS,
            BrainPacing.restMs(emptyList())
        )
    }

    // (c) a task with a healthy own-key route never rests
    // more than ~60 s — a healthy route contributes 0.

    @Test
    fun healthyRouteMeansNoRest() {
        assertEquals(
            0L,
            BrainPacing.restMs(listOf(0L, 900_000L, 1_800_000L))
        )
        assertEquals(0L, BrainPacing.restMs(listOf(0L)))
    }

    // (d) pacing binds per route only: a step a healthy
    // route can serve is not delayed by another route's
    // pacing floor.

    @Test
    fun pacingFloorBindsOnlyTheRouteThatDemandsIt() {
        val now = 1_000_000L
        val justCalled = now - 1_000L // 1 s ago
        // Pollinations-class: inside its ~50 s floor.
        assertTrue(
            BrainPacing.routePacingWaitMs(
                "pollinations", justCalled, now
            ) > 0L
        )
        // The user's own Groq key, called at the very same
        // moment: NO floor — the step proceeds at once.
        assertEquals(
            0L,
            BrainPacing.routePacingWaitMs("groq", justCalled, now)
        )
        assertEquals(
            0L,
            BrainPacing.routePacingWaitMs("horde", justCalled, now)
        )
        assertEquals(
            0L,
            BrainPacing.routePacingWaitMs("custom", justCalled, now)
        )
        // And once the floor has elapsed, even the floored
        // route is free.
        assertEquals(
            0L,
            BrainPacing.routePacingWaitMs(
                "pollinations", now - BrainPacing.MIN_INTERVAL_MS, now
            )
        )
        // A route never called may be called now.
        assertEquals(
            0L,
            BrainPacing.routePacingWaitMs("pollinations", 0L, now)
        )
    }

    // (a) key-save wakes waiting tasks: the pure selector
    // the wake pass enqueues from — exactly the waiting
    // states, never parked (human), never terminal.

    @Test
    fun wakePassSelectsExactlyTheWaitingTasks() {
        fun task(id: String, state: String) =
            JSONObject().put("id", id).put("state", state)
        val tasks = listOf(
            task("a", TaskStates.WAITING_ROUTE),
            task("b", TaskStates.RUNNING),
            task("c", TaskStates.WAITING_NETWORK),
            task("d", TaskStates.PARKED_AT_WALL),
            task("e", TaskStates.COMPLETED),
            task("f", TaskStates.QUEUED),
            task("g", TaskStates.FAILED)
        )
        assertEquals(
            listOf("a", "c"),
            TaskStates.waitingTaskIds(tasks)
        )
        assertTrue(TaskStates.waitingTaskIds(emptyList()).isEmpty())
    }
}
