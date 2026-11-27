package org.freeagent.app

import org.json.JSONObject

/**
 * BROWSER CONTROL COMMANDS (v1.0.10) — "close the
 * browser", "log out of X", "switch to @handle" are
 * COMMANDS, handled deterministically before the
 * brain, exactly like TaskControl's kill/resume.
 *
 * Born of 2026-11-27 ~16:26 (Ethuram, on 1.0.9):
 * "when i demand for her to close the browser she
 * cannot close the browser automatically this is
 * annoying" — the demand fell to the brain, which
 * could narrate a closing but had no machinery to
 * perform one: the panel's dismissal existed only
 * behind its Close button, and the work driving the
 * panel had no spoken halt. And the session commands
 * (Fix 3.5): "log out" / "switch to @…" resolve here
 * to a session pass (TaskWorker's account-session
 * mode) over the scripted routines in
 * [AccountSession] — the chat never improvises an
 * account action.
 *
 * Grammar discipline (Ethuram: "No hardcoding"):
 * STRUCTURE, never phrase tables — a leading verb
 * from a closed class + a referent built from closed
 * classes, whole-message, exactly the kill/resume
 * discipline. Content ABOUT browsers is not a
 * command: "close the browser tab in the article"
 * carries extra referent tokens and parses as
 * nothing; "please close the browser" does not lead
 * with its verb and parses as nothing.
 *
 * Pure + org.json; pinned by Lille110Test.
 */
object BrowserControl {

    // ---------- close the browser (Fix 1) ----------

    private val CLOSE_VERBS =
        setOf("close", "dismiss", "shut", "hide", "stop")
    private val REFERENT_NOUNS =
        setOf("browser", "panel", "webview")
    private val DETERMINERS = setOf("the", "this", "that")

    /**
     * True when the whole message is a close-the-
     * browser command: a leading close verb, an
     * optional determiner, and a browser/panel
     * referent ("close the browser", "close browser",
     * "dismiss the browser", "shut the panel",
     * "close the browser panel") — or "stop browsing".
     * An optional trailing "now" is allowed, as in
     * the resume grammar. Anything after the referent
     * makes the message a sentence about a browser,
     * not the command.
     */
    fun parseCloseBrowser(text: String): Boolean {
        var tokens = commandTokens(text) ?: return false
        if (tokens.lastOrNull() == "now") {
            tokens = tokens.dropLast(1)
        }
        if (tokens.size < 2) return false
        val verb = tokens[0]
        if (verb !in CLOSE_VERBS) return false
        var rest = tokens.drop(1)
        if (rest.firstOrNull() in DETERMINERS) {
            rest = rest.drop(1)
        }
        if (rest.isEmpty()) return false
        if (rest == listOf("browsing")) return verb == "stop"
        if (rest.size == 1) return rest[0] in REFERENT_NOUNS
        return rest.size == 2 && rest[0] == "browser" &&
            rest[1] == "panel"
    }

    /** Record field: the browser was closed over this
     *  record by the user's own word — the status
     *  surfaces can name why the work paused. */
    const val CLOSED_FIELD = "browserClosedByUser"

    const val CLOSED_LINE = "Browser closed."
    const val NOT_OPEN_LINE = "The browser isn't open."
    const val CLOSED_NOTE =
        "You closed the browser — this work is paused. " +
            "Say “resume” to pick it back up."

    /** The states a close command can halt the lane's
     *  work INTO a pause from: live work and work
     *  about to run. A parked record is not driving —
     *  it waits on the user already — and terminal
     *  work has nothing to halt. */
    private val HALTABLE_STATES = setOf(
        TaskStates.RUNNING, TaskStates.QUEUED,
        TaskStates.WAITING_NETWORK, TaskStates.WAITING_ROUTE
    )

    /** What a close command DOES, decided purely from
     *  the facts: [dismissTaskId] is the record whose
     *  panel view closes (the dismissal + the
     *  commanded-close stamps land on it), [haltTaskId]
     *  is the browser lane's driving record to pause
     *  (never a kill — it resumes by word), and [reply]
     *  is the deterministic line. */
    data class ClosePlan(
        val dismissTaskId: String?,
        val haltTaskId: String?,
        val reply: String
    )

