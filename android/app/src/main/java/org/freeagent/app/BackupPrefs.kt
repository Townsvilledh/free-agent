package org.freeagent.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Backup settings (v0.11, BACKUP.md) — plain SharedPreferences:
 * nothing here is a secret. The folder URI is a device-local
 * SAF grant (meaningless on another phone, so it is NOT part
 * of the backup payload); the mode, the Drive consent, and the
 * sessions opt-in are user choices that DO ride the payload,
 * so a restored Lille keeps the same backup posture.
 *
 * The dirty flag is the seam between the change-driven writes
 * and the in-memory key: data changes mark dirty; the write
 * happens immediately when the key is held, or at the next
 * unlock (one signature / passphrase entry) when it is not.
 */
class BackupPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            "lille_backup", Context.MODE_PRIVATE
        )

    var setupDone: Boolean
        get() = prefs.getBoolean("setupDone", false)
        set(value) { prefs.edit().putBoolean("setupDone", value).apply() }

    /** BackupCrypto.MODE_SIGNATURE or MODE_PASSPHRASE. */
    var mode: String
        get() = prefs.getString("mode", BackupCrypto.MODE_SIGNATURE)
            ?: BackupCrypto.MODE_SIGNATURE
        set(value) { prefs.edit().putString("mode", value).apply() }

    var folderUri: String?
        get() = prefs.getString("folderUri", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("folderUri") else putString("folderUri", value)
            }.apply()
        }

    /** Layer 2 consent: the user agreed to sync the sealed
     *  blob to their own Google Drive appDataFolder. */
    var driveEnabled: Boolean
        get() = prefs.getBoolean("driveEnabled", false)
        set(value) { prefs.edit().putBoolean("driveEnabled", value).apply() }

    var driveAccount: String?
        get() = prefs.getString("driveAccount", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("driveAccount") else putString("driveAccount", value)
            }.apply()
        }

    /** Passphrase mode's salt, Base64. A salt is not a secret
     *  (every blob's envelope carries its own copy); storing it
     *  lets automatic writes re-derive nothing — the held key
     *  pairs with this salt — and lets a fresh write happen
     *  after one passphrase entry per process. */
    var passphraseSalt: String?
        get() = prefs.getString("passphraseSalt", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("passphraseSalt")
                else putString("passphraseSalt", value)
            }.apply()
        }

    /** Browser-session export opt-in (default OFF — a session
     *  cookie is a logged-in account in a file, even sealed). */
    var sessionsOptIn: Boolean
        get() = prefs.getBoolean("sessionsOptIn", false)
        set(value) { prefs.edit().putBoolean("sessionsOptIn", value).apply() }

    var lastBackupAt: Long
        get() = prefs.getLong("lastBackupAt", 0L)
        set(value) { prefs.edit().putLong("lastBackupAt", value).apply() }

    var dirty: Boolean
        get() = prefs.getBoolean("dirty", false)
        set(value) { prefs.edit().putBoolean("dirty", value).apply() }

    /** The user was offered a restore on this install and
     *  declined / completed it — don't nag on every start. */
    var restoreOffered: Boolean
        get() = prefs.getBoolean("restoreOffered", false)
        set(value) { prefs.edit().putBoolean("restoreOffered", value).apply() }

    companion object {
        /** The payload-carried subset (mode + choices), as a
         *  prefs-file snapshot for BackupPayload. */
        fun payloadSnapshot(prefs: BackupPrefs): Map<String, Any?> = mapOf(
            "mode" to prefs.mode,
            "sessionsOptIn" to prefs.sessionsOptIn,
            "setupDone" to prefs.setupDone,
            "driveEnabled" to prefs.driveEnabled
        )

        /** Apply a restored payload snapshot (device-local
         *  fields — folderUri, dirty, lastBackupAt — are NOT
         *  restored: they describe the old phone, not this one). */
        fun applyPayloadSnapshot(prefs: BackupPrefs, values: Map<String, Any?>) {
            (values["mode"] as? String)?.let { prefs.mode = it }
            (values["sessionsOptIn"] as? Boolean)?.let { prefs.sessionsOptIn = it }
            (values["setupDone"] as? Boolean)?.let { prefs.setupDone = it }
            (values["driveEnabled"] as? Boolean)?.let { prefs.driveEnabled = it }
        }
    }
}
