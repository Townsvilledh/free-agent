package org.freeagent.app

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WORK-LEVEL TRANSPARENCY (v1.0.2, Ethuram 2026-11-27:
 * "I don't know what she's doing — she's not transparent").
 *
 * Status-from-state (v1.0.1) shipped too thin: a bare step
 * COUNT, no named steps, no wait reason, and near-identical
 * status bubbles stacking on every probe. But the task
 * record already holds everything — the checkpointed
 * message/tool history, the state, the note, the retry
 * time, the wall step. This component renders THAT,
 * deterministically, in work terms:
 *
 * - every completed step NAMED from the tool call the
 *   runner actually recorded (never invented, never a
 *   percentage, never a plan — agent work is indeterminate,
 *   so the list ends at what was done);
 * - the current state as work: what she is doing now, what
 *   she is waiting for, when she continues, and — when she
 *   is parked — the exact step that is the user's;
 * - the last-movement time, from the record's updatedAt.
 *
 * The seamless law binds every word here: no route names,
 * no provider names, no rate-limit or pacing vocabulary,
 * no attribution. Waiting is stated as what it is in work
 * terms ("a short pause before the next step", "the
 * network"), never as infrastructure. Model-written text
 * reaches these surfaces only through NarrationGuard.
 *
 * Pure + org.json, pinned by TaskTransparencyTest.
 */
object TaskTransparency {

    /** Reports show at most this many steps before the
     *  "…and N more" tail — a 40-step browser run must not
     *  become a wall of text in the thread. */
    const val MAX_REPORT_STEPS = 10

    /** One completed step, named in work terms. [ok] is
     *  false when the recorded tool result was an error —
     *  the step was attempted and did not work, and the
     *  rendering says so instead of dressing it as done. */
    data class Step(val name: String, val ok: Boolean = true)

    // ---------- step derivation ----------

    /**
     * The named steps of a task, derived ONLY from its
     * recorded history: each assistant message the runner
     * checkpointed is parsed for the tool call it carried
     * (the same tolerant parse the runner itself uses), and
     * the following "Tool result (…)" message grades it.
     * Assistant replies that carried no tool call were
     * nudges, not work — no step. `finish` calls are the
     * runner's exit bookkeeping, not work either: a
     * completed task gains one synthetic final step from
     * its verified summary instead.
     */
    fun stepsFor(task: JSONObject): List<Step> {
        val messages = task.optJSONArray("messages")
            ?: return emptyList()
        val steps = mutableListOf<Step>()
        var site = ""
        var pendingTool: String? = null
        var pendingArgs: JSONObject? = null
        var pendingSite = ""

        fun flush(observation: String?) {
            val tool = pendingTool ?: return
            pendingTool = null
            val args = pendingArgs ?: JSONObject()
            pendingArgs = null
            if (tool == "finish") return // bookkeeping, see above
            steps.add(nameStep(tool, args, observation, pendingSite))
            // The page a browser step landed on becomes the
            // site the next steps are working on.
            observation?.let { obsSite(it) }?.let { site = it }
            if (tool == "browser_open") {
                siteLabel(args.optString("url")).let {
                    if (it.isNotEmpty()) site = it
                }
            }
        }

        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val content = m.optString("content")
            when (m.optString("role")) {
                "assistant" -> {
                    flush(null)
                    val call = parseToolCall(content)
                    if (call != null) {
                        pendingTool = call.first
                        pendingArgs = call.second
                        pendingSite = site
                    }
                }
                "user" -> {
                    if (content.startsWith("Tool result (")) {
                        flush(observationBody(content))
                    } else if (content.startsWith("finish rejected")) {
                        // A rejected finish never happened —
                        // drop the pending call, don't list it.
                        pendingTool = null
                        pendingArgs = null
                    }
                }
            }
        }
        flush(null)
        if (task.optString("state") == TaskStates.COMPLETED) {
            val summary = task.optString("summary").trim()
            steps.add(
                Step(
                    if (summary.isEmpty()) "Finished the work"
                    else "Finished — " + gist(summary, 80)
                )
            )
        }
        return steps
    }

    /** The runner's parse, mirrored: first '{' to last '}',
     *  a JSON object carrying a "tool" name. Records were
     *  produced by that parse, so what it executed is
     *  exactly what this finds. */
    private fun parseToolCall(content: String): Pair<String, JSONObject>? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(content.substring(start, end + 1))
            if (!obj.has("tool")) null
            else obj.optString("tool") to
                (obj.optJSONObject("args") ?: JSONObject())
        } catch (e: Exception) {
            null
        }
    }

    /** The observation half of a recorded "Tool result
     *  (name): …" message. */
    private fun observationBody(content: String): String {
        val close = content.indexOf("): ")
        return if (close >= 0) content.substring(close + 3) else content
    }

    /** The site a browser observation reports ("URL: …"
     *  lines in the formatted page states), as a label. */
    private fun obsSite(observation: String): String? {
        for (line in observation.split("\n")) {
            if (line.startsWith("URL: ")) {
                val label = siteLabel(line.substring(5).trim())
                if (label.isNotEmpty()) return label
            }
        }
        return null
    }

    /** "https://www.maildrop.cc/inbox" → "maildrop.cc". */
    fun siteLabel(url: String): String {
        val host = BrowserPerception.domainOf(url)
        return if (host.startsWith("www.")) host.substring(4) else host
    }

    /** Name one recorded tool call in work terms. The
     *  observation decides the honest outcome: an error
     *  result marks the step as not-worked; an observed
     *  CHALLENGE renames a browser step to the wall it
     *  reached (the wall is the story, not a failure). */
    private fun nameStep(
        tool: String, args: JSONObject,
        observation: String?, site: String
    ): Step {
        // For an open, the step's own site is the one being
        // opened; for later steps it is the page they are
        // on — the tracked site, or (first browser step of
        // a run) the site its own observation reports.
        val here = if (tool == "browser_open") {
            siteLabel(args.optString("url")).ifEmpty { site }
        } else {
            site.ifEmpty {
                observation?.let { obsSite(it) } ?: ""
            }
        }
        val onSite = if (here.isEmpty()) "" else " on $here"
        if (observation != null &&
            observation.startsWith("CHALLENGE")
        ) {
            return Step("Reached a human check$onSite")
        }
        if (observation != null &&
            observation.startsWith(UserHandoff.OBSERVATION_PREFIX)
        ) {
            return Step("Handed the browser to you for a step")
        }
        val ok = observation == null ||
            !observation.startsWith("error:")
        val name = when (tool) {
            "web_search" ->
                "Searched the web: " + gist(args.optString("query"), 60)
            "browser_open" -> {
                val s = siteLabel(args.optString("url"))
                if (s.isEmpty()) "Opened a page" else "Opened $s"
            }
            "browser_read" -> "Read the page$onSite"
            "browser_click" -> {
                val text = args.optString("text").trim()
                if (text.isNotEmpty()) {
                    "Tapped “" + gist(text, 30) + "”$onSite"
                } else "Tapped a control$onSite"
            }
            "browser_type" -> "Entered text in a field$onSite"
            "browser_type_secret" -> "Entered a saved secret$onSite"
            "browser_upload" -> {
                val path = args.optString("path")
                    .substringAfterLast("/")
                if (path.isEmpty()) "Uploaded a file$onSite"
                else "Uploaded $path$onSite"
            }
            "browser_screenshot" -> "Took a screenshot$onSite"
            "browser_wait" -> {
                val text = args.optString("text").trim()
                if (text.isNotEmpty()) {
                    "Waited for “" + gist(text, 30) + "” to appear$onSite"
                } else "Waited on the page$onSite"
            }
            "browser_handoff" -> "Handed the browser to you for a step"
            "vault_gen_secret" -> "Created a password in the vault"
            "vault_record_account" -> {
                val s = args.optString("site").trim()
                if (s.isEmpty()) "Recorded the account details"
                else "Recorded the $s account details"
            }
            "x_session_status" -> "Checked the X sign-in"
            "x_read_feed" -> "Read the X feed"
            "x_search" ->
                "Searched X: " + gist(args.optString("query"), 60)
            "x_post" -> "Posted on X"
            "write_note" ->
                "Wrote " + gist(args.optString("path"), 60)
            "read_note" ->
                "Read " + gist(args.optString("path"), 60)
            "list_notes" -> "Listed the work files"
            else -> "Completed a step"
        }
        return Step(name, ok)
    }

    /** Whitespace-collapsed, capped with an ellipsis —
     *  gists stay one-glance short. */
    fun gist(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat
        else flat.substring(0, max - 1).trimEnd() + "…"
    }

    // ---------- titles ----------

    /**
     * The task's short title: the record's own title when
     * it carries one (packaged tasks do), else derived
     * from its text — the chat-intake direction and any
     * reply-marking stripped, first line, gist-capped.
     */
    fun titleFor(task: JSONObject): String {
        val own = task.optString("title").trim()
        if (own.isNotEmpty()) return gist(own, 60)
        var text = task.optString("text")
        val intake = text.indexOf("\n\n[Chat intake:")
        if (intake >= 0) text = text.substring(0, intake)
        if (text.startsWith("[Replying to ")) {
            val rest = text.indexOf("]\n\n")
            if (rest >= 0 && text.substring(rest + 3).isNotBlank()) {
                text = text.substring(rest + 3)
            }
        }
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim().orEmpty()
        if (firstLine.isEmpty()) return "Untitled work"
        return gist(firstLine, 60)
    }

    // ---------- times ----------

    /** A clock label for a record timestamp ("3:41 AM") —
     *  the device's own timezone, the user's own frame. */
    fun clockLabel(epochMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date(epochMs))

    // ---------- the living status text ----------

    /**
     * The rich status block for a task — the ONE living
     * status text its chat carries (stored, updated in
     * place): headline + task title, the last step BY NAME,
     * the state in work terms (what she is waiting for and
     * when she continues, or the exact step that is the
     * user's), and the last-movement time. Deterministic
     * from the record; [now] only decides whether a retry
     * time is still ahead.
     */
    fun statusText(task: JSONObject, now: Long): String {
        val state = task.optString("state")
        val steps = stepsFor(task)
        val out = StringBuilder()
        // A task killed by the user's own hand headlines
        // as HIS act (v1.0.5) — "Stopped", with the detail
        // "You stopped this." — the same words the status
        // line and the reason probe use, on every surface.
        val headline = if (TaskControl.wasKilled(task)) {
            "Stopped"
        } else {
            headlineFor(state)
        }
        out.append(headline).append(" — ").append(titleFor(task))
        if (steps.isNotEmpty()) {
            out.append("\nLast step: ").append(renderStep(steps.last()))
        }
        detailFor(task, state, now)?.let {
            out.append("\n").append(it)
        }
        // STATE COHERENCE (v1.0.8): a gated record whose
        // finish doesn't hold tells that ON the status
        // itself — the headline may still read Finished
        // in the pre-audit window, but the cross-check
        // line rides with it, so the card never presents
        // the false finish as the whole story. A record
        // the audit already reopened carries the reopened
        // line under its live headline instead.
        if (FinishGate.shouldReopen(task)) {
            out.append(
                "\nThat finish doesn't stand: " +
                    FinishGate.discrepancyFor(task) + "."
            )
        } else if (
            task.optString(FinishGate.DISCREPANCY_FIELD).isNotEmpty()
        ) {
            out.append(
                "\nReopened: this was marked finished, but " +
                    task.optString(FinishGate.DISCREPANCY_FIELD) +
                    " — the work is open again."
            )
        }
        declarationFor(task)?.let {
            out.append("\n").append(it)
        }
        val updatedAt = task.optLong("updatedAt", 0L)
        if (updatedAt > 0L) {
            out.append("\nLast movement ").append(clockLabel(updatedAt)).append(".")
        }
        return out.toString()
    }

    private fun headlineFor(state: String): String = when (state) {
        TaskStates.QUEUED -> "Queued here"
        TaskStates.RUNNING -> "Working on it here"
        TaskStates.WAITING_NETWORK, TaskStates.WAITING_ROUTE ->
            "Working on it here"
        TaskStates.PARKED_AT_WALL -> "Waiting on one step from you"
        TaskStates.PAUSED -> "Paused here"
        TaskStates.NEEDS_RUNNER -> "With the runner"
        TaskStates.COMPLETED -> "Finished"
        TaskStates.FAILED -> "Failed — nothing about it counts as done"
        TaskStates.CANCELLED -> "Cancelled"
        else -> "State: $state"
    }

    /** The work-terms detail line for a state: what she is
     *  waiting for + when she continues (waiting states),
     *  the user's exact step (parked), the verified summary
     *  (completed), the vetted reason (failed/paused). */
    private fun detailFor(task: JSONObject, state: String, now: Long): String? =
        if (TaskControl.wasKilled(task)) {
            "You stopped this."
        } else when (state) {
            TaskStates.QUEUED ->
                "Starts on its own — nothing needed from you."
            TaskStates.RUNNING ->
                "Now: working on the next step."
            TaskStates.WAITING_NETWORK ->
                "Waiting for the network to come back — " +
                    "she continues on her own." + retryBit(task, now) +
                    watchdogBit(task)
            TaskStates.WAITING_ROUTE ->
                if (task.optBoolean(StepBudget.REST_FLAG)) {
                    // A budget rest (v1.0.3) is work resting,
                    // not a route resting: it says so, in the
                    // brief's own terms — resting, continues
                    // on her own.
                    "Resting between work sessions — she " +
                        "continues on her own." + retryBit(task, now) +
                        watchdogBit(task)
                } else {
                    "Taking a short pause before the next step — " +
                        "she continues on her own." + retryBit(task, now) +
                        watchdogBit(task)
                }
            TaskStates.PARKED_AT_WALL -> {
                val step = task.optString("wallStep").trim()
                when {
                    step.isNotEmpty() -> "Your step: $step"
                    StateCoherence.coherentNote(task, state) != null ->
                        "Your step: " +
                            StateCoherence.coherentNote(task, state)
                    else ->
                        "Your step: take the step waiting in the " +
                            "browser panel above."
                }
            }
            TaskStates.PAUSED ->
                StateCoherence.coherentNote(task, state)
                    ?: "Say the word here and she picks it back up."
            TaskStates.NEEDS_RUNNER ->
                "This job needs code execution, which can't run " +
                    "on this phone — it was exported as a task " +
                    "spec instead of being faked."
            TaskStates.COMPLETED ->
                task.optString("summary").trim().ifEmpty { null }
            TaskStates.FAILED ->
                StateCoherence.coherentNote(task, state)
                    ?.let { "Why: $it" }
            else -> null
        }

    /**
     * The up-front tool declaration (v1.0.3): when the
     * record's plan names classes this phone does not have,
     * the status says so from the job's FIRST appearance —
     * what she has here, what needs the computer lane, and
     * that the rest will be marked in her report. The truth
     * at the start, not in the final report (the Agentware
     * lesson). Composed deterministically from the plan
     * fields (TaskTools), never from narration.
     */
    fun declarationFor(task: JSONObject): String? {
        val plan = TaskTools.planFromRecord(task) ?: return null
        val parts = mutableListOf<String>()
        // v1.0.4: routed classes are HANDED OFF, with the
        // receipt named — not merely "unavailable".
        if (plan.routed.isNotEmpty()) {
            parts.add(
                "This job also names " +
                    TaskTools.labelsFor(plan.routed) +
                    " — that work is handed to the computer lane " +
                    "with a written brief (receipt: " +
                    task.optString(
                        "laneReceipt",
                        "specs/${task.optString("id")}.json"
                    ) +
                    "), so she'll do the parts this phone " +
                    "supports and mark the rest in her report."
            )
        }
        // v1.0.4: mail without a connection declares its
        // ONE Settings step, not a dead end.
        if (plan.mailNotConnected) {
            parts.add(
                "Email is part of this job and Gmail isn't " +
                    "connected on this phone yet — that part " +
                    "waits for one step in Settings (Gmail → " +
                    "Connect); everything else proceeds here."
            )
        }
        val other = plan.unavailable - plan.routed -
            if (plan.mailNotConnected) {
                setOf(TaskTools.ToolClass.MAIL)
            } else {
                emptySet()
            }
        if (other.isNotEmpty()) {
            parts.add(
                "This job also names " +
                    TaskTools.labelsFor(other) +
                    " — that needs the computer lane, so she'll " +
                    "do the parts this phone supports and mark " +
                    "the rest in her report."
            )
        }
        if (parts.isEmpty()) return null
        return TaskTools.availableLine(plan) + " " +
            parts.joinToString(" ")
    }

    /** The watchdog's visible trace (v1.0.2): when a forced
     *  retry still ended waiting, the record's watchdogNote
     *  says so in work terms — stuckness is visible state,
     *  never silence. Vetted like every record-echoed line. */
    private fun watchdogBit(task: JSONObject): String {
        val note = NarrationGuard.cleanOrNull(
            task.optString("watchdogNote")
        ) ?: return ""
        return " $note"
    }

    /** " Next try around 4:05 AM." when the record carries
     *  a retry time still ahead; silence otherwise (a due
     *  retry needs no narration — the worker is on it). */
    private fun retryBit(task: JSONObject, now: Long): String {
        val next = task.optLong("nextRetryAt", 0L)
        return if (next > now) {
            " Next try around " + clockLabel(next) + "."
        } else ""
    }

    /**
     * The deterministic answer to a status probe: the
     * living status text, plus — when the record has not
     * moved since the status line last reflected it — one
     * short no-change note. Never a second copy of the
     * block (the caller upserts this into the ONE status
     * message; see ChatData.upsertTaskStatus).
     */
    fun probeAnswer(
        task: JSONObject, now: Long, noChangeSince: Long? = null
    ): String {
        val base = statusText(task, now)
        return if (noChangeSince != null && noChangeSince > 0L) {
            base + "\nNo change since " + clockLabel(noChangeSince) + "."
        } else base
    }

    // ---------- expansion + reports ----------

    private fun renderStep(step: Step): String =
        if (step.ok) step.name else step.name + " — didn't work"

    /**
     * The full step list for the tap-to-expand view: every
     * recorded step named and marked, then — for live
     * work — the current/waiting line the status ends on.
     * Nothing pending is invented; the list is the past,
     * plus the one honest "now" line.
     */
    fun expandedText(task: JSONObject, now: Long): String {
        val steps = stepsFor(task)
        val state = task.optString("state")
        val out = StringBuilder()
        out.append("Steps so far (").append(steps.size).append("):")
        if (steps.isEmpty()) {
            out.append("\nNo steps recorded yet.")
        }
        for (step in steps) {
            out.append("\n").append(if (step.ok) "✓ " else "✗ ")
                .append(renderStep(step))
        }
        // The "now" line exists only for states where
        // something is genuinely pending — a finished or
        // failed list simply ends at what was done.
        val nowLine = when (state) {
            TaskStates.RUNNING -> "Working on the next step"
            TaskStates.QUEUED -> "Queued — starts on its own"
            TaskStates.WAITING_NETWORK ->
                "Waiting for the network — continues on her own" +
                    retryBit(task, now)
            TaskStates.WAITING_ROUTE ->
                if (task.optBoolean(StepBudget.REST_FLAG)) {
                    "Resting between work sessions — continues " +
                        "on her own" + retryBit(task, now)
                } else {
                    "Pausing briefly — continues on her own" +
                        retryBit(task, now)
                }
            TaskStates.PARKED_AT_WALL ->
                "Waiting on your step (named above)"
            TaskStates.PAUSED -> "Paused"
            TaskStates.NEEDS_RUNNER -> "With the runner"
            else -> null
        }
        if (nowLine != null) {
            out.append("\n● ").append(nowLine)
        }
        // The recorded plan (v1.0.4, steal #8): sub-goals
        // with their standing, from the plan notes — the
        // user sees the shape of the work, not just steps.
        TaskPlanning.planBlock(task)?.let {
            out.append("\n").append(it)
        }
        // A pending approval names itself (v1.0.4): the
        // plan or the send waiting on the user's word.
        val approvalKind = task.optString(PlanAct.APPROVAL_KIND_FIELD)
        if (approvalKind.isNotEmpty()) {
            out.append("\n● ").append(
                if (approvalKind == "gmail_send") {
                    "Waiting on your word: an email send is ready " +
                        "(say \"approve send\")"
                } else {
                    "Waiting on your word: a plan is ready " +
                        "(say \"approve\")"
                }
            )
        }
        return out.toString()
    }

    /**
     * The named step list a graded report carries above
     * its outcome (scope item 5): what was actually done,
     * in order, graded from the record. Null when the
     * record holds no steps — a report never pads itself
     * with an empty list. Capped at [MAX_REPORT_STEPS]
     * with an honest tail.
     */
    fun reportSteps(task: JSONObject): String? {
        var steps = stepsFor(task)
        // A completed report prints the verified summary in
        // full right below the list — the synthetic finish
        // step (its gist) would only stutter against it.
        if (task.optString("state") == TaskStates.COMPLETED &&
            steps.isNotEmpty() && steps.last().name.startsWith("Finished")
        ) {
            steps = steps.dropLast(1)
        }
        if (steps.isEmpty()) return null
        val header = when (task.optString("state")) {
            TaskStates.COMPLETED -> "What was done:"
            TaskStates.FAILED -> "What was done before it stopped:"
            TaskStates.PARKED_AT_WALL -> "What she's done so far:"
            TaskStates.PAUSED -> "What was done before it paused:"
            TaskStates.NEEDS_RUNNER -> "What was done before the export:"
            else -> "What she's done so far:"
        }
        val out = StringBuilder(header)
        val shown = steps.take(MAX_REPORT_STEPS)
        for (step in shown) {
            out.append("\n").append(if (step.ok) "✓ " else "✗ ")
                .append(renderStep(step))
        }
        if (steps.size > shown.size) {
            out.append("\n…and ")
                .append(steps.size - shown.size)
                .append(" more steps.")
        }
        return out.toString()
    }
}
