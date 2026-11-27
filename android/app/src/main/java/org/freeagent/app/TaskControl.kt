package org.freeagent.app

import org.json.JSONObject

/**
 * TASK CONTROL COMMANDS (v1.0.5) — the deterministic
 * kill and the honest restart.
 *
 * Born of the 2026-11-27 screenshot: Ethuram typed
 * "kill all task" and the message fell through to the
 * BRAIN, which announced "All active tasks have been
 * stopped" — a claim with no machinery behind it. No
 * deterministic kill existed anywhere in the intake, so
 * nothing had actually been stopped; minutes later his
 * "why paused" was answered from that fiction. A command
 * that stops work is far too consequential to be a model
 * utterance: it is a whole-message shape, handled before
 * JobDetect and before the brain, and its reply is
 * composed from what the kill actually did.
 *
 * KILL SEMANTICS (verified against the engine, not
 * assumed): every task — general or packaged (the X
 * lanes carry a "kind" on the SAME store) — lives in the
 * one TaskStore, and every non-terminal state can
 * transition to CANCELLED. Killing stamps the record
 * (killedByUser + the user's own command text + a
 * "killed" event) and moves it to CANCELLED, which is
 * terminal in the state machine: the watchdog only ever
 * touches waiting states, boot recovery only resumable
 * ones, the budget machinery only waiting ones — a
 * killed task cannot auto-resume under any of them. It
 * restarts only when the user explicitly starts it
 * again, and an X-lane restart CREATES a fresh record
 * (XAccountTask.create always mints a new id) — a killed
 * record's identity is never resurrected.
 *
 * RESTART HONESTY (the same screenshot's second half):
 * when the user re-taps a packaged action whose earlier
 * run is still live-but-paused, the run is re-adopted —
 * and that inheritance is now EXPLICIT on the record:
 * [stampRestart] writes restartedAt + resumedAtStep +
 * restartCount + a "restarted" event, so the status
 * surfaces and the reason probe can say "you restarted
 * it — it picked up at step N" instead of the pickup
 * being silent state the user never agreed to see.
 *
 * v1.0.6: the kill widens to TARGETED referents and a
 * deterministic RESUME joins it ("resume" restarts the
 * record's own lane; a parked record reopens the
 * browser panel at the user's step). Both commands
 * parse by STRUCTURE — verb + referent grammar over
 * closed classes with an open hint slot — never by
 * phrase tables (Ethuram: "No hardcoding"); the 1.0.5
 * kill-all set is the grammar's all-referent case.
 *
 * Pure + org.json; pinned by Lille105Test + Lille106Test.
 */
object TaskControl {

    const val KILLED_FIELD = "killedByUser"
    const val KILL_COMMAND_FIELD = "killCommand"
    const val RESTARTED_AT_FIELD = "restartedAt"
    const val RESUMED_AT_STEP_FIELD = "resumedAtStep"
    const val RESTART_COUNT_FIELD = "restartCount"

    /** True when the whole message is a kill-all command
     *  — the [KillCommand.All] case of the structural
     *  grammar in [parseKill] (verb + "all"/"everything"
     *  referent). No phrase table: any wording the grammar
     *  parses as verb + all-referent is the command, and
     *  anything else is not. */
    fun isKillAll(text: String): Boolean =
        parseKill(text) == KillCommand.All

    /** A record a kill-all stops: anything not already
     *  terminal. Packaged tasks are records on the same
     *  store — no lane is exempt, none is special-cased. */
    fun isStoppable(task: JSONObject): Boolean =
        !TaskStates.isTerminal(task.optString("state"))

    /**
     * Stamp a record as killed by the user's own hand:
     * the flag, his command verbatim (the reason probe
     * names it back to him), and the event. The caller
     * performs the state transition to CANCELLED in the
     * same store update and cancels the scheduled work.
     */
    fun stampKill(task: JSONObject, command: String, now: Long) {
        task.put(KILLED_FIELD, true)
        task.put(KILL_COMMAND_FIELD, command.trim())
        EventLog.append(
            task, "killed", now,
            JSONObject().put("command", command.trim())
        )
    }

