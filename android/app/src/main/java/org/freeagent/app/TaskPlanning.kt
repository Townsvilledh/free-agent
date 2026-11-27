package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * PLANNING INTERVAL + FINAL-ANSWER CHECKS + VERIFY-STATE
 * (v1.0.4) — steals #8 (smolagents) and #2 (DroidRun),
 * reimplemented as patterns for Lille's runner.
 *
 * PLANNING INTERVAL: every N steps (10 in a browser run,
 * 8 in a note run — aligned with the step budgets), the
 * run must stop acting and RECORD a plan note: the facts
 * so far and the remaining sub-goals, each sub-goal
 * carried as open / done / dropped. The note is a tool
 * (plan_note) so it lands in the same protocol; the
 * runner requests it by appending the request to a tool
 * result, and merges the answer into the record's plan
 * state — which the transparency view renders and the
 * final checks read. A plan that lives only in the
 * model's head is not a plan.
 *
 * VERIFY-STATE, NEVER TRUST DONE: before a finish is
 * accepted, the runner RE-READS the end state the job
 * claims — the note it says it wrote, the page it says
 * it reached, the calendar event it says it created —
 * and the report's success stands on that re-read. The
 * model's finish summary is a claim; the probe is the
 * evidence. A mismatch is named in work terms and
 * downgrades the finish honestly.
 *
 * FINAL-ANSWER CHECKS: deterministic validators over the
 * record at finish — every recorded sub-goal ticked or
 * honestly dropped; the state verified (above); a
 * partial job's summary naming what was not done here.
 * A failed check BOUNCES the finish back to work once,
 * with the failing check recorded; a second failure
 * finishes honestly with the check named in the report.
 *
 * Pure + org.json; pinned by TaskPlanningTest.
 */
object TaskPlanning {

    const val PLAN_STATE_FIELD = "planState"
    const val PLAN_NOTES_FIELD = "planNotes"
    const val BOUNCES_FIELD = "finalCheckBounces"

    const val BROWSER_INTERVAL = 10
    const val NOTE_INTERVAL = 8

    /** The instruction appended to a tool result when a
     *  planning checkpoint falls due. */
    const val PLAN_REQUEST =
        " PLANNING CHECKPOINT: before your next action, reply with " +
            "one fenced JSON block {\"tool\": \"plan_note\", \"args\": " +
            "{\"facts\": \"what is now known/done\", \"subgoals\": " +
            "[\"remaining sub-goal\", ...], \"done\": [\"sub-goal now " +
            "complete\", ...], \"dropped\": [\"sub-goal you are " +
            "honestly dropping, with the reason inside the string\", " +
            "...]}}. Then continue."

    /** A planning checkpoint is due when the window's step
     *  count crosses a multiple of the interval. */
    fun planDue(stepsInWindow: Int, browserActive: Boolean): Boolean {
        val interval = if (browserActive) BROWSER_INTERVAL else NOTE_INTERVAL
        return stepsInWindow > 0 && stepsInWindow % interval == 0
    }

    data class Subgoal(val text: String, val status: String) {
        companion object {
            const val OPEN = "open"
            const val DONE = "done"
            const val DROPPED = "dropped"
        }
    }

