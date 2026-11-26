package org.freeagent.app

/**
 * The chat send queue (chat-parity fold-in): while a reply is
 * in flight the composer stays live, and a message sent during
 * the flight is QUEUED — shown in the transcript immediately
 * with a badge, sent in order the moment the current reply
 * finishes or is stopped. Nothing is dropped, merged, or
 * reordered silently.
 *
 * Pure state machine, JVM-pinned. The activity owns the
 * bubbles; this class owns the order and the flight flag.
 */
class ChatQueue {

    private val pending = ArrayDeque<String>()

    var inFlight: Boolean = false
        private set

    /**
     * Offer a message. Returns 0 when it can start right now
     * (nothing in flight, queue empty) — the caller sends it
     * and calls [beginFlight]. Otherwise the message waits and
     * the return is its 1-based position among the waiting.
     */
    fun offer(text: String): Int {
        if (!inFlight && pending.isEmpty()) return 0
        pending.addLast(text)
        return pending.size
    }

    /** Mark the reply cycle as running. */
    fun beginFlight() {
        inFlight = true
    }

    /** The current reply ended (finished or stopped): release
     *  the flight. The queue is untouched — a stopped reply
     *  never eats the messages behind it. */
    fun endFlight() {
        inFlight = false
    }

    /** Take the next waiting message, if any, starting its
     *  flight. Null when the queue is empty or a flight is
     *  still marked active. */
    fun startNext(): String? {
        if (inFlight || pending.isEmpty()) return null
        inFlight = true
        return pending.removeFirst()
    }

    fun queuedTexts(): List<String> = pending.toList()

    val waitingCount: Int get() = pending.size

    companion object {
        /** The badge a queued bubble carries. */
        fun badge(position: Int): String =
            if (position <= 1) "queued — sends next"
            else "queued — $position in line"
    }
}
