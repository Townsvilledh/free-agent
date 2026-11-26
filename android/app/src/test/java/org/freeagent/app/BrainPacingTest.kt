package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BrainPacing (v0.9.1): the pacing/cooldown arithmetic behind
 * the fix for the first live device-lane stall (2026-11-26 —
 * the X account task froze at steps=3 in waiting_route because
 * brain calls fired back-to-back into the keyless tier's
 * ~1-call-per-45 s anonymous limit, and the old flat 45 s
 * recovery let every wake-up re-trip it instantly).
 */
class BrainPacingTest {

    // ---- minimum interval between brain calls ----

    @Test
    fun neverCalledMayCallNow() {
        // lastBrainCallAt absent/0 — every pre-0.9.1 record.
        assertEquals(0L, BrainPacing.remainingIntervalMs(0L, 1_700_000_000_000L))
    }

    @Test
    fun intervalEnforcedFromLastCall() {
        val t0 = 1_000_000L
        assertEquals(
            BrainPacing.MIN_INTERVAL_MS,
            BrainPacing.remainingIntervalMs(t0, t0)
        )
        assertEquals(20_000L, BrainPacing.remainingIntervalMs(t0, t0 + 30_000L))
        assertEquals(1L, BrainPacing.remainingIntervalMs(t0, t0 + 49_999L))
    }

    @Test
    fun intervalExpiresAtFiftySeconds() {
        val t0 = 1_000_000L
        assertEquals(0L, BrainPacing.remainingIntervalMs(t0, t0 + 50_000L))
        assertEquals(0L, BrainPacing.remainingIntervalMs(t0, t0 + 3_600_000L))
    }

    // ---- escalating cooldown after 402/429 ----

    @Test
    fun cooldownEscalatesByDoubling() {
        assertEquals(60_000L, BrainPacing.cooldownMs(null, 0))
        assertEquals(120_000L, BrainPacing.cooldownMs(null, 1))
        assertEquals(240_000L, BrainPacing.cooldownMs(null, 2))
        assertEquals(480_000L, BrainPacing.cooldownMs(null, 3))
    }

    @Test
    fun cooldownIsCappedAtFifteenMinutes() {
        // 60 s × 2^4 = 960 s would exceed the cap.
        assertEquals(BrainPacing.MAX_COOLDOWN_MS, BrainPacing.cooldownMs(null, 4))
        assertEquals(BrainPacing.MAX_COOLDOWN_MS, BrainPacing.cooldownMs(null, 10))
        assertEquals(900_000L, BrainPacing.MAX_COOLDOWN_MS)
    }

    @Test
    fun retryAfterWinsWhenLarger() {
        assertEquals(300_000L, BrainPacing.cooldownMs(300L, 0))
        assertEquals(600_000L, BrainPacing.cooldownMs(600L, 2))
    }

    @Test
    fun retryAfterLosesWhenSmallerAndIsCappedWhenHuge() {
        assertEquals(240_000L, BrainPacing.cooldownMs(30L, 2))
        assertEquals(60_000L, BrainPacing.cooldownMs(45L, 0))
        assertEquals(BrainPacing.MAX_COOLDOWN_MS, BrainPacing.cooldownMs(3600L, 0))
    }

    // ---- streak counter lifecycle ----

    @Test
    fun counterIncrementsOnRateLimitAndResetsOn200() {
        var streak = 0
        streak = BrainPacing.nextCounter(streak, 429)
        assertEquals(1, streak)
        streak = BrainPacing.nextCounter(streak, 402)
        assertEquals(2, streak)
        streak = BrainPacing.nextCounter(streak, 200)
        assertEquals(0, streak)
    }

    @Test
    fun counterUnchangedByOtherOutcomes() {
        assertEquals(2, BrainPacing.nextCounter(2, 500))
        assertEquals(0, BrainPacing.nextCounter(0, 404))
    }

    // ---- plain-language notes ----

    @Test
    fun notesStateTheWaitPlainly() {
        // Seamless law: the waiting note is ONE plain line —
        // the wait is real, its cause and duration are not
        // narrated (no route names, no rate-limit talk).
        for (note in listOf(
            BrainPacing.rateLimitNote(240_000L),
            BrainPacing.pacingNote(30_000L)
        )) {
            assertTrue(note.contains("Waiting"))
            assertTrue(note.contains("continues automatically"))
            assertTrue(note.contains("not failed"))
            assertFalse(NarrationGuard.containsNarration(note))
            assertFalse(note.contains("~"))
        }
    }

    // ---- pre-0.9.1 task records load cleanly ----

    /**
     * An old-shape record — exactly what TaskStore.create wrote
     * under 0.9.0, plus the fields the 0.9.0 runner checkpoints —
     * has NO lastBrainCallAt / consecutiveRateLimits. Read through
     * the new code's defaults it must behave as a fresh, un-paced
     * record: the existing on-phone task resumes instead of being
     * orphaned or stuck.
     */
    @Test
    fun oldShapeRecordDefaultsCleanly() {
        val old = JSONObject()
            .put("id", "task-deadbeef")
            .put("text", "Lille: create my X account")
            .put("state", TaskStates.WAITING_ROUTE)
            .put("steps", 3)
            .put("messages", JSONArray())
            .put("summary", "")
            .put("note", "Free route rate-limited — task is waiting, not failed.")
            .put("nextRetryAt", 123L)
            .put("updatedAt", 456L)
            .put("kind", "browser")
            .put("lane", "device")

        val lastCall = old.optLong("lastBrainCallAt", 0L)
        val streak = old.optInt("consecutiveRateLimits", 0)
        assertEquals(0L, lastCall)
        assertEquals(0, streak)
        // New code on old data: first call allowed immediately,
        // first cooldown is the base 60 s — the run picks up
        // exactly where the 0.9.0 checkpoint left it (steps=3).
        assertEquals(0L, BrainPacing.remainingIntervalMs(lastCall, 1_700_000_000_000L))
        assertEquals(60_000L, BrainPacing.cooldownMs(null, streak))
        assertEquals(3, old.optInt("steps", 0))
    }
}
