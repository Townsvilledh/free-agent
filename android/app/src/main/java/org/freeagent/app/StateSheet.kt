package org.freeagent.app

import org.json.JSONObject

/**
 * THE STATE SHEET (v1.0.8) — the CapabilitySheet's
 * sibling: not what she CAN do, but what she IS doing —
 * composed deterministically from the live stores and
 * riding every chat system prompt, re-composed per
 * turn, never cached.
 *
 * Born of the 2026-11-27 screenshots: her X record said
 * Finished (falsely), and every question about it —
 * "so the x profile is created?", "why did you claim
 * its complete", "so where does the task stand now" —
 * fell to a brain that answered "I can't confirm…",
 * "I can't verify an active task's current status from
 * this chat", and invented a confession about treating
 * a status message as proof. The brain had no access
 * to her records, so it narrated ignorance about her
 * own browser. The probe layer answers the shaped
 * questions deterministically (StatusProbe); this
 * sheet is for everything else: whatever the user asks
 * about her work, the facts are IN her context, stated
 * plainly, including the uncomfortable ones — a finish
 * that doesn't stand is stated as not standing, a
 * browser signed in as the user is stated as his.
 *
 * Facts only, no narration. Pure + org.json; pinned by
 * Lille108Test.
 */
object StateSheet {

    /** Live facts the glue reads at ask time (the
     *  browser's resting page from the session, her
     *  verified handle from the records, the wallet
     *  families that exist). */
    data class Facts(
        val browserUrl: String,
        val browserTitle: String,
        val xVerifiedHandle: String?,
        val walletFamilies: List<String>,
        /** The accounts registry's summary lines
         *  (AccountsRegistry.summaryLines) — the accounts
         *  she holds, whose they are, which is active. */
        val accountsLines: List<String> = emptyList(),
        /** The live session's last-activity marker
         *  (v1.0.9) — the freshness rule's input beside
         *  the records' takeover/handback stamps: an
         *  identity stamp older than the browser's last
         *  activity is rendered as BEING RE-CHECKED,
         *  never stated as the current sign-in. */
        val browserActivityAt: Long = 0L,
        /** The session SENSE line (v1.0.10): the
         *  browser session as continuously sensed, with
         *  its sensed-at time (SessionSense.senseLine) —
         *  the sheet's freshest identity fact, carried
         *  WITH its age like every last-read value. */
        val senseLine: String? = null
    )

    /** How many task lines the sheet carries — the
     *  newest work is the conversation; older records
     *  are a count, not a scroll. */
    private const val MAX_TASKS = 5

    /** A terminal record rides the sheet for a day —
     *  long enough for "did it finish?" to be answered
     *  from fact, short enough to stay current. */
    private const val TERMINAL_WINDOW_MS = 24L * 60 * 60 * 1000

    /** The fact block appended to the chat system
     *  prompt, after the capability sheet. */
    fun render(
        tasks: List<JSONObject>, facts: Facts, now: Long
    ): String {
        val out = StringBuilder()
        out.append(
            "YOUR WORK RIGHT NOW — facts from your own " +
                "records, read fresh for this reply. Answer " +
                "any question about your tasks, your " +
                "browser, your sign-in state or your " +
                "accounts FROM THESE FACTS, never from " +
                "memory or guesswork. You can always see " +
                "your own work: never say you can't tell, " +
                "verify or confirm your own task status, " +
                "browser or login state — the facts are here."
        )
        val activity = SessionIdentity.lastBrowserActivity(
            tasks, facts.browserActivityAt
        )
        val lines = taskLines(tasks, now, activity)
        if (lines.isEmpty()) {
            out.append("\n- No tasks on record right now.")
        } else {
            for (line in lines) out.append("\n").append(line)
        }
        browserLine(tasks, facts, activity)?.let {
            out.append("\n").append(it)
        }
        facts.senseLine?.let {
            out.append("\n- ").append(it)
        }
        out.append("\n").append(xLine(facts))
        if (facts.accountsLines.isNotEmpty()) {
            out.append(
                "\nAccounts she holds (her registry — " +
                    "the browser holds one active account " +
                    "per platform):"
            )
            for (line in facts.accountsLines) {
                out.append("\n- ").append(line)
            }
        }
        if (facts.walletFamilies.isNotEmpty()) {
            out.append(
                "\n- Your agent wallet: " +
                    facts.walletFamilies.joinToString(", ") + "."
            )
        }
        // HER OWN FACTS (v1.0.14): the canonical sources
        // are recorded facts on every state sheet — a
        // verify question about her own product is
        // answered from these, never from an invented
        // address (the battery's verify-a/b failure).
        out.append("\n- ").append(SelfFacts.sheetLine())
        return out.toString()
    }

