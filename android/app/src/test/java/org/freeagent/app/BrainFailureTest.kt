package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BrainFailure (v0.12): the classification + retry
 * discipline born of the 2026-11-26 live failure — "hi"
 * answered with a terminal "check your connection" while
 * the phone was online and the free route was the problem.
 * These tests pin: the cause is classified from evidence,
 * retryable failures never dead-end, and "check your
 * connection" copy exists ONLY for a genuinely offline
 * device.
 */
class BrainFailureTest {

    // ---- classification ----

    @Test
    fun rateLimitCodes() {
        assertEquals(
            BrainFailure.Class.RATE_LIMITED,
            BrainFailure.classify(402, null, deviceOnline = true)
        )
        assertEquals(
            BrainFailure.Class.RATE_LIMITED,
            BrainFailure.classify(429, null, deviceOnline = true)
        )
    }

    @Test
    fun serverCodes() {
        assertEquals(
            BrainFailure.Class.SERVER,
            BrainFailure.classify(500, null, deviceOnline = true)
        )
        assertEquals(
            BrainFailure.Class.SERVER,
            BrainFailure.classify(503, null, deviceOnline = true)
        )
    }

    @Test
    fun permanentCodes() {
        assertEquals(
            BrainFailure.Class.PERMANENT,
            BrainFailure.classify(400, null, deviceOnline = true)
        )
        assertEquals(
            BrainFailure.Class.PERMANENT,
            BrainFailure.classify(404, null, deviceOnline = true)
        )
    }

    @Test
    fun ioFailureWhileOnlineIsNetworkNeverOffline() {
        // The 2026-11-26 bug, pinned: an I/O failure with the
        // device online is a ROUTE problem.
        assertEquals(
            BrainFailure.Class.NETWORK,
            BrainFailure.classify(null, BrainFailure.IoKind.OTHER, deviceOnline = true)
        )
        assertEquals(
            BrainFailure.Class.NETWORK,
            BrainFailure.classify(null, BrainFailure.IoKind.TIMEOUT, deviceOnline = true)
        )
    }

    @Test
    fun ioFailureWhileOfflineIsOffline() {
        assertEquals(
            BrainFailure.Class.OFFLINE,
            BrainFailure.classify(null, BrainFailure.IoKind.OTHER, deviceOnline = false)
        )
        assertEquals(
            BrainFailure.Class.OFFLINE,
            BrainFailure.classify(null, BrainFailure.IoKind.TIMEOUT, deviceOnline = false)
        )
    }

    // ---- retry discipline ----

    @Test
    fun retryableClassesNeverDeadEnd() {
        assertTrue(BrainFailure.isRetryable(BrainFailure.Class.OFFLINE))
        assertTrue(BrainFailure.isRetryable(BrainFailure.Class.NETWORK))
        assertTrue(BrainFailure.isRetryable(BrainFailure.Class.RATE_LIMITED))
        assertTrue(BrainFailure.isRetryable(BrainFailure.Class.SERVER))
        assertFalse(BrainFailure.isRetryable(BrainFailure.Class.PERMANENT))
    }

    @Test
    fun waitsEscalateAndHonourRetryAfter() {
        // Same BrainPacing arithmetic as the task engine.
        assertEquals(60_000L, BrainFailure.waitMs(BrainFailure.Class.SERVER, null, 0))
        assertEquals(120_000L, BrainFailure.waitMs(BrainFailure.Class.SERVER, null, 1))
        assertEquals(
            300_000L,
            BrainFailure.waitMs(BrainFailure.Class.RATE_LIMITED, 300, 0)
        )
        // Offline probes wait at least two minutes.
        assertTrue(BrainFailure.waitMs(BrainFailure.Class.OFFLINE, null, 0) >= 120_000L)
        // The cap holds for every class.
        assertTrue(BrainFailure.waitMs(BrainFailure.Class.NETWORK, null, 12) <= 900_000L)
    }

    // ---- route chain ----

    @Test
    fun chainFallsThroughInOrder() {
        assertEquals(
            BrainFailure.Route.HORDE,
            BrainFailure.nextRoute(BrainFailure.Route.POLLINATIONS)
        )
        assertNull(BrainFailure.nextRoute(BrainFailure.Route.HORDE))
    }

