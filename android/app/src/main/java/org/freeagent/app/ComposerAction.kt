package org.freeagent.app

/**
 * THE COMPOSER ACTION STATE MACHINE (v1.0.6) — the chat
 * parity law (2026-11-26) made mechanical: while she
 * answers, Send becomes Stop AND the box never locks.
 *
 * The shipped 1.0.5 button broke the second half of that
 * law: its face was decided by the flight flag alone, so
 * with a reply in flight the button was a STOP square no
 * matter what the composer held — Ethuram's screenshot
 * (2026-11-27) shows his typed message ("the task I want
 * you to resume is creating your x profile") sitting over
 * a stop button, unsendable until she finished. His
 * words: "I can't send when she's thinking or haven't
 * responded."
 *
 * The rule is now a pure function of two facts — does the
 * composer hold content, and is work in flight:
 *
 * - text + idle  → SEND (starts now)
 * - text + busy  → SEND_QUEUE (sends at once; the message
 *   joins the transcript badged "queued" and is processed
 *   next, in order — the ChatQueue machinery)
 * - empty + busy → STOP (the only face that stops)
 * - empty + idle → SEND (inert, as before)
 *
 * The activity reads the action fresh on every tap and on
 * every composer/flight change, so the button can never
 * be a stop sign over sendable text again.
 *
 * Pure; pinned by Lille106Test.
 */
object ComposerAction {

    enum class Action { SEND, SEND_QUEUE, STOP }

    /** The button's action for the composer's content and
     *  the flight state. [hasContent] is text (beyond
     *  whitespace) or a pending attachment — anything
     *  submitMessage would actually send. */
    fun actionFor(hasContent: Boolean, busy: Boolean): Action = when {
        !busy -> Action.SEND
        hasContent -> Action.SEND_QUEUE
        else -> Action.STOP
    }

    /** The drawable the button shows for [action] — a
     *  stop square ONLY for the stop face; a send arrow
     *  whenever tapping would send (now or queued). */
    fun iconName(action: Action): String = when (action) {
        Action.STOP -> "ic_stop"
        Action.SEND, Action.SEND_QUEUE -> "ic_send"
    }

    /** The button's spoken description for [action]. */
    fun description(action: Action): String = when (action) {
        Action.STOP -> "Stop"
        Action.SEND, Action.SEND_QUEUE -> "Send message"
    }
}
