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

    /** Write the stamp: [handle] null = the page showed
     *  signed OUT (an explicit read, recorded as such). */
    fun stamp(task: JSONObject, handle: String?, now: Long) {
        task.put(AS_FIELD, handle ?: "")
        task.put(AT_FIELD, now)
    }

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
