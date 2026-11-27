package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * PLAN / ACT GATE + PER-STEP CHECKPOINTS (v1.0.4) —
 * steal #11 (Cline), reimplemented as a pattern.
 *
 * PLAN AS A TOOL GATE, NOT A PROMPT: a consequential
 * general task (its own words commit the user — sends,
 * payments, posts, bookings, deletions, account
 * creation; TaskTools.isConsequential) starts in PLAN
 * mode. In PLAN mode the mutating tools are ABSENT from
 * the registry the model is offered — not discouraged,
 * not policy-worded: absent. The model may read, search,
 * browse-read and plan; its one productive act is
 * propose_plan. The runner's dispatch is the second
 * wall: a mutating call named anyway is refused by
 * Sentinel (TaskTools.PLAN_REFUSAL).
 *
 * The user approves in the chat where the work lives
 * (a deterministic approval shape — no model decides
 * what counts as approval), the mode flips to ACT, and
 * the run proceeds under the ordinary approval policies
 * for the rest of its life. The same approval channel
 * carries the gmail_send park: a send inside an approved
 * task still parks ONCE for the user's word, because a
 * send is the one act whose approval must name the act.
 *
 * CHECKPOINTS: before a mutating step that touches a
 * note, the note's prior content (or its absence) is
 * snapshotted onto the record. Any step can then be
 * undone — the snapshot restores the note byte-for-byte
 * (or removes a note the step created). Bounded, like
 * everything on the record.
 *
 * Pure + org.json; pinned by PlanActTest.
 */
object PlanAct {

    const val MODE_FIELD = "actMode"
    const val MODE_PLAN = "plan"
    const val MODE_ACT = "act"

    /** Record fields for a pending user approval: the
     *  kind ("plan" | "gmail_send") and a work-terms
     *  description of the act awaiting the word. */
    const val APPROVAL_KIND_FIELD = "approvalKind"
    const val APPROVAL_DETAIL_FIELD = "approvalDetail"

    /** The tools a PLAN-mode run is offered: the read-only
     *  kit plus the two planning acts. Everything else —
     *  above all every mutating tool — is absent. */
    val PLAN_TOOLS: List<String> = listOf(
        "browser_open", "browser_read", "browser_screenshot",
        "browser_wait", "web_search", "read_note", "list_notes",
        "calendar_read", "gmail_list", "gmail_search", "gmail_read",
        "memory_read", "memory_search", "plan_note", "propose_plan",
        "finish"
    )

    /** The system prompt for a PLAN-mode run: the registry
     *  above, stated plainly. The gate is the registry —
     *  this text just keeps the model from wasting steps
     *  asking for tools it cannot have. */
    const val PLAN_SYSTEM_PROMPT =
        "You are Lille, planning a consequential task on the user's phone. " +
            "This run is in PLAN MODE: you cannot change anything yet. " +
            "Reply with exactly ONE fenced JSON block per step, like:\n" +
            "```json\n{\"tool\": \"browser_read\", \"args\": {}}\n```\n" +
            "Tools: browser_open{url}, browser_read{}, browser_screenshot{name}, " +
            "browser_wait{seconds or text,timeout}, web_search{query}, " +
            "read_note{path}, list_notes{}, calendar_read{range}, " +
            "gmail_list{max}, gmail_search{query,max}, gmail_read{id}, " +
            "memory_read{}, memory_search{query}, plan_note{facts,subgoals,done,dropped}, " +
            "propose_plan{plan,subgoals}, finish{summary}. " +
            "Investigate what you need, then call propose_plan with the full " +
            "plan and its sub-goals. The user approves the plan in the chat; " +
            "only then do the acting tools exist. Never invent tool results."

    fun mode(task: JSONObject): String =
        task.optString(MODE_FIELD, MODE_ACT)

    fun isPlanMode(task: JSONObject): Boolean = mode(task) == MODE_PLAN

    /** Stamp the starting mode for a general task: PLAN
     *  when the job is consequential, ACT otherwise.
     *  Packaged tasks are never gated here (their packages
     *  carry their own approval discipline). */
    fun stampInitialMode(task: JSONObject, text: String) {
        if (!task.has(MODE_FIELD)) {
            task.put(
                MODE_FIELD,
                if (TaskTools.isConsequential(text)) MODE_PLAN else MODE_ACT
            )
        }
    }

