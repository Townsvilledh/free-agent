package org.freeagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The packaged "Connect my X account" task (v0.10.1) — the
 * counterpart to Lille's OWN account task, and the opposite
 * gate: that one is owner-only; this one is for EVERY user.
 *
 * What it does: a device-lane browser task that opens X's
 * login flow in Lille's WebView session and then PARKS via
 * the planned user handoff (UserHandoff / browser_handoff)
 * so the USER types their own credentials into the session
 * themselves. Lille never sees, types, asks for, or stores
 * the user's password — the sign-in happens with the user's
 * own hands in the parked session ("Lille needs you" →
 * BrowserActivity → "Done — hand back to Lille"), and the
 * session persists in her WebView storage exactly like her
 * own lane's sessions.
 *
 * On handback the task verifies with x_session_status and
 * reports "Connected as @handle" from the recorded evidence
 * (the runner stores xHandle on the record) — or an honest
 * not-connected state. Connecting authorises nothing else:
 * posting from a connected USER account stays approval-gated
 * (xPostMode=user, xPostApproved=false — XLANE.md policy,
 * unchanged).
 */
object XConnectTask {

    const val TITLE = "Connect my X account"

    /** The plain-language promise shown with the task — the
     *  user should never have to wonder where their password
     *  goes. It doesn't come near Lille. */
    const val PLAIN_COPY =
        "Lille will open X and wait — you log in yourself, in " +
            "her browser, then hand back. She never sees your " +
            "password."

    val DOMAINS = listOf("x.com")

    val CRITERIA = listOf(
        "x_session_status verifies a live session as the user's own handle on x.com",
        "The sign-in was the user's own: the task parked for the user's step (user_step) and resumed after handback, or the session was already live — Lille never saw or typed the password"
    )

    const val TASK_TEXT: String =
        "Connect the USER's own X account in your browser, Lille. The account is " +
        "THEIRS, not yours: you open the door and hold it — the user signs in " +
        "THEMSELVES, in your browser session, with their own hands. You never " +
        "see, type, ask for, or store their password — not in a field, not in a " +
        "note, not anywhere. If the user ever pastes a password into a message, " +
        "do NOT use it: hand over again instead (step 3).\n\n" +
        "FLOW\n" +
        "1. Call x_session_status first. If it reports logged_in as a handle, " +
        "the account is already connected: finish with the summary exactly: " +
        "Connected as @<handle> (with the real handle).\n" +
        "2. Otherwise browser_open https://x.com/i/flow/login and browser_read " +
        "to confirm the sign-in form is on screen. (If X shows a human check " +
        "instead, the usual wall rules apply: finish naming the wall and the " +
        "task parks for the user.)\n" +
        "3. Hand the browser over: call browser_handoff with step \"Log in to " +
        "X in Lille's browser: tap 'Open Lille's browser', sign in with your " +
        "OWN X account, then tap 'Done — hand back to Lille'. Lille never " +
        "sees your password.\" — then call finish. The task parks; the user " +
        "signs in inside your session; the session persists in your browser " +
        "storage like any lane session.\n" +
        "4. When the task resumes after handback, call x_session_status again. " +
        "If it reports logged_in, finish with the summary exactly: Connected " +
        "as @<handle>. If it reports logged_out, the sign-in did not complete: " +
        "you may hand over ONE more time (step 3) if steps remain; after that, " +
        "finish honestly with the summary: Not connected — X still shows no " +
        "signed-in account after handback.\n" +
        "5. Connecting an account authorises NOTHING else: no posting, no " +
        "following, no profile changes in this task."

    /** Create the packaged task in the store (queued, lane=device,
     *  kind=browser, userHandoff armed, criteria for the report
     *  view). Returns the task record. Available to every user —
     *  deliberately NOT owner-gated. */
    fun create(context: Context): JSONObject {
        val store = TaskStore(context)
        val task = store.create(TASK_TEXT)
        val id = task.optString("id")
        val domains = JSONArray()
        DOMAINS.forEach { domains.put(it) }
        val criteria = JSONArray()
        CRITERIA.forEach { criteria.put(it) }
        store.update(id) {
            it.put("title", TITLE)
            it.put("kind", "browser")
            it.put("lane", "device")
            it.put("browserDomains", domains)
            it.put("userHandoff", true)
            it.put("xPostMode", "user")
            it.put("xPostApproved", false)
            it.put("criteria", criteria)
        }
        return store.get(id) ?: task
    }

    /** The graded report for a connect-task record — status
     *  from STATE + recorded evidence (xHandle written by the
     *  runner's x_session_status; userHandoffDone written when
     *  the task parked for the user's own sign-in step). */
    fun reportFor(task: JSONObject): String {
        val state = task.optString("state")
        val handle = task.optString("xHandle")
        val wallKind = task.optString("wallKind")
        val status = when {
            state == TaskStates.COMPLETED && handle.isNotEmpty() ->
                "CONNECTED — Connected as @$handle"
            state == TaskStates.PARKED_AT_WALL &&
                wallKind == UserHandoff.WALL_KIND ->
                "NEEDS YOU — Lille is waiting in her browser for your sign-in"
            state == TaskStates.PARKED_AT_WALL ->
                "NEEDS YOU — parked at a human-only wall during sign-in"
            state == TaskStates.COMPLETED ->
                "NOT CONNECTED — the run finished without a verified session"
            state == TaskStates.FAILED -> "FAILED — not connected"
            state == TaskStates.CANCELLED -> "CANCELLED"
            else -> "IN PROGRESS (state: $state)"
        }
        val lines = mutableListOf<String>()
        lines.add("LILLE — TASK REPORT (device lane)")
        lines.add("Task: $TITLE")
        lines.add("STATUS: $status")
        lines.add("Cost: $0 — keyless routes only")
        lines.add("Lane: device (the on-device browser session on this phone)")
        lines.add(PLAIN_COPY)
        lines.add("Acceptance criteria:")
        val criteria = task.optJSONArray("criteria")
        if (criteria != null) {
            for (i in 0 until criteria.length()) {
                val c = criteria.optString(i)
                val (met, evidence) = criterionEvidence(c, task, handle)
                lines.add("- [${if (met) "x" else " "}] $c — evidence: $evidence")
            }
        }
        if (handle.isNotEmpty()) lines.add("Account: @$handle on x.com")
        val summary = task.optString("summary")
        if (summary.isNotEmpty()) lines.add("Lille's summary: $summary")
        if (state == TaskStates.PARKED_AT_WALL) {
            lines.add("Your step: ${task.optString("wallStep")}")
        }
        val note = task.optString("note")
        if (note.isNotEmpty()) lines.add("Note: $note")
        lines.add("Steps done: ${task.optInt("steps")}")
        return lines.joinToString("\n")
    }

    private fun criterionEvidence(
        criterion: String, task: JSONObject, handle: String
    ): Pair<Boolean, String> {
        val low = criterion.lowercase()
        return when {
            low.contains("x_session_status") ->
                if (handle.isNotEmpty()) {
                    true to "x_session_status verified a live session as @$handle"
                } else {
                    false to "no verified session recorded yet"
                }
            low.contains("sign-in was the user's own") ->
                when {
                    task.optBoolean("userHandoffDone") ->
                        true to "the task parked for the user's own sign-in " +
                            "step and resumed after handback"
                    handle.isNotEmpty() ->
                        true to "the session was already live — no sign-in " +
                            "step was needed, and no password was handled"
                    else ->
                        false to "no handback recorded yet"
                }
            else -> false to "no checkable evidence recorded"
        }
    }
}
