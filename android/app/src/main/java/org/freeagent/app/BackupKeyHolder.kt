package org.freeagent.app

/**
 * The backup key, in memory only (v0.11, BACKUP.md).
 *
 * The derived key (from the Fluid signature, or from the
 * backup passphrase) is held here while the app process
 * lives, so automatic backups can seal new blobs without
 * asking for a signature on every chat message. It is NEVER
 * written to disk, never logged, and it dies with the
 * process: after a cold start, the next backup needs one
 * fresh unlock — a signature tap in Fluid, or the passphrase
 * — and BackupPrefs.dirty remembers that a write is owed.
 * "Forget backup key" (Backup screen) clears it on demand.
 */
object BackupKeyHolder {

    @Volatile
    private var key: ByteArray? = null

    @Volatile
    var mode: String? = null
        private set

    fun hold(derivedKey: ByteArray, keyMode: String) {
        key = derivedKey.copyOf()
        mode = keyMode
    }

    fun hasKey(): Boolean = key != null

    /** A copy for one crypto operation (callers must not keep
     *  it beyond the call). Null when no key is held. */
    fun keyCopy(): ByteArray? = key?.copyOf()

    fun forget() {
        key?.fill(0)
        key = null
        mode = null
    }
}
