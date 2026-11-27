package org.freeagent.app


import org.json.JSONObject


/**
 * CONTINUOUS SESSION AWARENESS (v1.0.10) — the
 * browser's sign-in state is a SENSE, not a
 * checkpoint.
 *
 * Born of 2026-11-27 ~16:32 (Ethuram, stated as a
 * principle): "she must be fully aware of her own
 * system, continuously — basic reasoning, not a
 * feature." The proof of the gap: his Connect
 * sign-in was never read back, so the accounts
 * registry — her supposed awareness — stood EMPTY
 * about the live session while the browser sat
 * signed in as him. The registry is a book; a book
 * is not awareness. Awareness is refreshed by the
 * system's own motion:
 *
 * - PAGE SETTLE: every collected page on a platform
 *   domain is sensed (BrowserSession.collect feeds
 *   [sensePageSettled]) — where the settled page
 *   itself derives the session (the session's own
 *   chrome names the handle; sign-in chrome alone
 *   is signed out), the sense updates with no task
 *   running and nobody asking.
 * - READS: every concluding identity read (the
 *   navigation proof, [XIdentityRead]) is also a
 *   sense update, via the runner's registry sync.
 * - PANEL OPEN / APP OPEN: the last settled state
 *   is re-sensed, and records whose stamps fail the
 *   freshness rule get a re-check enqueued
 *   ([appOpenSweep], also run by the watchdog —
 *   the heartbeat's on-device form).
 * - HANDBACK: the verification read (unchanged
 *   machinery, navigation-based now).
 *
 * From the sense, automatically: the registry
 * syncs (a found sign-in registers + activates,
 * backed by the live session; a found sign-out
 * clears the active entry), a record whose stamp
 * CONTRADICTS the sensed reality is corrected on
 * the spot with an "identity_sensed" event —
 * reality outranks stamps — and a sign-in /
 * sign-out / switch is ANNOUNCED unprompted in the
 * chat the browser work belongs to ("Signed in as
 * @…." / "Signed out of X." / "Switched to @…."),
 * or held as a pending notification for the next
 * open when no chat owns the work.
 *
 * One soundness rule: a profile URL alone never
 * senses a sign-in. Resting on x.com/<someone> is
 * VIEWING a page, not holding a session — the
 * handle is derived only from the session's own
 * furniture (the profile tab's target, the account
 * switcher's text). The navigation read's landed
 * URL is a different mechanism (X's own-profile
 * route chose that URL for this session) and is
 * not this layer's business.
 *
 * The sense document is metadata only: platform,
 * handle, time, page, source. No cookies, no
 * credentials, ever.
 *
 * Pure over the sense document where possible;
 * [SessionSenseStore] owns the file, the glue
 * functions own the effects. Pinned by Lille110Test.
 */
object SessionSense {

    const val SOURCE_PAGE = "page"
    const val SOURCE_READ = "read"

