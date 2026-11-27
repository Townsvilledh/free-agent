package org.freeagent.app

/**
 * Brain-call pacing + rate-limit cooldown arithmetic (v0.9.1) —
 * a pure component (no Android, no I/O), pinned by JVM tests,
 * in the same style as BrowserPerception.
 *
 * Why this exists: the first live device-lane run (2026-11-26,
 * Ethuram's phone, "Lille: create my X account") stalled at
 * steps=3 in waiting_route. The runner fired model calls
 * back-to-back with no pacing between successful calls, tripped
 * the keyless anonymous tier (which tolerates roughly one call
 * per ~45 s per IP — the host loop only works with --pace 50),
 * and the old recovery waited only Retry-After-or-45 s before
 * retrying, so each wake-up's first call could re-trip the tier
 * instantly: 429 → wait → 429, steps frozen.
 *
 * The rules here:
 * - A minimum interval between brain calls (MIN_INTERVAL_MS),
 *   enforced PER ROUTE from a timestamp persisted in the task
 *   record so it survives process death and worker retries.
 *   The floor binds only the route that demands it (the
 *   keyless anonymous tier); a route with no floor is never
 *   delayed by another route's pacing (v1.0.2, NO SLEEPING
 *   WHILE A ROUTE LIVES).
 * - An escalating cooldown after each 402/429: 60 s × 2^n for
 *   the n-th consecutive rate limit, honouring a larger
 *   Retry-After, capped at MAX_COOLDOWN_MS. The streak is
 *   PER ROUTE — escalation lives on the route that earned
 *   it, never on the task.
 * - The consecutive counter resets on the first HTTP 200.
 * - A TASK's rest after an all-routes failure is [restMs]:
 *   the SHORTEST per-route cooldown among configured routes,
 *   capped at MAX_REST_MS — never an escalating task-level
 *   backoff. While any route is configured, no single rest
 *   exceeds ~60 s without a fresh all-routes attempt.
 */
object BrainPacing {

    /** Minimum gap between two brain calls for one task. */
    const val MIN_INTERVAL_MS = 50_000L

    /** Cooldown after the first rate limit of a streak. */
    const val BASE_COOLDOWN_MS = 60_000L

    /** No single route wait exceeds this, however angry the tier. */
    const val MAX_COOLDOWN_MS = 15 * 60_000L

    /**
     * Milliseconds the runner must still wait before the next
     * brain call is allowed; 0 means "call now". A task that has
     * never called (lastCallAtMs <= 0 — including every pre-0.9.1
     * record, where the field is absent and defaults to 0) may
     * call immediately.
     */
    fun remainingIntervalMs(lastCallAtMs: Long, nowMs: Long): Long {
        if (lastCallAtMs <= 0L) return 0L
        val elapsed = nowMs - lastCallAtMs
        return if (elapsed >= MIN_INTERVAL_MS) 0L
        else MIN_INTERVAL_MS - elapsed
    }

    /**
     * Cooldown after a 402/429. [consecutiveRateLimits] is the
     * streak count BEFORE this response (0 for the first). A
     * Retry-After larger than the escalated wait wins; the
     * result is capped at MAX_COOLDOWN_MS.
     */
    fun cooldownMs(retryAfterSec: Long?, consecutiveRateLimits: Int): Long {
        var escalated = BASE_COOLDOWN_MS
        var n = consecutiveRateLimits.coerceIn(0, 16)
        while (n > 0 && escalated < MAX_COOLDOWN_MS) {
            escalated = minOf(escalated * 2, MAX_COOLDOWN_MS)
            n--
        }
        val retryAfterMs = (retryAfterSec ?: 0L).coerceAtLeast(0L) * 1000L
        return minOf(maxOf(escalated, retryAfterMs), MAX_COOLDOWN_MS)
    }

    /**
     * The streak counter's next value after a brain response:
     * +1 on 402/429, reset to 0 on HTTP 200, unchanged otherwise.
     */
    fun nextCounter(current: Int, httpCode: Int): Int = when (httpCode) {
        200 -> 0
        402, 429 -> current.coerceAtLeast(0) + 1
        else -> current
    }