    fun planClose(
        tasks: List<JSONObject>,
        panelTaskId: String?,
        panelShowing: Boolean,
        laneOwnerTaskId: String?
    ): ClosePlan {
        val owner = laneOwnerTaskId?.let { id ->
            tasks.firstOrNull { it.optString("id") == id }
        }
        val halt = owner?.takeIf {
            it.optString("state") in HALTABLE_STATES &&
                !TaskControl.isArchived(it) &&
                !TaskControl.wasKilled(it)
        }
        val haltId = halt?.optString("id")?.takeIf { it.isNotEmpty() }
        val dismissId = panelTaskId?.takeIf {
            it.isNotEmpty() && (panelShowing || haltId != null)
        }
        val reply = if (panelShowing || haltId != null) {
            CLOSED_LINE
        } else {
            NOT_OPEN_LINE
        }
        return ClosePlan(dismissId, haltId, reply)
    }

    /** Stamp the halted record: closed by his word,
     *  the note the status surfaces read back, and
     *  the event. The caller performs the transition
     *  to PAUSED in the same store update. */
    fun stampBrowserClosed(
        task: JSONObject, command: String, now: Long
    ) {
        task.put(CLOSED_FIELD, true)
        task.put("note", CLOSED_NOTE)
        EventLog.append(
            task, "browser_closed", now,
            JSONObject().put("command", command.trim())
        )
    }

    // ---------- log out / switch (Fix 3.5) ----------

    private val PLATFORM_TOKENS = setOf("x", "twitter")

    /** True when the whole message is a log-out
     *  command: a leading "log out" / "sign out" /
     *  "logout" / "signout", then an optional platform
     *  referent (["of"|"from"] ["the"] "x"|"twitter",
     *  or the platform bare). "Log out of the app" is
     *  not it — its referent is not the platform. */
    fun parseLogOut(text: String): Boolean {
        var tokens = commandTokens(text) ?: return false
        if (tokens.lastOrNull() == "now") {
            tokens = tokens.dropLast(1)
        }
        val rest = when {
            tokens.size >= 2 && tokens[0] == "log" &&
                tokens[1] == "out" -> tokens.drop(2)
            tokens.size >= 2 && tokens[0] == "sign" &&
                tokens[1] == "out" -> tokens.drop(2)
            tokens.firstOrNull() == "logout" -> tokens.drop(1)
            tokens.firstOrNull() == "signout" -> tokens.drop(1)
            else -> return false
        }
        if (rest.isEmpty()) return true
        var tail = rest
        if (tail.firstOrNull() == "of" ||
            tail.firstOrNull() == "from"
        ) {
            tail = tail.drop(1)
        }
        if (tail.firstOrNull() == "the") tail = tail.drop(1)
        return tail.size == 1 && tail[0] in PLATFORM_TOKENS
    }

    /** A parsed switch command: the target [handle]
     *  (no @) and whether the command NAMED its target
     *  unambiguously ([explicit]: an @ handle, or the
     *  word "account" in the command). A bare
     *  "switch to <word>" intercepts only when the
     *  word is a registered account — [shouldIntercept]
     *  — so "switch to dark" (a theme, a mode) is
     *  never stolen from conversation. */
    data class SwitchCommand(
        val handle: String, val explicit: Boolean
    )

    private val HANDLE_RE = Regex("^[A-Za-z0-9_]{1,15}$")

    fun parseSwitch(text: String): SwitchCommand? {
        val tokens = commandTokens(text) ?: return null
        if (tokens.firstOrNull() != "switch") return null
        var rest = tokens.drop(1)
        var explicit = false
        if (rest.firstOrNull() == "account" ||
            rest.firstOrNull() == "accounts"
        ) {
            explicit = true
            rest = rest.drop(1)
        }
        if (rest.firstOrNull() == "to") rest = rest.drop(1)
        if (rest.size != 1) return null
        var handle = rest[0]
        if (handle.startsWith("@")) {
            explicit = true
            handle = handle.drop(1)
        }
        if (!HANDLE_RE.matches(handle)) return null
        return SwitchCommand(handle, explicit)
    }

