package org.freeagent.app

import org.json.JSONObject

/**
 * THE X CREATION PHASE MACHINE (v1.0.8, Ethuram
 * 2026-11-27: "she keeps repeating the same email
 * circles but never ends up finishing the x profile
 * creation").
 *
 * The lane used to free-drive: a long task text, a
 * brain, and no stamped progress. Every resume
 * re-wandered — the mailbox shopping (emailnator →
 * guerrillamail → maildrop → …) restarted instead of
 * the run advancing, because nothing on the record said
 * a phase was DONE. 37 steps of circles, and the run
 * stood exactly where it started.
 *
 * Now the lane is a scripted phase machine:
 *
 *   MAILBOX → SIGNUP_FORM → EMAIL_VERIFY →
 *   PASSWORD_PROFILE → VERIFY_ACCOUNT (the finish gate)
 *
 * with HUMAN_CHECK as a conditional park over any of
 * them (the existing wall machinery — a park is a
 * pause, never a phase reset). Each phase has a script
 * (entry actions + exit verification, below), and its
 * completion is a CHECKPOINT stamped on the record
 * with its evidence and time. The current phase is
 * DERIVED from the checkpoints — a completed phase is
 * never re-entered within the run, and a resume starts
 * at the first incomplete phase, by construction. The
 * brain may recover WITHIN a phase; it may not reorder
 * or repeat phases (the runner refuses a completed
 * phase's ground and the loop breaker parks circling).
 *
 * MAILBOX COMMITMENT: the lane acquires ONE address,
 * stamps it, and uses it for the whole run. A switch
 * is allowed only on recorded evidence — X refused the
 * address (knowable only after a submit), or the
 * verification code never arrived within the phase's
 * poll budget (the runner counts the polls itself) —
 * at most ONE switch per run, the reason on the record.
 *
 * Pure over the record; the runner performs the reads
 * and tool calls that produce the evidence. Pinned by
 * Lille108Test.
 */
object XCreationPhases {

    enum class Phase {
        MAILBOX, SIGNUP_FORM, EMAIL_VERIFY,
        PASSWORD_PROFILE, VERIFY_ACCOUNT
    }

    /** The checkpoints object on the record: phase name
     *  → {at, evidence}. Presence IS completion. */
    const val CHECKPOINTS_FIELD = "creationPhases"

    /** The phase the run last posted its script for —
     *  the dedup stamp for phase instructions (a phase's
     *  script is posted once, when the run enters it). */
    const val NOTE_FIELD = "creationPhaseNoted"

    /** The committed mailbox address (hers — a public
     *  disposable address, not a secret) and its
     *  provider domain. */
    const val ADDRESS_FIELD = "mailboxAddress"
    const val PROVIDER_FIELD = "mailboxProvider"

    /** Mailbox switches used this run (max [MAX_SWITCHES]),
     *  the reason for the last one, and the address it
     *  replaced. */
    const val SWITCHES_FIELD = "mailboxSwitches"
    const val SWITCH_REASON_FIELD = "mailboxSwitchReason"
    const val PREVIOUS_FIELD = "mailboxPrevious"

    /** The runner's own count of mailbox polls during
     *  EMAIL_VERIFY, and the stamp that the poll budget
     *  ran out — the recorded evidence a code-never-
     *  arrived switch stands on. */
    const val POLLS_FIELD = "emailPollCount"
    const val POLLS_EXHAUSTED_FIELD = "codePollExhausted"

    const val MAX_SWITCHES = 1
    const val POLL_BUDGET = 6

    /** Switch reasons — recorded evidence kinds, named
     *  on the record when a switch is taken. */
    const val REASON_X_REFUSED = "x_refused"
    const val REASON_CODE_NEVER_ARRIVED = "code_never_arrived"

    // ---------- checkpoints ----------

    fun checkpoints(task: JSONObject): JSONObject =
        task.optJSONObject(CHECKPOINTS_FIELD) ?: JSONObject()

    fun isComplete(task: JSONObject, phase: Phase): Boolean =
        checkpoints(task).has(phase.name)

    fun evidenceFor(task: JSONObject, phase: Phase): String =
        checkpoints(task).optJSONObject(phase.name)
            ?.optString("evidence") ?: ""

    /** Stamp a phase complete, with the evidence that
     *  earned it. Idempotent: the first stamp stands —
     *  a completed phase's evidence is never rewritten. */
    fun stamp(
        task: JSONObject, phase: Phase, evidence: String, now: Long
    ) {
        val all = checkpoints(task)
        if (all.has(phase.name)) return
        all.put(
            phase.name,
            JSONObject().put("at", now).put("evidence", evidence)
        )
        task.put(CHECKPOINTS_FIELD, all)
        task.put("creationPhase", phase.name)
    }

    fun completedPhases(task: JSONObject): List<Phase> =
        Phase.values().filter { isComplete(task, it) }

