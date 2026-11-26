package org.freeagent.app

import org.json.JSONObject

/**
 * The chat↔task link (v0.13) — how a task born in a chat
 * reports back INTO that chat.
 *
 * A chat-originated task record carries two extra fields
 * (Tasks-screen tasks never set them, and every reader here
 * treats their absence as "not a chat task"):
 *
 * - [ORIGIN_FIELD]: the id of the chat the job was given in.
 * - [REPORTED_FIELD]: the last state already reported into
 *   that chat — the dedup stamp. Creation stamps it RUNNING
 *   (the "Starting that now…" acknowledgement in the chat IS
 *   the started report), so a state is reported exactly once
 *   however often the engine re-enters it.
 *
 * [reportFor] is the graded-report composer: pure, over the
 * task record, in the house style. It speaks only from the
 * record — the finish summary the runner verified, the note
 * it left, the wall step it recorded — never from narration.
 * A state that is not material (queued, running, waiting on
 * network/route, cancelled by the user's own hand) composes
 * nothing: waiting states resolve themselves, and silence
 * about them is the honest report.
 */
object ChatTaskLink {

    const val ORIGIN_FIELD = "originChatId"
    const val REPORTED_FIELD = "chatReportState"

    const val COST_LINE = "Cost: $0 — keyless routes only."

    /** States worth a message in the originating chat. */
    val MATERIAL: Set<String> = setOf(
        TaskStates.COMPLETED,
        TaskStates.FAILED,
        TaskStates.PARKED_AT_WALL,
        TaskStates.NEEDS_RUNNER,
        TaskStates.PAUSED
    )

    fun isChatTask(task: JSONObject): Boolean =
        task.optString(ORIGIN_FIELD).isNotEmpty()

    /**
     * The chat message for [task] arriving at [newState], or
     * null when nothing should be said: not a chat task, the
     * state is not material, or this state was already
     * reported (the [REPORTED_FIELD] stamp matches).
     */
    fun reportFor(task: JSONObject, newState: String): String? {
        if (!isChatTask(task)) return null
        if (newState !in MATERIAL) return null
        if (task.optString(REPORTED_FIELD) == newState) return null
        val summary = task.optString("summary").trim()
        val note = task.optString("note").trim()
        return when (newState) {
            TaskStates.COMPLETED -> buildString {
                append("Done — that task is finished.")
                if (summary.isNotEmpty()) {
                    append("\n\n").append(summary)
                }
                if (note.isNotEmpty() && note != summary) {
                    append("\n\n").append(note)
                }
                append("\n\n").append(COST_LINE)
            }
            TaskStates.FAILED -> buildString {
                append("That task failed — it did not complete, and ")
                append("nothing about it counts as done.")
                if (note.isNotEmpty()) {
                    append("\n\nWhy: ").append(note)
                }
                append("\n\nThe full record is in the Tasks screen; ")
                append("ask me to retry it, or tell me how you'd ")
                append("like it handled.")
            }
            TaskStates.PARKED_AT_WALL -> buildString {
                append("I need you for one step on that task.")
                val step = task.optString("wallStep").trim()
                if (step.isNotEmpty()) {
                    append("\n\n").append(step)
                } else if (note.isNotEmpty()) {
                    append("\n\n").append(note)
                }
                append("\n\nEverything else keeps moving — only ")
                append("that step is waiting on you.")
            }
            TaskStates.NEEDS_RUNNER -> buildString {
                append("That job needs code execution, which I ")
                append("can't do on this phone — I exported it as ")
                append("a task spec for the runner lane instead of ")
                append("faking it.")
                if (note.isNotEmpty()) {
                    append("\n\n").append(note)
                }
            }
            TaskStates.PAUSED -> buildString {
                append("That task paused before it finished.")
                if (note.isNotEmpty()) {
                    append("\n\n").append(note)
                }
                append("\n\nYou can resume it from the Tasks screen.")
            }
            else -> null
        }
    }
}
