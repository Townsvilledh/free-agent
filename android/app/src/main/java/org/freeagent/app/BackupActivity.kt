package org.freeagent.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Backup & restore screen (v0.11, BACKUP.md) — the whole
 * lifecycle in one place:
 *
 * SETUP: one Fluid signature, twice. Identical derived keys
 * (Fluid signs deterministically — verified here, at runtime,
 * every setup) enable signature mode: from then on, backups
 * seal automatically with the in-memory key. If a wallet's
 * signatures ever differ, setup offers passphrase mode
 * instead — same blob format, PBKDF2 key.
 *
 * LAYERS: a folder on the phone (SAF grant; survives
 * uninstall) and, with the user's Google consent, their own
 * Drive appDataFolder. Drive ships disabled-by-config until
 * the OAuth client exists (BACKUP.md lists the console
 * steps); this screen says so plainly rather than failing.
 *
 * RESTORE: pick a file or pull from Drive, produce the key
 * (one signature / the passphrase), and the payload is
 * bind-checked against the verified Fluid identity before
 * anything is applied.
 */
class BackupActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var prefs: BackupPrefs
    private lateinit var passphrasePanel: LinearLayout
    private lateinit var passphraseLabel: TextView
    private lateinit var passphraseInput: EditText

    private var identity: FluidIdentityStore.Identity? = null

    private enum class PassAction { SETUP, UNLOCK_WRITE, RESTORE_PENDING, EXPORT_PENDING }
    private var passAction: PassAction? = null
    private var pendingBlob: String? = null
    private var pendingExportBlob: String? = null

    private enum class GooglePurpose { ENABLE_DRIVE, RESTORE_DRIVE }
    private var googlePurpose: GooglePurpose? = null

    companion object {
        private val io = Executors.newSingleThreadExecutor()
    }

    // ---------- launchers ----------

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onFolderPicked(uri) }

    private val pickBackupFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onBackupFilePicked(uri) }

    private val createBackupFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val blob = pendingExportBlob
        pendingExportBlob = null
        if (uri != null && blob != null) {
            io.execute {
                val ok = try {
                    contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(blob.toByteArray(Charsets.UTF_8))
                    } != null
                } catch (e: Exception) {
                    false
                }
                runOnUiThread {
                    setStatus(
                        if (ok) "Backup file exported. Keep it somewhere safe — " +
                            "it is sealed, and only your Fluid wallet (or your " +
                            "backup passphrase) can open it."
                        else "Couldn't write the backup file there."
                    )
                }
            }
        }
    }

    private val googleSignIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val purpose = googlePurpose
        googlePurpose = null
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            onGoogleAccount(account, purpose)
        } catch (e: ApiException) {
            setStatus("Google sign-in didn't complete (code ${e.statusCode}). Nothing was changed.")
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        prefs = BackupPrefs(this)
        identity = FluidIdentityStore(this).load()
        FluidLogin.init(this)
        BackupWorker.schedule(this)

        status = findViewById(R.id.backupStatus)
        passphrasePanel = findViewById(R.id.passphrasePanel)
        passphraseLabel = findViewById(R.id.passphraseLabel)
        passphraseInput = findViewById(R.id.passphraseInput)

        findViewById<Button>(R.id.setupBackupButton).setOnClickListener { startSetup() }
        findViewById<Button>(R.id.chooseFolderButton).setOnClickListener {
            pickFolder.launch(null)
        }
        findViewById<Button>(R.id.driveButton).setOnClickListener { onDriveButton() }
        findViewById<Button>(R.id.backupNowButton).setOnClickListener { backupNow() }
        findViewById<Button>(R.id.forgetKeyButton).setOnClickListener {
            BackupKeyHolder.forget()
            setStatus("Backup key forgotten on this phone. The next backup or " +
                "restore will ask for one fresh signature (or your passphrase).")
        }
        findViewById<Button>(R.id.restoreFileButton).setOnClickListener {
            pickBackupFile.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.restoreDriveButton).setOnClickListener { restoreFromDrive() }
        findViewById<Button>(R.id.exportFileButton).setOnClickListener { exportFile() }
        findViewById<Button>(R.id.passphraseGoButton).setOnClickListener { onPassphraseGo() }

        val sessions = findViewById<CheckBox>(R.id.sessionsCheckBox)
        sessions.isChecked = prefs.sessionsOptIn
        sessions.setOnCheckedChangeListener { _, checked ->
            prefs.sessionsOptIn = checked
            BackupManager.requestBackup(this)
            refreshStatus()
        }

        refreshStatus()
        discoverDriveBackup()
    }

    // ---------- status ----------

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun refreshStatus() {
        val lines = mutableListOf<String>()
        val id = identity
        if (id == null) {
            lines += "Connect your Fluid name first — a backup belongs to a Fluid identity."
        }
        if (prefs.setupDone) {
            lines += if (prefs.mode == BackupCrypto.MODE_PASSPHRASE) {
                "Automatic backup: ON — passphrase key."
            } else {
                "Automatic backup: ON — Fluid signature key."
            }
            lines += if (BackupKeyHolder.hasKey()) {
                "Backup key: unlocked on this phone right now."
            } else {
                "Backup key: locked — one signature (or passphrase) unlocks it again."
            }
        } else {
            lines += "Automatic backup: not set up yet."
        }
        lines += if (prefs.folderUri != null) {
            "Folder: chosen — a sealed copy lives on this phone and survives uninstall."
        } else {
            "Folder: not chosen — pick one so a copy survives uninstalling Lille."
        }
        lines += when {
            !DriveBackup.isConfigured() ->
                "Google Drive: not configured in this build yet (see BACKUP.md) — folder + file backups work fully."
            prefs.driveEnabled ->
                "Google Drive: ON — a sealed copy syncs to your own Drive, so a new phone can restore."
            else ->
                "Google Drive: off — turn it on to survive losing this phone."
        }
        lines += if (prefs.lastBackupAt > 0) {
            "Last backup: " + DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT
            ).format(Date(prefs.lastBackupAt)) + "."
        } else {
            "No backup written yet."
        }
        if (prefs.setupDone && prefs.dirty) {
            lines += "New changes are waiting to be backed up."
        }
        setStatus(lines.joinToString("\n"))
    }

    // ---------- signature plumbing ----------

    /** One personal_sign of the backup message, raw signature
     *  or error on the UI thread. */
    private fun requestSignRaw(block: (String?, String?) -> Unit) {
        val id = identity
        if (id == null) {
            block(null, "Connect your Fluid name first.")
            return
        }
        setStatus(
            "Approve the backup signature in Fluid. It signs a message only — " +
                "no transaction, nothing moves."
        )
        FluidLogin.requestPersonalSign(BackupCrypto.SIGN_MESSAGE, id.address) { sig, err ->
            runOnUiThread { block(sig, err) }
        }
    }

    /** Unlock the holder with one signature, then run [block]. */
    private fun withUnlockedKey(block: () -> Unit) {
        if (BackupKeyHolder.hasKey()) {
            block()
            return
        }
        requestSignRaw { sig, err ->
            if (sig == null) {
                setStatus(err ?: "Signing was refused — nothing was backed up or restored.")
                return@requestSignRaw
            }
            BackupKeyHolder.hold(
                BackupCrypto.keyFromSignature(sig), BackupCrypto.MODE_SIGNATURE
            )
            block()
        }
    }

    // ---------- setup ----------

    private fun startSetup() {
        if (identity == null) {
            setStatus("Connect your Fluid name first — the backup key is your wallet's signature.")
            return
        }
        if (prefs.setupDone && BackupKeyHolder.hasKey()) {
            setStatus("Automatic backup is already set up and unlocked.")
            return
        }
        // Determinism check: sign the same message twice.
        requestSignRaw { sig1, err1 ->
            if (sig1 == null) {
                setStatus(err1 ?: "Signing was refused — backup setup stopped.")
                return@requestSignRaw
            }
            setStatus("One more signature — Lille checks your wallet signs the same way twice.")
            requestSignRaw { sig2, err2 ->
                if (sig2 == null) {
                    setStatus(err2 ?: "Signing was refused — backup setup stopped.")
                    return@requestSignRaw
                }
                if (BackupCrypto.sameKey(sig1, sig2)) {
                    BackupKeyHolder.hold(
                        BackupCrypto.keyFromSignature(sig1), BackupCrypto.MODE_SIGNATURE
                    )
                    prefs.mode = BackupCrypto.MODE_SIGNATURE
                    prefs.setupDone = true
                    BackupWorker.schedule(this)
                    flushThenStatus(
                        "Automatic backup is ON. Your wallet signs deterministically, " +
                            "so its signature is your backup key — no passphrase to remember."
                    )
                } else {
                    // Honest fallback: this wallet's signatures
                    // differ per signing, so they can't be a key.
                    showPassphrase(
                        PassAction.SETUP,
                        "Your wallet's signatures are not identical twice, so they " +
                            "can't be the backup key. Choose a backup passphrase " +
                            "instead — it protects the same backup, and only you know it."
                    )
                }
            }
        }
    }

    private fun flushThenStatus(doneMessage: String) {
        io.execute {
            val wrote = BackupManager.flushNow(this)
            runOnUiThread {
                if (wrote) {
                    setStatus("$doneMessage First backup written.")
                } else {
                    setStatus(
                        "$doneMessage The first write is still owed — choose a " +
                            "backup folder (or enable Drive) so it has somewhere to live."
                    )
                }
                refreshStatus()
            }
        }
    }

    // ---------- write paths ----------

    private fun backupNow() {
        if (!prefs.setupDone) {
            startSetup()
            return
        }
        if (prefs.mode == BackupCrypto.MODE_PASSPHRASE && !BackupKeyHolder.hasKey()) {
            showPassphrase(
                PassAction.UNLOCK_WRITE,
                "Enter your backup passphrase to unlock this backup."
            )
            return
        }
        withUnlockedKey {
            io.execute {
                val wrote = BackupManager.flushNow(this)
                runOnUiThread {
                    if (wrote) {
                        Toast.makeText(this, "Backup written.", Toast.LENGTH_SHORT).show()
                    } else {
                        setStatus(
                            "Couldn't write the backup — choose a backup folder " +
                                "so it has somewhere to live."
                        )
                    }
                    refreshStatus()
                }
            }
        }
    }

    private fun onFolderPicked(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            // Some providers don't persist; the grant may still
            // work for this session — keep the URI and let the
            // write result tell the truth.
        }
        prefs.folderUri = uri.toString()
        setStatus("Backup folder chosen.")
        if (prefs.setupDone && BackupKeyHolder.hasKey()) {
            io.execute {
                BackupManager.flushNow(this)
                runOnUiThread { refreshStatus() }
            }
        } else {
            refreshStatus()
        }
    }

    private fun exportFile() {
        if (!prefs.setupDone) {
            setStatus("Set up backup first — an export is a sealed backup file.")
            return
        }
        if (prefs.mode == BackupCrypto.MODE_PASSPHRASE && !BackupKeyHolder.hasKey()) {
            showPassphrase(
                PassAction.EXPORT_PENDING,
                "Enter your backup passphrase to seal the export."
            )
            return
        }
        withUnlockedKey {
            io.execute {
                val blob = BackupManager.sealCurrent(this)
                runOnUiThread {
                    if (blob == null) {
                        setStatus("Couldn't seal a backup right now.")
                    } else {
                        pendingExportBlob = blob
                        createBackupFile.launch(BackupManager.BLOB_FILE_NAME)
                    }
                }
            }
        }
    }

    // ---------- passphrase panel ----------

    private fun showPassphrase(action: PassAction, label: String) {
        passAction = action
        passphraseLabel.text = label
        passphraseInput.text.clear()
        passphrasePanel.visibility = View.VISIBLE
        passphraseInput.requestFocus()
    }

    private fun hidePassphrase() {
        passAction = null
        passphrasePanel.visibility = View.GONE
        passphraseInput.text.clear()
    }

    private fun onPassphraseGo() {
        val action = passAction ?: return
        val passphrase = passphraseInput.text.toString()
        if (passphrase.length < 8) {
            setStatus("Passphrases need at least 8 characters.")
            return
        }
        hidePassphrase()
        when (action) {
            PassAction.SETUP -> {
                AlertDialog.Builder(this)
                    .setTitle("Remember this passphrase")
                    .setMessage(
                        "It is the ONLY way back into passphrase-mode backups. " +
                            "If you forget it, they are gone — nobody can reset it."
                    )
                    .setNegativeButton("Go back", null)
                    .setPositiveButton("I understand") { _, _ ->
                        io.execute {
                            val blob = BackupManager.sealWithPassphrase(
                                this, passphrase, createSaltIfMissing = true
                            )
                            val wrote = blob?.let { BackupManager.writeBlob(this, it) } ?: false
                            runOnUiThread {
                                if (blob != null) {
                                    prefs.mode = BackupCrypto.MODE_PASSPHRASE
                                    prefs.setupDone = true
                                    BackupWorker.schedule(this)
                                    setStatus(
                                        if (wrote) {
                                            "Automatic backup is ON — passphrase key. First backup written."
                                        } else {
                                            "Automatic backup is ON — passphrase key. Choose a backup folder so writes have somewhere to live."
                                        }
                                    )
                                } else {
                                    setStatus("Couldn't seal the first backup.")
                                }
                                refreshStatus()
                            }
                        }
                    }
                    .show()
            }
            PassAction.UNLOCK_WRITE -> {
                io.execute {
                    // Verify against the existing folder blob
                    // when there is one: a wrong passphrase must
                    // never overwrite a good backup.
                    val existing = BackupManager.folderBlob(this)
                    val verified = existing == null ||
                        BackupCrypto.decryptWithPassphrase(passphrase, existing) != null
                    val blob = if (verified) {
                        // No stored salt (e.g. first write after
                        // a restore onto a new phone) + no folder
                        // blob to verify against: this entry
                        // defines the salt going forward.
                        BackupManager.sealWithPassphrase(
                            this, passphrase,
                            createSaltIfMissing = existing == null
                        )
                    } else null
                    val wrote = blob?.let { BackupManager.writeBlob(this, it) } ?: false
                    runOnUiThread {
                        when {
                            !verified ->
                                setStatus("That passphrase doesn't open your existing backup — nothing was overwritten.")
                            wrote -> {
                                Toast.makeText(this, "Backup written.", Toast.LENGTH_SHORT).show()
                                refreshStatus()
                            }
                            else ->
                                setStatus("Couldn't write the backup — choose a backup folder first.")
                        }
                    }
                }
            }
            PassAction.RESTORE_PENDING -> {
                val blob = pendingBlob
                pendingBlob = null
                if (blob == null) {
                    setStatus("That restore was interrupted — pick the file again.")
                    return
                }
                io.execute {
                    val outcome = BackupManager.openBlobWithPassphrase(this, blob, passphrase)
                    runOnUiThread { handleRestoreOutcome(outcome) }
                }
            }
            PassAction.EXPORT_PENDING -> {
                io.execute {
                    val blob = BackupManager.sealWithPassphrase(
                        this, passphrase, createSaltIfMissing = false
                    )
                    runOnUiThread {
                        if (blob == null) {
                            setStatus("That passphrase didn't unlock the backup key.")
                        } else {
                            pendingExportBlob = blob
                            createBackupFile.launch(BackupManager.BLOB_FILE_NAME)
                        }
                    }
                }
            }
        }
    }

    // ---------- restore paths ----------

    private fun onBackupFilePicked(uri: Uri) {
        io.execute {
            val text = BackupManager.readBlobFromUri(this, uri)
            runOnUiThread {
                if (text == null || !BackupCrypto.isBackupBlob(text)) {
                    setStatus("That file isn't a Lille backup.")
                } else {
                    beginRestore(text)
                }
            }
        }
    }

    private fun beginRestore(blobText: String) {
        when (BackupCrypto.modeOf(blobText)) {
            BackupCrypto.MODE_SIGNATURE -> {
                requestSignRaw { sig, err ->
                    if (sig == null) {
                        setStatus(err ?: "Signing was refused — restore stopped.")
                        return@requestSignRaw
                    }
                    val key = BackupCrypto.keyFromSignature(sig)
                    BackupKeyHolder.hold(key, BackupCrypto.MODE_SIGNATURE)
                    io.execute {
                        val outcome = BackupManager.openBlob(this, blobText, key)
                        runOnUiThread { handleRestoreOutcome(outcome) }
                    }
                }
            }
            BackupCrypto.MODE_PASSPHRASE -> {
                pendingBlob = blobText
                showPassphrase(
                    PassAction.RESTORE_PENDING,
                    "This backup is locked with a passphrase. Enter it to open the backup."
                )
            }
            else -> setStatus("That file isn't a Lille backup.")
        }
    }

    private fun handleRestoreOutcome(outcome: BackupManager.RestoreOutcome) {
        when (outcome) {
            is BackupManager.RestoreOutcome.Restored -> {
                val parsed = outcome.parsed
                val whenText = if (parsed.createdAt > 0) {
                    DateFormat.getDateTimeInstance(
                        DateFormat.SHORT, DateFormat.SHORT
                    ).format(Date(parsed.createdAt))
                } else "an unknown date"
                AlertDialog.Builder(this)
                    .setTitle("Restore this backup?")
                    .setMessage(
                        "Backup of @${parsed.username} from $whenText. Restoring " +
                            "replaces the chats, tasks and vault on this phone " +
                            "with the backup's. Your Fluid connection stays as it is."
                    )
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Restore") { _, _ ->
                        io.execute {
                            val ok = BackupManager.applyRestore(this, parsed)
                            runOnUiThread {
                                if (ok) {
                                    prefs.restoreOffered = true
                                    Toast.makeText(
                                        this, "Backup restored — welcome back.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    val intent = Intent(this, MainActivity::class.java)
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    startActivity(intent)
                                    finish()
                                } else {
                                    setStatus("The backup opened but couldn't be applied.")
                                }
                            }
                        }
                    }
                    .show()
            }
            BackupManager.RestoreOutcome.WrongKey ->
                setStatus(
                    "That key doesn't open this backup. In signature mode the " +
                        "backup belongs to a different wallet; in passphrase mode " +
                        "the passphrase was wrong — or the file is damaged."
                )
            is BackupManager.RestoreOutcome.WrongWallet ->
                setStatus(
                    "This backup belongs to @${outcome.backupUsername}. A backup " +
                        "restores only into the same Fluid name that made it."
                )
            BackupManager.RestoreOutcome.NotABackup ->
                setStatus("That file isn't a Lille backup.")
            is BackupManager.RestoreOutcome.Failed ->
                setStatus(outcome.message)
        }
    }

    // ---------- Drive ----------

    private fun onDriveButton() {
        if (!DriveBackup.isConfigured()) {
            setStatus(
                "Google Drive sync is built but not configured in this build: it " +
                    "turns on when Lille's Google Cloud OAuth client is registered " +
                    "(BACKUP.md lists the exact steps). Folder and file backups " +
                    "work fully today."
            )
            return
        }
        if (prefs.driveEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Turn off Drive sync?")
                .setMessage(
                    "The sealed copy already in your Drive stays there; Lille " +
                        "just stops updating it."
                )
                .setNegativeButton("Keep it on", null)
                .setPositiveButton("Turn off") { _, _ ->
                    prefs.driveEnabled = false
                    refreshStatus()
                }
                .show()
        } else {
            googlePurpose = GooglePurpose.ENABLE_DRIVE
            launchGoogleSignIn()
        }
    }

    private fun restoreFromDrive() {
        if (!DriveBackup.isConfigured()) {
            setStatus(
                "Google Drive restore isn't configured in this build yet — " +
                    "restore from a backup file instead (BACKUP.md explains)."
            )
            return
        }
        val accountName = prefs.driveAccount
        if (accountName != null) {
            fetchDriveBackup(accountName)
        } else {
            googlePurpose = GooglePurpose.RESTORE_DRIVE
            launchGoogleSignIn()
        }
    }

    private fun launchGoogleSignIn() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveBackup.SCOPE_APPDATA))
            .build()
        googleSignIn.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }

    private fun onGoogleAccount(account: GoogleSignInAccount, purpose: GooglePurpose?) {
        val email = account.email
        if (email == null) {
            setStatus("Google didn't share an account email — sign-in incomplete.")
            return
        }
        prefs.driveAccount = email
        when (purpose) {
            GooglePurpose.ENABLE_DRIVE -> {
                prefs.driveEnabled = true
                setStatus("Google Drive sync is ON for $email.")
                if (BackupKeyHolder.hasKey()) {
                    io.execute {
                        BackupManager.flushNow(this)
                        runOnUiThread { refreshStatus() }
                    }
                } else {
                    refreshStatus()
                }
            }
            GooglePurpose.RESTORE_DRIVE -> fetchDriveBackup(email)
            null -> refreshStatus()
        }
    }

    private fun fetchDriveBackup(accountName: String) {
        setStatus("Looking in your Google Drive for a Lille backup…")
        io.execute {
            val token = BackupManager.driveToken(this, accountName)
            // A null outcome means beginRestore took over the
            // status line on the UI thread — don't overwrite it.
            val outcome: String? = when {
                token == null ->
                    "Google needs a fresh sign-in for Drive access — tap Restore from Google Drive again."
                else -> when (val found = DriveBackup.findBackupFileId(token)) {
                    is DriveBackup.Result.Ok -> {
                        val fileId = found.value
                        if (fileId == null) {
                            "No Lille backup is in this Google account's Drive."
                        } else when (val dl = DriveBackup.download(token, fileId)) {
                            is DriveBackup.Result.Ok -> {
                                val text = String(dl.value, Charsets.UTF_8)
                                runOnUiThread { beginRestore(text) }
                                null
                            }
                            is DriveBackup.Result.Failed -> dl.message
                            DriveBackup.Result.Disabled -> "Drive is not configured."
                        }
                    }
                    is DriveBackup.Result.Failed -> found.message
                    DriveBackup.Result.Disabled -> "Drive is not configured."
                }
            }
            if (outcome != null) runOnUiThread { setStatus(outcome) }
        }
    }

    /** Fresh-install discovery: when this phone has no chats
     *  yet and a Google account is already signed in, say so
     *  if a backup is waiting — the seamless path should be
     *  visible, not buried. */
    private fun discoverDriveBackup() {
        if (!DriveBackup.isConfigured()) return
        if (!ChatStore(this).isEmpty()) return
        val account = try {
            GoogleSignIn.getLastSignedInAccount(this)
        } catch (e: Exception) {
            null
        } ?: return
        val email = account.email ?: return
        io.execute {
            val token = BackupManager.driveToken(this, email) ?: return@execute
            val found = DriveBackup.findBackupFileId(token)
            if (found is DriveBackup.Result.Ok && found.value != null) {
                runOnUiThread {
                    setStatus(
                        "A Lille backup is waiting in your Google Drive ($email). " +
                            "Tap “Restore from Google Drive” to continue where you left off."
                    )
                }
            }
        }
    }
}