    /** One line per task: non-terminal first (newest
     *  first), then the day's terminal records, capped.
     *  Each line is the record's own truth: the state
     *  line, the last step by name, the last movement —
     *  plus the two facts a bare state line hides: a
     *  finish that doesn't stand (FinishGate), and the
     *  browser's identity stamp (SessionIdentity). */
    fun taskLines(
        tasks: List<JSONObject>, now: Long, activityAt: Long = 0L
    ): List<String> {
        val visible = tasks.filter { !TaskControl.isArchived(it) }
        val live = visible.filter {
            !TaskStates.isTerminal(it.optString("state"))
        }.sortedByDescending {
            maxOf(it.optLong("createdAt"), it.optLong("updatedAt"))
        }
        val recentDone = visible.filter {
            TaskStates.isTerminal(it.optString("state")) &&
                now - it.optLong("updatedAt", 0L) <= TERMINAL_WINDOW_MS
        }.sortedByDescending { it.optLong("updatedAt", 0L) }
        val picked = (live + recentDone).take(MAX_TASKS)
        val lines = picked.map { task ->
            taskLine(task, activityAt)
        }.toMutableList()
        val hidden = visible.size - picked.size
        if (hidden > 0) {
            lines.add("- …and $hidden more task(s) on record.")
        }
        return lines
    }

    /** One task's fact line. */
    fun taskLine(
        task: JSONObject, activityAt: Long = 0L
    ): String {
        val out = StringBuilder()
        out.append("- Task “")
            .append(TaskTransparency.titleFor(task)).append("”: ")
            .append(StatusProbe.statusLineFor(task))
        val last = TaskTransparency.stepsFor(task).lastOrNull()
        if (last != null) {
            out.append(" Last step: “").append(last.name).append("”.")
        }
        val updatedAt = task.optLong("updatedAt", 0L)
        if (updatedAt > 0L) {
            out.append(" Last movement ")
                .append(TaskTransparency.clockLabel(updatedAt)).append(".")
        }
        // The finish cross-check: a gated record claiming
        // Finished while its criteria fail is stated as
        // NOT done — the sheet never lets the bare state
        // name speak for a false finish.
        if (task.optString("state") == TaskStates.COMPLETED &&
            FinishGate.isGated(task) && !FinishGate.holds(task)
        ) {
            out.append(
                " BUT that finish does not stand: " +
                    FinishGate.discrepancyFor(task) +
                    ". Treat this work as NOT done."
            )
        }
        // A reopened record carries its story: the finish
        // was corrected, and the discrepancy is the fact.
        val discrepancy = task.optString(FinishGate.DISCREPANCY_FIELD)
        if (discrepancy.isNotEmpty() &&
            task.optString("state") != TaskStates.COMPLETED
        ) {
            out.append(
                " It was marked finished earlier; that " +
                    "finish was reopened because $discrepancy."
            )
        }
        // The identity stamp, when this task's lane read
        // it — under the v1.0.9 freshness rule: a stamp
        // older than the browser's last activity is NOT
        // current fact (it is being re-checked), and an
        // unreadable latest read replaces the stamp's
        // claim outright. Neither may be quoted as the
        // sign-in now.
        val unreadable = SessionIdentity.unreadableReason(task)
        if (unreadable != null) {
            out.append(
                " The latest sign-in check couldn't read " +
                    "the page ($unreadable) — a re-check " +
                    "is running; no older sign-in state " +
                    "is current."
            )
        } else if (SessionIdentity.checked(task)) {
            val signed = SessionIdentity.signedInAs(task)
            val at = SessionIdentity.checkedLabel(task)
            if (SessionIdentity.stampStale(task, activityAt)) {
                out.append(
                    " Browser sign-in is being re-checked: " +
                        "the last read said "
                )
                out.append(
                    if (signed != null) {
                        "signed in as @$signed"
                    } else {
                        "signed out"
                    }
                )
                if (at != null) out.append(" (checked $at)")
                out.append(
                    ", but the browser has moved since, " +
                        "so that is not the current state."
                )
            } else if (signed != null) {
                out.append(" Browser signed in as @$signed")
                if (at != null) out.append(" (checked $at)")
                out.append(".")
            } else {
                out.append(" Browser signed out")
                if (at != null) out.append(" (checked $at)")
                out.append(".")
            }
        }
        // The creation lane's phase + checkpoints ride
        // the line (the v1.0.8 phase machine): which
        // phase she is in, what is already stamped done,
        // and the mailbox address the run committed to —
        // so any chat question about the creation is
        // answerable from the record.
        if (LilleXState.isCreationTask(task)) {
            out.append(" ").append(XCreationPhases.summaryLine(task))
        }
        return out.toString()
    }