    fun parseRoot(text: String?): JSONObject {
        if (text.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun sense(root: JSONObject, platform: String): JSONObject? =
        root.optJSONObject("senses")?.optJSONObject(platform)

    fun checked(root: JSONObject, platform: String): Boolean =
        sense(root, platform)?.optBoolean("checked") == true

    /** The sensed handle, or null when the sense says
     *  signed out (or was never sensed — tell them
     *  apart with [checked]). */
    fun handle(root: JSONObject, platform: String): String? =
        sense(root, platform)?.optString("handle")
            ?.trim()?.ifEmpty { null }

    fun sensedAt(root: JSONObject, platform: String): Long =
        sense(root, platform)?.optLong("at", 0L) ?: 0L

    fun platformLabel(platform: String): String =
        if (platform == AccountsRegistry.PLATFORM_X) "X" else platform

    /**
     * Derive the session from a settled page — ONLY
     * where the page itself testifies: the session's
     * own chrome names its handle; sign-in chrome
     * with no session furniture is a signed-out page.
     * Anything else derives NOTHING (null) — the
     * sense keeps its last conclusion rather than
     * guessing from a page that doesn't say.
     */
    fun deriveFromPage(
        state: BrowserPerception.PageState
    ): XIdentityRead.Outcome? {
        BrowserPerception.xHandleFromState(state)?.let {
            return XIdentityRead.Outcome.SignedIn(it)
        }
        return if (
            BrowserPerception.xIdentityShape(state, null) ==
            BrowserPerception.XIdentityShape.SIGNED_OUT
        ) {
            XIdentityRead.Outcome.SignedOut
        } else {
            null
        }
    }

    private fun sameHandle(a: String?, b: String?): Boolean =
        (a ?: "").equals(b ?: "", ignoreCase = true)

    /** Apply a conclusion to the sense document.
     *  Returns true when the CONCLUSION changed
     *  (first sense, sign-in, sign-out, switch) —
     *  the change is what fires the registry sync,
     *  the contradiction pass and the notification.
     *  The sensed-at time and page always refresh. */
    fun applyConclusion(
        root: JSONObject, platform: String,
        handle: String?, url: String, source: String,
        now: Long
    ): Boolean {
        val senses = root.optJSONObject("senses")
            ?: JSONObject().also { root.put("senses", it) }
        val cur = senses.optJSONObject(platform)
        val prevChecked = cur?.optBoolean("checked") == true
        val prevHandle = if (prevChecked) {
            cur?.optString("handle")?.ifEmpty { null }
        } else {
            null
        }
        val changed = !prevChecked || !sameHandle(prevHandle, handle)
        senses.put(
            platform,
            JSONObject()
                .put("checked", true)
                .put("handle", handle ?: "")
                .put("at", now)
                .put("url", url)
                .put("source", source)
        )
        return changed
    }

    /** Whose account a sensed handle is: hers when it
     *  matches a handle already verified as hers,
     *  otherwise the user's (a session she found is
     *  presumed his until her own record claims it —
     *  the creation lane's attribution settles hers
     *  through its own stamps). */
    fun ownerForHandle(
        handle: String, herHandles: Set<String>
    ): String =
        if (herHandles.any { it.equals(handle, true) }) {
            AccountsRegistry.OWNER_LILLE
        } else {
            AccountsRegistry.OWNER_USER
        }

    /** The unprompted announcement for a sensed
     *  change — the 1.0.8 handback announcement,
     *  generalised to ANY sensed session change.
     *  Null when nothing announceable changed (a
     *  first sense of signed-out is standing state,
     *  not news). */
    fun notificationFor(
        platform: String, prevChecked: Boolean,
        prevHandle: String?, newHandle: String?
    ): String? {
        if (newHandle != null) {
            return when {
                !prevChecked || prevHandle == null ->
                    "Signed in as @$newHandle."
                !prevHandle.equals(newHandle, true) ->
                    "Switched to @$newHandle."
                else -> null
            }
        }
        return if (prevChecked && prevHandle != null) {
            "Signed out of ${platformLabel(platform)}."
        } else {
            null
        }
    }

    /** The state sheet's sense line: the browser
     *  session as last sensed, WITH its sensed-at
     *  time — an answer can say how fresh the fact
     *  is, and a stale sense never poses as current
     *  (the freshness rule governs its use). */
    fun senseLine(root: JSONObject, platform: String): String? {
        val s = sense(root, platform) ?: return null
        if (!s.optBoolean("checked")) return null
        val label = TaskTransparency.clockLabel(s.optLong("at", 0L))
            ?: return null
        val h = s.optString("handle")
        return if (h.isNotEmpty()) {
            "Her ${platformLabel(platform)} session " +
                "(sensed $label): signed in as @$h."
        } else {
            "Her ${platformLabel(platform)} session " +
                "(sensed $label): signed out."
        }
    }

    /** A fresh sensed conclusion, when the sense
     *  postdates the browser lane's last activity —
     *  the only condition under which a sense may be
     *  quoted as the current sign-in (the 1.0.9
     *  freshness rule, applied to the sense itself). */
    data class Fresh(val handle: String?)

    fun freshConclusion(
        root: JSONObject, platform: String,
        tasks: List<JSONObject>, sessionActivityAt: Long
    ): Fresh? {
        if (!checked(root, platform)) return null
        val activity = SessionIdentity.lastBrowserActivity(
            tasks, sessionActivityAt
        )
        if (sensedAt(root, platform) < activity) return null
        return Fresh(handle(root, platform))
    }

    /** Records whose identity stamp the sensed
     *  reality contradicts: stamped, not terminal,
     *  not archived/killed, on an X-domain record,
     *  concluded no later than the sense, and naming
     *  a different sign-in. Reality corrects these
     *  on the spot (the caller stamps + events). */
    fun contradictions(
        tasks: List<JSONObject>, handle: String?, senseAt: Long
    ): List<JSONObject> = tasks.filter {
        BrowserControl.isXDomainRecord(it) &&
            !TaskStates.isTerminal(it.optString("state")) &&
            !TaskControl.isArchived(it) &&
            !TaskControl.wasKilled(it) &&
            SessionIdentity.checked(it) &&
            SessionIdentity.concludedAt(it) <= senseAt &&
            !sameHandle(SessionIdentity.signedInAs(it), handle)
    }

    /** The app-open / heartbeat sweep's selection:
     *  live X records whose identity needs a live
     *  re-read under the freshness rule (never
     *  checked, stale, handback pending, unreadable,
     *  or a re-check already owed). */
    fun recheckTaskIds(
        tasks: List<JSONObject>, sessionActivityAt: Long
    ): List<String> {
        val activity = SessionIdentity.lastBrowserActivity(
            tasks, sessionActivityAt
        )
        return tasks.filter {
            BrowserControl.isXDomainRecord(it) &&
                !TaskStates.isTerminal(it.optString("state")) &&
                !TaskControl.isArchived(it) &&
                !TaskControl.wasKilled(it) &&
                SessionIdentity.needsLiveRead(it, activity)
        }.map { it.optString("id") }.filter { it.isNotEmpty() }
    }
}