    /** The plan state recorded on the task: sub-goals with
     *  their standing. Empty when no plan note exists yet. */
    fun subgoals(task: JSONObject): List<Subgoal> {
        val arr = task.optJSONObject(PLAN_STATE_FIELD)
            ?.optJSONArray("subgoals") ?: return emptyList()
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Subgoal(o.optString("text"), o.optString("status", Subgoal.OPEN))
        }
    }

    /**
     * Merge one plan_note call into the record: sub-goals
     * named in [args.subgoals] are added as OPEN (or kept),
     * those in [args.done] / [args.dropped] change state
     * (matched by case-insensitive text), and the note
     * itself is appended to the plan-notes list (bounded).
     * Returns the observation the model hears.
     */
    fun applyPlanNote(task: JSONObject, args: JSONObject, step: Int): String {
        val current = subgoals(task).toMutableList()
        fun find(text: String): Int =
            current.indexOfFirst { it.text.equals(text.trim(), ignoreCase = true) }
        fun strings(key: String): List<String> {
            val a = args.optJSONArray(key) ?: return emptyList()
            return List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
        }
        for (text in strings("subgoals")) {
            if (find(text) < 0) current.add(Subgoal(text.trim(), Subgoal.OPEN))
        }
        for (text in strings("done")) {
            val i = find(text)
            if (i >= 0) current[i] = current[i].copy(status = Subgoal.DONE)
            else current.add(Subgoal(text.trim(), Subgoal.DONE))
        }
        for (text in strings("dropped")) {
            val i = find(text)
            if (i >= 0) current[i] = current[i].copy(status = Subgoal.DROPPED)
            else current.add(Subgoal(text.trim(), Subgoal.DROPPED))
        }
        val state = JSONObject().put("subgoals", JSONArray().apply {
            current.forEach {
                put(JSONObject().put("text", it.text).put("status", it.status))
            }
        })
        task.put(PLAN_STATE_FIELD, state)
        val notes = task.optJSONArray(PLAN_NOTES_FIELD) ?: JSONArray()
        notes.put(
            JSONObject()
                .put("step", step)
                .put("facts", args.optString("facts").take(600))
                .put("open", current.count { it.status == Subgoal.OPEN })
        )
        while (notes.length() > 12) notes.remove(0)
        task.put(PLAN_NOTES_FIELD, notes)
        val open = current.count { it.status == Subgoal.OPEN }
        return "PLAN_RECORDED subgoals=${current.size} open=$open — " +
            "the plan is on the record; continue with the next step."
    }

    /** The plan block the transparency view renders: the
     *  recorded sub-goals with their standing marks. Null
     *  when no plan exists — nothing is invented. */
    fun planBlock(task: JSONObject): String? {
        val goals = subgoals(task)
        if (goals.isEmpty()) return null
        val out = StringBuilder("Plan so far:")
        for (g in goals) {
            val mark = when (g.status) {
                Subgoal.DONE -> "✓"
                Subgoal.DROPPED -> "—"
                else -> "●"
            }
            out.append("\n").append(mark).append(" ").append(g.text)
        }
        return out.toString()
    }
}

/**
 * VERIFY-STATE (steal #2): the probe selection + verdicts.
 * The runner performs the actual re-read (it owns the
 * browser session, the note files, the gateways); this
 * object decides WHICH probe a finish owes and grades
 * what the re-read found.
 */
object FinishVerifier {

    enum class ProbeKind { NOTE, BROWSER, CALENDAR, NONE }

    data class Result(
        val kind: ProbeKind,
        val ran: Boolean,
        val ok: Boolean,
        val detail: String
    )

    const val VERIFIED_FIELD = "stateVerified"
    const val VERIFY_DETAIL_FIELD = "verifyDetail"

    /** Which probe a finish owes, from the record: a
     *  created calendar event outranks a written note,
     *  which outranks the browser's resting page. */
    fun probeFor(task: JSONObject, browserActive: Boolean): ProbeKind =
        when {
            task.has("calendarCreatedTitle") -> ProbeKind.CALENDAR
            task.optString("lastNotePath").isNotEmpty() -> ProbeKind.NOTE
            browserActive -> ProbeKind.BROWSER
            else -> ProbeKind.NONE
        }

    fun noteVerdict(path: String, exists: Boolean, length: Int): Result =
        if (exists && length > 0) {
            Result(
                ProbeKind.NOTE, true, true,
                "Verified: the note $path is on the record (${length} chars)."
            )
        } else {
            Result(
                ProbeKind.NOTE, true, false,
                "the note $path she said she wrote is not there"
            )
        }

