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
                val note = StateCoherence.coherentNote(
                    task, TaskStates.PARKED_AT_WALL
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
                if (hasDiscrepancy(task)) {
                    discrepancyAnswer(task, now)
                } else {
                    val summary = task.optString("summary").trim()
                    if (summary.isNotEmpty()) {
                        "“$title” finished — " +
                            TaskTransparency.gist(summary, 140)
                    } else {
                        "“$title” finished — the work is done."
                    }
                }
            }
            TaskStates.FAILED -> {
                val note = StateCoherence.coherentNote(
                    task, TaskStates.FAILED
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
        val note = StateCoherence.coherentNote(
            task, TaskStates.PAUSED
        )
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

    // ---------- needs / blockers probe (v1.0.7) ----------

    /**
     * NEEDS GRAMMAR (v1.0.7 — structure, not a phrase
     * table, same discipline as the probe grammar):
     * "how can we help her over all this" — Ethuram
     * asked what she NEEDS, and in 1.0.6 that question
     * fell to the brain, which answered with a blockers
     * table and a claim that her own X creation was
     * paused waiting for HIM to supply her handle,
     * email, password and phone. A needs question in a
     * chat with live work is a question about the
     * RECORD, and the record's truth is narrow: she
     * works on her own, and the only thing that can
     * ever need the user is a human step in the browser
     * panel. So the whole family is deterministic too.
     *
     * A needs probe is a whole message composed ONLY of
     * closed-class words (the probe function words plus
     * the needs content words) carrying one of three
     * frames:
     *  (a) an OFFER of help toward her — "help" with
     *      "you", or the "how can/could … help" shape;
     *  (b) HER need asked of her — a need word with
     *      "you", in question shape (a question-word
     *      lead, "from me", or a trailing "?");
     *  (c) what is IN HER WAY — a block word with "you"
     *      in question shape.
     * Any open-class word breaks the parse: "what do
     * you need for the report" carries "report" and
     * flows to the brain untouched — it is a question
     * about a job's inputs, not a control question.
     * "I need help" parses as nothing: the frames all
     * point at HER need, never the user's.
     */
    private val NEEDS_CONTENT = setOf(
        "help", "need", "needs", "needed",
        "blocking", "blocked", "blockers", "stopping",
        "assist", "assistance"
    )

    private val NEEDS_FUNCTION = PROBE_FUNCTION + setOf(
        "anything", "something", "else"
    )

    private val NEEDS_VOCAB = NEEDS_CONTENT + NEEDS_FUNCTION

    private val NEED_WORDS = setOf("need", "needs", "needed")

    private val BLOCK_WORDS =
        setOf("blocking", "blocked", "blockers", "stopping")

    /** Leads that make a message a question directed at
     *  her (frame b/c shape evidence). */
    private val NEEDS_QUESTION_LEADS = setOf(
        "what", "what's", "how", "how's", "do", "does",
        "is", "can", "could", "anything", "something"
    )

    /** True when the whole message is a needs/blockers
     *  question in the family above. The caller gates on
     *  the chat having work in a needs state ([needsTaskFor])
     *  — in a chat with no live work it is ordinary chat. */
    fun isNeedsProbe(text: String): Boolean {
        val norm = normalise(text)
        if (norm.isEmpty() || norm.length > 48) return false
        val tokens = norm.split(" ")
        if (tokens.size > 8) return false
        if (tokens.any { it !in NEEDS_VOCAB }) return false
        val set = tokens.toSet()
        val questioned = text.trim().endsWith("?") ||
            tokens[0] in NEEDS_QUESTION_LEADS ||
            ("from" in set && "me" in set)
        // (a) an offer of help toward her.
        if ("help" in set || "assist" in set ||
            "assistance" in set
        ) {
            if ("you" in set) return true
            if (tokens[0] == "how" &&
                ("can" in set || "could" in set)
            ) {
                return true
            }
        }
        // (b) her need, asked of her.
        if (set.any { it in NEED_WORDS } && "you" in set &&
            questioned
        ) {
            return true
        }
        // (c) what is in her way.
        if (set.any { it in BLOCK_WORDS } && "you" in set &&
            questioned
        ) {
            return true
        }
        return false
    }

    /** The states a needs question speaks to: work that
     *  is active, paused, resting or parked — the brief's
     *  family. Finished work asks nothing and a failed
     *  record's "what do you need" is a different
     *  conversation; neither is intercepted. */
    private val NEEDS_STATES = setOf(
        TaskStates.QUEUED, TaskStates.RUNNING,
        TaskStates.WAITING_ROUTE, TaskStates.WAITING_NETWORK,
        TaskStates.PAUSED, TaskStates.PARKED_AT_WALL,
        TaskStates.NEEDS_RUNNER
    )

    /** The chat's newest work when it stands in a needs
     *  state, else null — the gate for [isNeedsProbe],
     *  mirroring the other probes' latest-record rule. */
    fun needsTaskFor(
        tasks: List<JSONObject>, chatId: String
    ): JSONObject? =
        latestTaskForChat(tasks, chatId)?.takeIf {
            it.optString("state") in NEEDS_STATES
        }

    /**
     * The deterministic answer to a needs question,
     * composed ONLY from the record: the state and step,
     * what she does next ON HER OWN, and the only thing
     * that can ever need the user — a human check in the
     * browser panel, named when the record is parked at
     * one (an approval word or a browser hand-back when
     * the record says exactly that). It never requests
     * details, credentials or inputs — her own identity
     * items are hers, and a needs answer that asked for
     * them would be the 2026-11-27 failure wearing a
     * deterministic coat. The text is itself clean under
     * the chat guard (pinned).
     */
    fun needsAnswerFor(task: JSONObject, now: Long): String {
        val title = TaskTransparency.titleFor(task)
        val state = task.optString("state")
        val steps = task.optInt("steps", 0)
        val stepBit = when {
            steps <= 0 -> ""
            steps == 1 -> " at 1 step"
            else -> " at $steps steps"
        }
        val panelLine = "The only step that can ever need " +
            "you is a human check in the browser panel — " +
            "if one comes up she parks there and shows it."
        return when (state) {
            TaskStates.PARKED_AT_WALL -> {
                val step = task.optString("wallStep").trim()
                val stepText = if (step.isNotEmpty()) {
                    step
                } else {
                    "the step waiting in the browser panel"
                }
                val sep = if (stepText.endsWith(".")) " " else ". "
                "One thing, and it's in the panel, not in " +
                    "chat: “$title” is parked at $stepText" +
                    sep + "Pass it there and hand back — " +
                    "she does everything else herself, and " +
                    "she needs no details or inputs from you."
            }
            TaskStates.PAUSED -> when {
                task.optString(PlanAct.APPROVAL_KIND_FIELD)
                    .isNotEmpty() ->
                    "One thing: “$title” is paused$stepBit " +
                        "waiting on your word — a plan is " +
                        "ready for your approval (say " +
                        "“approve”). Nothing else is needed " +
                        "from you; the rest she does herself."
                task.optBoolean("browserTakeover", false) ->
                    "One thing: you have her browser right " +
                        "now — hand it back and “$title” " +
                        "continues on its own. Nothing else " +
                        "is needed from you."
                else -> {
                    val note = StateCoherence.coherentNote(
                        task, TaskStates.PAUSED
                    )
                    val noteBit = if (note != null) {
                        " — " + note.trim().trimEnd('.')
                    } else {
                        ""
                    }
                    "Nothing — “$title” is paused$stepBit" +
                        noteBit + ". She needs no details or " +
                        "inputs from you; say “resume” and " +
                        "she picks it up herself."
                }
            }
            TaskStates.WAITING_NETWORK ->
                "Nothing — “$title” is waiting on the " +
                    "network$stepBit, and she continues on " +
                    "her own the moment it returns. " +
                    panelLine
            TaskStates.WAITING_ROUTE ->
                "Nothing — “$title” is resting$stepBit " +
                    "between work sessions, and she " +
                    "continues on her own" +
                    retryBit(task, now) + ". " + panelLine
            TaskStates.RUNNING ->
                "Nothing — she's working on “$title” " +
                    "now" + lastStepBit(task) + ", and she " +
                    "carries on by herself. " + panelLine
            TaskStates.QUEUED ->
                "Nothing — “$title” is queued and starts " +
                    "on its own. " + panelLine
            TaskStates.NEEDS_RUNNER ->
                "Nothing — “$title” runs on the computer " +
                    "lane, not this phone, and it was handed " +
                    "there with a written brief. Nothing is " +
                    "needed from you here."
            else ->
                "Nothing — the record for “$title” asks " +
                    "nothing of you."
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

    // ---------- state questions (v1.0.8) ----------

    /**
     * THE STATE-QUESTION FAMILY (v1.0.8, born of the
     * 2026-11-27 screenshots): the probe grammar above
     * covers bare probes ("what's the status"), but the
     * questions Ethuram actually asked about the false
     * finish — "so the x profile is created?", "why did
     * you claim its complete", "so where does the task
     * stand now", "what page is she on" — carry objects
     * and fell to the brain, which answered every one
     * from ignorance ("I can't confirm…", "I can't
     * verify an active task's current status from this
     * chat") and invented a confession for the rest.
     *
     * The family is structural, like the grammars above
     * — no phrase lists. A message belongs when it is
     * SHORT, composed only of closed-class words plus
     * the referents OF A RECORD IN THIS CHAT (its title
     * words, its lane's referent class), and carries one
     * of the frames:
     *  - COMPLETION: a completion predicate (created,
     *    finished, done, verified, ready…) bound to a
     *    referent of the record — yes/no questions about
     *    the work's outcome, answered yes or no FROM the
     *    record, with a finished packaged record cross-
     *    checked against its finish gate;
     *  - ACCOUNTABILITY: a claim word (claim, said,
     *    told) with "you" — questions about a claim SHE
     *    made, answered from what the record actually
     *    said and whether it stands;
     *  - STAND: where/how + stand — the record's standing;
     *  - BROWSER: a browser word in question shape —
     *    answered from the session's resting page;
     *  - LOGIN (separate gate below): the sign-in state,
     *    answered from the record's identity stamp.
     * A message carrying an open-class object that is
     * no record's referent parses for NO record and
     * flows on untouched — "is the report ready" in a
     * chat whose work is not a report is the brain's,
     * exactly as the lookalike pins demand.
     */
    enum class StateKind {
        COMPLETION, BROWSER, ACCOUNTABILITY, STAND, LOGIN, GENERIC
    }

    private val COMPLETION_PREDICATES = setOf(
        "created", "create", "finished", "finish", "done",
        "complete", "completed", "verified", "verify",
        "exist", "exists", "live", "ready", "succeeded",
        "success", "happened"
    )

    private val CLAIM_WORDS = setOf(
        "claim", "claimed", "claiming", "claims",
        "said", "say", "saying", "says", "told", "telling"
    )

    private val BROWSER_WORDS = setOf(
        "browser", "page", "tab", "site", "screen"
    )

    private val STAND_WORDS = setOf("stand", "stands", "standing")

    private val QUESTION_LEADS = setOf(
        "is", "are", "was", "were", "did", "do", "does",
        "has", "have", "so", "how", "where", "what",
        "what's", "why", "who", "can", "could"
    )

    /** Function words for the state family: the probe
     *  grammar's closed class plus the question framing
     *  words this family needs ("so", "where", "its"…). */
    private val STATE_FUNCTION = PROBE_FUNCTION + setOf(
        "so", "where", "who", "when", "whether", "if",
        "why", "did", "just", "already", "its", "it's",
        "about", "into", "out", "off", "up", "down",
        "back", "again", "really", "actually", "get",
        "got", "getting", "made", "make", "she", "her",
        "he", "we", "us", "am"
    )

    /** Referents every record shares: the work itself,
     *  nameless. A completion question bound to one of
     *  these resolves to the chat's newest record. */
    private val GENERIC_REFERENTS = setOf(
        "it", "this", "that", "task", "work", "job", "thing"
    )

    /** The X lanes' referent class — the lane's own
     *  vocabulary (profile, handle, account), derived
     *  from the lane, not from anyone's phrasing. */
    private val X_REFERENTS = setOf(
        "x", "twitter", "profile", "account", "handle",
        "creation", "signup", "tweet", "tweets"
    )

    private val TITLE_STOPWORDS = setOf(
        "the", "a", "an", "of", "for", "to", "my", "her",
        "his", "your", "lille's", "lille", "and", "with",
        "on", "in", "at", "by", "from"
    )

    /** True when [task] is one of the X lanes (hers to
     *  create/connect, the user's to connect) — the
     *  records the X referent class belongs to. */
    fun isXTask(task: JSONObject): Boolean {
        if (LilleXState.isCreationTask(task)) return true
        val title = task.optString("title")
        return title == XAccountTask.CONNECT_TITLE ||
            title == XConnectTask.TITLE
    }

    /** A record's referent vocabulary: its title's
     *  content words, its lane's class words when it has
     *  one, and the generic work referents. */
    fun referentsFor(task: JSONObject): Set<String> {
        val out = mutableSetOf<String>()
        val title = TaskTransparency.titleFor(task).lowercase()
        for (raw in title.split(Regex("[^a-z0-9']+"))) {
            val w = raw.trim('\'')
            if (w.length >= 2 && w !in TITLE_STOPWORDS) {
                out.add(w)
            }
        }
        if (isXTask(task)) out.addAll(X_REFERENTS)
        out.addAll(GENERIC_REFERENTS)
        return out
    }

    /** This chat's records, newest first — the
     *  resolution order for every state question. */
    private fun chatTasksNewestFirst(
        tasks: List<JSONObject>, chatId: String
    ): List<JSONObject> =
        tasks.filter {
            it.optString(ChatTaskLink.ORIGIN_FIELD) == chatId &&
                !TaskControl.isArchived(it)
        }.sortedByDescending {
            maxOf(it.optLong("createdAt"), it.optLong("updatedAt"))
        }

    private fun stateTokens(text: String): List<String>? {
        val norm = normalise(text)
        if (norm.isEmpty() || norm.length > 90) return null
        val tokens = norm.split(" ")
        if (tokens.size > 12) return null
        return tokens
    }

    /**
     * Resolve [text] as a state question: the record it
     * asks about and the frame it carries, or null when
     * the message is not one (or this chat has no record
     * it could be about). Accountability resolves to the
     * chat's newest record — a claim she made in this
     * thread is about its newest work. The browser frame
     * resolves to the newest browser task when one
     * exists, else the newest record (the answer still
     * comes from the session, below).
     */
    fun stateQuestionFor(
        text: String, tasks: List<JSONObject>, chatId: String
    ): Pair<JSONObject, StateKind>? {
        val tokens = stateTokens(text) ?: return null
        val set = tokens.toSet()
        val records = chatTasksNewestFirst(tasks, chatId)
        if (records.isEmpty()) return null
        val questioned = text.trim().endsWith("?") ||
            tokens[0] in QUESTION_LEADS
        // ACCOUNTABILITY: a claim word with "you" — the
        // vocabulary is the global state class plus the
        // newest record's referents (the claim names
        // the work it was about).
        if (set.any { it in CLAIM_WORDS } && "you" in set) {
            val vocab = STATE_FUNCTION + CLAIM_WORDS +
                COMPLETION_PREDICATES + BROWSER_WORDS +
                STAND_WORDS + referentsFor(records.first())
            if (tokens.all { it in vocab }) {
                return records.first() to StateKind.ACCOUNTABILITY
            }
        }
        // BROWSER: a browser word in question shape.
        if (set.any { it in BROWSER_WORDS } && questioned) {
            val vocab = STATE_FUNCTION + BROWSER_WORDS +
                GENERIC_REFERENTS + setOf("on", "in", "open", "resting")
            if (tokens.all { it in vocab }) {
                val browserTask = records.firstOrNull {
                    it.optString("kind") == "browser" ||
                        TaskTransparency.stepsFor(it).any { s ->
                            s.name.contains("page", true) ||
                                s.name.contains("browser", true)
                        }
                }
                return (browserTask ?: records.first()) to
                    StateKind.BROWSER
            }
        }
        // Per-record frames: the message must parse
        // entirely inside the record's own vocabulary.
        for (task in records) {
            val referents = referentsFor(task)
            val vocab = STATE_FUNCTION + referents +
                COMPLETION_PREDICATES + STAND_WORDS
            if (!tokens.all { it in vocab }) continue
            val hasReferent = set.any { it in referents }
            if (!hasReferent) continue
            if (set.any { it in STAND_WORDS } &&
                ("where" in set || "how" in set)
            ) {
                return task to StateKind.STAND
            }
            if (set.any { it in COMPLETION_PREDICATES }) {
                return task to StateKind.COMPLETION
            }
        }
        return null
    }

    /** The accounts family (v1.0.8): which accounts she
     *  holds, whose they are, which is active — answered
     *  from the AccountsRegistry, never the brain.
     *  Closed vocabulary, an accounts word required, and
     *  a question or list shape: "which accounts do you
     *  have", "list her accounts", "what accounts are
     *  connected". A job carrying an open-class object
     *  ("connect my bank account") parses outside the
     *  vocabulary and flows on. */
    private val ACCOUNTS_VOCAB = STATE_FUNCTION + setOf(
        "accounts", "account", "profiles", "handles",
        "which", "list", "show", "active", "connected",
        "linked", "have", "has", "do", "does", "she",
        "her", "hers", "your", "yours", "mine", "my",
        "you", "on", "x", "twitter", "own", "all",
        "currently", "now", "there", "registered", "held",
        "holds", "holding"
    )

    fun isAccountsQuestion(text: String): Boolean {
        val tokens = stateTokens(text) ?: return false
        val set = tokens.toSet()
        if (!set.any {
                it in setOf("accounts", "account", "profiles", "handles")
            }
        ) {
            return false
        }
        if (!tokens.all { it in ACCOUNTS_VOCAB }) return false
        return text.trim().endsWith("?") ||
            tokens[0] in QUESTION_LEADS ||
            set.any { it in setOf("list", "show") }
    }

    /** Text-only browser-frame check — the glue uses it
     *  when the chat has no record at all: the answer
     *  then comes from the session's resting page alone. */
    fun isBrowserQuestion(text: String): Boolean {
        val tokens = stateTokens(text) ?: return false
        val set = tokens.toSet()
        if (!set.any { it in BROWSER_WORDS }) return false
        val questioned = text.trim().endsWith("?") ||
            tokens[0] in QUESTION_LEADS
        if (!questioned) return false
        val vocab = STATE_FUNCTION + BROWSER_WORDS +
            GENERIC_REFERENTS + setOf("on", "in", "open", "resting")
        return tokens.all { it in vocab }
    }

    // ---------- the login family ----------

    private val LOGIN_WORDS = setOf(
        "logged", "login", "signed", "signin"
    )

    private val LOGIN_VOCAB = STATE_FUNCTION + LOGIN_WORDS +
        setOf(
            "log", "sign", "in", "out", "into", "account",
            "profile", "browser", "session", "x", "twitter",
            "already", "now", "yet", "still", "back", "as",
            "who", "she", "her", "i", "i've", "ive", "am",
            "is", "are", "was", "were", "have", "has",
            "did", "do", "does", "me", "my", "you", "your",
            "the", "a", "an", "to", "on", "for", "with"
        )

    /**
     * Resolve a login-state message — a question ("am I
     * logged in", "is she signed in") OR the bare
     * assertion the connect flow produces ("already
     * logged in", said by the user who just did the
     * step himself) — to the record it belongs to: the
     * chat's newest X-lane record, parked record, or
     * identity-stamped record. Null when the message is
     * not in the family or no such record exists here.
     * The vocabulary is closed: "log in to the site and
     * post" carries an open-class object and is a job,
     * never this family.
     */
    fun loginTaskFor(
        text: String, tasks: List<JSONObject>, chatId: String
    ): JSONObject? {
        val tokens = stateTokens(text) ?: return null
        if (tokens.size > 7) return null
        val set = tokens.toSet()
        val loginHit = set.any { it in LOGIN_WORDS } ||
            (("log" in set || "sign" in set) &&
                ("in" in set || "out" in set))
        if (!loginHit) return null
        if (!tokens.all { it in LOGIN_VOCAB }) return null
        return chatTasksNewestFirst(tasks, chatId).firstOrNull {
            isXTask(it) || SessionIdentity.checked(it) ||
                it.optString("state") == TaskStates.PARKED_AT_WALL
        }
    }

    /**
     * The deterministic answer to a login-state message,
     * from the record's identity stamp — never from the
     * messages, never from the user's say-so. The stamp
     * is the page's own testimony: who it showed signed
     * in, and when she read it. A pending handback
     * verification is answered as what it is (the check
     * is running; its result lands from the record), and
     * a parked sign-in step points back at the panel,
     * where the step lives.
     */
    fun loginAnswerFor(task: JSONObject, now: Long): String {
        val title = TaskTransparency.titleFor(task)
        if (SessionIdentity.handbackPending(task)) {
            return "She's verifying that from the browser " +
                "itself right now — the handback started " +
                "the check, and the verified result lands " +
                "here from the record, not from messages."
        }
        val parkedBit = if (
            task.optString("state") == TaskStates.PARKED_AT_WALL &&
            task.optString("wallKind") == UserHandoff.WALL_KIND
        ) {
            " The sign-in step is in the browser panel — " +
                "sign in there and hand back, and she " +
                "verifies from the page the moment it " +
                "comes back."
        } else {
            ""
        }
        if (!SessionIdentity.checked(task)) {
            return "She hasn't read the browser's sign-in " +
                "on “$title” yet — the check runs when the " +
                "lane starts, resumes, or takes a handback, " +
                "and the answer comes from the page itself." +
                parkedBit
        }
        val signed = SessionIdentity.signedInAs(task)
        val at = SessionIdentity.checkedLabel(task)
        val atBit = if (at != null) " (she read it off the page at $at)" else ""
        return if (signed != null) {
            val ownerBit = when {
                LilleXState.isCreationTask(task) &&
                    SessionIdentity.ownerFor(task, null) ==
                    SessionIdentity.Owner.OTHER ->
                    " That's your account, not hers — her " +
                        "lane names that and won't act under it."
                task.optString("title") == XConnectTask.TITLE ->
                    " Posting from that account stays " +
                        "approval-gated."
                else -> ""
            }
            "Yes — the browser is signed in as @$signed" +
                atBit + "." + ownerBit + parkedBit
        } else {
            "No — her last check" +
                (if (at != null) " ($at)" else "") +
                " showed the browser signed out." + parkedBit
        }
    }

    // ---------- state answers ----------

    /** True when the record carries a finish
     *  discrepancy: stamped by the gate/audit, or
     *  derivable now (a gated record claiming Finished
     *  whose criteria fail — the pre-audit window). */
    fun hasDiscrepancy(task: JSONObject): Boolean =
        task.optString(FinishGate.DISCREPANCY_FIELD).isNotEmpty() ||
            (task.optString("state") == TaskStates.COMPLETED &&
                FinishGate.isGated(task) && !FinishGate.holds(task))

    /** The discrepancy at the heart of the false
     *  finish, in work terms — from the stamp when the
     *  audit wrote one, else derived from the gate. */
    private fun discrepancyText(task: JSONObject): String {
        val stamped = task.optString(FinishGate.DISCREPANCY_FIELD)
        return if (stamped.isNotEmpty()) {
            stamped
        } else {
            FinishGate.discrepancyFor(task)
        }
    }

    /** The last REAL step's name: stepsFor appends a
     *  synthetic "Finished — …" step to a completed
     *  record (the status card's own line), but an
     *  answer about where the work actually got to must
     *  name the last step the lane took, not the finish
     *  bookkeeping. */
    private fun lastStepName(task: JSONObject): String? {
        var steps = TaskTransparency.stepsFor(task)
        if (task.optString("state") == TaskStates.COMPLETED &&
            steps.isNotEmpty() &&
            steps.last().name.startsWith("Finished")
        ) {
            steps = steps.dropLast(1)
        }
        return steps.lastOrNull()?.name
    }

    /**
     * The false finish answered plainly: what the
     * record claimed, why it doesn't stand, and where
     * the work actually is. Used by the completion and
     * accountability answers and by the guard's
     * status-denial fallback — one story everywhere.
     */
    fun discrepancyAnswer(task: JSONObject, now: Long): String {
        val title = TaskTransparency.titleFor(task)
        val step = lastStepName(task)
        val stepBit = if (step != null) {
            " Its steps only got as far as “$step”."
        } else {
            ""
        }
        return if (task.optString("state") == TaskStates.COMPLETED) {
            "No — that finish doesn't stand. The record " +
                "says “$title” finished, but " +
                discrepancyText(task) + "." + stepBit +
                " The finish is reopened and the work " +
                "continues from where the steps stopped."
        } else {
            "No — and the record has already been " +
                "corrected. “$title” was marked finished, " +
                "but " + discrepancyText(task) + "." +
                stepBit + " The work stands at " +
                statusLineFor(task).replaceFirstChar {
                    it.lowercase()
                } +
                " She continues from there."
        }
    }

    /** The accountability answer: why she claimed what
     *  she claimed — from the record, never a model's
     *  invented confession. When the record was wrong,
     *  the answer says the record was wrong and what
     *  corrected it; when it was right, the evidence is
     *  named; when it never claimed the thing, that is
     *  said too. */
    fun accountabilityAnswer(task: JSONObject, now: Long): String {
        val title = TaskTransparency.titleFor(task)
        if (hasDiscrepancy(task)) {
            val step = lastStepName(task)
            val stepBit = if (step != null) {
                " Its steps only got as far as “$step”."
            } else {
                ""
            }
            val tense = if (
                task.optString("state") == TaskStates.COMPLETED
            ) {
                "That finish is being reopened — the work " +
                    "continues from where the steps stopped."
            } else {
                "That finish has been reopened — the work " +
                    "stands at " +
                    statusLineFor(task).replaceFirstChar {
                        it.lowercase()
                    }
            }
            return "You're right to pull that up — the " +
                "claim came from the record, and the " +
                "record was wrong. It marked “$title” " +
                "finished, but " + discrepancyText(task) +
                "." + stepBit + " " + tense
        }
        if (task.optString("state") == TaskStates.COMPLETED) {
            if (FinishGate.isGated(task)) {
                val handle = FinishGate.sessionHandle(task)
                val handleBit = if (handle != null) {
                    "a live session as @$handle is verified " +
                        "on the record, and her profile page " +
                        "was seen loading as hers"
                } else {
                    "its finish criteria hold on the record"
                }
                return "The record does say “$title” " +
                    "finished — and this one checks out: " +
                    handleBit + ". The claim came from the " +
                    "record, not from a guess."
            }
            val summary = task.optString("summary").trim()
            return if (summary.isNotEmpty()) {
                "The record says “$title” finished — " +
                    TaskTransparency.gist(summary, 140) +
                    " That claim is the record's own state."
            } else {
                "The record says “$title” finished — the " +
                    "work is done. That claim is the " +
                    "record's own state."
            }
        }
        val step = lastStepName(task)
        val stepBit = if (step != null) {
            " Last step: “$step”."
        } else {
            ""
        }
        return "The record doesn't claim that — “$title” " +
            "stands at " +
            statusLineFor(task).replaceFirstChar {
                it.lowercase()
            } + stepBit
    }

    /** The yes/no completion answer, cross-checked:
     *  a gated finish is claimed only when its criteria
     *  hold on the record. */
    fun completionAnswer(task: JSONObject, now: Long): String {
        val title = TaskTransparency.titleFor(task)
        if (hasDiscrepancy(task)) {
            return discrepancyAnswer(task, now)
        }
        if (task.optString("state") == TaskStates.COMPLETED) {
            if (FinishGate.isGated(task)) {
                val handle = FinishGate.sessionHandle(task)
                return if (handle != null) {
                    "Yes — “$title” is finished, and it " +
                        "checks out from the record: a live " +
                        "session as @$handle, and her " +
                        "profile page seen loading as hers."
                } else {
                    "Yes — “$title” is finished, and its " +
                        "finish criteria hold on the record."
                }
            }
            val summary = task.optString("summary").trim()
            return if (summary.isNotEmpty()) {
                "Yes — “$title” finished. " +
                    TaskTransparency.gist(summary, 140)
            } else {
                "Yes — “$title” finished."
            }
        }
        val step = lastStepName(task)
        val stepBit = if (step != null) {
            " Last step: “$step”."
        } else {
            ""
        }
        val wallBit = if (
            task.optString("state") == TaskStates.PARKED_AT_WALL
        ) {
            val stepText = task.optString("wallStep").trim()
            if (stepText.isNotEmpty()) {
                " It's waiting on one step from you: $stepText"
            } else {
                " It's waiting on one step from you in " +
                    "the browser panel."
            }
        } else {
            ""
        }
        return "No — “$title” isn't finished. " +
            statusLineFor(task) + stepBit + wallBit
    }

    /** The browser answer: the session's resting page,
     *  from the glue's read — plus the record it belongs
     *  to and its identity stamp, when a record is in
     *  hand. A null task answers from the session alone. */
    fun browserAnswer(
        task: JSONObject?, browserUrl: String, browserTitle: String
    ): String {
        val head = if (browserUrl.isNotEmpty()) {
            "Her browser is resting on $browserUrl" +
                if (browserTitle.isNotEmpty()) {
                    " — “$browserTitle”."
                } else {
                    "."
                }
        } else {
            "Her browser isn't resting on a page right now."
        }
        if (task == null) return head
        val title = TaskTransparency.titleFor(task)
        val step = lastStepName(task)
        val stepBit = if (step != null) {
            " That's where “$title” left it — last step " +
                "“$step”."
        } else {
            ""
        }
        val identityBit = if (SessionIdentity.checked(task)) {
            val signed = SessionIdentity.signedInAs(task)
            if (signed != null) {
                " It's signed in as @$signed."
            } else {
                " It's signed out."
            }
        } else {
            ""
        }
        return head + stepBit + identityBit
    }

    /**
     * The one deterministic answer for a state
     *  question, by kind. STAND and GENERIC ride the
     *  rich status rendering — with the discrepancy
     *  told first when the record carries one, so the
     *  headline can never repeat a false finish as the
     *  whole story.
     */
    fun stateAnswerFor(
        task: JSONObject,
        now: Long,
        kind: StateKind,
        browserUrl: String = "",
        browserTitle: String = ""
    ): String = when (kind) {
        StateKind.COMPLETION -> completionAnswer(task, now)
        StateKind.ACCOUNTABILITY -> accountabilityAnswer(task, now)
        StateKind.BROWSER -> browserAnswer(task, browserUrl, browserTitle)
        StateKind.LOGIN -> loginAnswerFor(task, now)
        StateKind.STAND, StateKind.GENERIC ->
            if (hasDiscrepancy(task) &&
                task.optString("state") != TaskStates.COMPLETED
            ) {
                discrepancyAnswer(task, now)
            } else {
                answerFor(task, now)
            }
    }

    /** The guard's status-denial fallback: the record's
     *  own answer to a question about her work — the
     *  discrepancy when there is one, else the standing. */
    fun recordAnswerFor(task: JSONObject, now: Long): String =
        stateAnswerFor(task, now, StateKind.GENERIC)
}
