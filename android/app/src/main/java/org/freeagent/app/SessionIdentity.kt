package org.freeagent.app

import org.json.JSONObject

/**
 * THE SESSION IDENTITY STAMP (v1.0.8, born of the
 * 2026-11-27 connect screenshots): Ethuram logged in
 * with his OWN account in seconds through the parked
 * panel and handed back — and in chat she answered "I
 * can't tell from here whether you're logged in. The
 * earlier messages don't reliably confirm the account
 * or its login status." She had no idea who was signed
 * into her own browser, and could not tell his account
 * from hers.
 *
 * So the browser lane now READS the signed-in identity
 * from the page state and stamps it on the record:
 * at every lane start/resume and after every handback,
 * the runner reads the signed-in handle from the
 * account UI (for X) and writes [AS_FIELD] — the
 * handle, or empty for an explicit signed-out read —
 * with [AT_FIELD] as the check time. An absent stamp
 * means "never checked", which is a different fact
 * from signed out, and the surfaces keep them apart.
 *
 * Login-state questions are answered from this stamp
 * (StatusProbe's login family), the state sheet
 * carries it, and the account-separation rules read
 * it: her creation lane asserts whose session it is
 * before acting, and no account action runs under a
 * stamp that names the wrong owner.
 *
 * Pure over the record; pinned by Lille108Test.
 */
object SessionIdentity {

    /** The signed-in handle read from the page, or ""
     *  for an explicit signed-out read. Absent = never
     *  checked. The VALUE is a public handle, never a
     *  credential. */
    const val AS_FIELD = "signedInAs"

    /** When the identity was last read from the page. */
    const val AT_FIELD = "signedInAt"

    /** When a handback's verification was announced in
     *  the originating chat — the dedup stamp (one
     *  announcement per handback, never a stack). */
    const val HANDBACK_ANNOUNCED_FIELD = "handbackAnnouncedAt"

    /** When a handback's verification last ran. */
    const val HANDBACK_VERIFIED_FIELD = "handbackVerifiedAt"

    /** The reason the latest identity read could NOT
     *  conclude (v1.0.9): the page showed neither a
     *  readable handle nor a sign-in form, or the
     *  account menu opened without one. A read that
     *  can't conclude stamps THIS — never a recycled
     *  signed-in/signed-out claim. */
    const val UNREADABLE_FIELD = "identityUnreadable"

    /** When the unreadable read happened. */
    const val UNREADABLE_AT_FIELD = "identityUnreadableAt"

    /** A live re-check request (v1.0.9): stamped by the
     *  chat when a login/which-account answer needs a
     *  fresh read, served by the runner's recheck pass.
     *  A concluded read (stamp or unreadable) at a later
     *  time satisfies it. */
    const val RECHECK_AT_FIELD = "identityRecheckAt"

    /** The chat a re-check's verified answer lands in. */
    const val RECHECK_CHAT_FIELD = "identityRecheckChat"

    /** When the user last took the browser over in the
     *  panel (v1.0.9) — browser activity the identity
     *  stamp must postdate to be quoted as current. */
    const val TAKEOVER_AT_FIELD = "takeoverAt"

    /** How long an unreadable conclusion rests before
     *  the watchdog retries the read on its own. */
    const val UNREADABLE_RETRY_MS = 300_000L

    /** Write the stamp: [handle] null = the page showed
     *  signed OUT (an explicit read, recorded as such).
     *  A concluded read supersedes an unreadable mark
     *  and satisfies a pending re-check request. */
    fun stamp(task: JSONObject, handle: String?, now: Long) {
        task.put(AS_FIELD, handle ?: "")
        task.put(AT_FIELD, now)
        task.remove(UNREADABLE_FIELD)
        task.remove(UNREADABLE_AT_FIELD)
        task.remove(RECHECK_AT_FIELD)
    }

    /** Stamp a read that could NOT conclude: the
     *  identity stamp itself is left untouched (its age
     *  is exactly why it may not be quoted), and this
     *  mark — newer than the stamp — is the record's
     *  current truth about the sign-in: unknown, being
     *  re-checked. Satisfies a re-check request (the
     *  request asked for a read; this WAS the read). */
    fun stampUnreadable(task: JSONObject, reason: String, now: Long) {
        task.put(UNREADABLE_FIELD, reason)
        task.put(UNREADABLE_AT_FIELD, now)
        task.remove(RECHECK_AT_FIELD)
    }