    /** Whether a parsed switch is a command at all:
     *  explicit switches always are; a bare one only
     *  when its target is a registered account. */
    fun shouldIntercept(
        cmd: SwitchCommand, registeredHandles: Set<String>
    ): Boolean = cmd.explicit || registeredHandles.any {
        it.equals(cmd.handle, ignoreCase = true)
    }

    // ---------- the session pass plan ----------

    /** The session pass's actions (TaskWorker's
     *  account-session mode runs exactly one). */
    enum class SessionAction { SIGN_OUT, SWITCH_HER, SWITCH_HIS }

    /** Record field: this record exists to carry a
     *  spoken session command — the pass completes it
     *  when the command lands (a borrowed lane record
     *  is only paused, never completed by a command). */
    const val SESSION_COMMAND_FIELD = "sessionCommand"

    const val SESSION_RECORD_TITLE = "X session"

    /** The text a command-created record carries: what
     *  a handback resume should do with it. */
    fun sessionRecordText(targetHandle: String?): String =
        if (targetHandle.isNullOrEmpty()) {
            "Session command record: hold Lille's X " +
                "session state for spoken session commands."
        } else {
            "The user signed in to X in your browser to " +
                "complete a switch to @$targetHandle. Call " +
                "x_session_status to verify the session, " +
                "then finish."
        }

    /**
     * Where a session command runs, decided purely:
     * the record is this chat's newest live X record,
     * else the newest live X record anywhere, else a
     * fresh record is created for the command
     * ([createRecord]). A switch's action is HER when
     * the registry says the target is hers or the
     * handle is her verified one; otherwise HIS (the
     * pass signs out and parks for his login — his
     * password is never attempted).
     */
    data class SessionPlan(
        val recordId: String?,
        val createRecord: Boolean,
        val action: SessionAction,
        val targetHandle: String?,
        val reply: String
    )

    fun planLogOut(
        tasks: List<JSONObject>, chatId: String
    ): SessionPlan {
        val record = sessionRecordFor(tasks, chatId)
        return SessionPlan(
            recordId = record?.optString("id")
                ?.takeIf { it.isNotEmpty() },
            createRecord = record == null,
            action = SessionAction.SIGN_OUT,
            targetHandle = null,
            reply = "Logging out of X — she'll confirm " +
                "from the page."
        )
    }

    fun planSwitch(
        tasks: List<JSONObject>,
        registryRoot: JSONObject,
        chatId: String,
        cmd: SwitchCommand,
        herVerifiedHandle: String?
    ): SessionPlan {
        val entry = AccountsRegistry.find(
            registryRoot, AccountsRegistry.PLATFORM_X, cmd.handle
        )
        val hers = entry?.optString("owner") ==
            AccountsRegistry.OWNER_LILLE ||
            (herVerifiedHandle != null &&
                herVerifiedHandle.equals(cmd.handle, true))
        val record = sessionRecordFor(tasks, chatId)
        return SessionPlan(
            recordId = record?.optString("id")
                ?.takeIf { it.isNotEmpty() },
            createRecord = record == null,
            action = if (hers) {
                SessionAction.SWITCH_HER
            } else {
                SessionAction.SWITCH_HIS
            },
            targetHandle = cmd.handle,
            reply = if (hers) {
                "Switching to @${cmd.handle} — she'll " +
                    "confirm from the page."
            } else {
                "Switching to @${cmd.handle} — she'll " +
                    "sign out first, then the sign-in " +
                    "step is yours in her browser."
            }
        )
    }

