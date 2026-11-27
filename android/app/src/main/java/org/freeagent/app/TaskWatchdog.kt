package org.freeagent.app

import org.json.JSONObject

/**
 * The self-healing watchdog (v1.0.2, Ethuram 2026-11-27,
 * with screenshot proof): both on-device tasks sat unmoved
 * for 35+ minutes AFTER he added his Groq key — a STUCK
 * state, not a rest. The escalating-cooldown theory caps at
 * ~15 min, so something in the wait chain never fired
 * (stale nextRetryAt, a broken WorkManager backoff chain,
 * a silent error loop). The fix must not depend on which:
 * corrupted or stale wait state heals BY CONSTRUCTION.
 *
 * - APP-OPEN KICK: a task in a waiting state whose rest has
 *   expired (nextRetryAt due) gets an immediate pass
 *   whenever the app opens or a worker runs — an expired
 *   wait never just sits.
 * - STALE-WAIT BREAKER: a task in a waiting state whose
 *   record has not moved for [STALE_MS] while a route is
 *   configured and eligible is force-retried IGNORING its
 *   stored nextRetryAt and all pacing state (the glue
 *   clears the per-route rest/pacing fields at force time).
 * - RUN NOW: the user's own wake button in the expanded
 *   status view — offered exactly for waiting states.
 * - VISIBILITY: when a forced pass STILL ends waiting, the
 *   record carries [FORCED_STILL_WAITING_NOTE] in the
 *   watchdogNote field, which the transparency renderer
 *   shows on the status line — a silent loop becomes
 *   visible state, and the breaker re-arms (the next stale
 *   window can force again).
 *
 * Pure selectors over the records; TaskWorker/MainActivity
 * do the enqueueing. Parked tasks are never touched: they
 * wait on a human, not on a timer.
 */
object TaskWatchdog {

    /** A waiting record unmoved for longer than this is
     *  STUCK, not resting — rests under the no-sleeping law
     *  never exceed ~60 s, so ten minutes of stillness is
     *  proof the wait chain broke somewhere. */
    const val STALE_MS = 600_000L

    /** The work-terms line recorded when a forced retry
     *  still ends waiting. No mechanics vocabulary — it
     *  renders on the status line via TaskTransparency. */
    const val FORCED_STILL_WAITING_NOTE =
        "A fresh try was made just now — the next step still " +
            "hasn't gone through, so she's still waiting and " +
            "will keep retrying on her own."

    /** Waiting tasks whose rest has expired at [nowMs]
     *  (nextRetryAt due or absent — an absent retry time on
     *  a waiting record is itself stale state, treated as
     *  due). The app-open kick enqueues exactly these. */
    fun expiredWaitIds(tasks: List<JSONObject>, nowMs: Long): List<String> =
        tasks.filter {
            TaskStates.isWaiting(it.optString("state")) &&
                it.optLong("nextRetryAt", 0L) <= nowMs
        }.map { it.optString("id") }.filter { it.isNotEmpty() }

    /** Waiting tasks whose record has not moved for longer
     *  than [STALE_MS] — force-retry candidates, whatever
     *  their stored nextRetryAt says (a future nextRetryAt
     *  is precisely the corruption this breaker exists to
     *  override). [routeAvailable] is the caller's verdict
     *  that at least one route is configured and eligible;
     *  with no route there is nothing to force with. */
    fun staleWaitIds(
        tasks: List<JSONObject>,
        nowMs: Long,
        routeAvailable: Boolean
    ): List<String> {
        if (!routeAvailable) return emptyList()
        return tasks.filter {
            val updatedAt = it.optLong("updatedAt", 0L)
            TaskStates.isWaiting(it.optString("state")) &&
                updatedAt > 0L && nowMs - updatedAt > STALE_MS
        }.map { it.optString("id") }.filter { it.isNotEmpty() }
    }

    /** The Run now action is offered exactly for waiting
     *  states — the states a user pass can actually move. */
    fun runNowOffered(state: String): Boolean =
        TaskStates.isWaiting(state)

    /** Identity re-checks owed (v1.0.9): records whose
     *  latest identity read could not conclude
     *  (SessionIdentity's unreadable mark is the latest
     *  word) and whose unreadable rest has expired —
     *  the watchdog retries the READ on its own, so an
     *  unreadable page never leaves the sign-in unknown
     *  forever. Terminal, archived and killed records
     *  are never re-read: their sign-in is history, and
     *  a killed task stays killed. */
    fun identityRecheckIds(
        tasks: List<JSONObject>, nowMs: Long
    ): List<String> =
        tasks.filter {
            !TaskStates.isTerminal(it.optString("state")) &&
                !TaskControl.isArchived(it) &&
                !TaskControl.wasKilled(it) &&
                SessionIdentity.unreadableReason(it) != null &&
                nowMs - it.optLong(
                    SessionIdentity.UNREADABLE_AT_FIELD, 0L
                ) >= SessionIdentity.UNREADABLE_RETRY_MS
        }.map { it.optString("id") }.filter { it.isNotEmpty() }
}
