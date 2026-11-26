package org.freeagent.app

/**
 * The visible browser in chat (2026-11-26, CHAT IS THE
 * ENGINE extension): when a thread's work is in a browser
 * step, her LIVE browser — the real device-lane WebView
 * session — shows as a panel in the conversation, so the
 * work is watchable, never invisible background motion.
 *
 * This component is the panel's state machine, pure over
 * facts the glue reads (the chat's own work record, the
 * browser lane's owner, the user's hold). The panel:
 *
 * - WATCH: her browser work in THIS chat holds the lane;
 *   the panel shows the live session while she drives.
 * - PARKED: the work is parked at his step; the panel
 *   shows the session and the step happens right here —
 *   "Done — hand back to Lille" resumes her (the same
 *   handback the browser screen performs).
 * - USER_HOLD: he took the browser over from a running
 *   task (the run paused); handback resumes her.
 * - WAITING_OTHER: this chat's browser work is waiting
 *   because ANOTHER chat's work holds the one browser
 *   lane — said in one plain line, no mechanics.
 * - HIDDEN: the work is not in a browser step (or there
 *   is no work) — no panel at all.
 *
 * Pinned by BrowserPanelTest.
 */
object BrowserPanel {

    enum class State { HIDDEN, WATCH, PARKED, USER_HOLD, WAITING_OTHER }

    const val WAITING_OTHER_LINE =
        "Her browser is busy with work in another chat — " +
            "it comes here when that's done."

    const val WATCH_LABEL = "Lille's browser — she's driving"
    const val PARKED_LABEL = "Lille's browser — your step"
    const val USER_HOLD_LABEL = "Lille's browser — you're driving"

    /**
     * @param taskState the chat's newest work record's
     *   state, or null when the chat has no work.
     * @param taskIsBrowser the work uses the browser lane.
     * @param laneHeldByThisChat the browser lane's owner is
     *   this chat's work.
     * @param laneHeldByOther the browser lane's owner is
     *   some other work (another chat's, or a non-chat task).
     * @param userHold the user took the browser over in
     *   this chat (the run paused by his hand).
     */
    fun stateFor(
        taskState: String?,
        taskIsBrowser: Boolean,
        laneHeldByThisChat: Boolean,
        laneHeldByOther: Boolean,
        userHold: Boolean
    ): State {
        if (taskState == null || !taskIsBrowser) return State.HIDDEN
        return when (taskState) {
            TaskStates.PARKED_AT_WALL -> State.PARKED
            TaskStates.PAUSED ->
                if (userHold) State.USER_HOLD else State.HIDDEN
            TaskStates.RUNNING ->
                if (laneHeldByOther && !laneHeldByThisChat) {
                    State.WAITING_OTHER
                } else {
                    State.WATCH
                }
            TaskStates.QUEUED,
            TaskStates.WAITING_ROUTE,
            TaskStates.WAITING_NETWORK ->
                if (laneHeldByOther) State.WAITING_OTHER else State.HIDDEN
            else -> State.HIDDEN
        }
    }
}
