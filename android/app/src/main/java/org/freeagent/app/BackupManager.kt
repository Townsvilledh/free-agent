package org.freeagent.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Backup orchestration (v0.11, BACKUP.md) — the glue between
 * the pure components (BackupCrypto, BackupPayload) and the
 * device: stores, vault, SAF folder, Drive, WebView cookies.
 *
 * The shape of every write:
 *   collect payload → seal with the held key → write the ONE
 *   blob to the granted folder (Layer 1) and, when consented
 *   and configured, to Drive appDataFolder (Layer 2).
 *
 * Writes are change-driven and debounced through the dirty
 * flag: any data change calls [requestBackup]; the write runs
 * at once when a key is held (BackupKeyHolder), and is owed
 * — visibly, on the Backup screen — when it is not. Nothing
 * here ever logs payload or key material.
 */
object BackupManager {

    const val BLOB_FILE_NAME = "lille-backup.lillebak"

    /** Domains whose WebView cookies the opt-in session
     *  export covers — her own lanes (X today, TikTok next). */
    private val SESSION_DOMAINS = listOf("x.com", "twitter.com", "tiktok.com")

    private val io = Executors.newSingleThreadExecutor()

    // ---------- payload collection ----------

    fun collectPayload(context: Context): String {
        val app = context.applicationContext
        val identity = FluidIdentityStore(app).load()
        val backupPrefs = BackupPrefs(app)
        val vault = DeviceVault(app)
        val prefsSnapshot = mutableMapOf<String, Map<String, Any?>>()
        prefsSnapshot["lille_fluid_identity"] = FluidIdentityStore(app).snapshotPrefs()
        prefsSnapshot["lille_backup"] = BackupPrefs.payloadSnapshot(backupPrefs)
        return BackupPayload.build(
            username = identity?.username ?: "",
            address = identity?.address ?: "",
            createdAt = System.currentTimeMillis(),
            chatsText = ChatStore(app).rawText(),
            tasksText = TaskStore(app).rawText(),
            proactiveText = ProactiveStore(app).rawText(),
            prefs = prefsSnapshot,
            vaultSecrets = vault.exportSecrets(),
            vaultAccounts = vault.exportAccountsJson(),
            sessions = if (backupPrefs.sessionsOptIn) exportSessions() else null
        )
    }

    // ---------- sealing + writing ----------

    /** Seal the current payload with the held key. In
     *  passphrase mode the held key pairs with the salt stored
     *  at setup (BackupPrefs.passphraseSalt), which the new
     *  blob's envelope carries — so automatic writes work the
     *  same in both modes once unlocked. Null when no key is
     *  held, no identity exists, or the salt is missing. */
    fun sealCurrent(context: Context): String? {
        val app = context.applicationContext
        val key = BackupKeyHolder.keyCopy() ?: return null
        val mode = BackupKeyHolder.mode ?: return null
        val identity = FluidIdentityStore(app).load() ?: return null
        val salt: ByteArray? = if (mode == BackupCrypto.MODE_PASSPHRASE) {
            val b64 = BackupPrefs(app).passphraseSalt ?: return null
            try { java.util.Base64.getDecoder().decode(b64) } catch (e: Exception) { return null }
        } else null
        val payload = collectPayload(context)
        return BackupCrypto.encrypt(
            key, payload.toByteArray(Charsets.UTF_8),
            mode, identity.username, salt, System.currentTimeMillis()
        )
    }

    /** Interactive passphrase seal (setup / first write):
     *  derives the key from the stored salt (creating and
     *  storing one at setup), holds it for automatic writes,
     *  and seals. Returns the blob, or null without identity. */
    fun sealWithPassphrase(
        context: Context, passphrase: String, createSaltIfMissing: Boolean
    ): String? {
        val app = context.applicationContext
        val prefs = BackupPrefs(app)
        var saltB64 = prefs.passphraseSalt
        if (saltB64 == null) {
            if (!createSaltIfMissing) return null
            saltB64 = java.util.Base64.getEncoder().encodeToString(BackupCrypto.newSalt())
            prefs.passphraseSalt = saltB64
        }
        val salt = java.util.Base64.getDecoder().decode(saltB64)
        val key = BackupCrypto.keyFromPassphrase(passphrase, salt)
        BackupKeyHolder.hold(key, BackupCrypto.MODE_PASSPHRASE)
        return sealCurrent(context)
    }