    /** The record a session command rides: live
     *  (non-terminal, unarchived) X-domain records
     *  only — this chat's newest first, then any
     *  chat's. A terminal record is never borrowed:
     *  the command creates its own. */
    private fun sessionRecordFor(
        tasks: List<JSONObject>, chatId: String
    ): JSONObject? {
        val live = tasks.filter {
            isXDomainRecord(it) &&
                !TaskStates.isTerminal(it.optString("state")) &&
                !TaskControl.isArchived(it) &&
                !TaskControl.wasKilled(it)
        }
        if (live.isEmpty()) return null
        val byRecency = compareByDescending<JSONObject> {
            maxOf(it.optLong("createdAt"), it.optLong("updatedAt"))
        }
        return live.filter {
            it.optString(ChatTaskLink.ORIGIN_FIELD) == chatId
        }.maxWithOrNull(byRecency)
            ?: live.maxWithOrNull(byRecency)
    }

    /** The pure form of the runner's X-domain test:
     *  the record's own browser allowlist carries
     *  x.com. */
    fun isXDomainRecord(task: JSONObject): Boolean {
        val arr = task.optJSONArray("browserDomains")
            ?: return false
        for (i in 0 until arr.length()) {
            val d = arr.optString(i)
            if (d == "x.com" || d.endsWith(".x.com")) return true
        }
        return false
    }

    // ---------- the pass's deterministic lines ----------

    /** The line a finished sign-out pass lands in the
     *  chat — composed from the routine's verified
     *  outcome, never from its taps. */
    fun signOutLine(result: AccountSession.SignOutResult): String =
        when (result) {
            is AccountSession.SignOutResult.SignedOut ->
                "Logged out of X — verified from the " +
                    "page: the browser is signed out."
            is AccountSession.SignOutResult.AlreadySignedOut ->
                "Already signed out of X — verified " +
                    "from the page."
            is AccountSession.SignOutResult.Failed ->
                "The log-out didn't complete: it failed " +
                    "at ${result.step} (${result.reason}). " +
                    "Nothing was claimed over the page's " +
                    "own state."
        }

    /** The line a finished switch pass lands (the
     *  NeedsUserLogin case lands the park step
     *  instead — [switchParkStep]). */
    fun switchLine(
        result: AccountSession.SwitchResult, target: String
    ): String = when (result) {
        is AccountSession.SwitchResult.Switched ->
            "Switched to @${result.handle} — verified " +
                "from the page."
        is AccountSession.SwitchResult.AlreadyActive ->
            "Already signed in as @${result.handle} — " +
                "verified from the page."
        is AccountSession.SwitchResult.NeedsUserLogin ->
            "Signed out — the browser is ready for " +
                "your sign-in as @$target."
        is AccountSession.SwitchResult.Walled ->
            "The switch to @$target stopped at a " +
                "human check on X (${result.kind}) — " +
                "that step is yours in her browser."
        is AccountSession.SwitchResult.Failed ->
            "The switch to @$target didn't complete: " +
                "it failed at ${result.step} " +
                "(${result.reason}). No action ran " +
                "under the wrong account."
    }

    /** The park step a switch-to-his waits on: his
     *  login, in her panel, in the connect lane's own
     *  words — she never sees his password. */
    fun switchParkStep(target: String): String =
        "Log in to X as @$target in Lille's browser: " +
            "tap 'Open Lille's browser', sign in with " +
            "your OWN account, then tap 'Done — hand " +
            "back to Lille'. Lille never sees your password."

    /** The honest line when a session command names a
     *  record whose work is mid-run: the pass never
     *  races a running lane. */
    const val SESSION_BUSY_LINE =
        "That account work is running right now — the " +
            "session command waits for it to pause."

    // ---------- shared tokenising ----------

    /** The command grammars' tokeniser: the probe
     *  normalisation (lowercase, collapsed spaces,
     *  trailing sentence punctuation trimmed), commas
     *  as separators, a whole-message length cap. */
    private fun commandTokens(text: String): List<String>? {
        val norm = StatusProbe.normalise(text)
            .replace(",", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (norm.isEmpty() || norm.length > 64) return null
        return norm.split(" ")
    }
}