    /** The first incomplete phase — where every run and
     *  every resume starts. Derived, never stored, so it
     *  can never disagree with the checkpoints. */
    fun currentPhase(task: JSONObject): Phase =
        Phase.values().firstOrNull { !isComplete(task, it) }
            ?: Phase.VERIFY_ACCOUNT

    // ---------- the mailbox commitment ----------

    fun committedAddress(task: JSONObject): String =
        task.optString(ADDRESS_FIELD).trim()

    fun switchesUsed(task: JSONObject): Int =
        task.optInt(SWITCHES_FIELD, 0)

    /** Commit the run's address (the runner calls this
     *  on the claim tool, or on first sight of the
     *  address typed into the signup form): the address,
     *  its provider, and the MAILBOX checkpoint, in one
     *  stamp. A second commit is refused — the address
     *  changes only through [applySwitch]. */
    fun commitAddress(
        task: JSONObject, address: String, now: Long
    ): Boolean {
        val addr = address.trim()
        if (!isEmailShaped(addr)) return false
        if (committedAddress(task).isNotEmpty()) return false
        task.put(ADDRESS_FIELD, addr)
        task.put(
            PROVIDER_FIELD,
            addr.substringAfter("@").lowercase()
        )
        stamp(task, Phase.MAILBOX, "mailbox address committed: $addr", now)
        return true
    }

    /** An email-shaped string: one @, a dotted domain,
     *  no spaces. The shape the signup form demands —
     *  and the shape that distinguishes an address from
     *  the short NAME a mailbox's name-box takes. */
    fun isEmailShaped(text: String): Boolean {
        val t = text.trim()
        if (t.contains(" ") || t.count { it == '@' } != 1) {
            return false
        }
        val domain = t.substringAfter("@")
        return t.substringBefore("@").isNotEmpty() &&
            domain.contains(".") && domain.length >= 4
    }

    /**
     * Whether a mailbox switch may be taken, and why
     * not when it may not — the evidence discipline:
     * one switch per run; [REASON_CODE_NEVER_ARRIVED]
     * stands only on the runner's own poll-exhaustion
     * stamp; [REASON_X_REFUSED] is knowable only once
     * the form has been submitted (SIGNUP_FORM complete
     * or beyond). Anything else is shopping, and the
     * lane does not shop.
     */
    fun switchVerdict(task: JSONObject, reason: String): String? {
        if (switchesUsed(task) >= MAX_SWITCHES) {
            return "the one mailbox switch for this run " +
                "is already used — the committed address stands"
        }
        if (committedAddress(task).isEmpty()) {
            return "no address is committed yet — there " +
                "is nothing to switch from; claim one first"
        }
        return when (reason) {
            REASON_CODE_NEVER_ARRIVED ->
                if (task.optBoolean(POLLS_EXHAUSTED_FIELD)) {
                    null
                } else {
                    "the code poll budget has not run out " +
                        "yet — keep polling the committed " +
                        "inbox; a switch needs that evidence"
                }
            REASON_X_REFUSED ->
                if (isComplete(task, Phase.SIGNUP_FORM)) {
                    null
                } else {
                    "X has not seen the address yet — a " +
                        "refusal can only be recorded after " +
                        "the form is submitted"
                }
            else ->
                "unknown switch reason “$reason” — the " +
                    "recorded kinds are $REASON_X_REFUSED " +
                    "and $REASON_CODE_NEVER_ARRIVED"
        }
    }

    /** Take the switch: the old address and the reason
     *  go on the record, the commitment clears, and the
     *  MAILBOX checkpoint reopens — the ONE sanctioned
     *  re-entry of a completed phase, bought with
     *  recorded evidence. The poll evidence resets with
     *  the new address. */
    fun applySwitch(task: JSONObject, reason: String, now: Long) {
        task.put(PREVIOUS_FIELD, committedAddress(task))
        task.put(SWITCH_REASON_FIELD, reason)
        task.put(SWITCHES_FIELD, switchesUsed(task) + 1)
        task.put(ADDRESS_FIELD, "")
        task.put(PROVIDER_FIELD, "")
        task.put(POLLS_FIELD, 0)
        task.remove(POLLS_EXHAUSTED_FIELD)
        val all = checkpoints(task)
        all.remove(Phase.MAILBOX.name)
        task.put(CHECKPOINTS_FIELD, all)
        EventLog.append(
            task, "mailbox_switch", now,
            JSONObject().put("reason", reason)
                .put("previous", task.optString(PREVIOUS_FIELD))
        )
    }

    // ---------- the scripts ----------

