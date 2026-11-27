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

    /**
     * PROBE GRAMMAR (v1.0.6 — structure, not a phrase
     * table; Ethuram: "No hardcoding"). A bare probe is a
     * whole message composed ONLY of closed-class words:
     * function words (determiners, auxiliaries, pronouns —
     * the grammar of a question about the work) plus at
     * least one status content word (update, paused,
     * done…). Any open-class word — an object — breaks
     * the parse: "update my bio" carries "bio", "why is
     * the sky blue" carries "sky"/"blue", and both flow
     * to the brain untouched. Every phrasing the old
     * exact sets accepted parses under this grammar, and
     * new phrasings of the same shape ("why is the task
     * stopped", "is the work finished yet") resolve
     * identically — the sets are gone.
     */
    private val PROBE_CONTENT = setOf(
        "update", "updates", "status", "progress", "why",
        "paused", "pause", "stopped", "stop", "stuck",
        "waiting", "done", "finished", "finish",
        "happened", "happening", "happen",
        "working", "running", "going", "work", "task", "job",
        "far", "long"
    )

    /** The cause words: a probe carrying one of these
     *  asks WHY the work is in its state, not just where
     *  it stands. */
    private val REASON_CONTENT = setOf(
        "why", "paused", "pause", "stopped", "stop",
        "stuck", "waiting", "happened", "happening", "happen"
    )

    private val PROBE_FUNCTION = setOf(
        "how", "how's", "what", "what's", "is", "isn't",
        "aren't", "it", "the", "a", "an", "are", "you",
        "me", "my", "your", "on", "of", "to", "be", "been",
        "has", "have", "was", "were", "do", "does", "did",
        "will", "would", "can", "could", "should", "any",
        "please", "yet", "still", "now", "along", "give",
        "that", "this", "there", "here", "not", "no",
        "and", "or", "in", "at", "for", "from", "with",
        "about", "i"
    )

    private val PROBE_VOCAB = PROBE_CONTENT + PROBE_FUNCTION

    private val PROBE_NEGATIONS = setOf("isn't", "aren't", "not")

    /** The shared whole-message normalisation: lowercase,
     *  trimmed, trailing . ! ? stripped, whitespace
     *  collapsed. Every deterministic chat command (probes,
     *  reason probes, kill/resume — TaskControl) reads the
     *  message through this one lens. */
    fun normalise(text: String): String = text.trim().lowercase()
        .trimEnd('.', '!', '?', ' ')
        .replace(Regex("\\s+"), " ")

    /** The message's tokens when it parses under the
     *  probe grammar (length caps + closed vocabulary),
     *  else null. */
    private fun probeTokens(text: String): List<String>? {
        val norm = normalise(text)
        if (norm.isEmpty() || norm.length > 40) return null
        val tokens = norm.split(" ")
        if (tokens.size > 6) return null
        if (tokens.any { it !in PROBE_VOCAB }) return null
        return tokens
    }

    /** True when the whole message is a bare status probe.
     *  Attachments and multi-sentence messages are never
     *  probes (the caller also gates on no-attachment). */
    fun isProbe(text: String): Boolean {
        val tokens = probeTokens(text) ?: return false
        return tokens.any { it in PROBE_CONTENT }
    }

    /**
     * REASON PROBES (v1.0.5, born of the 2026-11-27
     * screenshot): "why paused" is a status question too —
     * it asks for the CAUSE the record carries. Before
     * this detection existed it fell through to the brain,
     * which invented a cause (a recycled "tools aren't
     * available in this environment" narrative) for a
     * pause the user himself had caused and then
     * restarted. Under the v1.0.6 grammar a reason probe
     * is a probe-grammar message carrying a cause word —
     * with two structural refinements: a lone "why"
     * padded with other words ("why is it") names no
     * cause and is a plain probe, while bare "why" alone
     * stays a reason probe (the pinned shape); and
     * why + negation + a state word ("why isn't it
     * running") is the cause question about a state.
     * Answered by [reasonAnswerFor]. The caller gates on
     * the chat having work of its own, exactly as for
     * [isProbe] — in a chat with no work, "why" is
     * ordinary conversation.
     */
    fun isReasonProbe(text: String): Boolean {
        val tokens = probeTokens(text) ?: return false
        val reasonHits = tokens.filter { it in REASON_CONTENT }.toSet()
        if (reasonHits.isNotEmpty() &&
            !(reasonHits == setOf("why") && tokens.size > 1)
        ) {
            return true
        }
        return "why" in tokens &&
            tokens.any { it in PROBE_NEGATIONS } &&
            tokens.any { it in PROBE_CONTENT && it != "why" }
    }

    /** The newest work record belonging to [chatId] — the
     *  thread's own job. Active work wins over finished
     *  work of the same age by createdAt order alone: the
     *  newest record IS the thread's current story. Null
     *  when the chat has no work of its own. Records the
     *  user closed away ("kill this completely", v1.0.6)
     *  are skipped: an archived record never resurfaces,
     *  on this surface or any other that reads through
     *  here (probe answers, the chats-list work mark). */
    fun latestTaskForChat(
        tasks: List<JSONObject>, chatId: String
    ): JSONObject? =
        tasks.filter {
            it.optString(ChatTaskLink.ORIGIN_FIELD) == chatId &&
                !TaskControl.isArchived(it)
        }
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
