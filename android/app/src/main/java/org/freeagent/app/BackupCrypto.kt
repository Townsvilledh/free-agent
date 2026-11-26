package org.freeagent.app

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup encryption (v0.11, BACKUP.md) — the pure crypto
 * component: no Android, no I/O, pinned by JVM tests. The glue
 * (BackupManager) collects the payload and stores the blob;
 * every format and key decision lives here.
 *
 * THE KEY IS THE FLUID WALLET. In signature mode the AES-256
 * key is SHA-256 over the wallet's `personal_sign` signature
 * of one fixed, versioned message ([SIGN_MESSAGE]), requested
 * over the existing WalletConnect session. The signature and
 * the derived key are never logged and never persisted — the
 * key lives in memory only while backup work runs, and the
 * encrypted blob is the only thing ever stored.
 *
 * Signature mode is only enabled after a determinism check:
 * the wallet signs the same message twice and the derived
 * keys must be identical ([sameKey]). Fluid's signing is
 * deterministic; if a wallet ever fails the check, backup
 * falls back to passphrase mode (PBKDF2-HMAC-SHA256, random
 * salt carried in the envelope) with the same payload format.
 *
 * Blob format — one JSON envelope:
 * {"format":"LILLEBAK","version":1,"mode":"signature"|"passphrase",
 *  "salt":<b64, passphrase mode only>,"iv":<b64>,
 *  "ciphertext":<b64>,"createdAt":<ms>,"username":"<fluid name>"}
 * Encryption is AES-256-GCM (authenticated): a wrong key or a
 * tampered blob fails decryption outright — there is no
 * partial or silent restore. The username sits in the clear
 * header on purpose: a Fluid name is public registry data,
 * and it lets the restore screen say whose backup a file is
 * before asking for a signature.
 */
object BackupCrypto {

    const val FORMAT = "LILLEBAK"
    const val FORMAT_VERSION = 1
    const val MODE_SIGNATURE = "signature"
    const val MODE_PASSPHRASE = "passphrase"

    /** The exact message the wallet signs for backup. Fixed
     *  forever for format v1: changing a single character
     *  changes the key and orphans every existing backup. */
    const val SIGN_MESSAGE =
        "Lille backup key v1\n\n" +
            "Sign this message to create the key that locks your Lille backup. " +
            "This signature never moves funds and is never shared — Lille uses " +
            "it only to encrypt and decrypt your own backup on this device."

    const val PASSPHRASE_ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    // ---- key derivation ----

    /** SHA-256 over the UTF-8 bytes of the signature string
     *  exactly as the wallet returned it (hex, "0x…"). */
    fun keyFromSignature(signature: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.trim().toByteArray(Charsets.UTF_8))

    /** The determinism check: two signatures of the same
     *  message must derive the same key. Constant-time
     *  comparison — this is a key, treat it like one. */
    fun sameKey(signatureA: String, signatureB: String): Boolean =
        MessageDigest.isEqual(keyFromSignature(signatureA), keyFromSignature(signatureB))

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /** PBKDF2-HMAC-SHA256 — the passphrase-mode KDF. */
    fun keyFromPassphrase(passphrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            passphrase.toCharArray(), salt, PASSPHRASE_ITERATIONS, KEY_BITS
        )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    // ---- envelope ----

    fun isBackupBlob(text: String?): Boolean = parseEnvelope(text) != null

    /** The parsed envelope header, or null when the text is
     *  not a Lille backup blob. Does NOT decrypt anything. */
    fun parseEnvelope(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT) return null
            if (root.optInt("version") != FORMAT_VERSION) return null
            val mode = root.optString("mode")
            if (mode != MODE_SIGNATURE && mode != MODE_PASSPHRASE) return null
            if (root.optString("iv").isEmpty()) return null
            if (root.optString("ciphertext").isEmpty()) return null
            if (mode == MODE_PASSPHRASE && root.optString("salt").isEmpty()) return null
            root
        } catch (e: Exception) {
            null
        }
    }

    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        mode: String,
        username: String,
        salt: ByteArray?,
        createdAt: Long
    ): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        val ct = cipher.doFinal(plaintext)
        val enc = Base64.getEncoder()
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("mode", mode)
            .put("iv", enc.encodeToString(iv))
            .put("ciphertext", enc.encodeToString(ct))
            .put("createdAt", createdAt)
            .put("username", username)
        if (salt != null) root.put("salt", enc.encodeToString(salt))
        return root.toString()
    }

    /** Decrypt a blob with an already-derived key. Null on
     *  ANY failure — wrong key, tampered bytes, bad format —
     *  by design: GCM authentication makes failure total. */
    fun decrypt(key: ByteArray, blobText: String): ByteArray? {
        val root = parseEnvelope(blobText) ?: return null
        return try {
            val dec = Base64.getDecoder()
            val iv = dec.decode(root.getString("iv"))
            val ct = dec.decode(root.getString("ciphertext"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.doFinal(ct)
        } catch (e: Exception) {
            null
        }
    }

    /** Passphrase-mode convenience: pull the salt out of the
     *  envelope and derive. Null when the blob is not a
     *  passphrase blob or decryption fails. */
    fun decryptWithPassphrase(passphrase: String, blobText: String): ByteArray? {
        val root = parseEnvelope(blobText) ?: return null
        if (root.optString("mode") != MODE_PASSPHRASE) return null
        val salt = try {
            Base64.getDecoder().decode(root.getString("salt"))
        } catch (e: Exception) {
            return null
        }
        return decrypt(keyFromPassphrase(passphrase, salt), blobText)
    }

    fun modeOf(blobText: String): String? =
        parseEnvelope(blobText)?.optString("mode")?.takeIf { it.isNotEmpty() }

    fun usernameOf(blobText: String): String? =
        parseEnvelope(blobText)?.optString("username")?.takeIf { it.isNotEmpty() }
}
