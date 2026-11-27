package org.freeagent.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Settings — who Lille is connected as, her wallet,
 * backup & restore, Lille invites (referrals — the user's
 * link, share action, completed-invite count and the
 * export/import sync; REFERRALS.md), your own API keys
 * (v0.12.1), and disconnecting the Fluid name (the
 * FluidLogin disconnect): it
 * ends every WalletConnect session and forgets the stored
 * identity, so the login gate runs again on next open.
 *
 * "Your API keys" is the own-keys door (OwnKeyRoutes):
 * one block per provider, built programmatically below.
 * The discipline is the vault's: a key is typed here,
 * saved into the Keystore-wrapped DeviceVault, and never
 * displayed again — the field is cleared on save, the
 * status line says only saved / not saved, and the Test
 * button's one minimal call reports only its outcome
 * (OwnKeyRoutes.testLine), never the key.
 */
class SettingsActivity : AppCompatActivity() {

    private val vault by lazy { DeviceVault(this) }
    private val referralStore by lazy { ReferralStore(this) }
    private lateinit var referralCountView: TextView

    /** Referrals sync (REFERRALS.md): export writes the
     *  ledger's completed records as JSON to a file the user
     *  picks; import merges a file back in, first referrer
     *  wins. The user's own two phones are the framed use;
     *  the same export is the back-end count's input. */
    private val exportInvites = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(referralStore.exportText().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Invite list exported.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, "That export couldn't be written.", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val importInvites = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val added = referralStore.mergeFrom(text)
            refreshReferralCount()
            Toast.makeText(
                this,
                if (added > 0) {
                    "Imported — $added new completed invite(s) counted."
                } else {
                    "Nothing new in that file — your count is already in step."
                },
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, "That file couldn't be read as an invite list.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val identity = FluidIdentityStore(this).load()
        val identityView = findViewById<TextView>(R.id.settingsIdentity)
        identityView.text = if (identity != null) {
            val shortAddress = if (identity.address.length > 14) {
                identity.address.substring(0, 8) + "…" + identity.address.takeLast(4)
            } else identity.address
            "Connected with your Fluid name @${identity.username} ($shortAddress)."
        } else {
            "Not connected — Lille opens at the Fluid login."
        }

        findViewById<TextView>(R.id.settingsVersion).text =
            "Lille ${BuildConfig.VERSION_NAME} — free and open source. " +
                "No account, no API key, no telemetry."

        findViewById<Button>(R.id.settingsWalletButton).setOnClickListener {
            startActivity(Intent(this, WalletActivity::class.java))
        }
        findViewById<Button>(R.id.settingsBackupButton).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        buildReferralBlock(identity)
        findViewById<Button>(R.id.settingsDisconnectButton).setOnClickListener {
            val store = FluidIdentityStore(this)
            FluidLogin.init(this)
            FluidLogin.disconnectSessions(store, forgetIdentity = true)
            BackupKeyHolder.forget()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        val kit = findViewById<LinearLayout>(R.id.settingsKitContainer)
        kit.addView(buildGmailBlock())
        kit.addView(buildLanBlock())
        kit.addView(buildHeartbeatBlock())
        kit.addView(buildAccountsBlock())

        val container = findViewById<LinearLayout>(R.id.settingsKeysContainer)
        for (spec in OwnKeyRoutes.SPECS) {
            container.addView(buildKeyBlock(spec))
        }
    }

    // ---------- Lille's kit (v1.0.4) ----------

    /** True while a Gmail sign-in launched from here asked
     *  for the send scope too — the result handler turns
     *  the Settings send flag on only for that consent. */
    private var gmailSendRequested = false

    private val gmailSignIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn
                .getSignedInAccountFromIntent(result.data)
                .getResult(
                    com.google.android.gms.common.api.ApiException::class.java
                )
            if (account != null) {
                GmailConnection.onSignedIn(this, account)
                if (gmailSendRequested) {
                    GmailConnection.setSendEnabled(this, true)
                }
                Toast.makeText(
                    this, "Gmail connected.", Toast.LENGTH_SHORT
                ).show()
                recreate()
            }
        } catch (e: Exception) {
            Toast.makeText(
                this, "That sign-in didn't complete — nothing changed.",
                Toast.LENGTH_SHORT
            ).show()
        }
        gmailSendRequested = false
    }

