package org.freeagent.app

/**
 * The brain lane (2026-11-26, multitasking ruling):
 * process-wide arbitration for brain calls between CHAT
 * and TASKS, so a chat answer never waits behind task
 * brain work, and two tasks' calls never hammer the same
 * route at the same instant.
 *
 * The rules (all pinned by BrainLaneTest):
 * - CHAT NEVER WAITS. [chatWaitMs] is 0 by construction —
 *   there is no task state that delays a chat call. Chat
 *   firing while a task call is in flight is fine: the
 *   chain's silent fall-through absorbs a busy route.
 * - ONE task brain call in flight at a time. Task steps
 *   still overlap (a step is mostly tool work and waiting;
 *   the brain call itself is seconds), but two tasks never
 *   fire at the same route simultaneously — the keyless
 *   anonymous tier allows roughly one concurrent request
 *   per IP, and pretending otherwise just manufactures
 *   rate limits for both.
 * - CHAT YIELD: after a chat call finishes, a task call
 *   waits a short beat ([CHAT_YIELD_MS]) before firing —
 *   the user's conversation keeps the pipe it just used.
 *
 * This component decides; the glue sleeps. TaskRunner
 * polls [taskCallWaitMs] in-worker for a bounded stretch,
 * then falls back to its ordinary waiting state. Route
 * health/cooldowns stay with BrainFailure + the router —
 * this lane never touches them, so concurrency can never
 * become hammering a cooling route: a cooling route is
 * skipped by the chain exactly as before, whoever calls.
 */
class BrainLane {

    companion object {
        /** How often a waiting task re-checks the lane. */
        const val POLL_MS = 2_000L

        /** The beat a task leaves after a chat call before
         *  taking the pipe. */
        const val CHAT_YIELD_MS = 3_000L

        /** The process-wide lane the glue arbitrates on. */
        val shared = BrainLane()
    }

    private var chatInFlight = false
    private var chatLastFinishedAtMs = 0L
    private var taskInFlightId: String? = null

    /** Chat's contract: it never waits for the lane. */
    fun chatWaitMs(nowMs: Long): Long = 0L

    @Synchronized
    fun chatStarted() {
        chatInFlight = true
    }

    @Synchronized
    fun chatFinished(nowMs: Long) {
        chatInFlight = false
        chatLastFinishedAtMs = nowMs
    }

    /**
     * Milliseconds a task brain call must wait before it
     * may fire; 0 means fire now. Positive values are a
     * poll hint, not a promise about the route.
     */
    @Synchronized
    fun taskCallWaitMs(taskId: String, nowMs: Long): Long {
        if (chatInFlight) return POLL_MS
        val inFlight = taskInFlightId
        if (inFlight != null && inFlight != taskId) return POLL_MS
        if (chatLastFinishedAtMs > 0L) {
            val sinceChat = nowMs - chatLastFinishedAtMs
            if (sinceChat < CHAT_YIELD_MS) return CHAT_YIELD_MS - sinceChat
        }
        return 0L
    }

    @Synchronized
    fun taskStarted(taskId: String) {
        taskInFlightId = taskId
    }

    @Synchronized
    fun taskFinished(taskId: String) {
        if (taskInFlightId == taskId) taskInFlightId = null
    }

    /** Test/inspection hooks (traces only). */
    @Synchronized
    fun isChatInFlight(): Boolean = chatInFlight

    @Synchronized
    fun taskInFlight(): String? = taskInFlightId
}
