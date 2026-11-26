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

    // ---- copy ----

    @Test
    fun rateLimitCopyNamesTheSharedCarrierIp() {
        val line = BrainFailure.statusLine(
            BrainFailure.Class.RATE_LIMITED, "the slow community route", "1 minute"
        )
        assertTrue(line.contains("shared"))
        assertTrue(line.contains("carrier"))
        assertTrue(line.contains("the slow community route"))
        assertFalse(line.lowercase().contains("check your connection"))
    }

    @Test
    fun networkCopyNeverBlamesTheUsersConnection() {
        val line = BrainFailure.statusLine(
            BrainFailure.Class.NETWORK, null, "2 minutes"
        )
        assertTrue(line.contains("Your connection is fine"))
        assertFalse(line.lowercase().contains("check your connection"))
        assertTrue(line.contains("retry on her own"))
    }

    @Test
    fun offlineCopySaysTheMessageIsSaved() {
        val line = BrainFailure.statusLine(
            BrainFailure.Class.OFFLINE, null, "2 minutes"
        )
        assertTrue(line.contains("offline"))
        assertTrue(line.contains("saved here"))
    }
}
