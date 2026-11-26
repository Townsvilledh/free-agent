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
 *
 * FAMILIES: the wallet is one wallet with THREE keysets —
 * EVM, Bitcoin, Solana — each generated independently,
 * each stored under its own vault key, each riding the
 * same encrypted backup (the backup carries the whole
 * vault). The per-family functions below mirror the EVM
 * ones exactly; the family engines are their only signer
 * callers, under the same discipline.
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

        /** The vault key name for her Bitcoin private key. */
        const val BTC_VAULT_KEY = "lille.wallet.btc.v1"

        /** The vault key name for her Solana seed. */
        const val SOL_VAULT_KEY = "lille.wallet.sol.v1"
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

    // ---------------- Bitcoin keyset ----------------

    fun hasBtcWallet(): Boolean =
        vault.hasSecret(BTC_VAULT_KEY) && btcAddress() != null

    /** Her Bitcoin receive address (native segwit), or null
     *  before the keyset exists. */
    fun btcAddress(): String? = prefs.getString("btc_address", null)

    /** Create her Bitcoin keyset — same discipline as the
     *  EVM one: fresh randomness, a secp256k1-valid scalar,
     *  stored only in the vault; creating twice never
     *  replaces the key. */
    fun createBtcWallet(): String? {
        btcAddress()?.let { existing ->
            if (vault.hasSecret(BTC_VAULT_KEY)) return existing
        }
        val rnd = SecureRandom()
        var key = ByteArray(32)
        do {
            rnd.nextBytes(key)
        } while (!Evm.isValidPrivateKey(key))
        val address = Btc.addressOf(key)
        if (!vault.putSecret(BTC_VAULT_KEY, Evm.bytesToHex(key))) return null
        key.fill(0)
        prefs.edit().putString("btc_address", address).apply()
        return address
    }

    /** SIGNER-ONLY: her Bitcoin key bytes. BtcEngine is the
     *  only caller; same rules as [keyBytesForSigner]. */
    fun btcKeyBytesForSigner(): ByteArray? {
        val hex = vault.getSecret(BTC_VAULT_KEY) ?: return null
        return try {
            val bytes = Evm.hexToBytes(hex)
            if (Evm.isValidPrivateKey(bytes)) bytes else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Solana keyset ----------------

    fun hasSolWallet(): Boolean =
        vault.hasSecret(SOL_VAULT_KEY) && solAddress() != null

    /** Her Solana address (base58 of her ed25519 public
     *  key), or null before the keyset exists. */
    fun solAddress(): String? = prefs.getString("sol_address", null)

    /** Create her Solana keyset: 32 random bytes as the
     *  ed25519 seed (RFC 8032 — the seed is hashed and
     *  clamped at signing time, so any 32 bytes are a valid
     *  seed; there is no invalid-key retry loop here, by
     *  design of the algorithm). Vault-only storage;
     *  creating twice never replaces the key. */
    fun createSolWallet(): String? {
        solAddress()?.let { existing ->
            if (vault.hasSecret(SOL_VAULT_KEY)) return existing
        }
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val address = Sol.addressOf(seed)
        if (!vault.putSecret(SOL_VAULT_KEY, Evm.bytesToHex(seed))) return null
        seed.fill(0)
        prefs.edit().putString("sol_address", address).apply()
        return address
    }

    /** SIGNER-ONLY: her Solana seed bytes. SolEngine is the
     *  only caller; same rules as [keyBytesForSigner]. */
    fun solSeedBytesForSigner(): ByteArray? {
        val hex = vault.getSecret(SOL_VAULT_KEY) ?: return null
        return try {
            val bytes = Evm.hexToBytes(hex)
            if (bytes.size == 32) bytes else null
        } catch (e: Exception) {
            null
        }
    }

    /** Create every keyset that doesn't exist yet — the
     *  Wallet screen's one create action ("her wallet" is
     *  all three families; a user should never have to know
     *  the word keyset). True when all three exist after
     *  the call. */
    fun createAllWallets(): Boolean {
        if (!hasWallet()) createWallet()
        if (!hasBtcWallet()) createBtcWallet()
        if (!hasSolWallet()) createSolWallet()
        return hasWallet() && hasBtcWallet() && hasSolWallet()
    }
}
