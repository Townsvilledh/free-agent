package org.freeagent.app

import org.json.JSONObject

/**
 * Lille's OWN X account — the created-state (2026-11-26,
 * state-driven owner action ruling).
 *
 * The owner's X action in the main chat is DERIVED from
 * the record, never toggled by hand:
 *
 * - No verified account on record → the action is
 *   "Create Lille's X account" (XAccountTask).
 * - Creation VERIFIED — the creation task completed with
 *   her handle recorded as evidence (xHandle, written by
 *   the runner's x_session_status) — → the action becomes
 *   "Connect Lille's X account" and Create never shows
 *   again.
 *
 * The state lives in the task record, which rides the
 * encrypted backup inside the payload's tasks snapshot —
 * so a restored copy or a new phone derives Connect, not
 * Create, with no extra machinery. Her password never
 * lives here: it exists only in the encrypted vault
 * (vault key [X_PASSWORD_VAULT_KEY], written by
 * vault_gen_secret during creation, typed only via
 * browser_type_secret), and the vault rides the same
 * backup — so Connect can sign her back in from the
 * vault credential, parking only for X's own checks.
 *
 * Pure over the task list + the vault-accounts text, so
 * the whole chain (creation record → backup → restore →
 * Connect offered + credential present) is pinned by
 * LilleXStateTest at the BackupPayload level.
 */
object LilleXState {

    /** The vault key her X password lives under — the
     *  creation task text names it (vault_gen_secret /
     *  browser_type_secret / vault_record_account). The
     *  VALUE is never read by this component. */
    const val X_PASSWORD_VAULT_KEY = "x_password"

    /** True when [task] is Lille's creation task, under
     *  either its current title or the shipped legacy one
     *  ("Lille: create my X account" — records on phones
     *  from the shipped build carry it). */
    fun isCreationTask(task: JSONObject): Boolean {
        val title = task.optString("title")
        return title == XAccountTask.TITLE ||
            title == XAccountTask.LEGACY_TITLE
    }

    /**
     * Her verified handle, or null when no verified
     * account is on record. Verification is graded
     * evidence, not narration: a creation task in state
     * COMPLETED with a non-empty recorded xHandle.
     */
    fun verifiedHandle(tasks: List<JSONObject>): String? {
        for (task in tasks) {
            if (!isCreationTask(task)) continue
            if (task.optString("state") != TaskStates.COMPLETED) continue
            val handle = task.optString("xHandle").trim()
            if (handle.isNotEmpty()) return handle
        }
        return null
    }

    fun hasVerifiedAccount(tasks: List<JSONObject>): Boolean =
        verifiedHandle(tasks) != null

    /**
     * Her handle from the vault-accounts text (the
     * accounts array DeviceVault exports — the string that
     * rides the backup alongside the secrets), or null.
     * The vault_record_account evidence written at
     * creation: a second, independent witness next to the
     * task record. Metadata only — no secret value ever
     * lives in the accounts array.
     */
    fun accountsRecordHandle(accountsText: String?): String? {
        if (accountsText.isNullOrBlank()) return null
        return try {
            val accounts = org.json.JSONArray(accountsText)
            for (i in 0 until accounts.length()) {
                val a = accounts.getJSONObject(i)
                if (a.optString("site") == "x.com") {
                    val user = a.optString("username").trim()
                    if (user.isNotEmpty()) return user
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
