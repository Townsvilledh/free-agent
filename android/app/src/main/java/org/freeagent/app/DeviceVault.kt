package org.freeagent.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * On-device vault (v0.9) — the device-lane mirror of Lille's
 * encrypted core vault (free_agent/vault.py).
 *
 * Secrets live in EncryptedSharedPreferences, whose master key
 * is wrapped by the Android Keystore — the same store APP.md's
 * security map already designates for the app's vault. The
 * discipline is identical to the host lane:
 *
 * - genSecret() generates a strong password and stores it under
 *   a KEY NAME. It returns only success/failure — the value is
 *   never returned to the caller, never shown to the model,
 *   never written to a task record, trace, or log.
 * - getSecret() is called ONLY by the browser driver at the
 *   moment of typing (browser_type_secret). The value crosses
 *   from here into the WebView field and nowhere else.
 * - recordAccount() stores account METADATA (site, username,
 *   vault key name) in a separate, non-secret store — the same
 *   shape as the core's accounts.json.
 */
class DeviceVault(context: Context) {

    private val appContext = context.applicationContext

    private fun secrets(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            "lille_device_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun accounts(): SharedPreferences =
        appContext.getSharedPreferences("lille_accounts", Context.MODE_PRIVATE)

    companion object {
        private val KEY_RE = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private const val ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*-_=+"

        fun validKeyName(key: String): Boolean = KEY_RE.matches(key)
    }

    /** Generate + store a secret under `key`. Returns true on
     *  success. The VALUE is never returned — callers (and the
     *  model) only ever hold the key name. */
    fun genSecret(key: String, length: Int = 24): Boolean {
        if (!validKeyName(key)) return false
        val n = length.coerceIn(12, 64)
        val rnd = SecureRandom()
        val value = buildString {
            repeat(n) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        }
        return try {
            secrets().edit().putString(key, value).commit()
        } catch (e: Exception) {
            false
        }
    }

    /** Store a caller-generated secret under `key` (v0.12).
     *  Exists for exactly one caller class: the agent wallet's
     *  key custody (AgentWallet), which generates Lille's own
     *  EVM private key on-device and keeps it here — the same
     *  store, the same discipline. The value is never returned
     *  by this call, never shown to the model, never logged.
     *  Returns true on success. */
    fun putSecret(key: String, value: String): Boolean {
        if (!validKeyName(key)) return false
        return try {
            secrets().edit().putString(key, value).commit()
        } catch (e: Exception) {
            false
        }
    }

    /** Driver-only: resolve a key name to its value at the moment
     *  of typing. Never called by the model layer, never logged. */
    fun getSecret(key: String): String? {
        if (!validKeyName(key)) return null
        return try {
            secrets().getString(key, null)
        } catch (e: Exception) {
            null
        }
    }

    fun hasSecret(key: String): Boolean {
        if (!validKeyName(key)) return false
        return try {
            secrets().contains(key)
        } catch (e: Exception) {
            false
        }
    }

    // ---- backup export / import (v0.11, BACKUP.md) ----
    //
    // These are the ONLY other callers allowed to read secret
    // values besides the browser driver, and they exist for
    // exactly one purpose: the values cross directly into the
    // ENCRYPTED backup payload (BackupManager seals them with
    // BackupCrypto before anything is stored or sent anywhere)
    // or back out of one on restore. They are never called by
    // the model layer, never logged, and the payload they feed
    // is never written anywhere in the clear.

    /** Every secret as key name → value, for the encrypted
     *  backup payload. Empty when the vault cannot be opened. */
    fun exportSecrets(): Map<String, String> {
        return try {
            val out = mutableMapOf<String, String>()
            for ((k, v) in secrets().all) {
                if (v is String) out[k] = v
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Restore secrets from a decrypted backup payload. */
    fun importSecrets(values: Map<String, String>): Boolean {
        return try {
            val edit = secrets().edit()
            for ((k, v) in values) {
                if (validKeyName(k)) edit.putString(k, v)
            }
            edit.commit()
        } catch (e: Exception) {
            false
        }
    }

    /** The account-metadata array (the "accounts" string of
     *  the non-secret store), for the backup payload. */
    fun exportAccountsJson(): String? =
        try { accounts().getString("accounts", null) } catch (e: Exception) { null }

    fun importAccountsJson(json: String): Boolean =
        try {
            JSONArray(json) // must at least be a JSON array
            accounts().edit().putString("accounts", json).commit()
        } catch (e: Exception) {
            false
        }

    /** Account metadata only — no secret value ever lands here. */
    fun recordAccount(site: String, username: String, vaultKey: String): Boolean {
        if (!validKeyName(vaultKey)) return false
        return try {
            val prefs = accounts()
            val arr = JSONArray(prefs.getString("accounts", "[]"))
            arr.put(
                JSONObject()
                    .put("site", site)
                    .put("username", username)
                    .put("vault_key", vaultKey)
                    .put("created_at", System.currentTimeMillis())
            )
            prefs.edit().putString("accounts", arr.toString()).commit()
        } catch (e: Exception) {
            false
        }
    }
}