    /** The browser's resting page + the freshest
     *  identity read across the records, when either is
     *  known. The resting page is session state the glue
     *  reads; the read is record state — under the
     *  v1.0.9 freshness rule the freshest read is the
     *  browser's current identity ONLY when it postdates
     *  the browser's last activity; a stale read renders
     *  as being re-checked, and an unreadable latest
     *  read renders as unknown — neither is stated as
     *  the sign-in. */
    fun browserLine(
        tasks: List<JSONObject>, facts: Facts,
        activityAt: Long = 0L
    ): String? {
        val freshest = tasks
            .filter {
                SessionIdentity.checked(it) ||
                    SessionIdentity.unreadableReason(it) != null
            }
            .maxByOrNull { SessionIdentity.concludedAt(it) }
        val unreadable = freshest?.let {
            SessionIdentity.unreadableReason(it)
        }
        val signed = freshest?.let { SessionIdentity.signedInAs(it) }
        val checkedAt = freshest?.let { SessionIdentity.checkedLabel(it) }
        if (facts.browserUrl.isEmpty() && freshest == null) {
            return null
        }
        val out = StringBuilder("- Her browser")
        if (facts.browserUrl.isNotEmpty()) {
            out.append(" is resting on ").append(facts.browserUrl)
            if (facts.browserTitle.isNotEmpty()) {
                out.append(" — “").append(facts.browserTitle).append("”")
            }
        }
        if (freshest != null) {
            val sep = if (facts.browserUrl.isNotEmpty()) {
                "; "
            } else {
                " "
            }
            when {
                unreadable != null -> out.append(sep).append(
                    "its sign-in couldn't be read at the " +
                        "latest check ($unreadable) — a " +
                        "re-check is running, and no older " +
                        "sign-in state is current"
                )
                SessionIdentity.stampStale(freshest, activityAt) ->
                    out.append(sep).append(
                        "its sign-in is being re-checked — " +
                            "the last read (" +
                            (if (signed != null) {
                                "signed in as @$signed"
                            } else {
                                "signed out"
                            }) +
                            (if (checkedAt != null) {
                                ", checked $checkedAt"
                            } else {
                                ""
                            }) +
                            ") predates the latest browser " +
                            "activity, so it is not current"
                    )
                signed != null -> {
                    out.append(sep).append(
                        if (facts.browserUrl.isNotEmpty()) {
                            "it is signed in as @$signed"
                        } else {
                            "is signed in as @$signed"
                        }
                    )
                    if (checkedAt != null) {
                        out.append(" (checked $checkedAt)")
                    }
                }
                else -> {
                    out.append(sep).append(
                        if (facts.browserUrl.isNotEmpty()) {
                            "it is signed out"
                        } else {
                            "is signed out"
                        }
                    )
                    if (checkedAt != null) {
                        out.append(" (checked $checkedAt)")
                    }
                }
            }
        }
        out.append(".")
        return out.toString()
    }

    /** Her own X account's standing, from the verified
     *  record — the same fact LilleXState derives for
     *  the owner action, stated for the brain. */
    fun xLine(facts: Facts): String =
        if (!facts.xVerifiedHandle.isNullOrEmpty()) {
            "- Your own X account: created — @" +
                facts.xVerifiedHandle +
                ", verified on the record."
        } else {
            "- Your own X account: NOT created — no " +
                "verified account is on record. Never say " +
                "or imply it exists."
        }
}
