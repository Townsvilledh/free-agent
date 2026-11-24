package org.freeagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reboot recovery (v0.4): after BOOT_COMPLETED, re-queue every task
 * left in a resumable state (queued / running / waiting_*). A task
 * that was mid-step when the phone died resumes from its last
 * checkpoint — WorkManager's persistent queue is the second safety
 * net; this receiver covers tasks whose work request was already
 * consumed. Android may still delay background work under Doze /
 * battery optimisation — see OFFLINE.md for the honest limits.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = TaskStore(context)
        for (task in store.all()) {
            val state = task.optString("state")
            if (TaskStates.isResumable(state)) {
                TaskWorker.enqueue(context, task.optString("id"))
            }
        }
    }
}
