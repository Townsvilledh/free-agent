package org.freeagent.app

import org.json.JSONObject

/**
 * The chat↔task link (v0.13; CHAT IS THE ENGINE since
 * 2026-11-26) — how work that lives in a chat thread
 * reports INTO that same thread.
 *
 * The conversation is the workspace: a job given in chat
 * runs as part of that thread, on the durable engine as
 * its backbone (checkpoints, background survival, waiting
 * states, resume). There is no hand-off to a separate
 * room — the acknowledgement, the parked step and the
 * graded result all land in the thread where the work
 * lives, and the wording never sends the user elsewhere
 * to follow it.
 *
 * A chat-originated task record carries two extra fields
 * (records not born in a chat never set them, and every
 * reader here treats their absence as "not a chat task"):
 *
 * - [ORIGIN_FIELD]: the id of the chat the job was given in.
 * - [REPORTED_FIELD]: the last state already reported into
 *   that chat — the dedup stamp. Creation stamps it RUNNING
 *   (the [START_ACK] acknowledgement in the chat IS the
 *   started report), so a state is reported exactly once
 *   however often the engine re-enters it.
 *
 * [reportFor] is the graded-report composer: pure, over the
 * task record, in the house style. It speaks only from the
 * record — the finish summary the runner verified, the note
 * it left (vetted by NarrationGuard: a legacy note carrying
 * route narration is withheld), the wall step it recorded —
 * never from narration. A state that is not material
 * (queued, running, waiting on network/route, cancelled by
 * the user's own hand) composes nothing: waiting states
 * resolve themselves, and silence about them is the honest
 * report.
 */
object ChatTaskLink {

    const val ORIGIN_FIELD = "originChatId"
    const val REPORTED_FIELD = "chatReportState"

    const val COST_LINE = "Cost: $0 — keyless routes only."

    /** The acknowledgement shown in the thread when a job
     *  starts there. In-thread wording only (CHAT IS THE
     *  ENGINE): she is starting work HERE, in this
     *  conversation — no hand-off, no other screen named. */
    const val START_ACK =
        "Starting that now — I'll work on it here and " +
            "report back in this conversation as it lands."

    /**
     * The standing direction appended to every chat-born
     * task's text (the fluency standard, 2026-11-26): the
     * job may have arrived as a fragment, a link or a file
     * reference rather than a tidy instruction, and missing
     * pieces are HERS to gather with her tools during
     * execution — never a reason to come back asking for
     * inputs one by one. The only legitimate stop is a step
     * that is genuinely the user's alone.
     */
    const val INTAKE_NOTE =
        "[Chat intake: this job arrived in chat, possibly as " +
            "a fragment, a link, or a file reference rather " +
            "than a tidy instruction. Work out the full shape " +
            "of the job yourself. If a piece is missing and " +
            "you can obtain it with your tools — fetch the " +
            "link, read what was sent, search — gather it " +
            "yourself and keep going; do not stop to ask the " +
            "user for inputs one by one. Stop only for a step " +
            "that is genuinely the user's alone — a sign-in " +
            "tap or a human check — and name that one step.]"

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
        // The runner's note is echoed only when clean: a
        // legacy note carrying route narration is withheld
        // (seamless law) — the report stands on the state.
        val note = NarrationGuard.cleanOrNull(task.optString("note"))
        return when (newState) {
            TaskStates.COMPLETED -> buildString {
                append("Done — that work is finished.")
                if (summary.isNotEmpty()) {
                    append("\n\n").append(summary)
                }
                if (note != null && note != summary) {
                    append("\n\n").append(note)
                }
                append("\n\n").append(COST_LINE)
            }
            TaskStates.FAILED -> buildString {
                append("That work failed — it did not complete, and ")
                append("nothing about it counts as done.")
                if (note != null) {
                    append("\n\nWhy: ").append(note)
                }
                append("\n\nAsk me to retry it here, or tell me ")
                append("how you'd like it handled.")
            }
            TaskStates.PARKED_AT_WALL -> buildString {
                append("I need you for one step on this work.")
                val step = task.optString("wallStep").trim()
                if (step.isNotEmpty()) {
                    append("\n\n").append(step)
                } else if (note != null) {
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
                if (note != null) {
                    append("\n\n").append(note)
                }
            }
            TaskStates.PAUSED -> buildString {
                append("That work paused before it finished.")
                if (note != null) {
                    append("\n\n").append(note)
                }
                append("\n\nSay the word here and I'll pick it back up.")
            }
            else -> null
        }
    }
}
