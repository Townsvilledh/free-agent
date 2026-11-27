package org.freeagent.app

import org.json.JSONObject

/**
 * FINISH GATES FOR PACKAGED LANES (v1.0.8, born of the
 * 2026-11-27 false finish): the X creation record read
 * "Finished — Create Lille's X account. Last step:
 * Finished — Done." while its own step list showed the
 * run had never left the mailbox phase (emailnator →
 * guerrillamail, browser resting on guerrillamail.com).
 * The generic verify-state probe had passed because the
 * browser rested on an ALLOWED page — allowed, but not
 * the goal. A sub-goal (a mailbox acquired, a form
 * filled) had been allowed to pose as the finish.
 *
 * So a packaged lane with definable criteria may reach
 * Finished ONLY when those criteria evaluate true FROM
 * THE RECORD'S EVIDENCE — for her X creation:
 *
 *  1. a live X session as HER handle — the xHandle the
 *     runner's x_session_status read off the live page,
 *     attributable to her (see [sessionHandle]);
 *  2. her profile page seen loading as hers — the
 *     runner's recorded observation of the browser
 *     resting on x.com/<her handle>, or a first post
 *     verified on that profile.
 *
 * The driver (TaskRunner) evaluates the gate at the
 * finish call: unmet criteria refuse the finish, the
 * record goes to WAITING with the unmet criterion named
 * in work terms, and the run continues toward them. The
 * audit (MainActivity, on open and on render) reopens a
 * record an older build falsely finished: [shouldReopen]
 * + [stampReopen].
 *
 * The connect lanes are deliberately NOT gated, and the
 * reason is structural: their finishes cannot lie. A
 * connect record that completes without a verified
 * session is graded NOT CONNECTED by its own report
 * (XConnectTask.reportFor) — the word "Finished" there
 * never claims a connection. Creation's surfaces read
 * plain "Finished", so creation is where a false finish
 * can stand; it is the lane the gate governs. (The
 * identity stamps and the probe cross-checks still
 * cover the connect lanes' truth.)
 *
 * Pure + org.json; pinned by Lille108Test.
 */
object FinishGate {

    /** The record's finish-discrepancy, in work terms —
     *  stamped by the gate (a refused finish) and by the
     *  audit (a reopened one), read by every surface that
     *  talks about the record (status card, probes, the
     *  state sheet) so the story is the same everywhere.
     *  Cleared when a finish finally holds. */
    const val DISCREPANCY_FIELD = "finishDiscrepancy"

    /** The runner's recorded observation of her profile
     *  page loading as hers: the URL the browser rested
     *  on (x.com/<handle>). */
    const val PROFILE_EVIDENCE_FIELD = "profileEvidence"

    /** Her handle as recorded by vault_record_account at
     *  creation — the attribution anchor for session
     *  evidence: a session read as any OTHER handle is
     *  someone else's, never hers. */
    const val HER_HANDLE_FIELD = "herHandle"

    /** When the record's xHandle evidence was read. */
    const val X_HANDLE_AT_FIELD = "xHandleAt"

    data class Criterion(val name: String, val met: Boolean)

    /** The lanes the gate governs: her X creation, on a
     *  build that can actually run it (a hollow-build
     *  record is honestly FAILED at creation and never
     *  reaches a finish call). */
    fun isGated(task: JSONObject): Boolean =
        LilleXState.isCreationTask(task) &&
            !XAccountTask.isUnavailableRecord(task)

    /**
     * Her session handle AS EVIDENCE, or null when the
     * record's session evidence does not hold: no handle
     * recorded; a recorded handle contradicted by the
     * vault-recorded herHandle (the session was someone
     * else's); or a LATER identity stamp naming a
     * different handle than the session evidence (the
     * browser has since been seen signed in as another
     * account — the earlier verification no longer
     * describes her browser).
     */
    fun sessionHandle(task: JSONObject): String? {
        val handle = task.optString("xHandle").trim()
        if (handle.isEmpty()) return null
        val her = task.optString(HER_HANDLE_FIELD).trim()
        if (her.isNotEmpty() && !her.equals(handle, true)) {
            return null
        }
        val signed = task.optString(SessionIdentity.AS_FIELD).trim()
        if (signed.isNotEmpty() && !signed.equals(handle, true)) {
            val signedAt = task.optLong(SessionIdentity.AT_FIELD, 0L)
            val handleAt = task.optLong(X_HANDLE_AT_FIELD, 0L)
            if (signedAt >= handleAt) return null
        }
        return handle
    }

    /** The profile criterion: her profile page seen
     *  loading as hers (the runner's observation), or a
     *  first post verified on that profile — the post
     *  verification opens the profile page itself, so it
     *  is the same evidence one step further. */
    fun profileMet(task: JSONObject): Boolean =
        task.optString(PROFILE_EVIDENCE_FIELD).isNotEmpty() ||
            task.optString("firstPostUrl").isNotEmpty()

    /** The gate's criteria for [task], or null when the
     *  lane carries no gate. */
    fun criteriaFor(task: JSONObject): List<Criterion>? {
        if (!isGated(task)) return null
        return listOf(
            Criterion(
                "a live X session as her handle",
                sessionHandle(task) != null
            ),
            Criterion(
                "her profile page loading as hers",
                profileMet(task)
            )
        )
    }

