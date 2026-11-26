package org.freeagent.app

import android.content.Context

/**
 * The parked-step handback (WALLS.md), shared by the two
 * surfaces that can perform it: the browser screen
 * (BrowserActivity) and — since CHAT IS THE ENGINE — the
 * in-chat browser panel in MainActivity. One
 * implementation, so the semantics can never drift: a
 * task parked at a human-only wall moves back to RUNNING
 * with the handback stamped, and the worker resumes it.
 * The runner reads handedBackAt against the wall record
 * and tells her the step was taken — she verifies from
 * the page, never from assumption.
 */
object WallHandback {

    /** True when the handback was performed (the task was
     *  parked and is now resuming). */
    fun handBack(context: Context, taskId: String): Boolean {
        val store = TaskStore(context)
        val state = store.get(taskId)?.optString("state") ?: ""
        if (state != TaskStates.PARKED_AT_WALL ||
            !TaskStates.canTransition(state, TaskStates.RUNNING)
        ) {
            return false
        }
        store.update(taskId, TaskStates.RUNNING) {
            it.put("handedBackAt", System.currentTimeMillis())
        }
        TaskWorker.enqueue(context, taskId)
        return true
    }
}
