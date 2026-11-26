package org.freeagent.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Settings — who Lille is connected as, her wallet,
 * backup & restore, your own API keys (v0.12.1), and
 * disconnecting the Fluid name. The disconnect mirrors
 * the one on the Tasks screen (same FluidLogin call): it
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

        val container = findViewById<LinearLayout>(R.id.settingsKeysContainer)
        for (spec in OwnKeyRoutes.SPECS) {
            container.addView(buildKeyBlock(spec))
        }
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
            val present = OwnKeyRoutes.presentRoutes { n -> vault.hasSecret(n) }
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
