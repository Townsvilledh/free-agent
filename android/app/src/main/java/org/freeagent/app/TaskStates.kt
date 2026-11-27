package org.freeagent.app

/**
 * Durable-task state machine (v0.4) — the SAME state names as the
 * Python core (free_agent/taskstore.py), plus one Android-only state:
 * NEEDS_RUNNER, for tasks the phone must not fake (code execution) —
 * those are exported as a task spec for the GitHub Actions runner
 * lane instead (OFFLINE.md mode C).
 *
 * PARKED_AT_WALL (v0.9, WALLS.md build-next #3) is shared with the
 * core: a browser task that OBSERVED a human-only wall (a CHALLENGE
 * tool result — evidence, never narration) parks instead of
 * completing or failing. The record carries the wall (domain, kind,
 * the ONE named step). On-device the wall is reachable: the
 * "Lille needs you" card hands Ethuram the parked WebView session,
 * he passes the check, and the handback moves the task
 * PARKED_AT_WALL -> RUNNING, from where it can complete. A parked
 * task is deliberately NOT in the boot/WorkManager recovery set:
 * it resumes only when the human step is taken, never on a timer.
 *
 * Pure Kotlin (no Android imports) so the transition rules are
 * unit-testable on the JVM.
 */
object TaskStates {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val WAITING_NETWORK = "waiting_network"
    const val WAITING_ROUTE = "waiting_route"
    const val PAUSED = "paused"
    const val FAILED = "failed"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
    const val NEEDS_RUNNER = "needs_runner"
    const val PARKED_AT_WALL = "parked_at_wall"

    val ALL = setOf(
        QUEUED, RUNNING, WAITING_NETWORK, WAITING_ROUTE, PAUSED,
        FAILED, COMPLETED, CANCELLED, NEEDS_RUNNER, PARKED_AT_WALL
    )

    private val TRANSITIONS: Map<String, Set<String>> = mapOf(
        QUEUED to setOf(RUNNING, CANCELLED, PAUSED),
        RUNNING to setOf(
            WAITING_NETWORK, WAITING_ROUTE, PAUSED, FAILED,
            COMPLETED, CANCELLED, NEEDS_RUNNER, PARKED_AT_WALL
        ),
        WAITING_NETWORK to setOf(RUNNING, PAUSED, CANCELLED),
        WAITING_ROUTE to setOf(RUNNING, PAUSED, CANCELLED),
        PAUSED to setOf(QUEUED, RUNNING, CANCELLED),
        FAILED to setOf(QUEUED),               // honest retry is allowed
        COMPLETED to emptySet(),
        CANCELLED to emptySet(),
        NEEDS_RUNNER to setOf(CANCELLED, QUEUED), // queued = dispatched to a runner
        // The human took the step (handback -> RUNNING, then the
        // runner may complete it), or the user gives up on it.
        PARKED_AT_WALL to setOf(RUNNING, CANCELLED)
    )

    fun canTransition(from: String, to: String): Boolean =
        TRANSITIONS[from]?.contains(to) == true

    fun isTerminal(state: String): Boolean =
        state == COMPLETED || state == CANCELLED

    /** States a boot/WorkManager recovery pass should re-queue.
     *  PARKED_AT_WALL is excluded on purpose: only the human
     *  handback resumes a parked task. */
    fun isResumable(state: String): Boolean =
        state == QUEUED || state == RUNNING ||
            state == WAITING_NETWORK || state == WAITING_ROUTE

    /** Waiting states are NOT failures: the network or the free
     *  route will come back, and the task resumes by itself. */
    fun isWaiting(state: String): Boolean =
        state == WAITING_NETWORK || state == WAITING_ROUTE

    /** A parked task is waiting on a HUMAN, not on the network:
     *  not a failure, not terminal, never auto-resumed. */
    fun isParked(state: String): Boolean = state == PARKED_AT_WALL

    /** The ids of every record currently in a waiting state —
     *  the WAKE ON NEW CAPACITY pass (v1.0.2): when new brain
     *  capacity lands (an own key saved in Settings), every
     *  waiting task gets an expedited retry instead of
     *  sleeping out a rest that was timed against capacity
     *  that no longer bounds it. Parked tasks are NOT woken:
     *  they wait on a human, never on a timer. Pure selector
     *  over the records; TaskWorker does the enqueueing. */
    fun waitingTaskIds(tasks: List<org.json.JSONObject>): List<String> =
        tasks.filter { isWaiting(it.optString("state")) }
            .map { it.optString("id") }
            .filter { it.isNotEmpty() }
}
