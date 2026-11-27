package org.freeagent.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Stored Fluid identity (v0.10) — the verified result of the
 * Fluid login gate (LOGIN.md): the Fluid username the user
 * typed and the EVM address the registry + WalletConnect
 * session jointly proved owns it.
 *
 * This is NOT a secret: a Fluid name and its published address
 * are public registry data, so plain SharedPreferences are the
 * right store (the encrypted DeviceVault is for secrets, and
 * no secret ever lands here). The WalletConnect session itself
 * is persisted by the WC SDK in its own storage.
 *
 * A "pending" record bridges the approval gap: the user taps
 * Connect, switches to Fluid to approve, and Android may kill
 * the login screen meanwhile. The pending name + expected
 * address let any later screen complete the verification from
 * the live session alone (FluidLogin.reconcilePending).
 */
class FluidIdentityStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            "lille_fluid_identity", Context.MODE_PRIVATE
        )

    data class Identity(val username: String, val address: String)
    data class Pending(val username: String, val expectedAddress: String)

    fun load(): Identity? {
        val name = prefs.getString("username", null) ?: return null
        val address = prefs.getString("address", null) ?: return null
        return Identity(name, address)
    }

    fun save(identity: Identity) {
        prefs.edit()
            .putString("username", identity.username)
            .putString("address", identity.address)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Backup snapshot (v0.11): every entry of this prefs
     *  file as a plain map, for BackupPayload's typed encoding.
     *  Public registry data only — no secret ever lands here. */
    fun snapshotPrefs(): Map<String, Any?> = prefs.all.toMap()

    fun savePending(pending: Pending) {
        prefs.edit()
            .putString("pending_username", pending.username)
            .putString("pending_address", pending.expectedAddress)
            .apply()
    }

    fun loadPending(): Pending? {
        val name = prefs.getString("pending_username", null) ?: return null
        val address = prefs.getString("pending_address", null) ?: return null
        return Pending(name, address)
    }

    fun clearPending() {
        prefs.edit()
            .remove("pending_username")
            .remove("pending_address")
            .apply()
    }

    // ---- last-good registry cache (2026-11-27) ----
    //
    // Every registry body that passes FluidGate.assessRegistry
    // is saved here by the fetch glue (login + wallet name
    // lookups). When NO live source answers, the login gate
    // checks the typed name against this saved copy instead of
    // dead-ending, and says so on screen (FluidGate's cached-
    // copy messages). The registry is public data — the same
    // reasoning as the identity above — so it belongs in this
    // prefs file, and rides the backup snapshot like the rest.

    data class CachedRegistry(val json: String, val fetchedAtMs: Long)

    fun saveRegistryCache(json: String, fetchedAtMs: Long) {
        prefs.edit()
            .putString("registry_cache_json", json)
            .putLong("registry_cache_at", fetchedAtMs)
            .apply()
    }

    fun loadRegistryCache(): CachedRegistry? {
        val json = prefs.getString("registry_cache_json", null) ?: return null
        return CachedRegistry(json, prefs.getLong("registry_cache_at", 0L))
    }
}