    private fun launchGmailSignIn(withSend: Boolean) {
        gmailSendRequested = withSend
        val client = com.google.android.gms.auth.api.signin.GoogleSignIn
            .getClient(this, GmailConnection.signInOptions(withSend))
        gmailSignIn.launch(client.signInIntent)
    }

    private fun blockTitle(text: String): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = 16f
        v.setTextColor(0xFFF5EFE7.toInt())
        return v
    }

    private fun blockHint(text: String): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = 12f
        v.setTextColor(0xFF8A8378.toInt())
        return v
    }

    private fun blockStatus(): TextView {
        val v = TextView(this)
        v.textSize = 13f
        v.setTextColor(0xFFB9B0A2.toInt())
        return v
    }

    private fun newBlock(): LinearLayout {
        val block = LinearLayout(this)
        block.orientation = LinearLayout.VERTICAL
        block.setPadding(0, dp(10), 0, dp(6))
        return block
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (b in buttons) {
            val p = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            row.addView(b, p)
        }
        return row
    }

    /** Accounts (v1.0.8): the registry made visible —
     *  every account Lille holds a session or
     *  relationship with: the handle, whose it is, which
     *  is active in her browser, and the scope in plain
     *  words. Read-only here: accounts land on the list
     *  when a sign-in is verified from the page (hers at
     *  creation, the user's through Connect), and the
     *  browser's one active account is switched by the
     *  lanes, as a recorded step. Metadata only — no
     *  passwords are ever shown or stored here. */
    private fun buildAccountsBlock(): LinearLayout {
        val block = newBlock()
        block.addView(blockTitle("Accounts"), wrapParams())
        block.addView(
            blockHint(
                "The accounts Lille can act for, kept " +
                    "separate. Hers she runs herself, within " +
                    "policy; yours she only ever uses with " +
                    "your approval. Her browser holds one " +
                    "active account at a time, and she " +
                    "checks the active one matches before " +
                    "she acts."
            ),
            wrapParams(2)
        )
        val status = blockStatus()
        val lines = AccountsRegistry.summaryLines(
            AccountRegistryStore(this).root()
        )
        status.text = if (lines.isEmpty()) {
            "No accounts yet — hers appears when her own " +
                "account is created; yours when you connect " +
                "one from the chat."
        } else {
            lines.joinToString("\n")
        }
        block.addView(status, wrapParams(4))
        return block
    }

    /** Gmail (v1.0.4): a separate consent on the app's own
     *  Google stack. Read-only first; sending is a second,
     *  explicit step — its own scope request plus this
     *  flag — and every send still parks for the user's
     *  word in the chat (MailSendGate's triple gate). */
    private fun buildGmailBlock(): LinearLayout {
        val block = newBlock()
        block.addView(blockTitle("Gmail"), wrapParams())
        block.addView(
            blockHint(
                "Connect your Gmail so Lille can read and search " +
                    "your mail inside tasks. What she reads is " +
                    "redacted before it reaches her brain — codes " +
                    "and secrets never do. Sending stays OFF until " +
                    "you turn it on here, and even then every send " +
                    "asks you first."
            ),
            wrapParams(2)
        )
        val status = blockStatus()
        val connected = GmailConnection.isConnected(this)
        status.text = if (connected) {
            "Connected as ${GmailConnection.account(this)}" +
                if (GmailConnection.sendScopeGranted(this)) {
                    " — sending " +
                        if (GmailConnection.sendEnabled(this)) {
                            "ON (each send still asks you first)."
                        } else {
                            "off."
                        }
                } else {
                    " — read-only."
                }
        } else {
            "Not connected."
        }
        block.addView(status, wrapParams(4))

        val connect = Button(this).apply {
            text = if (connected) "Reconnect" else "Connect Gmail"
        }
        val disconnect = Button(this).apply { text = "Disconnect" }
        val sendToggle = Button(this).apply {
            text = if (GmailConnection.sendEnabled(this@SettingsActivity)) {
                "Turn sending off"
            } else {
                "Enable sending"
            }
        }
        block.addView(
            buttonRow(connect, disconnect, sendToggle), wrapParams(6)
        )
        connect.setOnClickListener { launchGmailSignIn(withSend = false) }
        disconnect.setOnClickListener {
            GmailConnection.disconnect(this)
            Toast.makeText(this, "Gmail disconnected.", Toast.LENGTH_SHORT)
                .show()
            recreate()
        }
        sendToggle.setOnClickListener {
            when {
                !GmailConnection.isConnected(this) ->
                    Toast.makeText(
                        this, "Connect Gmail first.",
                        Toast.LENGTH_SHORT
                    ).show()
                GmailConnection.sendEnabled(this) -> {
                    GmailConnection.setSendEnabled(this, false)
                    recreate()
                }
                GmailConnection.sendScopeGranted(this) -> {
                    GmailConnection.setSendEnabled(this, true)
                    recreate()
                }
                else -> launchGmailSignIn(withSend = true)
            }
        }
        return block
    }

    /** Your computer (v1.0.4, steal #13): the LAN brain
     *  route. Address + model live in the vault; the switch
     *  is LanRoute's. Off by default; when on and reachable
     *  it leads her own routes, and when it is not there
     *  she falls back without a word. */
    private fun buildLanBlock(): LinearLayout {
        val block = newBlock()
        block.addView(blockTitle("Your computer"), wrapParams())
        block.addView(
            blockHint(
                "Point Lille at a model server on your own " +
                    "computer — LM Studio, Ollama, llama.cpp, any " +
                    "OpenAI-compatible server on your network. " +
                    "She prefers it while it answers and falls " +
                    "back seamlessly when it doesn't."
            ),
            wrapParams(2)
        )
        val status = blockStatus()
        fun refreshStatus() {
            val hasAddress = vault.hasSecret(OwnKeyRoutes.LAN_URL_VAULT_KEY)
            status.text = when {
                !hasAddress -> "No address saved."
                LanRoute.isEnabled(this) ->
                    "On — your computer leads when it answers."
                else -> "Address saved — the route is off."
            }
        }
        block.addView(status, wrapParams(4))

        val urlField = EditText(this)
        urlField.hint = "Server address — e.g. http://192.168.1.20:1234/v1"
        urlField.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI
        urlField.setSingleLine(true)
        urlField.setText(vault.getSecret(OwnKeyRoutes.LAN_URL_VAULT_KEY) ?: "")
        block.addView(urlField, wrapParams(6))

        val modelField = EditText(this)
        modelField.hint = "Model name (optional)"
        modelField.inputType = InputType.TYPE_CLASS_TEXT
        modelField.setSingleLine(true)
        modelField.setText(
            vault.getSecret(OwnKeyRoutes.LAN_MODEL_VAULT_KEY) ?: ""
        )
        block.addView(modelField, wrapParams(4))

        val result = blockStatus()
        block.addView(result, wrapParams(4))

        val save = Button(this).apply { text = "Save" }
        val test = Button(this).apply { text = "Test" }
        val toggle = Button(this).apply {
            text = if (LanRoute.isEnabled(this@SettingsActivity)) {
                "Turn off"
            } else {
                "Turn on"
            }
        }
        block.addView(buttonRow(save, test, toggle), wrapParams(6))
        refreshStatus()

        save.setOnClickListener {
            val url = urlField.text.toString().trim()
            if (url.isEmpty()) {
                vault.removeSecret(OwnKeyRoutes.LAN_URL_VAULT_KEY)
                vault.removeSecret(OwnKeyRoutes.LAN_MODEL_VAULT_KEY)
                result.text = "Address cleared — the route is gone."
            } else {
                vault.putSecret(OwnKeyRoutes.LAN_URL_VAULT_KEY, url)
                val model = modelField.text.toString().trim()
                if (model.isEmpty()) {
                    vault.removeSecret(OwnKeyRoutes.LAN_MODEL_VAULT_KEY)
                } else {
                    vault.putSecret(OwnKeyRoutes.LAN_MODEL_VAULT_KEY, model)
                }
                result.text = "Saved."
            }
            refreshStatus()
        }
        test.setOnClickListener {
            val url = urlField.text.toString().trim()
            if (url.isEmpty()) {
                result.text = "Type the server address first."
                return@setOnClickListener
            }
            test.isEnabled = false
            result.text = "Asking your computer…"
            Thread {
                val ok = TaskRunner.lanHealthy(url)
                runOnUiThread {
                    result.text = if (ok) {
                        "Your computer answered."
                    } else {
                        "No answer from that address right now — " +
                            "she would fall back without a word."
                    }
                    test.isEnabled = true
                }
            }.start()
        }
        toggle.setOnClickListener {
            LanRoute.setEnabled(this, !LanRoute.isEnabled(this))
            recreate()
        }
        return block
    }

    /** The HEARTBEAT.md editor (v1.0.4, steal #1): the
     *  checklist her proactive passes work through. Plain
     *  text in the agent-state store — it rides the
     *  encrypted backup like the rest of her kit. */
    private fun buildHeartbeatBlock(): LinearLayout {
        val block = newBlock()
        block.addView(blockTitle("Heartbeat checklist"), wrapParams())
        block.addView(
            blockHint(
                "One item per line, starting with - [ ]. While " +
                    "you're away her passes check these and only " +
                    "speak up when something needs you. Add " +
                    "\"due: YYYY-MM-DD\" to an item to make it " +
                    "surface on its day."
            ),
            wrapParams(2)
        )
        val store = AgentStateStore(this)
        val field = EditText(this)
        field.hint = "- [ ] Check the thing that matters"
        field.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        field.minLines = 4
        field.setText(
            store.readSection(AgentStateStore.SECTION_HEARTBEAT)
                ?: AgentStateStore.HEARTBEAT_TEMPLATE
        )
        block.addView(field, wrapParams(6))
        val save = Button(this).apply { text = "Save checklist" }
        block.addView(buttonRow(save), wrapParams(6))
        save.setOnClickListener {
            store.writeSection(
                AgentStateStore.SECTION_HEARTBEAT,
                field.text.toString()
            )
            Toast.makeText(this, "Checklist saved.", Toast.LENGTH_SHORT)
                .show()
        }
        return block
    }

    /** The referrals block (REFERRALS.md): the user's
     *  personal invite link (selectable, copyable), the
     *  share action, the completed-invite count from the
     *  on-device ledger, and export/import sync. The copy
     *  is Lille invites only — nothing here names the
     *  login's other product or the back-end count. */
    private fun buildReferralBlock(identity: FluidIdentityStore.Identity?) {
        val linkView = findViewById<TextView>(R.id.settingsReferralLink)
        referralCountView = findViewById(R.id.settingsReferralCount)
        val link = identity?.let { Referrals.inviteLink(it.username) }
        linkView.text = link ?: "Sign in first — your invite link carries your name."
        refreshReferralCount()

        findViewById<Button>(R.id.settingsReferralShareButton).setOnClickListener {
            val pitch = identity?.let { Referrals.shareText(it.username) }
            if (pitch == null) {
                Toast.makeText(
                    this, "Sign in first — then your link is ready to share.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, pitch)
            }
            startActivity(Intent.createChooser(send, "Share your Lille invite"))
        }
        findViewById<Button>(R.id.settingsReferralCopyButton).setOnClickListener {
            if (link == null) {
                Toast.makeText(
                    this, "Sign in first — then your link is ready to copy.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val clipboard =
                getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Lille invite link", link))
            Toast.makeText(this, "Invite link copied.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.settingsReferralExportButton).setOnClickListener {
            exportInvites.launch("lille-invites.json")
        }
        findViewById<Button>(R.id.settingsReferralImportButton).setOnClickListener {
            importInvites.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    private fun refreshReferralCount() {
        if (!::referralCountView.isInitialized) return
        val identity = FluidIdentityStore(this).load()
        referralCountView.text =
            "Friends who joined through your invite: " +
                referralStore.completedCount(identity?.username)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun wrapParams(topMarginDp: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(topMarginDp)
        return p
    }

    /** One provider's Settings block: name, where to get a
     *  free key, saved status, the key field (plus base
     *  URL + model fields for the custom endpoint), and
     *  Save / Test / Remove. */
    private fun buildKeyBlock(spec: OwnKeyRoutes.Spec): LinearLayout {
        val isCustom = spec.route == BrainFailure.Route.CUSTOM
        val block = LinearLayout(this)
        block.orientation = LinearLayout.VERTICAL
        block.setPadding(0, dp(10), 0, dp(6))

        val name = TextView(this)
        name.text = spec.displayName
        name.textSize = 16f
        name.setTextColor(0xFFF5EFE7.toInt())
        block.addView(name, wrapParams())

        val hint = TextView(this)
        hint.text = spec.keyHint
        hint.textSize = 12f
        hint.setTextColor(0xFF8A8378.toInt())
        block.addView(hint, wrapParams(2))

        val status = TextView(this)
        status.textSize = 13f
        status.setTextColor(0xFFB9B0A2.toInt())
        block.addView(status, wrapParams(4))

        var urlField: EditText? = null
        var modelField: EditText? = null
        if (isCustom) {
            urlField = EditText(this)
            urlField.hint = "Base URL — e.g. https://your-server/v1"
            urlField.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            urlField.setSingleLine(true)
            // The saved URL is the user's own config, shown
            // so they can see/edit where the route points.
            // (The KEY field below is never prefilled.)
            urlField.setText(vault.getSecret(OwnKeyRoutes.CUSTOM_URL_VAULT_KEY) ?: "")
            block.addView(urlField, wrapParams(6))

            modelField = EditText(this)
            modelField.hint = "Model name (optional)"
            modelField.inputType = InputType.TYPE_CLASS_TEXT
            modelField.setSingleLine(true)
            modelField.setText(vault.getSecret(OwnKeyRoutes.CUSTOM_MODEL_VAULT_KEY) ?: "")
            block.addView(modelField, wrapParams(4))
        }

        val keyField = EditText(this)
        keyField.hint = if (isCustom) "API key for that endpoint" else "Paste your ${spec.displayName} key"
        keyField.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyField.setSingleLine(true)
        block.addView(keyField, wrapParams(6))

        val result = TextView(this)
        result.textSize = 13f
        result.setTextColor(0xFFB9B0A2.toInt())
        block.addView(result, wrapParams(4))

        fun refreshStatus() {
            val present = OwnKeyRoutes.presentRoutes({ n -> vault.hasSecret(n) }, lanEnabled = LanRoute.isEnabled(this))
            status.text = if (spec.route in present) {
                "Key saved — this is one of Lille's brain routes now."
            } else if (vault.hasSecret(spec.vaultKeyName)) {
                // Custom with a key but no base URL yet.
                "Key saved — add the base URL above to complete this route."
            } else {
                "No key saved."
            }
        }
        refreshStatus()

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val saveButton = Button(this).apply { text = "Save" }
        val testButton = Button(this).apply { text = "Test" }
        val removeButton = Button(this).apply { text = "Remove" }
        for (b in listOf(saveButton, testButton, removeButton)) {
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(b, p)
        }
        block.addView(row, wrapParams(6))

        saveButton.setOnClickListener {
            val key = keyField.text.toString().trim()
            if (isCustom) {
                val url = urlField?.text.toString().trim()
                if (url.isEmpty()) {
                    result.text = "Add the base URL first — a custom route " +
                        "needs both the address and the key."
                    return@setOnClickListener
                }
                vault.putSecret(OwnKeyRoutes.CUSTOM_URL_VAULT_KEY, url)
                val model = modelField?.text.toString().trim()
                if (model.isEmpty()) {
                    vault.removeSecret(OwnKeyRoutes.CUSTOM_MODEL_VAULT_KEY)
                } else {
                    vault.putSecret(OwnKeyRoutes.CUSTOM_MODEL_VAULT_KEY, model)
                }
            }
            if (key.isEmpty()) {
                result.text = if (vault.hasSecret(spec.vaultKeyName)) {
                    "The field is empty, so nothing changed — your saved " +
                        "key is untouched. Use Remove to delete it."
                } else {
                    "Paste a key first — nothing was saved."
                }
                refreshStatus()
                return@setOnClickListener
            }
            if (vault.putSecret(spec.vaultKeyName, key)) {
                keyField.text.clear()
                result.text = "Saved — it lives only in the encrypted " +
                    "vault on this phone."
                // WAKE ON NEW CAPACITY (v1.0.2): a waiting
                // task's rest was timed against the routes
                // that existed before this key — it must not
                // keep sleeping now that a new route can
                // carry it. Expedited retry for every
                // waiting task, no waiting for timers.
                TaskWorker.wakeWaitingTasks(this)
            } else {
                result.text = "That key couldn't be saved to the vault."
            }
            refreshStatus()
        }

        removeButton.setOnClickListener {
            vault.removeSecret(spec.vaultKeyName)
            if (isCustom) {
                vault.removeSecret(OwnKeyRoutes.CUSTOM_URL_VAULT_KEY)
                vault.removeSecret(OwnKeyRoutes.CUSTOM_MODEL_VAULT_KEY)
                urlField?.text?.clear()
                modelField?.text?.clear()
            }
            keyField.text.clear()
            result.text = "Removed — that route is gone from her chain."
            refreshStatus()
        }

        testButton.setOnClickListener {
            val typedKey = keyField.text.toString().trim()
            val key = typedKey.ifEmpty { vault.getSecret(spec.vaultKeyName) }
            if (key.isNullOrEmpty()) {
                result.text = "No key to test — paste one above or save " +
                    "one first."
                return@setOnClickListener
            }
            val typedUrl = urlField?.text.toString().trim()
            val base = if (isCustom) {
                typedUrl.ifEmpty {
                    vault.getSecret(OwnKeyRoutes.CUSTOM_URL_VAULT_KEY)
                }
            } else {
                null
            }
            if (isCustom && base.isNullOrEmpty()) {
                result.text = "Add the base URL first — there is no " +
                    "address to test against."
                return@setOnClickListener
            }
            testButton.isEnabled = false
            result.text = "Testing — one small call to ${spec.displayName}…"
            Thread {
                var code: Int? = null
                var count: Int? = null
                try {
                    val request = Request.Builder()
                        .url(OwnKeyRoutes.modelsUrl(spec, base))
                        .header("Authorization", "Bearer $key")
                        .get()
                        .build()
                    testClient.newCall(request).execute().use { response ->
                        code = response.code
                        if (response.code in 200..299) {
                            count = OwnKeyRoutes.countModels(
                                response.body?.string() ?: ""
                            )
                        }
                    }
                } catch (e: Exception) {
                    code = null
                }
                val line = OwnKeyRoutes.testLine(spec, code, count)
                runOnUiThread {
                    result.text = line
                    testButton.isEnabled = true
                }
            }.start()
        }

        return block
    }
}