    /** Plain-language wait for the task note ("~4 min", "~45 sec"). */
    fun describeWait(waitMs: Long): String =
        if (waitMs >= 60_000L) "~${(waitMs + 59_999L) / 60_000L} min"
        else "~${(waitMs + 999L) / 1000L} sec"

    /** The waiting_route note. Seamless law (2026-11-26):
     *  the note states the plain fact of waiting and
     *  NOTHING about why — no route, no limit, no pacing
     *  mechanics. The cause lives in the record's evidence
     *  fields, never in the note a surface echoes. */
    fun rateLimitNote(waitMs: Long): String = WAITING_NOTE

    /** The waiting_route note for a pacing wait — the same
     *  plain line; the user is never told which internal
     *  discipline produced the wait. */
    fun pacingNote(waitMs: Long): String = WAITING_NOTE

    /** The one plain waiting note for task records. */
    const val WAITING_NOTE =
        "Waiting — continues automatically (task is waiting, not failed)."

    // ---- v1.0.2: NO SLEEPING WHILE A ROUTE LIVES ----
    //
    // Ethuram's hard requirement (2026-11-27): the old design
    // let a task sit in escalating task-level rests (up to
    // ~15 min) even when another route could carry it, and a
    // rest timed before he pasted an own key kept running
    // after the key landed. The fix is arithmetic, here:
    // rests derive from PER-ROUTE state only.

    /** No single task rest exceeds this while at least one
     *  route is configured: after ~60 s there is always a
     *  fresh all-routes attempt. */
    const val MAX_REST_MS = 60_000L

    /**
     * The task's rest after a brain call exhausted every
     * route: the SHORTEST per-route cooldown among the
     * configured routes, capped at [MAX_REST_MS]. A route
     * that is healthy now contributes 0 — the rest is 0 and
     * the next attempt fires at once. This function takes NO
     * streak: repeated all-routes failures with the same
     * per-route state return the same rest, forever — the
     * task level never escalates. (Each route's own cooldown
     * may escalate via [cooldownMs]; that is the route's
     * business, and the shortest one still governs the rest.)
     * An empty collection (no route configured — not a state
     * the app can reach, the keyless pair is always present)
     * rests one capped stretch rather than spinning.
     */
    fun restMs(routeCooldownsMs: Collection<Long>): Long {
        if (routeCooldownsMs.isEmpty()) return MAX_REST_MS
        val shortest = routeCooldownsMs.minOrNull() ?: MAX_REST_MS
        return shortest.coerceIn(0L, MAX_REST_MS)
    }

    /** Routes whose anonymous tier demands a minimum interval
     *  between calls (the Pollinations-class per-IP limit).
     *  Every other route — the user's own keys, the Horde
     *  queue — has NO floor here: their own limits surface as
     *  402/429 and land in the per-route cooldown instead. */
    private val PACING_FLOOR_ROUTES = setOf("pollinations")

    /** The minimum-interval floor for ONE route; 0 for any
     *  route that does not demand one. */
    fun pacingFloorMs(routeKey: String): Long =
        if (routeKey in PACING_FLOOR_ROUTES) MIN_INTERVAL_MS else 0L

    /**
     * How long before [routeKey] may be called again under
     * ITS OWN pacing floor, given when THAT route was last
     * called; 0 = call now. Another route's last call is
     * never an input — the floor binds per route only, so a
     * step a healthy route can serve is never delayed by a
     * different route's pacing.
     */
    fun routePacingWaitMs(routeKey: String, lastCallAtMs: Long, nowMs: Long): Long {
        val floor = pacingFloorMs(routeKey)
        if (floor <= 0L || lastCallAtMs <= 0L) return 0L
        val elapsed = nowMs - lastCallAtMs
        return if (elapsed >= floor) 0L else floor - elapsed
    }
}