    fun wasKilled(task: JSONObject): Boolean =
        task.optBoolean(KILLED_FIELD, false)

    /** The user's kill command as recorded, or "" when
     *  the record was not killed by hand. */
    fun killCommand(task: JSONObject): String =
        if (wasKilled(task)) task.optString(KILL_COMMAND_FIELD) else ""

    /** The deterministic reply to a kill-all, composed
     *  from what the kill actually stopped — never a
     *  blanket claim. */
    fun replyFor(stoppedTitles: List<String>): String = when {
        stoppedTitles.isEmpty() ->
            "No work was running — nothing to stop."
        stoppedTitles.size == 1 ->
            "Stopped — " + stoppedTitles[0] + "."
        else -> "All active tasks have been stopped."
    }

    // ---------- targeted kill (v1.0.6) ----------

    /** Record field: the user closed this record away
     *  ("kill this completely"). An archived record does
     *  not resurface — probe answers, the chats-list work
     *  mark and heartbeat surfacing all skip it — whatever
     *  state it died in. */
    const val ARCHIVED_FIELD = "archivedByUser"

    /** What a whole message asks to kill: everything
     *  ([All] — the 1.0.5 shapes), or one task ([Targeted]:
     *  [Targeted.hint] names it — "the X task" — or is
     *  null for "this", resolved by position/recency;
     *  [Targeted.completely] additionally archives the
     *  record so it never resurfaces). */
    sealed class KillCommand {
        object All : KillCommand()
        data class Targeted(
            val hint: String?,
            val completely: Boolean
        ) : KillCommand()
    }

    /** The kill grammar's closed classes. The command is
     *  STRUCTURE, not a phrase table (Ethuram, 2026-11-27:
     *  "No hardcoding"): a verb from [KILL_VERBS], then a
     *  referent built from determiners/demonstratives, the
     *  quantifier "all"/"everything", an open hint slot,
     *  and a work noun from [KILL_NOUNS]; completeness
     *  adverbs may appear anywhere after the verb. Any
     *  wording with that structure is the command —
     *  "kill the lights" is not, because its referent
     *  carries no work noun. A leading/trailing
     *  "please" is stripped before matching (v1.0.11,
     *  audit G4 — he speaks that way; "please kill
     *  this task now" IS the command), but nothing
     *  else may precede the verb clause except the
     *  "not …" exception below. */
    private val KILL_VERBS = setOf("kill", "stop", "cancel")
    private val KILL_NOUNS =
        setOf("task", "tasks", "job", "jobs", "work", "one")
    private val KILL_ADVERBS =
        setOf("completely", "entirely", "fully")
    private val DEMONSTRATIVES = setOf("this", "that", "it")
    private val DEFINITES = setOf("the", "that")