    /** The criteria [task] has NOT met (empty for an
     *  ungated lane — nothing is demanded of it here). */
    fun unmet(task: JSONObject): List<Criterion> =
        criteriaFor(task)?.filter { !it.met } ?: emptyList()

    /** True when the lane's finish may stand: ungated,
     *  or every criterion met from the record. */
    fun holds(task: JSONObject): Boolean = unmet(task).isEmpty()

    /** True when [url] is her profile page for [handle]:
     *  x.com/<handle> exactly (the path's first segment),
     *  case-insensitive on the handle. */
    fun isProfileUrl(url: String, handle: String): Boolean {
        if (url.isEmpty() || handle.isEmpty()) return false
        if (BrowserPerception.domainOf(url) != "x.com") return false
        val afterScheme = url.substringAfter("://", "")
        val path = afterScheme.substringAfter("/", "")
        val first = path.substringBefore("/")
            .substringBefore("?").substringBefore("#")
        return first.equals(handle, ignoreCase = true)
    }

    // ---------- wording (work terms, from the record) ----------

    /** The unmet criteria as one work-terms clause:
     *  "no X session has been verified as hers" /
     *  "her profile page has not been seen loading as
     *  hers" / both joined. Empty when nothing is unmet. */
    fun discrepancyFor(task: JSONObject): String {
        val missing = unmet(task)
        if (missing.isEmpty()) return ""
        val parts = missing.map { c ->
            when (c.name) {
                "a live X session as her handle" ->
                    "no X session has been verified as hers"
                "her profile page loading as hers" ->
                    "her profile page has not been seen " +
                        "loading as hers"
                else -> "the “${c.name}” check has not been met"
            }
        }
        return parts.joinToString(" and ")
    }

    /** The cross-check sentence for a record CLAIMING
     *  Finished while its criteria fail — the probe and
     *  state-sheet wording (the brief's shape): the
     *  record says Finished, the evidence says otherwise,
     *  so the finish doesn't stand. */
    fun falseFinishLine(task: JSONObject): String {
        val last = TaskTransparency.stepsFor(task).lastOrNull()
        val stepBit = if (last != null) {
            " Its steps only got as far as “${last.name}”."
        } else {
            ""
        }
        return "The record says Finished, but that finish " +
            "doesn't stand: ${discrepancyFor(task)}.$stepBit"
    }

    /** The record note for a finish the gate refused:
     *  the work continues, and the note names what is
     *  missing in work terms. */
    fun waitNote(task: JSONObject): String =
        "Not finished — ${discrepancyFor(task)}. The work " +
            "continues until it checks out."

    /** The model-facing refusal at a gated finish call:
     *  the criteria, what is missing, and the rule —
     *  sub-goals are steps, never the finish. */
    fun refusalNote(task: JSONObject): String {
        val missing = unmet(task).joinToString("; ") { it.name }
        return "finish refused by the finish gate: your X " +
            "account does not count as created until " +
            "x_session_status verifies a live session as " +
            "your handle AND your profile page " +
            "(x.com/<your handle>) loads as yours. Still " +
            "missing: $missing. A mailbox, a filled form " +
            "and a resting page are steps, not the finish " +
            "— keep working."
    }

    // ---------- the audit (self-healing) ----------

    /** A record an older build falsely finished: in
     *  COMPLETED, gated, criteria unmet. It must not sit
     *  Finished — the audit reopens it. */
    fun shouldReopen(task: JSONObject): Boolean =
        task.optString("state") == TaskStates.COMPLETED &&
            isGated(task) && unmet(task).isNotEmpty()

    /** The reopened record's note: what was claimed, why
     *  it doesn't stand, and what happens now — in work
     *  terms, from the record. */
    fun reopenNote(task: JSONObject): String =
        "This was marked finished, but ${discrepancyFor(task)} " +
            "— the account doesn't count as created, so the " +
            "work is open again and she continues from where " +
            "the steps stopped."

    /**
     * Stamp the reopen onto [task] (the caller performs
     * the state transition to WAITING_ROUTE — the one
     * sanctioned exit from COMPLETED, added to the state
     * machine for exactly this): the discrepancy is
     * recorded, the false summary is retired into the
     * audit event (evidence kept, claim withdrawn), and
     * the stale finish stamps — the chat-report dedup,
     * the partial label, the failed-check list, the
     * verify stamps — are cleared so no later surface
     * echoes the false finish as fact. Steps and message
     * history stay: the work resumes from them.
     */
    fun stampReopen(task: JSONObject, now: Long) {
        val oldSummary = task.optString("summary")
        task.put(DISCREPANCY_FIELD, discrepancyFor(task))
        task.put("note", reopenNote(task))
        task.put("summary", "")
        task.remove(ChatTaskLink.REPORTED_FIELD)
        task.remove("partialCompletion")
        task.remove(FinalChecks.FAILED_FIELD)
        task.remove(FinishVerifier.VERIFIED_FIELD)
        task.remove(FinishVerifier.VERIFY_DETAIL_FIELD)
        task.remove(TaskPlanning.BOUNCES_FIELD)
        EventLog.append(
            task, "finish_audit", now,
            JSONObject()
                .put("claimedSummary", oldSummary)
                .put("discrepancy", discrepancyFor(task))
        )
    }
}