    /** The tools offered to [allTools] in this mode: PLAN
     *  filters to the read-only registry — absence, not
     *  refusal. ACT offers everything. */
    fun offeredTools(mode: String, allTools: List<String>): List<String> =
        if (mode == MODE_PLAN) {
            allTools.filter { it in PLAN_TOOLS }
        } else {
            allTools
        }

    /** True when a tool call must be refused in this mode
     *  (the dispatch wall behind the registry wall). */
    fun refuses(mode: String, tool: String): Boolean =
        mode == MODE_PLAN && TaskTools.isMutating(tool)

    /** Approval shapes: the WHOLE message (trimmed,
     *  lowercased, trailing punctuation stripped) must be
     *  one of these — an approval buried in a longer
     *  sentence is a conversation, not an approval, and
     *  the chat brain handles it as one. */
    private val APPROVALS = setOf(
        "approve", "approved", "approve it", "approve the plan",
        "approve send", "go ahead", "go ahead please",
        "yes go ahead", "yes, go ahead", "proceed", "do it",
        "yes do it", "send it", "yes send it"
    )

    fun isApprovalMessage(text: String): Boolean {
        val norm = text.trim().lowercase()
            .trimEnd('.', '!', ' ')
        return norm in APPROVALS
    }

    /** The park note a plan (or a send) leaves for the
     *  user, in work terms. */
    fun parkNote(kind: String, detail: String): String =
        when (kind) {
            "gmail_send" ->
                "She's ready to send that email ($detail) — " +
                    "say \"approve send\" here and it goes; " +
                    "anything else and it stays unsent."
            else ->
                "Plan ready — she has laid out the work and is " +
                    "waiting for your word. Say \"approve\" here " +
                    "and she carries it out."
        }
}

/**
 * PER-STEP CHECKPOINTS (v1.0.4, steal #11's second half):
 * snapshots of what a mutating step is about to change,
 * kept on the record so the step can be undone. v1.0.4
 * snapshots NOTES (the artefact class a step can fully
 * restore); the entry shape carries a kind so browser /
 * calendar snapshots can join later without a format
 * change.
 */
object Checkpoints {

    const val FIELD = "checkpoints"
    const val MAX_KEPT = 20

    /** Snapshot a note's state before a write: [before]
     *  null = the note did not exist (undo deletes it). */
    fun noteEntry(step: Int, path: String, before: String?): JSONObject =
        JSONObject()
            .put("step", step)
            .put("kind", "note")
            .put("path", path)
            .put("existed", before != null)
            .put("before", before ?: "")

    fun append(task: JSONObject, entry: JSONObject) {
        val arr = task.optJSONArray(FIELD) ?: JSONArray()
        arr.put(entry)
        while (arr.length() > MAX_KEPT) arr.remove(0)
        task.put(FIELD, arr)
    }

    fun count(task: JSONObject): Int =
        task.optJSONArray(FIELD)?.length() ?: 0

    /** The newest checkpoint, or null. */
    fun last(task: JSONObject): JSONObject? {
        val arr = task.optJSONArray(FIELD) ?: return null
        if (arr.length() == 0) return null
        return arr.getJSONObject(arr.length() - 1)
    }

    /** Remove and return the newest checkpoint (undo
     *  consumes it — an undone step is not re-undoable
     *  from the same snapshot). */
    fun pop(task: JSONObject): JSONObject? {
        val arr = task.optJSONArray(FIELD) ?: return null
        if (arr.length() == 0) return null
        val entry = arr.getJSONObject(arr.length() - 1)
        arr.remove(arr.length() - 1)
        task.put(FIELD, arr)
        return entry
    }

    /** What undo must restore for an entry: the path and
     *  the content to write back — null content means the
     *  note must be DELETED (the step created it). */
    fun restoreTarget(entry: JSONObject): Pair<String, String?> =
        entry.optString("path") to
            if (entry.optBoolean("existed")) entry.optString("before") else null
}
