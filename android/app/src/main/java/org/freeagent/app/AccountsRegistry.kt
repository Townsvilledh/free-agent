package org.freeagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * THE ACCOUNTS REGISTRY (v1.0.8, Ethuram 2026-11-27:
 * he plans to connect MORE THAN ONE of his own X
 * profiles, plus hers — "there should be a list of
 * accounts logged in so she can deal with them
 * separately").
 *
 * One durable entry per account she holds a session or
 * relationship with: {platform, handle, owner, scope,
 * addedAt, lastVerifiedAt, lastActiveAt, state,
 * active}. An account is registered when a sign-in is
 * VERIFIED — the handle read off the page by the lane,
 * never an anonymous "logged in" and never a name from
 * chat. Her own account registers at creation
 * verification; his register when a connect lane
 * verifies his sign-in.
 *
 * Ownership carries the scope, in plain words on every
 * surface: HERS (owner "lille") — she acts on her own
 * within policy; HIS (owner "user") — approval-gated,
 * always. His passwords are never stored anywhere; her
 * password lives only in the vault, as before. The
 * registry itself is METADATA — it rides the encrypted
 * backup as its own field, merged (never overwritten)
 * on restore, and holds no cookies and no credentials.
 *
 * ONE ACTIVE SESSION: the browser holds one active
 * account per platform, and the registry tracks which
 * entry that is. The identity precondition (the runner
 * enforces it before any account-scoped action — post,
 * read-as, profile change) reads the registry: the
 * active handle must equal the task's target handle,
 * or the lane switches first (a recorded step) or
 * stops and names the mismatch. No action ever runs
 * under the wrong account.
 *
 * Pure over the root document; [AccountRegistryStore]
 * owns the file. Pinned by Lille108Test.
 */
object AccountsRegistry {

    const val OWNER_LILLE = "lille"
    const val OWNER_USER = "user"

    const val STATE_SIGNED_IN = "signed_in"
    const val STATE_SIGNED_OUT = "signed_out"

    /** The platform key the X lanes register under. */
    const val PLATFORM_X = "x"

    fun parseRoot(text: String?): JSONObject {
        if (text.isNullOrBlank()) return JSONObject()
        return try {
            val root = JSONObject(text)
            if (root.optJSONArray("entries") == null) {
                root.put("entries", JSONArray())
            }
            root
        } catch (e: Exception) {
            JSONObject().put("entries", JSONArray())
        }
    }

    fun entries(root: JSONObject): List<JSONObject> {
        val arr = root.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun key(handle: String): String =
        handle.trim().removePrefix("@").lowercase()

    fun find(
        root: JSONObject, platform: String, handle: String
    ): JSONObject? = entries(root).firstOrNull {
        it.optString("platform") == platform &&
            key(it.optString("handle")) == key(handle)
    }

    fun activeEntry(
        root: JSONObject, platform: String
    ): JSONObject? = entries(root).firstOrNull {
        it.optString("platform") == platform &&
            it.optBoolean("active")
    }

    /** The scope an owner carries, as stored on the
     *  entry and shown in plain words at the surfaces. */
    fun scopeFor(owner: String): String =
        if (owner == OWNER_LILLE) "autonomous" else "approval"

    fun scopeWords(entry: JSONObject): String =
        if (entry.optString("owner") == OWNER_LILLE) {
            "she acts on her own, within policy"
        } else {
            "yours — she asks before acting"
        }

    fun ownerWords(entry: JSONObject): String =
        if (entry.optString("owner") == OWNER_LILLE) {
            "hers"
        } else {
            "yours"
        }

    /**
     * Register (or re-verify) an account from a
     * VERIFIED sign-in — the caller read the handle off
     * the page. First registration stamps addedAt; every
     * call refreshes lastVerifiedAt and the signed-in
     * state. Registration does NOT make the entry
     * active — [markActive] is the browser's truth, a
     * separate stamp.
     */
    fun register(
        root: JSONObject, platform: String, handle: String,
        owner: String, now: Long
    ): JSONObject {
        val clean = handle.trim().removePrefix("@")
        val existing = find(root, platform, clean)
        if (existing != null) {
            existing.put("lastVerifiedAt", now)
            existing.put("state", STATE_SIGNED_IN)
            // SESSION AS THE SAVED CREDENTIAL (v1.0.10):
            // a verified sign-in read means the live
            // browser session backs this entry — the
            // persistent profile IS the credential store
            // (his password was never captured; it never
            // left the site's page).
            existing.put("sessionBacked", true)
            existing.put("sessionExpired", false)
            return existing
        }
        val entry = JSONObject()
            .put("platform", platform)
            .put("handle", clean)
            .put("owner", owner)
            .put("scope", scopeFor(owner))
            .put("addedAt", now)
            .put("lastVerifiedAt", now)
            .put("lastActiveAt", 0L)
            .put("state", STATE_SIGNED_IN)
            .put("sessionBacked", true)
            .put("sessionExpired", false)
            .put("active", false)
        val arr = root.optJSONArray("entries") ?: JSONArray()
        arr.put(entry)
        root.put("entries", arr)
        return entry
    }

    /** Mark the platform's active account — the browser
     *  holds exactly one. Every other entry on the
     *  platform goes inactive in the same stamp, and the
     *  newly active entry's lastActiveAt moves. Returns
     *  the handle that was active before (for the
     *  switch event), or null. */
    fun markActive(
        root: JSONObject, platform: String, handle: String, now: Long
    ): String? {
        val previous = activeEntry(root, platform)
            ?.optString("handle")?.takeIf { it.isNotEmpty() }
        for (entry in entries(root)) {
            if (entry.optString("platform") != platform) continue
            val isTarget = key(entry.optString("handle")) == key(handle)
            if (!isTarget && entry.optBoolean("active") &&
                entry.optString("state") == STATE_SIGNED_IN
            ) {
                // The browser holds ONE session: an entry
                // losing the active mark has lost its live
                // session with it — expired, available
                // again only through a fresh sign-in.
                entry.put("sessionExpired", true)
                entry.put("sessionExpiredAt", now)
            }
            entry.put("active", isTarget)
            if (isTarget) {
                entry.put("lastActiveAt", now)
                entry.put("state", STATE_SIGNED_IN)
                entry.put("sessionExpired", false)
            }
        }
        return previous
    }

    /** The platform's browser signed out: the active
     *  entry keeps its registration, loses the active
     *  mark and the signed-in state — and its session
     *  is expired (v1.0.10): the next task for that
     *  account parks once for a fresh sign-in. */
    fun markSignedOut(
        root: JSONObject, platform: String, now: Long = 0L
    ) {
        for (entry in entries(root)) {
            if (entry.optString("platform") != platform) continue
            if (entry.optBoolean("active")) {
                entry.put("state", STATE_SIGNED_OUT)
                entry.put("sessionExpired", true)
                if (now > 0L) entry.put("sessionExpiredAt", now)
            }
            entry.put("active", false)
        }
    }

    /** True when [handle]'s entry is backed by the
     *  LIVE browser session right now: registered,
     *  active, signed in, session-backed and not
     *  expired. A task scoped to this account checks
     *  this FIRST (with the sense) — a live session
     *  means no login step and no ask. */
    fun sessionLive(
        root: JSONObject, platform: String, handle: String
    ): Boolean {
        val entry = find(root, platform, handle) ?: return false
        return entry.optBoolean("active") &&
            entry.optString("state") == STATE_SIGNED_IN &&
            entry.optBoolean("sessionBacked") &&
            !entry.optBoolean("sessionExpired")
    }

    /** Stamp that a task has parked for this
     *  account's fresh sign-in (v1.0.10, park ONCE):
     *  while one park stands for the current expiry,
     *  other tasks for the account wait instead of
     *  parking a second time. */
    fun noteSessionLoginPark(
        root: JSONObject, platform: String, handle: String,
        now: Long
    ) {
        find(root, platform, handle)
            ?.put("loginParkAt", now)
    }

    /** True when a login park already stands for
     *  this account's CURRENT expiry (a park stamped
     *  after the session expired). */
    fun sessionLoginParked(
        root: JSONObject, platform: String, handle: String
    ): Boolean {
        val entry = find(root, platform, handle) ?: return false
        val parkAt = entry.optLong("loginParkAt", 0L)
        return parkAt > 0L &&
            parkAt >= entry.optLong("sessionExpiredAt", 0L)
    }

    enum class Precondition {
        /** The active account IS the target — act. */
        MATCH,
        /** The target is registered but not active —
         *  switch first (a recorded step). */
        NEEDS_SWITCH,
        /** The target was never verified onto the
         *  registry — there is nothing to act as. */
        NOT_REGISTERED,
        /** The target is registered, nothing is active
         *  (signed out) — a sign-in comes first. */
        NO_ACTIVE
    }

    /** The identity precondition, read off the registry:
     *  may a lane act as [targetHandle] on [platform]
     *  right now? */
    fun precondition(
        root: JSONObject, platform: String, targetHandle: String
    ): Precondition {
        val target = find(root, platform, targetHandle)
            ?: return Precondition.NOT_REGISTERED
        val active = activeEntry(root, platform)
            ?: return Precondition.NO_ACTIVE
        return if (key(active.optString("handle")) == key(targetHandle)) {
            Precondition.MATCH
        } else {
            Precondition.NEEDS_SWITCH
        }
    }

    /** Merge a backup's registry into this one: entries
     *  join by (platform, handle); the newer verification
     *  wins the timestamps and state; the LOCAL active
     *  truth stands (a backup's active flag is a stale
     *  snapshot — the browser on this device decides).
     *  Returns how many entries were added. */
    fun mergeRoots(into: JSONObject, text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val other = parseRoot(text)
        var added = 0
        for (entry in entries(other)) {
            val platform = entry.optString("platform")
            val handle = entry.optString("handle")
            if (platform.isEmpty() || handle.isEmpty()) continue
            val local = find(into, platform, handle)
            if (local == null) {
                val copy = JSONObject(entry.toString())
                copy.put("active", false)
                val arr = into.optJSONArray("entries") ?: JSONArray()
                arr.put(copy)
                into.put("entries", arr)
                added++
            } else if (
                entry.optLong("lastVerifiedAt") >
                local.optLong("lastVerifiedAt")
            ) {
                local.put(
                    "lastVerifiedAt", entry.optLong("lastVerifiedAt")
                )
                local.put("state", entry.optString("state"))
                if (entry.optLong("lastActiveAt") >
                    local.optLong("lastActiveAt")
                ) {
                    local.put(
                        "lastActiveAt", entry.optLong("lastActiveAt")
                    )
                }
            }
        }
        return added
    }

    /** One line per entry — the shape the state sheet,
     *  the chat answer and the Settings list all share:
     *  handle, owner, the active mark, the scope in
     *  plain words. */
    fun summaryLines(root: JSONObject): List<String> =
        entries(root)
            .sortedWith(
                compareByDescending<JSONObject> { it.optBoolean("active") }
                    .thenBy { it.optString("platform") }
                    .thenBy { it.optString("handle") }
            )
            .map { entry ->
                val activeBit = if (entry.optBoolean("active")) {
                    " · active now"
                } else if (
                    entry.optString("state") == STATE_SIGNED_OUT
                ) {
                    " · signed out"
                } else {
                    ""
                }
                "@${entry.optString("handle")} — " +
                    ownerWords(entry) + activeBit + " · " +
                    scopeWords(entry)
            }

    /** The deterministic chat answer to the accounts
     *  family — which accounts she holds, whose they
     *  are, which is active — from the registry alone. */
    fun answerFor(root: JSONObject): String {
        val all = entries(root)
        if (all.isEmpty()) {
            return "No accounts are registered yet. An " +
                "account lands on her list when a sign-in " +
                "is verified from the page itself — hers " +
                "when her account is created, yours when " +
                "you connect one."
        }
        val lines = summaryLines(root)
        val active = all.firstOrNull { it.optBoolean("active") }
        val activeBit = if (active != null) {
            " The browser holds one active account at a " +
                "time — right now that's @" +
                active.optString("handle") + "."
        } else {
            " No account is active in the browser " +
                "right now."
        }
        return "She holds ${all.size} account" +
            (if (all.size == 1) "" else "s") + ": " +
            lines.joinToString("; ") + "." + activeBit
    }
}

/**
 * The registry's file: one JSON document in the app's
 * private storage (filesDir/lille_account_registry.json),
 * rewritten atomically on every change — the
 * ReferralStore discipline. Metadata only: no cookies,
 * no credentials, ever.
 */
class AccountRegistryStore(context: Context) {

    private val file =
        File(context.filesDir, "lille_account_registry.json")

    @Synchronized
    fun root(): JSONObject =
        AccountsRegistry.parseRoot(rawText())

    @Synchronized
    private fun writeRoot(root: JSONObject) {
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    fun rawText(): String? =
        if (file.exists()) {
            try { file.readText() } catch (e: Exception) { null }
        } else {
            null
        }

    @Synchronized
    fun mergeFrom(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val root = root()
        val added = AccountsRegistry.mergeRoots(root, text)
        if (added > 0) writeRoot(root)
        return added
    }

    /** A verified sign-in, from the lane that read the
     *  handle off the page: register + mark active in
     *  one write. Returns the previously active handle
     *  when the active account CHANGED (the switch the
     *  acting task records), else null. */
    @Synchronized
    fun recordVerifiedSignIn(
        platform: String, handle: String, owner: String, now: Long
    ): String? {
        val root = root()
        AccountsRegistry.register(root, platform, handle, owner, now)
        val previous = AccountsRegistry.markActive(
            root, platform, handle, now
        )
        writeRoot(root)
        return if (
            previous != null &&
            !previous.equals(handle, ignoreCase = true)
        ) {
            previous
        } else {
            null
        }
    }

    @Synchronized
    fun recordSignedOut(platform: String) {
        val root = root()
        AccountsRegistry.markSignedOut(
            root, platform, System.currentTimeMillis()
        )
        writeRoot(root)
    }

    /** Park-once bookkeeping (v1.0.10): a task parked
     *  for this account's fresh sign-in. */
    @Synchronized
    fun recordSessionLoginPark(
        platform: String, handle: String, now: Long
    ) {
        val root = root()
        AccountsRegistry.noteSessionLoginPark(
            root, platform, handle, now
        )
        writeRoot(root)
    }
}
