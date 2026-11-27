package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scheduled jobs: due selection and ordering, afterRun
 * success/failure arithmetic (pinned exactly), the
 * one-shot no-reschedule rule, the surfacing threshold,
 * and the copy-not-mutate guarantee.
 */
class ScheduledJobsTest {

    private val now = 1_000_000L

    private fun job(
        id: String = "job-1",
        everySeconds: Long = 3600L,
        nextRunAt: Long = 0L,
        failCount: Int = 0,
        lastError: String = ""
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("title", "Job $id")
        .put("everySeconds", everySeconds)
        .put("nextRunAt", nextRunAt)
        .put("delivery", "announce")
        .put("failCount", failCount)
        .put("lastError", lastError)

    // ---------- dueJobs ----------

    @Test
    fun dueJobsSelectsAndOrdersByNextRunAt() {
        val jobs = listOf(
            job("late", nextRunAt = 900_000L),
            job("early", nextRunAt = 100_000L),
            job("future", nextRunAt = 2_000_000L),
            job("never", nextRunAt = 0L),
            job("exactly-now", nextRunAt = now)
        )
        val due = ScheduledJobs.dueJobs(jobs, now)
        assertEquals(
            listOf("early", "late", "exactly-now"),
            due.map { it.optString("id") }
        )
    }

    @Test
    fun dueJobsEmptyWhenNothingDue() {
        assertTrue(ScheduledJobs.dueJobs(emptyList(), now).isEmpty())
        assertTrue(
            ScheduledJobs.dueJobs(listOf(job(nextRunAt = 0L)), now).isEmpty()
        )
    }

    // ---------- afterRun: success ----------

    @Test
    fun successRecurringReschedulesOneIntervalFromNow() {
        val out = ScheduledJobs.afterRun(
            job(everySeconds = 3600L, failCount = 2, lastError = "boom"),
            success = true, error = null, nowMs = now
        )
        assertEquals(0, out.optInt("failCount"))
        assertEquals("", out.optString("lastError"))
        assertEquals(now + 3_600_000L, out.optLong("nextRunAt"))
    }

    @Test
    fun successOneShotNeverRunsAgain() {
        val out = ScheduledJobs.afterRun(
            job(everySeconds = 0L, nextRunAt = 500L),
            success = true, error = null, nowMs = now
        )
        assertEquals(0, out.optInt("failCount"))
        assertEquals(0L, out.optLong("nextRunAt"))
    }

    // ---------- afterRun: failure backoff (pinned) ----------

    @Test
    fun failureBackoffArithmeticIsExact() {
        // interval = 3600s = 3_600_000 ms.
        // failCount 1 (new): min(interval * 2^1, cap) = 7_200_000
        val first = ScheduledJobs.afterRun(
            job(everySeconds = 3600L, failCount = 0),
            success = false, error = "net down", nowMs = now
        )
        assertEquals(1, first.optInt("failCount"))
        assertEquals("net down", first.optString("lastError"))
        assertEquals(now + 7_200_000L, first.optLong("nextRunAt"))

        // failCount 2 (new): interval * 2^2 = 14_400_000
        val second = ScheduledJobs.afterRun(
            job(everySeconds = 3600L, failCount = 1, lastError = "net down"),
            success = false, error = "net down again", nowMs = now
        )
        assertEquals(2, second.optInt("failCount"))
        assertEquals(now + 14_400_000L, second.optLong("nextRunAt"))

        // failCount 3 (new): interval * 2^3 = 28_800_000
        val third = ScheduledJobs.afterRun(
            job(everySeconds = 3600L, failCount = 2, lastError = "x"),
            success = false, error = "x", nowMs = now
        )
        assertEquals(now + 28_800_000L, third.optLong("nextRunAt"))

        // failCount 5 (new): interval * 2^5 = 115_200_000,
        // capped at one day = 86_400_000.
        val fifth = ScheduledJobs.afterRun(
            job(everySeconds = 3600L, failCount = 4, lastError = "x"),
            success = false, error = "x", nowMs = now
        )
        assertEquals(5, fifth.optInt("failCount"))
        assertEquals(now + 86_400_000L, fifth.optLong("nextRunAt"))
    }

    @Test
    fun failureWithNullErrorRecordsGenericFailure() {
        val out = ScheduledJobs.afterRun(
            job(everySeconds = 60L),
            success = false, error = null, nowMs = now
        )
        assertEquals("failed", out.optString("lastError"))
        assertEquals(1, out.optInt("failCount"))
    }

    @Test
    fun oneShotFailureDoesNotReschedule() {
        val out = ScheduledJobs.afterRun(
            job(everySeconds = 0L, nextRunAt = 500L),
            success = false, error = "boom", nowMs = now
        )
        assertEquals(1, out.optInt("failCount"))
        assertEquals("boom", out.optString("lastError"))
        assertEquals(0L, out.optLong("nextRunAt"))
    }

    // ---------- afterRun: input untouched ----------

    @Test
    fun afterRunDoesNotMutateTheInput() {
        val input = job(
            everySeconds = 3600L, nextRunAt = 123L,
            failCount = 1, lastError = "old"
        )
        val before = input.toString()
        ScheduledJobs.afterRun(input, false, "new error", now)
        ScheduledJobs.afterRun(input, true, null, now)
        assertEquals(before, input.toString())
        assertEquals(123L, input.optLong("nextRunAt"))
        assertEquals(1, input.optInt("failCount"))
        assertEquals("old", input.optString("lastError"))
    }

    // ---------- failureNeedsSurfacing ----------

    @Test
    fun surfacingThresholdIsTwoFailuresWithAnError() {
        assertFalse(
            ScheduledJobs.failureNeedsSurfacing(
                job(failCount = 0, lastError = "")
            )
        )
        // One failure: retried quietly.
        assertFalse(
            ScheduledJobs.failureNeedsSurfacing(
                job(failCount = 1, lastError = "boom")
            )
        )
        // Two failures with an error: the user's business.
        assertTrue(
            ScheduledJobs.failureNeedsSurfacing(
                job(failCount = 2, lastError = "boom")
            )
        )
        assertTrue(
            ScheduledJobs.failureNeedsSurfacing(
                job(failCount = 7, lastError = "still broken")
            )
        )
        // No recorded error to show: nothing to surface.
        assertFalse(
            ScheduledJobs.failureNeedsSurfacing(
                job(failCount = 3, lastError = "")
            )
        )
    }

    @Test
    fun surfacingFollowsAfterRunFailures() {
        val once = ScheduledJobs.afterRun(
            job(everySeconds = 3600L), false, "boom", now
        )
        assertFalse(ScheduledJobs.failureNeedsSurfacing(once))
        val twice = ScheduledJobs.afterRun(once, false, "boom again", now)
        assertTrue(ScheduledJobs.failureNeedsSurfacing(twice))
        // And a later success clears the surfacing state.
        val healed = ScheduledJobs.afterRun(twice, true, null, now)
        assertFalse(ScheduledJobs.failureNeedsSurfacing(healed))
    }
}
