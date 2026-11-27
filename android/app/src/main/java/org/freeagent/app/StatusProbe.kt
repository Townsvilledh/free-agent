package org.freeagent.app

import org.json.JSONObject

/**
 * STATUS FROM STATE (2026-11-26, born of a live failure):
 * a bare status probe in a chat — "update", "status",
 * "how far" — is answered DETERMINISTICALLY from the
 * chat's own work record, with no brain call at all.
 *
 * What happened live: Ethuram typed "update" in the chat
 * where Lille was working; the message fell through to a
 * fallback chat model, which answered with confabulated
 * self-dialogue presented as progress. A status question
 * needs no model — the record is the truth, and since the
 * chat IS the engine (same ruling), the thread simply
 * reads its own state.
 *
 * Detection is deliberately narrow — the WHOLE message
 * must be a bare probe. Anything carrying an object
 * ("update my bio", "check the status of bitcoin") is a
 * job or a question and is NOT intercepted: it flows on
 * to JobDetect / the brain exactly as before. And a probe
 * in a chat with no work of its own is ordinary chat too.
 *
 * Pure + org.json, pinned by StatusProbeTest both ways.
 */
object StatusProbe {

    /** Whole-message probe shapes (after normalisation:
     *  lowercase, trimmed, trailing . ! ? stripped). */
    private val EXACT_PROBES = setOf(
        "update", "status", "progress",
        "any update", "any updates", "any progress",
        "update me", "status update", "progress update",
        "update please", "status please",
        "how far", "how far along", "how far are you",
        "how far is it", "how is it going", "how's it going",
        "how is the work going", "how is the task going",
        "what's happening", "what is happening",
        "what happened", "is it done", "is it finished",
        "are you done", "are you finished", "done yet",
        "finished yet", "still working", "are you still working",
        "task status", "update on the task", "update on it",
        "how long", "what's the status", "what is the status",
        "give me an update", "give me a status update"
    )

    /** True when the whole message is a bare status probe.
     *  Attachments and multi-sentence messages are never
     *  probes (the caller also gates on no-attachment). */
    fun isProbe(text: String): Boolean {
        val norm = text.trim().lowercase()
            .trimEnd('.', '!', '?', ' ')
            .replace(Regex("\\s+"), " ")
        if (norm.isEmpty() || norm.length > 40) return false
        if (norm.split(" ").size > 6) return false
        return norm in EXACT_PROBES
    }

    /** The newest work record belonging to [chatId] — the
     *  thread's own job. Active work wins over finished
     *  work of the same age by createdAt order alone: the
     *  newest record IS the thread's current story. Null
     *  when the chat has no work of its own. */
    fun latestTaskForChat(
        tasks: List<JSONObject>, chatId: String
    ): JSONObject? =
        tasks.filter { it.optString(ChatTaskLink.ORIGIN_FIELD) == chatId }
            .maxByOrNull {
                maxOf(it.optLong("createdAt"), it.optLong("updatedAt"))
            }

    /** The SHORT state line: state + step count, plain
     *  words, no causes. Kept for mid-sentence embeddings
     *  ("already under way — …"); the in-thread status
     *  surface itself is TaskTransparency's rich block
     *  since v1.0.2 (a bare count was the thin rendering
     *  Ethuram called not transparent). */
    fun statusLineFor(task: JSONObject): String {
        val state = task.optString("state")
        val steps = task.optInt("steps", 0)
        val stepBit = when {
            steps <= 0 -> ""
            steps == 1 -> " · 1 step done"
            else -> " · $steps steps done"
        }
        return when (state) {
            TaskStates.QUEUED -> "Queued here — starts on its own."
            TaskStates.RUNNING -> "Working on it here$stepBit."
            TaskStates.WAITING_ROUTE, TaskStates.WAITING_NETWORK ->
                "Working on it here$stepBit · waiting, continues on its own."
            TaskStates.PARKED_AT_WALL ->
                "Waiting on one step from you (just above)."
            TaskStates.PAUSED -> "Paused here$stepBit."
            TaskStates.NEEDS_RUNNER ->
                "Exported for the runner lane — it can't run on this phone."
            TaskStates.COMPLETED -> "Finished$stepBit."
            TaskStates.FAILED -> "Failed$stepBit — nothing about it counts as done."
            TaskStates.CANCELLED -> "Cancelled."
            else -> "State: $state$stepBit."
        }
    }

    /** The full deterministic answer to a status probe,
     *  composed ONLY from the record — since v1.0.2 that
     *  is [TaskTransparency]'s rich rendering: the last
     *  step BY NAME, the state in work terms (what she is
     *  waiting for, when she continues, the exact parked
     *  step), the verified summary when finished, and the
     *  last-movement time. Notes reach it only through
     *  NarrationGuard (a legacy note carrying route
     *  narration is withheld, never echoed). [noChangeSince]
     *  is the record's updatedAt when the task has not
     *  moved since the chat's living status message last
     *  reflected it — the answer then says so in one short
     *  line instead of repeating the block as a new bubble
     *  (the caller upserts; there is only ever one). */
    fun answerFor(
        task: JSONObject, now: Long, noChangeSince: Long? = null
    ): String = TaskTransparency.probeAnswer(task, now, noChangeSince)
}