    /** The unreadable reason when the unreadable mark is
     *  the record's LATEST identity event (newer than
     *  the identity stamp); null when a concluded read
     *  is the latest word. */
    fun unreadableReason(task: JSONObject): String? {
        val at = task.optLong(UNREADABLE_AT_FIELD, 0L)
        if (at <= 0L || at < task.optLong(AT_FIELD, 0L)) return null
        return task.optString(UNREADABLE_FIELD)
            .ifEmpty { "the page didn't show the sign-in" }
    }

    /** When the identity was last READ, whichever way
     *  the read landed (a conclusion or an unreadable). */
    fun concludedAt(task: JSONObject): Long = maxOf(
        task.optLong(AT_FIELD, 0L),
        task.optLong(UNREADABLE_AT_FIELD, 0L)
    )

    /** True when a re-check was requested after the
     *  last read landed — the request is still owed. */
    fun recheckRequested(task: JSONObject): Boolean {
        val at = task.optLong(RECHECK_AT_FIELD, 0L)
        return at > 0L && at > concludedAt(task)
    }

    // ---------- freshness (v1.0.9) ----------

    /**
     * THE FRESHNESS RULE (v1.0.9, born of the 2026-11-27
     * 14:29 screenshot): asked "which x account is
     * logged in", she quoted a stamp that read signed
     * OUT — while her own browser panel sat open on a
     * signed-in home feed, because he had signed in
     * during a takeover after the stamp was written.
     * A stamp is quotable as current ONLY when it
     * postdates the browser lane's last activity: page
     * changes (the session's own marker), takeover
     * starts, and handbacks. Anything older is history,
     * and the answer must come from a fresh read.
     */

    /** The browser lane's last activity across the
     *  records plus the live session's marker: the
     *  newest of every handback, every takeover start,
     *  and the session's last page activity. */
    fun lastBrowserActivity(
        tasks: List<JSONObject>, sessionActivityAt: Long
    ): Long {
        var latest = sessionActivityAt
        for (t in tasks) {
            latest = maxOf(latest, t.optLong("handedBackAt", 0L))
            latest = maxOf(latest, t.optLong(TAKEOVER_AT_FIELD, 0L))
        }
        return latest
    }

    /** True when the record's identity stamp predates
     *  the browser lane's last activity — the browser
     *  has moved since the read, so the stamp is not
     *  current fact. */
    fun stampStale(task: JSONObject, lastActivityAt: Long): Boolean =
        checked(task) &&
            task.optLong(AT_FIELD, 0L) < lastActivityAt

    /** True when a login-state answer for this record
     *  must come from a LIVE read, not the stamp: never
     *  read, stamp older than the browser's last
     *  activity, a handback verification still pending,
     *  the latest read unreadable, or a re-check already
     *  requested and still owed. */
    fun needsLiveRead(task: JSONObject, lastActivityAt: Long): Boolean =
        !checked(task) ||
            stampStale(task, lastActivityAt) ||
            handbackPending(task) ||
            unreadableReason(task) != null ||
            recheckRequested(task)

    /** The stamped handle, or null when the stamp says
     *  signed out OR was never written — tell them apart
     *  with [checked]. */
    fun signedInAs(task: JSONObject): String? =
        task.optString(AS_FIELD).trim().ifEmpty { null }

    /** True when the identity has been read at least
     *  once (the stamp exists, whichever way it read). */
    fun checked(task: JSONObject): Boolean = task.has(AT_FIELD)

    /** True when a handback is waiting on its
     *  verification: handed back after the wall, not yet
     *  verified for that handback. */
    fun handbackPending(task: JSONObject): Boolean {
        val handedBackAt = task.optLong("handedBackAt", 0L)
        return handedBackAt > 0L &&
            task.optLong(HANDBACK_VERIFIED_FIELD, 0L) < handedBackAt
    }

    /** The clock label of the last identity check, or
     *  null when never checked. */
    fun checkedLabel(task: JSONObject): String? {
        val at = task.optLong(AT_FIELD, 0L)
        return if (at > 0L) TaskTransparency.clockLabel(at) else null
    }

    // ---------- account separation ----------

    /**
     * Whose session the stamp describes, for HER
     * creation lane — the separation the lane asserts
     * before acting:
     * - [SELF]: signed in as her own verified/recorded
     *   handle — her account's session, hers to use.
     * - [OTHER]: signed in as a handle known to belong
     *   to someone else (the user's own connected
     *   handle, or a handle contradicting her recorded
     *   one) — never hers to act under.
     * - [UNKNOWN]: signed in as a handle nothing on
     *   record attributes — mid-creation it may be the
     *   account she is in the middle of creating; the
     *   lane proceeds with the caution note, and the
     *   attribution is settled by vault_record_account.
     * - [SIGNED_OUT] / [UNCHECKED]: no session / no read.
     */
    enum class Owner { SELF, OTHER, UNKNOWN, SIGNED_OUT, UNCHECKED }

