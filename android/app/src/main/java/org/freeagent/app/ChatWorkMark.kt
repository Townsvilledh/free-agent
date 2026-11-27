package org.freeagent.app

import org.json.JSONObject

/**
 * The work mark on a chat's row in the chats list
 * (2026-11-26, CHAT IS THE ENGINE + the Tasks screen is
 * gone): with no Tasks page anywhere, a chat whose work
 * is running, waiting or parked must be recognisable AT
 * A GLANCE from the list — one plain mark, no mechanics.
 * The full state lives in the thread itself (StatusProbe);
 * this is only the glance.
 *
 * Pure, pinned by ChatWorkMarkTest.
 */
object ChatWorkMark {

    /** The mark for a chat whose newest work record is in
     *  [taskState]; null state (no work) and quiet terminal
     *  states that need nothing from anyone mark nothing —
     *  except a failure, which stays visible until seen. */
    fun markFor(taskState: String?): String? = when (taskState) {
        TaskStates.RUNNING -> "· working"
        TaskStates.QUEUED -> "· queued"
        TaskStates.WAITING_ROUTE, TaskStates.WAITING_NETWORK -> "· waiting"
        TaskStates.PARKED_AT_WALL -> "· needs you"
        TaskStates.PAUSED -> "· paused"
        TaskStates.NEEDS_RUNNER -> "· with the runner"
        TaskStates.FAILED -> "· failed"
        else -> null
    }

    /** The record form (v1.0.5): a task the user killed
     *  by hand marks "· stopped" — the glance form of the
     *  "Stopped — you stopped this." every other surface
     *  renders — instead of vanishing like a quiet
     *  completion. A plain cancelled record (not killed
     *  by hand) keeps the state form's silence. */
    fun markFor(task: JSONObject): String? =
        if (TaskControl.wasKilled(task)) {
            "· stopped"
        } else {
            markFor(task.optString("state"))
        }
}
