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

    /** The shared whole-message normalisation: lowercase,
     *  trimmed, trailing . ! ? stripped, whitespace
     *  collapsed. Every deterministic chat command (probes,
     *  reason probes, kill — TaskControl) reads the message
     *  through this one lens. */
    fun normalise(text: String): String = text.trim().lowercase()
        .trimEnd('.', '!', '?', ' ')
        .replace(Regex("\\s+"), " ")

    /** True when the whole message is a bare status probe.
     *  Attachments and multi-sentence messages are never
     *  probes (the caller also gates on no-attachment). */
    fun isProbe(text: String): Boolean {
        val norm = normalise(text)
        if (norm.isEmpty() || norm.length > 40) return false
        if (norm.split(" ").size > 6) return false
        return norm in EXACT_PROBES
    }

    /**
     * REASON PROBES (v1.0.5, born of the 2026-11-27
     * screenshot): "why paused" is a status question too —
     * it asks for the CAUSE the record carries. Before
     * this set existed it fell through to the brain, which
     * invented a cause (a recycled "tools aren't available
     * in this environment" narrative) for a pause the user
     * himself had caused and then restarted. The shapes
     * are whole-message and exact, under the same
     * lookalike discipline as [isProbe]: a message
     * carrying an object ("explain why paused trading is
     * risky", "why is the sky blue") is a question for the
     * brain and is NOT intercepted. "what happened" was
     * already a probe; it is a reason shape too and is
     * answered by [reasonAnswerFor].
     */
    private val REASON_PROBES = setOf(
        "why",
        "why paused", "why is it paused", "why are you paused",
        "why did it pause", "why did you pause",
        "why stopped", "why is it stopped", "why has it stopped",
        "why did it stop", "why did you stop", "why did you stop it",
        "why isn't it running", "why is it not running",
        "why isn't it working", "why is it not working",
        "why aren't you working",
        "why is it stuck", "why is it waiting",
        "what happened"
    )

    /** True when the whole message asks WHY the chat's
     *  work is in the state it is in. The caller gates on
     *  the chat having work of its own, exactly as for
     *  [isProbe] — in a chat with no work, "why" is
     *  ordinary conversation. */
    fun isReasonProbe(text: String): Boolean {
        val norm = normalise(text)
        if (norm.isEmpty() || norm.length > 40) return false
        if (norm.split(" ").size > 6) return false
        return norm in REASON_PROBES
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
        // A task the user killed by hand reads as HIS act
        // on every surface (v1.0.5) — never the bare state
        // name, which let a killed X run masquerade as
        // merely "cancelled" next to an "under way" claim.
        if (TaskControl.wasKilled(task)) {
            return "Stopped — you stopped this."
        }
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

    /**
     * The deterministic answer to a REASON probe, composed
     * ONLY from the record: the task named, its state, and
     * the reason the record itself carries — killed by the
     * user's own command (his command quoted back), a
     * budget rest, a parked wall step, a stall park, a
     * browser takeover, an approval wait, a recorded note
     * (vetted). When the record carries no reason, the
     * answer says exactly that — a cause is never invented
     * (the 2026-11-27 failure was an invented cause). One
     * or two plain lines; no model is consulted.
     */
    fun reasonAnswerFor(task: JSONObject, now: Long): String {
        val title = TaskTransparency.titleFor(task)
        val state = task.optString("state")
        val steps = task.optInt("steps", 0)
        val stepBit = when {
            steps <= 0 -> ""
            steps == 1 -> " at 1 step"
            else -> " at $steps steps"
        }
        // A restart by the user's own tap is part of the
        // story whenever the run is live again: the pickup
        // is stated, never silent inheritance.
        val resumed = TaskControl.resumedAtStep(task)
        val restartBit = if (resumed != null &&
            (state == TaskStates.PAUSED || state == TaskStates.RUNNING)
        ) {
            "You restarted it — it picked up at step $resumed. "
        } else {
            ""
        }
        if (TaskControl.wasKilled(task)) {
            val command = TaskControl.killCommand(task)
            return if (command.isNotEmpty()) {
                "You stopped “$title” yourself — your message " +
                    "was “$command”. It stays stopped until you " +
                    "start it again."
            } else {
                "You stopped “$title” yourself. It stays " +
                    "stopped until you start it again."
            }
        }
        return when (state) {
            TaskStates.RUNNING ->
                restartBit + "“$title” isn't paused — she's " +
                    "working on it now" +
                    lastStepBit(task) + "."
            TaskStates.QUEUED ->
                "“$title” isn't paused — it's queued and " +
                    "starts on its own."
            TaskStates.WAITING_NETWORK ->
                "“$title” isn't paused — she's waiting for " +
                    "the network to come back, and she " +
                    "continues on her own when it does."
            TaskStates.WAITING_ROUTE ->
                if (task.optBoolean(StepBudget.REST_FLAG)) {
                    "“$title” isn't paused — it's resting " +
                        "between work sessions after a stretch " +
                        "of steps, and it continues on its own" +
                        retryBit(task, now) + "."
                } else {
                    "“$title” isn't paused — it's taking a " +
                        "short pause before the next step, and " +
                        "it continues on its own" +
                        retryBit(task, now) + "."
                }
            TaskStates.PARKED_AT_WALL -> {
                val step = task.optString("wallStep").trim()
                val note = NarrationGuard.cleanOrNull(
                    task.optString("note")
                )
                when {
                    step.isNotEmpty() ->
                        "“$title” is waiting on one step from " +
                            "you: $step"
                    note != null ->
                        "“$title” is waiting on one step from " +
                            "you. $note"
                    else ->
                        "“$title” is waiting on one step from " +
                            "you — the step waiting in the " +
                            "browser panel."
                }
            }
            TaskStates.PAUSED -> restartBit + pausedReason(task, title, stepBit)
            TaskStates.NEEDS_RUNNER ->
                "“$title” isn't paused — that job needs code " +
                    "execution, which runs on the computer " +
                    "lane, so it was handed there with a " +
                    "written brief instead of being faked here."
            TaskStates.COMPLETED -> {
                val summary = task.optString("summary").trim()
                if (summary.isNotEmpty()) {
                    "“$title” finished — " +
                        TaskTransparency.gist(summary, 140)
                } else {
                    "“$title” finished — the work is done."
                }
            }
            TaskStates.FAILED -> {
                val note = NarrationGuard.cleanOrNull(
                    task.optString("note")
                )
                if (note != null) {
                    "“$title” stopped$stepBit. Why: $note"
                } else {
                    "“$title” failed$stepBit — the record " +
                        "doesn't carry a reason."
                }
            }
            TaskStates.CANCELLED ->
                "“$title” was cancelled — it isn't running."
            else ->
                "“$title” is in state $state — that's all " +
                    "the record says."
        }
    }

    /** The recorded reason a PAUSED task carries, in
     *  priority order: an approval wait (a fact of the
     *  record, not a note), the user's own browser
     *  takeover, then the vetted note the runner left
     *  (a step-budget park and a stall park both record
     *  their reason there). No reason recorded → the
     *  answer says so plainly. */
    private fun pausedReason(
        task: JSONObject, title: String, stepBit: String
    ): String {
        if (task.optString(PlanAct.APPROVAL_KIND_FIELD).isNotEmpty()) {
            return "“$title” is paused$stepBit — it's waiting " +
                "on your word: a plan is ready for your " +
                "approval (say “approve”)."
        }
        if (task.optBoolean("browserTakeover", false)) {
            return "“$title” is paused$stepBit — you took " +
                "the browser over in the chat, so she's " +
                "paused while you're driving. Hand it back " +
                "and she resumes."
        }
        val note = NarrationGuard.cleanOrNull(task.optString("note"))
        if (note != null) {
            return "“$title” is paused$stepBit — $note"
        }
        return "“$title” is paused$stepBit, and the record " +
            "doesn't say why — say the word here and she " +
            "picks it back up."
    }

    /** " (last step: …)" from the recorded history, or ""
     *  when no step can be named. */
    private fun lastStepBit(task: JSONObject): String {
        val last = TaskTransparency.stepsFor(task).lastOrNull()
            ?: return ""
        return " (last step: " + last.name + ")"
    }

    /** " around 4:05 PM" when a retry time is recorded
     *  and still ahead; "" otherwise. */
    private fun retryBit(task: JSONObject, now: Long): String {
        val next = task.optLong("nextRetryAt", 0L)
        return if (next > now) {
            " around " + TaskTransparency.clockLabel(next)
        } else {
            ""
        }
    }

    /**
     * The acknowledgement when a packaged action (her X
     * creation/connect, the user's X connect) is tapped
     * while its record is already live — ONE composer, so
     * the lead-in can never contradict the state it
     * prefixes (v1.0.5: the shipped text read "already
     * under way — paused here", an impossible pairing).
     * "Under way" is claimed ONLY for states that are
     * actually in motion; a paused record is announced as
     * paused, a parked one as waiting on the user, a
     * killed one as stopped — by his hand.
     */
    fun liveAckFor(label: String, task: JSONObject): String {
        val line = statusLineFor(task)
            .replaceFirstChar { it.lowercase() }
        return when (task.optString("state")) {
            TaskStates.QUEUED, TaskStates.RUNNING,
            TaskStates.WAITING_ROUTE, TaskStates.WAITING_NETWORK ->
                "$label is already under way — $line"
            else -> "$label — $line"
        }
    }
}
