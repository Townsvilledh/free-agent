package org.freeagent.app

/**
 * Durable-task state machine (v0.4) — the SAME state names as the
 * Python core (free_agent/taskstore.py), plus one Android-only state:
 * NEEDS_RUNNER, for tasks the phone must not fake (code execution) —
 * those are exported as a task spec for the GitHub Actions runner
 * lane instead (OFFLINE.md mode C).
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

    val ALL = setOf(
        QUEUED, RUNNING, WAITING_NETWORK, WAITING_ROUTE, PAUSED,
        FAILED, COMPLETED, CANCELLED, NEEDS_RUNNER
    )

    private val TRANSITIONS: Map<String, Set<String>> = mapOf(
        QUEUED to setOf(RUNNING, CANCELLED, PAUSED),
        RUNNING to setOf(
            WAITING_NETWORK, WAITING_ROUTE, PAUSED, FAILED,
            COMPLETED, CANCELLED, NEEDS_RUNNER
        ),
        WAITING_NETWORK to setOf(RUNNING, PAUSED, CANCELLED),
        WAITING_ROUTE to setOf(RUNNING, PAUSED, CANCELLED),
        PAUSED to setOf(QUEUED, RUNNING, CANCELLED),
        FAILED to setOf(QUEUED),               // honest retry is allowed
        COMPLETED to emptySet(),
        CANCELLED to emptySet(),
        NEEDS_RUNNER to setOf(CANCELLED, QUEUED) // queued = dispatched to a runner
    )

    fun canTransition(from: String, to: String): Boolean =
        TRANSITIONS[from]?.contains(to) == true

    fun isTerminal(state: String): Boolean =
        state == COMPLETED || state == CANCELLED

    /** States a boot/WorkManager recovery pass should re-queue. */
    fun isResumable(state: String): Boolean =
        state == QUEUED || state == RUNNING ||
            state == WAITING_NETWORK || state == WAITING_ROUTE

    /** Waiting states are NOT failures: the network or the free
     *  route will come back, and the task resumes by itself. */
    fun isWaiting(state: String): Boolean =
        state == WAITING_NETWORK || state == WAITING_ROUTE
}
