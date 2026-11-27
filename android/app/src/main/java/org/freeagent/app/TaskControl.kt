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
 * Pure + org.json; pinned by Lille105Test.
 */
object TaskControl {

    const val KILLED_FIELD = "killedByUser"
    const val KILL_COMMAND_FIELD = "killCommand"
    const val RESTARTED_AT_FIELD = "restartedAt"
    const val RESUMED_AT_STEP_FIELD = "resumedAtStep"
    const val RESTART_COUNT_FIELD = "restartCount"

    /** Whole-message kill-all shapes (after the shared
     *  normalisation: lowercase, trimmed, trailing
     *  punctuation stripped). Deliberately exact — a
     *  sentence that merely mentions stopping work
     *  ("kill the lights", "stop the download after this
     *  one finishes") is conversation, not a command. */
    private val KILL_ALL_SHAPES = setOf(
        "kill all task", "kill all tasks",
        "stop all task", "stop all tasks",
        "cancel all task", "cancel all tasks",
        "kill all the tasks", "stop all the tasks",
        "cancel all the tasks",
        "kill all", "stop all", "cancel all",
        "kill everything", "stop everything",
        "cancel everything",
        "kill all work", "stop all work", "cancel all work"
    )

    /** True when the whole message is a kill-all command. */
    fun isKillAll(text: String): Boolean {
        val norm = StatusProbe.normalise(text)
        if (norm.isEmpty() || norm.length > 40) return false
        return norm in KILL_ALL_SHAPES
    }

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
