package org.freeagent.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backup crypto: the signature-key scheme, the determinism
 *  check, AES-GCM round-trips, and total failure on wrong keys
 *  or tampered blobs. */
class BackupCryptoTest {

    private val sigA = "0x" + "ab".repeat(65)
    private val sigB = "0x" + "cd".repeat(65)
    private val payload = """{"version":1,"username":"ethuram"}""".toByteArray()

    @Test
    fun signatureKeyIsStableAndSignatureBound() {
        assertArrayEquals(
            BackupCrypto.keyFromSignature(sigA),
            BackupCrypto.keyFromSignature(sigA)
        )
        assertFalse(
            BackupCrypto.keyFromSignature(sigA)
                .contentEquals(BackupCrypto.keyFromSignature(sigB))
        )
        assertEquals(32, BackupCrypto.keyFromSignature(sigA).size)
    }

    @Test
    fun determinismCheckComparesDerivedKeys() {
        assertTrue(BackupCrypto.sameKey(sigA, sigA))
        assertFalse(BackupCrypto.sameKey(sigA, sigB))
    }

    @Test
    fun signatureModeRoundTrip() {
        val key = BackupCrypto.keyFromSignature(sigA)
        val blob = BackupCrypto.encrypt(
            key, payload, BackupCrypto.MODE_SIGNATURE, "ethuram", null, 123L
        )
        assertTrue(BackupCrypto.isBackupBlob(blob))
        assertEquals(BackupCrypto.MODE_SIGNATURE, BackupCrypto.modeOf(blob))
        assertEquals("ethuram", BackupCrypto.usernameOf(blob))
        assertArrayEquals(payload, BackupCrypto.decrypt(key, blob))
    }

    @Test
    fun wrongKeyFailsCompletely() {
        val blob = BackupCrypto.encrypt(
            BackupCrypto.keyFromSignature(sigA), payload,
            BackupCrypto.MODE_SIGNATURE, "ethuram", null, 1L
        )
        assertNull(BackupCrypto.decrypt(BackupCrypto.keyFromSignature(sigB), blob))
    }

    @Test
    fun tamperedBlobFailsCompletely() {
        val key = BackupCrypto.keyFromSignature(sigA)
        val blob = BackupCrypto.encrypt(
            key, payload, BackupCrypto.MODE_SIGNATURE, "ethuram", null, 1L
        )
        val root = org.json.JSONObject(blob)
        val ct = root.getString("ciphertext")
        // Flip the first ciphertext character to a different valid b64 char.
        val flipped = (if (ct[0] == 'A') 'B' else 'A') + ct.substring(1)
        root.put("ciphertext", flipped)
        assertNull(BackupCrypto.decrypt(key, root.toString()))
    }

    @Test
    fun passphraseModeRoundTripAndWrongPassphrase() {
        val salt = BackupCrypto.newSalt()
        val key = BackupCrypto.keyFromPassphrase("correct horse", salt)
        val blob = BackupCrypto.encrypt(
            key, payload, BackupCrypto.MODE_PASSPHRASE, "ethuram", salt, 7L
        )
        assertEquals(BackupCrypto.MODE_PASSPHRASE, BackupCrypto.modeOf(blob))
        assertArrayEquals(
            payload, BackupCrypto.decryptWithPassphrase("correct horse", blob)
        )
        assertNull(BackupCrypto.decryptWithPassphrase("wrong horse", blob))
        // Passphrase path refuses a signature-mode blob.
        val sigBlob = BackupCrypto.encrypt(
            BackupCrypto.keyFromSignature(sigA), payload,
            BackupCrypto.MODE_SIGNATURE, "ethuram", null, 1L
        )
        assertNull(BackupCrypto.decryptWithPassphrase("correct horse", sigBlob))
    }

    @Test
    fun passphraseKeysDifferBySaltAndPassphrase() {
        val salt1 = BackupCrypto.newSalt()
        val salt2 = BackupCrypto.newSalt()
        assertFalse(salt1.contentEquals(salt2))
        assertFalse(
            BackupCrypto.keyFromPassphrase("pw", salt1)
                .contentEquals(BackupCrypto.keyFromPassphrase("pw", salt2))
        )
        assertFalse(
            BackupCrypto.keyFromPassphrase("pw", salt1)
                .contentEquals(BackupCrypto.keyFromPassphrase("pw2", salt1))
        )
    }

    @Test
    fun nonBlobsAreRejected() {
        assertFalse(BackupCrypto.isBackupBlob(null))
        assertFalse(BackupCrypto.isBackupBlob(""))
        assertFalse(BackupCrypto.isBackupBlob("{}"))
        assertFalse(BackupCrypto.isBackupBlob("""{"format":"OTHER","version":1}"""))
        assertNull(BackupCrypto.modeOf("hello"))
        assertNull(BackupCrypto.usernameOf("hello"))
        assertNull(BackupCrypto.decrypt(BackupCrypto.keyFromSignature(sigA), "hello"))
    }

    @Test
    fun eachEncryptionUsesAFreshIv() {
        val key = BackupCrypto.keyFromSignature(sigA)
        val one = BackupCrypto.encrypt(key, payload, BackupCrypto.MODE_SIGNATURE, "u", null, 1L)
        val two = BackupCrypto.encrypt(key, payload, BackupCrypto.MODE_SIGNATURE, "u", null, 1L)
        assertFalse(one == two)
        assertNotNull(BackupCrypto.decrypt(key, one))
        assertNotNull(BackupCrypto.decrypt(key, two))
    }
}