    fun ownerFor(
        task: JSONObject, hisHandle: String?
    ): Owner {
        if (!checked(task)) return Owner.UNCHECKED
        val signed = signedInAs(task) ?: return Owner.SIGNED_OUT
        val her = task.optString(FinishGate.HER_HANDLE_FIELD).trim()
        val session = FinishGate.sessionHandle(task)
        return when {
            her.isNotEmpty() && her.equals(signed, true) ->
                Owner.SELF
            session != null && session.equals(signed, true) ->
                Owner.SELF
            her.isNotEmpty() -> Owner.OTHER
            hisHandle != null &&
                hisHandle.equals(signed, true) -> Owner.OTHER
            else -> Owner.UNKNOWN
        }
    }

    /** The work-terms line naming a foreign session on
     *  her creation lane: whose it is, and that it is
     *  not hers — the sentence the status, the sheet and
     *  the model instruction all share. */
    fun foreignSessionLine(handle: String): String =
        "the browser is signed in as @$handle — your " +
            "account, not hers"

    /** The model-facing identity instruction for a
     *  foreign session on the creation lane: never act
     *  as that account; sign out first, as a recorded
     *  step, then create hers signed-out. */
    fun foreignSessionInstruction(handle: String): String =
        "IDENTITY CHECK: the browser is signed in as " +
            "@$handle — the USER's X account, not yours. " +
            "You must not post, edit, message or act as " +
            "that account. First sign out (account menu → " +
            "Log out), as a recorded step, then begin your " +
            "own signup signed out."

    /** The model-facing caution for an unattributed
     *  session on the creation lane: it may be the
     *  account this very task is creating — carry on
     *  and let vault_record_account settle it — but if
     *  it is not hers, the same rule applies. */
    fun unknownSessionInstruction(handle: String): String =
        "IDENTITY CHECK: the browser is signed in as " +
            "@$handle. If that is the account YOU are " +
            "creating in this task, carry on — and record " +
            "it with vault_record_account when the " +
            "profile is set. If it is NOT yours, do not " +
            "act as it: sign out first, then begin your " +
            "signup signed out."

    /** The model-facing instruction when a lane-start
     *  read could not conclude (v1.0.9): no account may
     *  be acted under until a read concludes — an
     *  unreadable page is not a signed-out page and not
     *  a signed-in one. */
    fun unreadableInstruction(reason: String): String =
        "IDENTITY CHECK: the sign-in read just now could " +
            "not read the page ($reason). Do not act as " +
            "any account and do not assume the sign-in " +
            "either way — a re-check runs on its own, and " +
            "the next read decides."

    /** The deterministic line a handback's verification
     *  lands when the read could not conclude (v1.0.9):
     *  the step came back, the check ran, the page
     *  wouldn't say — and the re-check is already owed,
     *  so nobody has to ask again. */
    fun handbackUnreadableLine(task: JSONObject, reason: String): String {
        val title = TaskTransparency.titleFor(task)
        return "She checked the browser as it came back, " +
            "and the sign-in couldn't be read from the " +
            "page ($reason). “$title” isn't acting on any " +
            "account until a read concludes — the check " +
            "retries on its own."
    }

    // ---------- the handback announcement ----------

    /**
     * The deterministic announcement a handback's
     * verification lands in the originating chat —
     * composed from what the page actually showed when
     * the browser came back, never from the user's say-so
     * and never from a model: signed in (as whom), still
     * signed out (the step is still his), or the human
     * check's standing. One per handback (the caller
     * dedups on [HANDBACK_ANNOUNCED_FIELD]).
     */
    fun handbackAnnouncement(
        task: JSONObject,
        signedInAs: String?,
        challengePresent: Boolean
    ): String {
        val kind = task.optString("wallKind")
        val title = TaskTransparency.titleFor(task)
        if (kind == UserHandoff.WALL_KIND) {
            return if (signedInAs != null) {
                "Signed in as @$signedInAs — verified from " +
                    "the page. “$title” is continuing from there."
            } else {
                "Still signed out — the sign-in step is " +
                    "still yours whenever you're ready; " +
                    "“$title” hasn't moved past it."
            }
        }
        return when {
            challengePresent ->
                "The human check is still on screen — that " +
                    "step is still yours; “$title” is " +
                    "waiting on it."
            signedInAs != null ->
                "Check passed — signed in as @$signedInAs, " +
                    "verified from the page. “$title” is " +
                    "continuing from there."
            else ->
                "That step is done — verified from the " +
                    "page. “$title” is continuing from there."
        }
    }
}
