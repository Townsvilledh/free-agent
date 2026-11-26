package org.freeagent.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fluid login screen (v0.10, LOGIN.md) — the first screen
 * whenever the FluidGate says LOGIN. Flow:
 *
 * 1. The user types their Fluid username.
 * 2. Lille fetches the Fluid names registry and checks the
 *    name (FluidGate.lookup): unknown name, or a name with no
 *    published EVM address, fails HERE with a specific message
 *    — before any pairing exists.
 * 3. Lille records the pending attempt (so an approval that
 *    lands after Android kills this screen still verifies) and
 *    asks FluidLogin for a pairing code (wc: URI).
 * 4. The user copies the code into Fluid (More →
 *    WalletConnect) and approves there.
 * 5. On approval, FluidGate.verify compares the connected
 *    wallet's eip155 account with the registry address for the
 *    typed name. Match → identity stored, app opens. Mismatch
 *    → the session is ended and the refusal says why.
 *
 * The gate guards the UI only. Background workers never come
 * through here and never consult login state.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var store: FluidIdentityStore
    private lateinit var nameInput: EditText
    private lateinit var connectButton: Button
    private lateinit var status: TextView
    private lateinit var pairingBox: View
    private lateinit var pairingCode: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The registry body behind the current attempt, kept so
     *  the approval can be verified through FluidGate.verify
     *  exactly as the JVM tests pin it. */
    @Volatile
    private var registryJson: String? = null

    @Volatile
    private var busy = false

    private val wcListener = object : FluidLogin.Listener {
        override fun onApproved(accounts: List<String>) {
            runOnUiThread { handleApproved(accounts) }
        }

        override fun onRejected() {
            runOnUiThread {
                busy = false
                store.clearPending()
                showForm(FluidGate.rejectedMessage())
            }
        }

        override fun onSessionDeleted() {
            // A session died elsewhere (e.g. disconnected from
            // inside Fluid). If it was ours, the stored identity
            // no longer has a live session behind it.
            val identity = store.load()
            if (identity != null) {
                val accounts = FluidLogin.sessionAccounts()
                if (accounts == null ||
                    !FluidGate.sessionMatchesIdentity(accounts, identity.address)
                ) {
                    store.clear()
                }
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                if (busy) {
                    busy = false
                    store.clearPending()
                    showForm(message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FluidIdentityStore(this)
        FluidLogin.init(this)
        FluidLogin.listener = wcListener

        // An approval may have landed while no screen was alive.
        FluidLogin.reconcilePending(store)
        val identity = store.load()
        if (identity != null &&
            FluidGate.decide(identity.username, FluidLogin.sessionStateFor(store)) ==
            FluidGate.Decision.OPEN
        ) {
            openMain()
            return
        }

        setContentView(R.layout.activity_login)
        nameInput = findViewById(R.id.fluidNameInput)
        connectButton = findViewById(R.id.fluidConnectButton)
        status = findViewById(R.id.fluidStatus)
        pairingBox = findViewById(R.id.pairingBox)
        pairingCode = findViewById(R.id.pairingCode)

        // Prefill: a returning user whose session expired, or a
        // pending attempt interrupted mid-flight.
        val prefill = identity?.username ?: store.loadPending()?.username
        if (!prefill.isNullOrBlank()) {
            nameInput.setText(prefill)
            status.text = "Welcome back — your Fluid session ended, " +
                "so Lille needs one fresh connection to open."
        }

        connectButton.setOnClickListener { startConnect() }
        findViewById<Button>(R.id.copyPairingButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Lille WalletConnect code", pairingCode.text.toString())
            )
            Toast.makeText(this, "Code copied — paste it in Fluid.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.openFluidSheetButton).setOnClickListener {
            // "Sign where you stand" (v0.12): Fluid opens
            // INSIDE Lille — no app-switching. The pairing
            // code rides the clipboard, because Fluid has no
            // wc: deep link today (its WalletConnect panel is
            // paste-only — checked against Fluid's source,
            // 2026-11-26). The approval still reaches the
            // session listener below: this screen stays alive
            // under the sheet.
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Lille WalletConnect code", pairingCode.text.toString())
            )
            Toast.makeText(
                this,
                "Code copied — in Fluid: More → WalletConnect → paste → Approve.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, FluidSheetActivity::class.java))
        }
        findViewById<Button>(R.id.cancelPairingButton).setOnClickListener {
            busy = false
            FluidLogin.cancelPendingPairing()
            store.clearPending()
            showForm("Cancelled — nothing was connected.")
        }
        findViewById<Button>(R.id.getFluidButton).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://fluidwallet.dpdns.org"))
            )
        }
        FluidLogin.initError?.let {
            status.text = "WalletConnect couldn't start: $it"
        }
    }

    override fun onDestroy() {
        if (FluidLogin.listener === wcListener) FluidLogin.listener = null
        super.onDestroy()
    }

    private fun startConnect() {
        if (busy) return
        val name = FluidGate.normaliseName(nameInput.text.toString())
        if (name.isEmpty()) {
            status.text = "Type your Fluid username first."
            return
        }
        busy = true
        connectButton.isEnabled = false
        pairingBox.visibility = View.GONE
        status.text = "Checking the Fluid names registry…"
        fetchRegistry { json, reachable ->
            runOnUiThread {
                if (!reachable || json == null) {
                    busy = false
                    showForm(FluidGate.registryUnreachableMessage())
                    return@runOnUiThread
                }
                registryJson = json
                when (val found = FluidGate.lookup(json, name)) {
                    is FluidGate.Lookup.Found -> {
                        store.savePending(
                            FluidIdentityStore.Pending(name, found.evmAddress)
                        )
                        status.text = "Printing your WalletConnect code…"
                        FluidLogin.connect(
                            onPairingUri = { uri ->
                                runOnUiThread {
                                    pairingCode.text = uri
                                    pairingBox.visibility = View.VISIBLE
                                    status.text = ""
                                }
                            },
                            onError = { message ->
                                runOnUiThread {
                                    busy = false
                                    store.clearPending()
                                    showForm(message)
                                }
                            }
                        )
                    }
                    FluidGate.Lookup.NameNotFound -> {
                        busy = false
                        showForm(FluidGate.notFoundMessage(name))
                    }
                    FluidGate.Lookup.NoEvmAddress -> {
                        busy = false
                        showForm(FluidGate.noEvmMessage(name))
                    }
                    FluidGate.Lookup.RegistryInvalid -> {
                        busy = false
                        showForm(FluidGate.registryUnreachableMessage())
                    }
                }
            }
        }
    }

    /** Registry fetch with Fluid's own fallback mirror: the
     *  canonical URL first, the Netlify mirror if it fails. */
    private fun fetchRegistry(done: (String?, Boolean) -> Unit) {
        val request = Request.Builder().url(FluidGate.REGISTRY_URL).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fetchFallback(done)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) done(body, true)
                else fetchFallback(done)
            }
        })
    }

    private fun fetchFallback(done: (String?, Boolean) -> Unit) {
        val request = Request.Builder().url(FluidGate.REGISTRY_FALLBACK_URL).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                done(null, false)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) done(body, true)
                else done(null, false)
            }
        })
    }

    private fun handleApproved(accounts: List<String>) {
        val pending = store.loadPending()
        val json = registryJson
        if (pending != null && json != null) {
            when (val result = FluidGate.verify(json, pending.username, accounts)) {
                is FluidGate.Verify.Verified -> {
                    store.save(
                        FluidIdentityStore.Identity(pending.username, result.address)
                    )
                    store.clearPending()
                    openMain()
                }
                is FluidGate.Verify.Mismatch -> {
                    FluidLogin.disconnectSessions(store, forgetIdentity = false)
                    busy = false
                    showForm(FluidGate.mismatchMessage(pending.username))
                }
                FluidGate.Verify.NoEip155Account -> {
                    FluidLogin.disconnectSessions(store, forgetIdentity = false)
                    busy = false
                    showForm(FluidGate.noEip155Message())
                }
                is FluidGate.Verify.LookupFailed -> {
                    FluidLogin.disconnectSessions(store, forgetIdentity = false)
                    busy = false
                    showForm(
                        when (result.lookup) {
                            FluidGate.Lookup.NameNotFound ->
                                FluidGate.notFoundMessage(pending.username)
                            FluidGate.Lookup.NoEvmAddress ->
                                FluidGate.noEvmMessage(pending.username)
                            else -> FluidGate.registryUnreachableMessage()
                        }
                    )
                }
            }
            return
        }
        // The screen was recreated mid-flight: the pending
        // record + live session are enough on their own.
        if (FluidLogin.reconcilePending(store) != null) {
            openMain()
            return
        }
        busy = false
        showForm(
            "A wallet connected, but Lille couldn't match it to a login " +
                "attempt. Tap Connect and try once more."
        )
    }

    private fun showForm(message: String) {
        pairingBox.visibility = View.GONE
        connectButton.isEnabled = true
        status.text = message
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