    @Test
    fun onlyPermanentEverywhereEndsTerminally() {
        assertTrue(BrainFailure.attemptEndsTerminally(BrainFailure.Class.PERMANENT))
        assertFalse(BrainFailure.attemptEndsTerminally(BrainFailure.Class.RATE_LIMITED))
        assertFalse(BrainFailure.attemptEndsTerminally(BrainFailure.Class.SERVER))
        assertFalse(BrainFailure.attemptEndsTerminally(BrainFailure.Class.NETWORK))
        assertFalse(BrainFailure.attemptEndsTerminally(BrainFailure.Class.OFFLINE))
    }

    // ---- copy (seamless law, v1.0.1) ----
    //
    // The v0.12 statusLine copy tests are GONE with the
    // function: route failures are never narrated now. The
    // only failure sentence a chat may show is the one
    // plain exhausted line — pinned here against every
    // banned-narration shape.

    @Test
    fun exhaustedLineIsTheOnePlainLine() {
        val line = BrainFailure.EXHAUSTED_LINE
        assertTrue(line.contains("try me again in a moment"))
        val low = line.lowercase()
        for (banned in listOf(
            "route", "pollinations", "horde", "groq", "gemini",
            "free brain", "community", "rate", "limit", "refus",
            "carrier", "shared ip", "key"
        )) {
            assertFalse(
                "exhausted line must not contain '$banned'",
                low.contains(banned)
            )
        }
        assertFalse(NarrationGuard.containsNarration(line))
    }

    @Test
    fun routesCarryNoUserFacingLabels() {
        // Route identity is a trace fact (the key), never a
        // display label: the enum exposes nothing else.
        assertEquals("pollinations", BrainFailure.Route.POLLINATIONS.key)
        assertEquals("horde", BrainFailure.Route.HORDE.key)
    }

    // ---- per-route health (v0.12 hunt) ----

    @Test
    fun routeCooldownEscalatesAndCaps() {
        assertEquals(0L, BrainFailure.routeCooldownMs(0))
        assertEquals(120_000L, BrainFailure.routeCooldownMs(1))
        assertEquals(600_000L, BrainFailure.routeCooldownMs(2))
        assertEquals(1_800_000L, BrainFailure.routeCooldownMs(3))
        assertEquals(1_800_000L, BrainFailure.routeCooldownMs(9))
    }

    @Test
    fun pickNextSkipsRestingRoutes() {
        val now = 1_000_000L
        val cooling = mapOf(BrainFailure.Route.HORDE to now + 60_000L)
        // Horde is resting: nothing healthy after Pollinations.
        assertNull(
            BrainFailure.pickNext(BrainFailure.Route.POLLINATIONS, cooling, now)
        )
        // Once its rest expires, it is picked again (automatic
        // recovery — no manual reset anywhere).
        assertEquals(
            BrainFailure.Route.HORDE,
            BrainFailure.pickNext(
                BrainFailure.Route.POLLINATIONS, cooling, now + 61_000L
            )
        )
        // Nothing exists after the last route.
        assertNull(
            BrainFailure.pickNext(BrainFailure.Route.HORDE, emptyMap(), now)
        )
    }

    @Test
    fun firstHealthyStartsWhereTheChainIsAlive() {
        val now = 5_000L
        assertEquals(
            BrainFailure.Route.POLLINATIONS,
            BrainFailure.firstHealthy(emptyMap(), now)
        )
        val mainResting = mapOf(BrainFailure.Route.POLLINATIONS to now + 1_000L)
        assertEquals(
            BrainFailure.Route.HORDE,
            BrainFailure.firstHealthy(mainResting, now)
        )
        val allResting = mapOf(
            BrainFailure.Route.POLLINATIONS to now + 1_000L,
            BrainFailure.Route.HORDE to now + 2_000L
        )
        assertNull(BrainFailure.firstHealthy(allResting, now))
        assertEquals(
            BrainFailure.Route.POLLINATIONS,
            BrainFailure.firstHealthy(allResting, now + 1_500L)
        )
    }
}
