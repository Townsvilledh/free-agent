package org.freeagent.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings (v0.11): a short, honest list — who Lille is
 * connected as, backup & restore, and disconnecting the
 * Fluid name. The disconnect mirrors the one on the Tasks
 * screen (same FluidLogin call): it ends every WalletConnect
 * session and forgets the stored identity, so the login gate
 * runs again on next open.
 */
class SettingsActivity : AppCompatActivity() {

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
    }
}
