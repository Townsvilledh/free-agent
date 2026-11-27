package org.freeagent.app

import org.json.JSONObject

/**
 * SCHEDULED JOBS (cron, kept separate from the heartbeat
 * on purpose).
 *
 * The distinction is the whole design, and it is worth
 * stating plainly because the two are easy to blur:
 *
 * - The HEARTBEAT (see Heartbeat) is a light periodic pass
 *   over standing state. It has no precise time, runs
 *   nothing heavy, and is silent unless something needs
 *   the user. It never runs a job.
 * - A SCHEDULED JOB is the opposite: a precise schedule
 *   ("run this, at this instant, then again every N
 *   seconds") that runs ISOLATED — one job, one run, its
 *   own record — whether or not anything else changed.
 *
 * A job record:
 *
 *   {"id":"...","title":"...",
 *    "recipeId":"morning-brief" (optional — the recipe to
 *        run; a job carries a recipeId, a taskText, or
 *        both/neither by the caller's convention),
 *    "taskText":"..." (optional),
 *    "everySeconds":86400 (0 = one-shot),
 *    "nextRunAt":1730000000000 (0 = never again),
 *    "tools":[...],
 *    "delivery":"announce"|"silent",
 *    "failCount":0,"lastError":""}
 *
 * The laws this component enforces:
 *
 * - DUE MEANS DUE. [dueJobs] returns exactly the jobs
 *   whose nextRunAt is in (0, nowMs] — a nextRunAt of 0 is
 *   a finished one-shot or a spent job, never due — in
 *   nextRunAt order, so the most overdue runs first.
 * - A RUN'S OUTCOME IS RECORDED ON A COPY. [afterRun]
 *   returns a mutated copy of the job record; the input
 *   is never touched. The caller persists the copy — the
 *   record on disk is the evidence, and rewriting history
 *   in place would make a failed persist indistinguishable
 *   from a run that never happened.
 * - SUCCESS RESETS AND RESCHEDULES. A success clears
 *   failCount and lastError, and a recurring job's next
 *   run is one interval from the run's end (nowMs +
 *   everySeconds*1000 — from NOW, not from the scheduled
 *   instant, so a late run does not bunch the next one
 *   into the past). A one-shot success sets nextRunAt 0:
 *   done, never again.
 * - FAILURE BACKS OFF, AND ONE-SHOTS DO NOT RETRY
 *   THEMSELVES. A recurring job's failure reschedules it
 *   at nowMs + min(interval * 2^failCount, one day), with
 *   failCount the NEW count — first failure waits two
 *   intervals, second four, and so on, capped at a day so
 *   a broken job slows down instead of hammering whatever
 *   it depends on. A ONE-SHOT failure does not reschedule
 *   at all (nextRunAt 0): there is no interval to back off
 *   along, and silently re-running a one-off the user
 *   asked for once would be inventing work. Its failure
 *   surfaces instead (below).
 * - REPEATED FAILURE IS THE USER'S BUSINESS. One failure
 *   is retried quietly — transient things fail. But
 *   [failureNeedsSurfacing] is true once failCount reaches
 *   2 with a recorded error: by then the backoff has
 *   already given the transient case its chance, and
 *   keeping a repeatedly failing job silent would be
 *   hiding broken work. The caller surfaces it (the
 *   delivery field says how loudly); this component only
 *   decides the threshold.
 *
 * Honest limits: this component owns the arithmetic and
 * the thresholds only. It does not own a clock, a timer,
 * persistence, or the running itself — an alarm/worker
 * layer wakes, asks [dueJobs], runs each due job in
 * isolation, and persists [afterRun]'s copy. A job whose
 * record is malformed reads through org.json's lenient
 * opt* accessors (missing everySeconds = one-shot, missing
 * nextRunAt = never due), never a crash.
 *
 * Pure Kotlin + org.json; pinned by ScheduledJobsTest.
 */
object ScheduledJobs {

    /** The backoff ceiling: a failing recurring job never
     *  waits longer than a day between attempts. */
    const val MAX_BACKOFF_MS = 86_400_000L

    /** The jobs due at [nowMs]: nextRunAt in (0, nowMs],
     *  ordered by nextRunAt (most overdue first). The
     *  returned list holds the caller's own records —
     *  selecting is not mutating. */
    fun dueJobs(jobs: List<JSONObject>, nowMs: Long): List<JSONObject> =
        jobs.filter {
            val next = it.optLong("nextRunAt", 0L)
            next > 0L && next <= nowMs
        }.sortedBy { it.optLong("nextRunAt", 0L) }

    /**
     * The job record after a run: a MUTATED COPY of [job]
     * (the input is left exactly as it was) with the
     * outcome recorded. See the class doc for the success,
     * recurring-failure and one-shot-failure rules; the
     * backoff arithmetic is pinned exactly by tests.
     */
    fun afterRun(
        job: JSONObject,
        success: Boolean,
        error: String?,
        nowMs: Long
    ): JSONObject {
        val out = JSONObject(job.toString())
        val everySeconds = job.optLong("everySeconds", 0L)
        if (success) {
            out.put("failCount", 0)
            out.put("lastError", "")
            out.put(
                "nextRunAt",
                if (everySeconds > 0L) {
                    nowMs + everySeconds * 1000L
                } else {
                    0L
                }
            )
            return out
        }
        val newFailCount = job.optInt("failCount", 0) + 1
        out.put("failCount", newFailCount)
        out.put("lastError", error ?: "failed")
        if (everySeconds <= 0L) {
            // One-shot: no self-retry. The failure surfaces.
            out.put("nextRunAt", 0L)
            return out
        }
        val intervalMs = everySeconds * 1000L
        // backoff = min(interval * 2^newFailCount, one day).
        // Computed with an overflow guard: once the running
        // product reaches the cap, further doubling is moot.
        var backoffMs = intervalMs
        var doublings = 0
        while (doublings < newFailCount && backoffMs < MAX_BACKOFF_MS) {
            backoffMs = if (backoffMs > MAX_BACKOFF_MS / 2L) {
                MAX_BACKOFF_MS
            } else {
                backoffMs * 2L
            }
            doublings++
        }
        out.put("nextRunAt", nowMs + minOf(backoffMs, MAX_BACKOFF_MS))
        return out
    }

    /** Whether this job's failures are the user's business
     *  now: at least two recorded failures AND a recorded
     *  error to show. One quiet retry is mercy; a second
     *  failure kept silent would be concealment. */
    fun failureNeedsSurfacing(job: JSONObject): Boolean =
        job.optInt("failCount", 0) >= 2 &&
            job.optString("lastError").isNotEmpty()
}
