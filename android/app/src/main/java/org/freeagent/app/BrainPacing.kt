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
 *   enforced from a timestamp persisted in the task record so
 *   it survives process death and worker retries.
 * - An escalating cooldown after each 402/429: 60 s × 2^n for
 *   the n-th consecutive rate limit, honouring a larger
 *   Retry-After, capped at MAX_COOLDOWN_MS.
 * - The consecutive counter resets on the first HTTP 200.
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
}