    /**
     * Parse a whole message as a kill command, or null
     * when it is not one. The grammar:
     *
     *   [not <clause>,] <verb> [adverbs] <referent>
     *
     * A leading "not …" clause names what NOT to kill
     * ("not this task, kill this" — the 2026-11-27
     * screenshot); the command is the verb clause that
     * follows it, and the clause never widens the kill.
     * Referents:
     *  - "all" ["the"] [<noun>] / "everything" → [All]
     *    (the 1.0.5 kill-all meaning, now structural).
     *  - <demonstrative> [<noun>] → [Targeted] with no
     *    hint — resolved by position/recency.
     *  - ["the"|"that"] <hint words> <noun> → [Targeted]
     *    named by its hint (≤ 4 words).
     */
    fun parseKill(text: String): KillCommand? {
        val norm = StatusProbe.normalise(text)
            .replace(",", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (norm.isEmpty() || norm.length > 64) return null
        var tokens = StatusProbe.stripPoliteness(norm.split(" "))
        if (tokens.isEmpty()) return null
        val verbIdx = tokens.indexOfFirst { it in KILL_VERBS }
        if (verbIdx < 0) return null
        if (verbIdx > 0) {
            // Only a leading "not …" clause may precede
            // the verb; anything else in front makes the
            // message a sentence ABOUT stopping, not a
            // command to stop.
            if (tokens[0] != "not") return null
            tokens = tokens.drop(verbIdx)
        }
        var completely = false
        var rest = tokens.drop(1)
        if (rest.any { it in KILL_ADVERBS }) {
            completely = true
            rest = rest.filter { it !in KILL_ADVERBS }
        }
        if (rest.isEmpty()) return null
        // The all-referent.
        if (rest == listOf("everything")) return KillCommand.All
        if (rest[0] == "all") {
            val tail = rest.drop(1)
            return if (tail.isEmpty() ||
                (tail.size == 1 && tail[0] in KILL_NOUNS) ||
                (tail.size == 2 && tail[0] == "the" &&
                    tail[1] in KILL_NOUNS)
            ) {
                KillCommand.All
            } else {
                null
            }
        }
        // The demonstrative referent: points at the work
        // on screen, names nothing.
        if (rest[0] in DEMONSTRATIVES) {
            return if (rest.size == 1 ||
                (rest.size == 2 && rest[1] in KILL_NOUNS)
            ) {
                KillCommand.Targeted(null, completely)
            } else {
                null
            }
        }
        // The named referent: an open hint slot closed by
        // a work noun — the noun is what makes it about
        // work at all.
        val body = if (rest[0] in DEFINITES) rest.drop(1) else rest
        if (body.size >= 2 && body.last() in KILL_NOUNS) {
            val hintWords = body.dropLast(1)
            if (hintWords.size <= 4) {
                return KillCommand.Targeted(
                    hintWords.joinToString(" "), completely
                )
            }
        }
        return null
    }

    /** True when [hint]'s every word appears as a whole
     *  word in the task's identity text — its title, its
     *  lane kind, the head of its job text. Whole words
     *  only: "x" matches "Create Lille's X account", not
     *  "execute". */
    private fun matchesHint(task: JSONObject, hint: String): Boolean {
        val hay = TaskTransparency.titleFor(task) + " " +
            task.optString("kind") + " " +
            task.optString("text").take(1000)
        val hayTokens = hay.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
            .toSet()
        val hintTokens = hint.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
        return hintTokens.isNotEmpty() &&
            hintTokens.all { it in hayTokens }
    }

    /**
     * Resolve a targeted kill's referent among [chatId]'s
     * own records (archived ones are already gone from
     * every surface and cannot be killed twice), in the
     * brief's fixed order:
     *
     * (a) a named hint — every word of it must match the
     *     record's identity; a hint that matches NOTHING
     *     resolves to null (never a guess at the nearest
     *     task: killing the wrong work is worse than
     *     asking). Among matches, live work beats finished
     *     work, newest wins within a class.
     * (b) no hint: the task whose living status message
     *     sits last in this chat — the one on screen above
     *     the user's message ([lastRenderedTaskId], read
     *     from the chat store by the caller).
     * (c) failing that, the chat's most recently active
     *     record.
     */
    fun resolveTarget(
        tasks: List<JSONObject>,
        chatId: String,
        hint: String?,
        lastRenderedTaskId: String?
    ): JSONObject? {
        val pool = tasks.filter {
            it.optString(ChatTaskLink.ORIGIN_FIELD) == chatId &&
                !isArchived(it)
        }
        if (pool.isEmpty()) return null
        if (hint != null) {
            val matches = pool.filter { matchesHint(it, hint) }
            if (matches.isEmpty()) return null
            return matches.maxWithOrNull(
                compareBy<JSONObject> {
                    if (isStoppable(it)) 1 else 0
                }.thenBy {
                    maxOf(it.optLong("createdAt"), it.optLong("updatedAt"))
                }
            )
        }
        if (lastRenderedTaskId != null) {
            pool.firstOrNull {
                it.optString("id") == lastRenderedTaskId
            }?.let { return it }
        }
        return pool.maxByOrNull {
            maxOf(it.optLong("createdAt"), it.optLong("updatedAt"))
        }
    }

    fun isArchived(task: JSONObject): Boolean =
        task.optBoolean(ARCHIVED_FIELD, false)

    /** Close a record away ("completely"): the archive
     *  stamp plus its event. The caller performs any state
     *  transition in the same store update. */
    fun stampArchive(task: JSONObject, now: Long) {
        task.put(ARCHIVED_FIELD, true)
        EventLog.append(task, "archived", now, JSONObject())
    }

    /** The confirmation for a targeted kill of live work:
     *  the house stopped line, naming the task. */
    fun replyForTarget(title: String): String =
        "Stopped — $title. You stopped this."

    /** The confirmation when "completely" closes a record
     *  that had already finished: nothing was running to
     *  stop, so the truth is the closure itself. */
    fun replyForClosed(title: String): String =
        "Closed — $title. It won't resurface."

    /** The honest answer when the named task already
     *  finished and no "completely" was said. */
    fun replyForAlreadyFinished(title: String): String =
        "“$title” already finished — there was nothing " +
            "left to stop."

    /** The one line when a targeted kill names nothing
     *  she can identify — deterministic, no brain call,
     *  and nothing was stopped. */
    const val NO_REFERENT_LINE =
        "I couldn't tell which task you mean — nothing " +
            "was stopped. Name the task, or say “kill all " +
            "tasks” to stop everything."

    // ---------- deterministic resume (v1.0.6) ----------

    /** Record field: the user resumed this record by
     *  word ("resume") — the deliberate-resume stamp the
     *  budget machinery already honours (a spent window
     *  entering a run reads as deliberately resumed). */
    const val RESUMED_FIELD = "resumedByUser"

    /** The states a spoken "resume" can act on: paused
     *  or resting work, work parked at the user's own
     *  step, and work already queued (answered, not
     *  re-run). Terminal and running records are not
     *  resume targets. */
    private val RESUMABLE_STATES = setOf(
        TaskStates.PAUSED,
        TaskStates.WAITING_ROUTE,
        TaskStates.WAITING_NETWORK,
        TaskStates.PARKED_AT_WALL,
        TaskStates.QUEUED
    )

    /** A parsed resume command: [hint] names the work
     *  ("resume the x task") or is null for the bare
     *  forms, resolved by position/recency like a
     *  targeted kill. */
    data class ResumeCommand(val hint: String?)

    private val RESUME_VERBS = setOf("resume", "continue")

    /**
     * Parse a whole message as a resume command — the
     * same structural discipline as [parseKill]: a verb
     * ("resume"/"continue", or the verb phrase "go on")
     * leading, then an optional referent in the kill
     * grammar's shapes (demonstrative, or an open hint
     * slot closed by a work noun). "Continue reading
     * this article to me" parses as nothing: its
     * referent carries no work noun. "Resume my
     * download" likewise — a download is not her work.
     */
    fun parseResume(text: String): ResumeCommand? {
        val norm = StatusProbe.normalise(text)
            .replace(",", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (norm.isEmpty() || norm.length > 64) return null
        var tokens = StatusProbe.stripPoliteness(norm.split(" "))
        if (tokens.isEmpty()) return null
        if (tokens.last() == "now") {
            tokens = tokens.dropLast(1)
            if (tokens.isEmpty()) return null
        }
        if (tokens == listOf("go", "on")) return ResumeCommand(null)
        if (tokens[0] !in RESUME_VERBS) return null
        val rest = tokens.drop(1)
        if (rest.isEmpty()) return ResumeCommand(null)
        if (rest[0] in DEMONSTRATIVES) {
            return if (rest.size == 1 ||
                (rest.size == 2 && rest[1] in KILL_NOUNS)
            ) {
                ResumeCommand(null)
            } else {
                null
            }
        }
        val body = if (rest[0] in DEFINITES) rest.drop(1) else rest
        if (body.size >= 2 && body.last() in KILL_NOUNS) {
            val hintWords = body.dropLast(1)
            if (hintWords.size <= 4) {
                return ResumeCommand(hintWords.joinToString(" "))
            }
        }
        return null
    }

    /**
     * Resolve a resume command's referent among
     * [chatId]'s resumable records — the targeted kill's
     * order and honesty: a hint matches or resolves to
     * null (never a guess); no hint falls to the task on
     * screen, then the most recently active. Already-
     * queued work ranks last within a class: resuming it
     * changes nothing, and the reply says so.
     */
    fun resolveResumeTarget(
        tasks: List<JSONObject>,
        chatId: String,
        hint: String?,
        lastRenderedTaskId: String?
    ): JSONObject? {
        val pool = tasks.filter {
            it.optString(ChatTaskLink.ORIGIN_FIELD) == chatId &&
                !isArchived(it) &&
                it.optString("state") in RESUMABLE_STATES
        }
        if (pool.isEmpty()) return null
        val byClassThenRecency =
            compareBy<JSONObject> {
                if (it.optString("state") == TaskStates.QUEUED) 0 else 1
            }.thenBy {
                maxOf(it.optLong("createdAt"), it.optLong("updatedAt"))
            }
        if (hint != null) {
            val matches = pool.filter { matchesHint(it, hint) }
            if (matches.isEmpty()) return null
            return matches.maxWithOrNull(byClassThenRecency)
        }
        if (lastRenderedTaskId != null) {
            pool.firstOrNull {
                it.optString("id") == lastRenderedTaskId
            }?.let { return it }
        }
        return pool.maxWithOrNull(byClassThenRecency)
    }

    /** Stamp a record as resumed by the user's own word,
     *  with his command verbatim and the event. The
     *  caller performs the enqueue/panel action. */
    fun stampResume(task: JSONObject, command: String, now: Long) {
        task.put(RESUMED_FIELD, true)
        EventLog.append(
            task, "resumed", now,
            JSONObject().put("command", command.trim())
        )
    }

    /** The confirmation when a resume restarts paused or
     *  resting work. */
    fun replyForResumed(title: String): String =
        "Resumed — $title. She's picking it back up."

    /** The answer when the work is parked at the user's
     *  own step: resume reopens the browser panel at
     *  that step — it never asks him anything in chat. */
    fun replyForParkedResume(title: String, wallStep: String): String {
        val step = wallStep.trim().ifEmpty {
            "a step that needs you"
        }
        return "“$title” is parked at $step — the browser " +
            "panel is open at that step. Do it there, then " +
            "tap “Done — hand back” and she continues."
    }

    /** The honest answer when the named work is already
     *  queued or under way. */
    fun replyForAlreadyGoing(title: String): String =
        "“$title” is already under way."

    /** The one line when a resume names nothing she can
     *  act on — deterministic, no brain call. */
    const val NO_RESUMABLE_LINE =
        "There's no paused work in this chat to resume."

    /**
     * Stamp a re-adopted run as restarted by the user's
     * own tap: when it restarted, and the step the
     * earlier run had reached — the inheritance, on the
     * record, where the probe can read it back to him.
     */
    fun stampRestart(task: JSONObject, now: Long) {
        val steps = task.optInt("steps", 0)
        task.put(RESTARTED_AT_FIELD, now)
        task.put(RESUMED_AT_STEP_FIELD, steps)
        task.put(RESTART_COUNT_FIELD, task.optInt(RESTART_COUNT_FIELD, 0) + 1)
        EventLog.append(
            task, "restarted", now,
            JSONObject().put("resumedAtStep", steps)
        )
    }

    /** The step a restarted run picked up at, or null
     *  when the record was never restarted by hand. */
    fun resumedAtStep(task: JSONObject): Int? =
        if (task.has(RESUMED_AT_STEP_FIELD)) {
            task.optInt(RESUMED_AT_STEP_FIELD, 0)
        } else {
            null
        }
}
