package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup payload (v0.11, BACKUP.md) — the pure (de)serialiser
 * for the plaintext that BackupCrypto seals. No Android, no
 * I/O, pinned by JVM tests; BackupManager collects the live
 * snapshots, hands them here, and applies a Parsed on restore.
 *
 * Payload shape (one JSON document):
 * {
 *   "version": 1,
 *   "createdAt": ms,
 *   "username": "<fluid name>", "address": "0x…",
 *   "chats":    "<lille_chats.json text>"     (optional),
 *   "tasks":    "<lille_tasks.json text>"     (optional),
 *   "proactive":"<lille_proactive.json text>" (optional),
 *   "referrals":"<lille_referrals.json text>" (optional — merged,
 *                never overwritten, on restore: first referrer wins),
 *   "prefs":    {"<prefs file>": {"<key>": {"t":..,"v":..}}},
 *   "vault":    {"secrets": {"<key name>": "<secret value>"},
 *                "accounts": "<lille_accounts 'accounts' string>"},
 *   "sessions": {"<domain>": "<cookie string>"}  (opt-in only)
 * }
 *
 * The store snapshots ride as TEXT: the restore writes them
 * back byte-for-byte, so a backup is faithful even across
 * versions that changed the in-memory handling. Vault secret
 * VALUES appear only here, inside the encrypted payload —
 * this document is never stored or logged in the clear.
 *
 * Binding: a payload belongs to exactly one Fluid identity.
 * [belongsTo] is the restore gate — a backup made under one
 * wallet can only be restored while that same wallet's name
 * is the verified identity (and in signature mode the key
 * itself already enforces this cryptographically).
 */
object BackupPayload {

    const val PAYLOAD_VERSION = 1

    data class Parsed(
        val username: String,
        val address: String,
        val createdAt: Long,
        val chatsText: String?,
        val tasksText: String?,
        val proactiveText: String?,
        val prefs: Map<String, Map<String, Any?>>,
        val vaultSecrets: Map<String, String>,
        val vaultAccounts: String?,
        val sessions: Map<String, String>,
        val referralsText: String? = null,
        /** Her agent state (v1.0.4): memory, skills, site
         *  cards, the heartbeat checklist, recipes and
         *  scheduled jobs — one raw store document, riding
         *  the backup exactly like chats and tasks. Absent
         *  in older payloads (null = nothing to restore). */
        val agentStateText: String? = null,
        /** The accounts registry (v1.0.8): METADATA only
         *  — platform, handle, owner, scope, timestamps.
         *  No cookies, no credentials (her password stays
         *  in the vault; his are never stored). Merged,
         *  never overwritten, on restore — the local
         *  active-account truth stands. Absent in older
         *  payloads. */
        val accountsRegistryText: String? = null
    )

    fun build(
        username: String,
        address: String,
        createdAt: Long,
        chatsText: String?,
        tasksText: String?,
        proactiveText: String?,
        prefs: Map<String, Map<String, Any?>>,
        vaultSecrets: Map<String, String>,
        vaultAccounts: String?,
        sessions: Map<String, String>?,
        referralsText: String? = null,
        agentStateText: String? = null,
        accountsRegistryText: String? = null
    ): String {
        val root = JSONObject()
            .put("version", PAYLOAD_VERSION)
            .put("createdAt", createdAt)
            .put("username", username)
            .put("address", address)
        if (chatsText != null) root.put("chats", chatsText)
        if (tasksText != null) root.put("tasks", tasksText)
        if (proactiveText != null) root.put("proactive", proactiveText)
        if (referralsText != null) root.put("referrals", referralsText)
        if (agentStateText != null) root.put("agentState", agentStateText)
        if (accountsRegistryText != null) {
            root.put("accountRegistry", accountsRegistryText)
        }
        root.put("prefs", encodePrefs(prefs))
        val vault = JSONObject()
        val secrets = JSONObject()
        vaultSecrets.forEach { (k, v) -> secrets.put(k, v) }
        vault.put("secrets", secrets)
        if (vaultAccounts != null) vault.put("accounts", vaultAccounts)
        root.put("vault", vault)
        if (sessions != null) {
            val s = JSONObject()
            sessions.forEach { (k, v) -> s.put(k, v) }
            root.put("sessions", s)
        }
        return root.toString()
    }

