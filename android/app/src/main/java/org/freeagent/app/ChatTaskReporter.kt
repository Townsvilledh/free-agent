package org.freeagent.app

import android.content.Context

/**
 * Delivers a chat-originated task's graded reports into its
 * originating chat (v0.13). The composition and dedup rules
 * are the pure [ChatTaskLink]; this class owns only the two
 * stores.
 *
 * Called from TaskWorker with the state a run settled at:
 * the message is appended to the chat's durable store (so it
 * survives process death and rides the backup), the task
 * record is stamped so the state is never reported twice,
 * and a backup is requested. MainActivity re-renders from
 * the store on resume (the renderedMtime mechanism), so a
 * report that lands while the user is elsewhere in the app
 * is simply there when they come back to the chat.
 */
class ChatTaskReporter(private val context: Context) {

    fun deliver(taskId: String, newState: String) {
        val taskStore = TaskStore(context)
        val task = taskStore.get(taskId) ?: return
        val text = ChatTaskLink.reportFor(task, newState) ?: return
        val chatId = task.optString(ChatTaskLink.ORIGIN_FIELD)
        val delivered = ChatStore(context)
            .appendMessage(chatId, "assistant", text)
        if (delivered) {
            taskStore.update(taskId) {
                it.put(ChatTaskLink.REPORTED_FIELD, newState)
            }
            BackupManager.requestBackup(context)
        }
    }
}
