package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat parity: the visible queue + stop state machine.
 *  Messages offered during a flight wait in order; stopping a
 *  reply never eats the queue; order is never changed. */
class ChatQueueTest {

    @Test
    fun firstMessageStartsImmediately() {
        val q = ChatQueue()
        assertEquals(0, q.offer("hello"))
        q.beginFlight()
        assertTrue(q.inFlight)
    }

    @Test
    fun messagesDuringFlightQueueInOrder() {
        val q = ChatQueue()
        assertEquals(0, q.offer("first"))
        q.beginFlight()
        assertEquals(1, q.offer("second"))
        assertEquals(2, q.offer("third"))
        assertEquals(listOf("second", "third"), q.queuedTexts())
        q.endFlight()
        assertEquals("second", q.startNext())
        q.endFlight()
        assertEquals("third", q.startNext())
        q.endFlight()
        assertNull(q.startNext())
    }

    @Test
    fun stopReleasesFlightButKeepsQueue() {
        val q = ChatQueue()
        q.offer("first")
        q.beginFlight()
        q.offer("second")
        // Stop = endFlight without touching the queue.
        q.endFlight()
        assertEquals("second", q.startNext())
    }

    @Test
    fun startNextRefusedWhileInFlight() {
        val q = ChatQueue()
        q.offer("first")
        q.beginFlight()
        q.offer("second")
        assertNull(q.startNext())
        assertEquals(listOf("second"), q.queuedTexts())
    }

    @Test
    fun queueSurvivesAWaitingPeriod() {
        // A queued message during a rate-limit wait stays
        // queued: nothing in the state machine drains on its
        // own — only endFlight + startNext moves it.
        val q = ChatQueue()
        q.offer("first")
        q.beginFlight()
        assertEquals(1, q.offer("during the wait"))
        assertEquals(1, q.waitingCount)
        assertEquals(listOf("during the wait"), q.queuedTexts())
    }

    @Test
    fun badges() {
        assertEquals("queued — sends next", ChatQueue.badge(1))
        assertEquals("queued — 3 in line", ChatQueue.badge(3))
    }
}