    fun browserVerdict(url: String, title: String, allowed: Boolean): Result =
        if (url.isNotEmpty() && allowed) {
            Result(
                ProbeKind.BROWSER, true, true,
                "Verified: the browser rests on $url" +
                    if (title.isNotEmpty()) " — \"$title\"." else "."
            )
        } else {
            Result(
                ProbeKind.BROWSER, true, false,
                "the browser is not resting on a page this job " +
                    "was allowed to reach"
            )
        }

    fun calendarVerdict(title: String, found: Boolean): Result =
        if (found) {
            Result(
                ProbeKind.CALENDAR, true, true,
                "Verified: the event \"$title\" is on the calendar."
            )
        } else {
            Result(
                ProbeKind.CALENDAR, true, false,
                "the event \"$title\" she said she created is not " +
                    "on the calendar"
            )
        }

    fun noneVerdict(): Result =
        Result(ProbeKind.NONE, false, true, "")

    /** A verdict supplied by an external check — a
     *  recipe's own success check (v1.0.4): the check IS
     *  the re-read for a recipe run, so its outcome enters
     *  the finish path as the verdict. */
    fun externalVerdict(ok: Boolean, detail: String): Result =
        Result(ProbeKind.NONE, true, ok, detail)

    /** Stamp the verdict onto the record — the graded
     *  report's verification line reads these fields. */
    fun stamp(task: JSONObject, result: Result) {
        if (result.ran) {
            task.put(VERIFIED_FIELD, result.ok)
            task.put(VERIFY_DETAIL_FIELD, result.detail)
        }
    }

    /** The discrepancy in work terms, for the report. */
    fun discrepancyLine(result: Result): String =
        "One claim did not check out: ${result.detail}."
}

/**
 * FINAL-ANSWER CHECKS (steal #8): deterministic, over the
 * record + the finish summary + the verification result.
 * A failure is a named, recorded fact — never a vibe.
 */
object FinalChecks {

    data class Failure(val check: String, val detail: String)

    const val FAILED_FIELD = "finalCheckFailed"

    /** The words that count as NAMING a not-done class in
     *  a finish summary — the summary must scope itself. */
    private fun classKeywords(toolClass: TaskTools.ToolClass): List<String> =
        when (toolClass) {
            TaskTools.ToolClass.MAIL -> listOf("email", "gmail", "mail")
            TaskTools.ToolClass.REPO_CODE ->
                listOf("code", "repository", "repo", "computer lane")
            TaskTools.ToolClass.VIDEO -> listOf("video", "computer lane")
            TaskTools.ToolClass.BROWSER -> listOf("browser")
            TaskTools.ToolClass.CALENDAR -> listOf("calendar")
        }

    fun evaluate(
        task: JSONObject,
        plan: TaskTools.Plan?,
        summary: String,
        verify: FinishVerifier.Result
    ): List<Failure> {
        val out = mutableListOf<Failure>()
        val open = TaskPlanning.subgoals(task)
            .filter { it.status == TaskPlanning.Subgoal.OPEN }
        if (open.isNotEmpty()) {
            out.add(
                Failure(
                    "subgoals_open",
                    "the plan still lists ${open.size} unfinished " +
                        "sub-goal(s): " +
                        open.joinToString("; ") { it.text.take(60) }
                )
            )
        }
        if (verify.ran && !verify.ok) {
            out.add(Failure("state_unverified", verify.detail))
        }
        if (plan != null && plan.partial) {
            val low = summary.lowercase()
            val unnamed = plan.unavailable.filter { toolClass ->
                classKeywords(toolClass).none { it in low }
            }
            if (unnamed.isNotEmpty()) {
                out.add(
                    Failure(
                        "partial_unnamed",
                        "the finish summary does not name what was " +
                            "not done here: " +
                            TaskTools.labelsFor(unnamed.toSet())
                    )
                )
            }
        }
        return out
    }

    /** The bounce message a rejected finish hears. */
    fun bounceNote(failures: List<Failure>): String =
        "finish rejected by the final checks: " +
            failures.joinToString("; ") { "${it.check} — ${it.detail}" } +
            ". Fix what the checks name (or record the sub-goals as " +
            "honestly dropped in a plan_note), then finish again."
}