    /** Parse a decrypted payload. Null when the document is
     *  not a recognisable Lille payload. */
    fun parse(payloadText: String): Parsed? {
        return try {
            val root = JSONObject(payloadText)
            if (root.optInt("version") != PAYLOAD_VERSION) return null
            val username = root.optString("username", "")
            val address = root.optString("address", "")
            if (username.isEmpty() || address.isEmpty()) return null
            val vault = root.optJSONObject("vault")
            val secrets = mutableMapOf<String, String>()
            vault?.optJSONObject("secrets")?.let { s ->
                val keys = s.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    secrets[k] = s.optString(k, "")
                }
            }
            val sessions = mutableMapOf<String, String>()
            root.optJSONObject("sessions")?.let { s ->
                val keys = s.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    sessions[k] = s.optString(k, "")
                }
            }
            Parsed(
                username = username,
                address = address,
                createdAt = root.optLong("createdAt", 0L),
                chatsText = root.optString("chats", "").takeIf { it.isNotEmpty() },
                tasksText = root.optString("tasks", "").takeIf { it.isNotEmpty() },
                proactiveText = root.optString("proactive", "").takeIf { it.isNotEmpty() },
                prefs = decodePrefs(root.optJSONObject("prefs") ?: JSONObject()),
                vaultSecrets = secrets,
                vaultAccounts = vault?.optString("accounts", "")?.takeIf { it.isNotEmpty() },
                sessions = sessions,
                referralsText = root.optString("referrals", "").takeIf { it.isNotEmpty() },
                agentStateText = root.optString("agentState", "").takeIf { it.isNotEmpty() },
                accountsRegistryText = root.optString("accountRegistry", "").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            null
        }
    }

    /** The restore binding: names compare case-insensitively
     *  (registry rules), addresses likewise (checksum casing). */
    fun belongsTo(parsed: Parsed, username: String, address: String): Boolean =
        parsed.username.equals(username.trim(), ignoreCase = true) &&
            parsed.address.equals(address.trim(), ignoreCase = true)

    // ---- SharedPreferences snapshots (typed, lossless) ----

    /** SharedPreferences values are String/Boolean/Int/Long/
     *  Float/Set<String>. Each rides tagged so restore puts
     *  back the exact type. Unknown types are dropped — a
     *  snapshot must never invent a value. */
    fun encodePrefs(prefs: Map<String, Map<String, Any?>>): JSONObject {
        val root = JSONObject()
        for ((file, entries) in prefs) {
            val fileObj = JSONObject()
            for ((key, value) in entries) {
                val tagged = when (value) {
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is Set<*> -> {
                        val arr = JSONArray()
                        value.forEach { if (it is String) arr.put(it) }
                        JSONObject().put("t", "ss").put("v", arr)
                    }
                    else -> null
                }
                if (tagged != null) fileObj.put(key, tagged)
            }
            root.put(file, fileObj)
        }
        return root
    }

    fun decodePrefs(root: JSONObject): Map<String, Map<String, Any?>> {
        val out = mutableMapOf<String, Map<String, Any?>>()
        val files = root.keys()
        while (files.hasNext()) {
            val file = files.next()
            val fileObj = root.optJSONObject(file) ?: continue
            val entries = mutableMapOf<String, Any?>()
            val keys = fileObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val tagged = fileObj.optJSONObject(key) ?: continue
                val value: Any? = when (tagged.optString("t")) {
                    "s" -> tagged.optString("v", "")
                    "b" -> tagged.optBoolean("v", false)
                    "i" -> tagged.optInt("v", 0)
                    "l" -> tagged.optLong("v", 0L)
                    "f" -> tagged.optDouble("v", 0.0).toFloat()
                    "ss" -> {
                        val arr = tagged.optJSONArray("v") ?: JSONArray()
                        val set = mutableSetOf<String>()
                        for (i in 0 until arr.length()) set.add(arr.optString(i, ""))
                        set
                    }
                    else -> null
                }
                if (value != null) entries[key] = value
            }
            out[file] = entries
        }
        return out
    }
}