    /** Write a sealed blob to every configured layer. Folder
     *  is the guaranteed layer; Drive is attempted when
     *  consented + configured and quietly skipped when its
     *  token needs a user tap (the folder copy is already
     *  safe). Returns true when at least one layer took it. */
    fun writeBlob(context: Context, blobText: String): Boolean {
        val app = context.applicationContext
        val prefs = BackupPrefs(app)
        var wrote = false
        if (writeToFolder(app, blobText)) wrote = true
        if (prefs.driveEnabled && DriveBackup.isConfigured()) {
            writeToDrive(app, blobText)
        }
        if (wrote) {
            prefs.lastBackupAt = System.currentTimeMillis()
            prefs.dirty = false
        }
        return wrote
    }

    private fun writeToFolder(context: Context, blobText: String): Boolean {
        val uriText = BackupPrefs(context).folderUri ?: return false
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriText)) ?: return false
            if (!tree.canWrite()) return false
            val existing = tree.findFile(BLOB_FILE_NAME)
            val target = existing ?: tree.createFile("application/octet-stream", BLOB_FILE_NAME)
                ?: return false
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(blobText.toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) {
            false
        }
    }

    private fun writeToDrive(context: Context, blobText: String) {
        try {
            val prefs = BackupPrefs(context)
            val account = prefs.driveAccount ?: return
            val token = driveToken(context, account) ?: return
            when (val found = DriveBackup.findBackupFileId(token)) {
                is DriveBackup.Result.Ok ->
                    DriveBackup.upload(token, blobText.toByteArray(Charsets.UTF_8), found.value)
                else -> Unit
            }
        } catch (e: Exception) {
            // Layer 2 is best-effort in the background; the
            // Backup screen surfaces Drive errors interactively.
        }
    }

    /** A Drive access token for the consented account, or null
     *  when one cannot be had without the user (Play services
     *  throws a recoverable-auth exception then — the Backup
     *  screen's interactive flow handles that case). */
    fun driveToken(context: Context, accountName: String): String? {
        return try {
            com.google.android.gms.auth.GoogleAuthUtil.getToken(
                context.applicationContext,
                android.accounts.Account(accountName, "com.google"),
                "oauth2:" + DriveBackup.SCOPE_APPDATA
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---------- change-driven writes ----------

    /** Mark the backup owed and, when a key is held (either
     *  mode — the passphrase key pairs with its stored salt),
     *  write it now on the IO executor. */
    fun requestBackup(context: Context) {
        val app = context.applicationContext
        val prefs = BackupPrefs(app)
        if (!prefs.setupDone) return
        prefs.dirty = true
        if (!BackupKeyHolder.hasKey()) return
        io.execute {
            val blob = sealCurrent(app)
            if (blob != null) writeBlob(app, blob)
        }
    }

    /** Flush an owed backup synchronously (Backup screen /
     *  worker). Returns true when a blob was written. */
    fun flushNow(context: Context): Boolean {
        val blob = sealCurrent(context.applicationContext) ?: return false
        return writeBlob(context.applicationContext, blob)
    }

    // ---------- reading + restore ----------

    /** The blob in the granted folder, if any (same-device
     *  reinstall detection). */
    fun folderBlob(context: Context): String? {
        val uriText = BackupPrefs(context.applicationContext).folderUri ?: return null
        return try {
            val tree = DocumentFile.fromTreeUri(
                context.applicationContext, Uri.parse(uriText)
            ) ?: return null
            val file = tree.findFile(BLOB_FILE_NAME) ?: return null
            context.contentResolver.openInputStream(file.uri)?.use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun readBlobFromUri(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.openInputStream(uri)?.use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }

    sealed class RestoreOutcome {
        data class Restored(val parsed: BackupPayload.Parsed) : RestoreOutcome()
        object WrongKey : RestoreOutcome()
        object NotABackup : RestoreOutcome()
        data class WrongWallet(val backupUsername: String) : RestoreOutcome()
        data class Failed(val message: String) : RestoreOutcome()
    }

    /** Decrypt + bind-check a blob against the CURRENT
     *  verified identity. Does not apply anything. */
    fun openBlob(context: Context, blobText: String, key: ByteArray): RestoreOutcome {
        if (!BackupCrypto.isBackupBlob(blobText)) return RestoreOutcome.NotABackup
        val plain = BackupCrypto.decrypt(key, blobText) ?: return RestoreOutcome.WrongKey
        val parsed = BackupPayload.parse(String(plain, Charsets.UTF_8))
            ?: return RestoreOutcome.NotABackup
        val identity = FluidIdentityStore(context.applicationContext).load()
            ?: return RestoreOutcome.Failed("No verified Fluid identity on this device yet.")
        if (!BackupPayload.belongsTo(parsed, identity.username, identity.address)) {
            return RestoreOutcome.WrongWallet(parsed.username)
        }
        return RestoreOutcome.Restored(parsed)
    }

    fun openBlobWithPassphrase(
        context: Context, blobText: String, passphrase: String
    ): RestoreOutcome {
        if (!BackupCrypto.isBackupBlob(blobText)) return RestoreOutcome.NotABackup
        val plain = BackupCrypto.decryptWithPassphrase(passphrase, blobText)
            ?: return RestoreOutcome.WrongKey
        val parsed = BackupPayload.parse(String(plain, Charsets.UTF_8))
            ?: return RestoreOutcome.NotABackup
        val identity = FluidIdentityStore(context.applicationContext).load()
            ?: return RestoreOutcome.Failed("No verified Fluid identity on this device yet.")
        if (!BackupPayload.belongsTo(parsed, identity.username, identity.address)) {
            return RestoreOutcome.WrongWallet(parsed.username)
        }
        return RestoreOutcome.Restored(parsed)
    }

    /** Apply a verified payload: stores byte-for-byte, prefs
     *  typed, vault re-sealed into this device's Keystore,
     *  sessions (when present and still opted in) re-set. */
    fun applyRestore(context: Context, parsed: BackupPayload.Parsed): Boolean {
        val app = context.applicationContext
        return try {
            parsed.chatsText?.let { ChatStore(app).replaceRaw(it) }
            parsed.tasksText?.let { TaskStore(app).replaceRaw(it) }
            parsed.proactiveText?.let { ProactiveStore(app).replaceRaw(it) }
            for ((file, entries) in parsed.prefs) {
                if (file == "lille_backup") {
                    BackupPrefs.applyPayloadSnapshot(BackupPrefs(app), entries)
                } else {
                    applyPrefsFile(app, file, entries)
                }
            }
            val vault = DeviceVault(app)
            if (parsed.vaultSecrets.isNotEmpty()) vault.importSecrets(parsed.vaultSecrets)
            parsed.vaultAccounts?.let { vault.importAccountsJson(it) }
            if (parsed.sessions.isNotEmpty() && BackupPrefs(app).sessionsOptIn) {
                importSessions(parsed.sessions)
            }
            BackupPrefs(app).dirty = false
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun applyPrefsFile(context: Context, file: String, entries: Map<String, Any?>) {
        val edit = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
        edit.clear()
        for ((k, v) in entries) {
            when (v) {
                is String -> edit.putString(k, v)
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is Float -> edit.putFloat(k, v)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    edit.putStringSet(k, v as? Set<String>)
                }
            }
        }
        edit.commit()
    }

    // ---------- WebView sessions (opt-in) ----------

    private fun exportSessions(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val cm = CookieManager.getInstance()
        for (domain in SESSION_DOMAINS) {
            val cookies = cm.getCookie("https://$domain")
            if (!cookies.isNullOrEmpty()) out[domain] = cookies
        }
        return out
    }

    private fun importSessions(sessions: Map<String, String>) {
        val cm = CookieManager.getInstance()
        for ((domain, cookieString) in sessions) {
            val url = "https://$domain"
            for (pair in cookieString.split(";")) {
                val trimmed = pair.trim()
                if (trimmed.isNotEmpty()) cm.setCookie(url, trimmed)
            }
        }
        cm.flush()
    }

    /** A scratch file handle for the manual export path. */
    fun blobFile(context: Context): File =
        File(context.applicationContext.cacheDir, BLOB_FILE_NAME)
}