    /** The phase's script, posted into the run when the
     *  phase is entered: the entry state, the scripted
     *  actions, the exit verification. The brain works
     *  inside it; the runner stamps the exit. */
    fun scriptFor(task: JSONObject, phase: Phase): String {
        val done = completedPhases(task)
        val doneBit = if (done.isEmpty()) {
            "No phase is complete yet."
        } else {
            "Completed phases (never repeated): " +
                done.joinToString(", ") { it.name } + "."
        }
        val head = "PHASE MACHINE — you are in phase " +
            "${phase.name}. $doneBit Work only within " +
            "this phase; its exit is stamped by evidence, " +
            "not by moving on."
        return head + "\n" + when (phase) {
            Phase.MAILBOX ->
                "Goal: ONE email address for the whole " +
                    "run. Open the FIRST mailbox provider " +
                    "in your EMAIL ladder; take the address " +
                    "its page gives you (a name-box takes " +
                    "ONLY a short name — the address is " +
                    "that name at the provider's domain). " +
                    "The moment you have the address, call " +
                    "mailbox_claim{address} — that commits " +
                    "it and completes this phase. Do not " +
                    "shop providers: a provider changes " +
                    "only on recorded evidence, later, " +
                    "through mailbox_switch."
            Phase.SIGNUP_FORM ->
                "Goal: the X signup form submitted once, " +
                    "with the committed address (" +
                    committedAddress(task) + ") and your " +
                    "vault password. Open the signup, fill " +
                    "ALL fields on the same page instance " +
                    "(email = the committed address; " +
                    "password via browser_type_secret), " +
                    "then ONE patient submit and wait. " +
                    "Never click the Terms/Privacy/Cookie " +
                    "links under the fields. Exit is " +
                    "stamped when the submitted form is " +
                    "observed."
            Phase.EMAIL_VERIFY ->
                "Goal: the verification code X sent to " +
                    committedAddress(task) + " entered on " +
                    "X. Poll the committed inbox (browser_" +
                    "open its page, wait, read again — the " +
                    "runner counts your polls; the budget " +
                    "is $POLL_BUDGET). When the code " +
                    "arrives, browser_open the X flow and " +
                    "type the code there — never into the " +
                    "mailbox page. Exit is stamped when " +
                    "the code is entered on X. If the " +
                    "budget runs out with no code, you may " +
                    "call mailbox_switch{reason: " +
                    "$REASON_CODE_NEVER_ARRIVED} ONCE."
            Phase.PASSWORD_PROFILE ->
                "Goal: the account itself — password set " +
                    "(via browser_type_secret from your " +
                    "vault) and the profile carried: " +
                    "display name, bio, avatar via Edit " +
                    "profile. Then call x_session_status: " +
                    "exit is stamped when it verifies a " +
                    "live session as your handle. Record " +
                    "the account with vault_record_account " +
                    "as soon as the handle is final."
            Phase.VERIFY_ACCOUNT ->
                "Goal: prove the account, then finish. " +
                    "Call x_session_status, open your " +
                    "profile page (x.com/<your handle>) so " +
                    "it is seen loading as yours, read " +
                    "your feed (x_read_feed), search " +
                    "(x_search), and post your first post " +
                    "(x_post). finish is accepted only " +
                    "when the finish gate's criteria hold " +
                    "— a live session as your handle and " +
                    "your profile page seen as yours."
        }
    }

    /** The one-line phase summary the state sheet and
     *  the status surfaces carry: current phase, the
     *  checkpoints earned, the committed address. */
    fun summaryLine(task: JSONObject): String {
        val phase = currentPhase(task)
        // A finish that HOLDS is VERIFY_ACCOUNT's
        // completion — the gate is its exit verification,
        // so no separate checkpoint is stamped for it.
        val verifiedFinish =
            task.optString("state") == TaskStates.COMPLETED &&
                FinishGate.holds(task)
        val parts = Phase.values().map { p ->
            when {
                isComplete(task, p) ||
                    (p == Phase.VERIFY_ACCOUNT && verifiedFinish) -> {
                    val label = when (p) {
                        Phase.MAILBOX ->
                            "mailbox ✓ (${committedAddress(task)})"
                        Phase.SIGNUP_FORM -> "signup form ✓"
                        Phase.EMAIL_VERIFY -> "email verified ✓"
                        Phase.PASSWORD_PROFILE -> "account live ✓"
                        Phase.VERIFY_ACCOUNT -> "verified ✓"
                    }
                    label
                }
                p == phase -> when (p) {
                    Phase.MAILBOX -> "mailbox ● (no address committed yet)"
                    Phase.SIGNUP_FORM -> "signup form ●"
                    Phase.EMAIL_VERIFY -> "email verify ●"
                    Phase.PASSWORD_PROFILE -> "password & profile ●"
                    Phase.VERIFY_ACCOUNT -> "verify account ●"
                }
                else -> when (p) {
                    Phase.MAILBOX -> "mailbox ○"
                    Phase.SIGNUP_FORM -> "signup form ○"
                    Phase.EMAIL_VERIFY -> "email verify ○"
                    Phase.PASSWORD_PROFILE -> "password & profile ○"
                    Phase.VERIFY_ACCOUNT -> "verify account ○"
                }
            }
        }
        val switchBit = if (switchesUsed(task) > 0) {
            " Mailbox switched once (" +
                task.optString(SWITCH_REASON_FIELD) +
                "); previous: " +
                task.optString(PREVIOUS_FIELD) + "."
        } else {
            ""
        }
        return "Creation phase: ${phase.name} — " +
            parts.joinToString(" · ") + "." + switchBit
    }
}
