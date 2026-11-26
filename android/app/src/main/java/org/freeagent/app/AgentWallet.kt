package org.freeagent.app

import android.content.Context
import java.security.SecureRandom

/**
 * Lille's OWN wallet — key custody (v0.12, WALLET.md).
 *
 * The companion wallet decided in the Lille-in-Fluid spec
 * (§11 Decision 1, Option B): a SEPARATE keyset, generated
 * on this device, never derived from the user's Fluid seed,
 * sharing no fate with it. The private key lives only in
 * the Keystore-wrapped DeviceVault under one key name; it
 * crosses into exactly one other place — the encrypted
 * v0.11 backup payload, via the vault's export path — and
 * into the signer (WalletEngine) at the moment of signing.
 * It is never shown on screen, never in a chat or task
 * record, never logged.
 *
 * Her address is public data and lives in plain prefs, like
 * the Fluid identity record.
 */
class AgentWallet(context: Context) {

    private val appContext = context.applicationContext
    private val vault = DeviceVault(appContext)
    private val prefs = appContext.getSharedPreferences(
        "lille_agent_wallet", Context.MODE_PRIVATE
    )

    companion object {
        /** The vault key name for her EVM private key. */
        const val VAULT_KEY = "lille.wallet.evm.v1"
    }

    fun hasWallet(): Boolean =
        vault.hasSecret(VAULT_KEY) && address() != null

    fun address(): String? = prefs.getString("address", null)

    /** Create her wallet. An explicit user action (the
     *  Wallet screen's button) — never called by the model
     *  layer. Returns her checksummed address, or null if
     *  the vault refused the write. Creating twice is a
     *  no-op that returns the existing address: her key is
     *  never silently replaced. */
    fun createWallet(): String? {
        address()?.let { existing ->
            if (vault.hasSecret(VAULT_KEY)) return existing
        }
        val rnd = SecureRandom()
        var key = ByteArray(32)
        do {
            rnd.nextBytes(key)
        } while (!Evm.isValidPrivateKey(key))
        val address = Evm.addressOf(key)
        if (!vault.putSecret(VAULT_KEY, Evm.bytesToHex(key))) return null
        key.fill(0)
        prefs.edit()
            .putString("address", address)
            .putLong("created_at", System.currentTimeMillis())
            .apply()
        return address
    }

    /** SIGNER-ONLY: her private key bytes, read from the
     *  vault at the moment of signing. WalletEngine is the
     *  only caller. Never call this from chat, task, or any
     *  model-reachable code — the key must not enter a
     *  prompt, a trace, or a log. The caller zeroes the
     *  array after use. */
    fun keyBytesForSigner(): ByteArray? {
        val hex = vault.getSecret(VAULT_KEY) ?: return null
        return try {
            val bytes = Evm.hexToBytes(hex)
            if (Evm.isValidPrivateKey(bytes)) bytes else null
        } catch (e: Exception) {
            null
        }
    }
}
