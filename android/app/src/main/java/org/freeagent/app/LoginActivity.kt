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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
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
    private lateinit var inviterInput: EditText
    private lateinit var connectButton: Button
    private lateinit var status: TextView
    private lateinit var pairingBox: View
    private lateinit var pairingCode: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Registry fetches run synchronously here (source walk +
     *  retry with backoff, FluidGate's fetch policy) — the
     *  same shape WalletEngine uses for its name lookups. */
    private val ioExecutor = Executors.newSingleThreadExecutor()

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
            // `message` is the SDK's raw error text — shown
            // nowhere; see the mapping inside.
            runOnUiThread {
                if (busy) {
                    // SDK errors during a login attempt are
                    // relay-class failures; the user gets
                    // Lille's plain-language message, never the
                    // SDK's raw text (2026-11-27 defect).
                    busy = false
                    store.clearPending()
                    showForm(FluidGate.relayUnreachableMessage())
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
        inviterInput = findViewById(R.id.inviterNameInput)
        connectButton = findViewById(R.id.fluidConnectButton)
        status = findViewById(R.id.fluidStatus)
        pairingBox = findViewById(R.id.pairingBox)
        pairingCode = findViewById(R.id.pairingCode)

        // Referrals (REFERRALS.md): if an invite link reached
        // the app before sign-in, its inviter pre-fills the
        // optional field. The user can edit or clear it —
        // what stands there at the first successful
        // connection is the inviter who counts.
        ReferralStore(this).pendingReferrer()?.let { inviterInput.setText(it) }

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
            // INSIDE Lille — no app-switching. Since v0.13.1
            // the pairing code rides Fluid's #wc= DEEP LINK
            // (Fluid build stamp 4d7f509193): the sheet loads
            // Fluid straight at its WalletConnect panel with
            // the pairing started — no paste step. The code
            // still goes to the clipboard as the fallback: if
            // the deep-link load fails, the sheet lands on
            // Fluid's plain page and the paste flow works
            // exactly as before. The approval reaches the
            // session listener below either way: this screen
            // stays alive under the sheet.
            val code = pairingCode.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Lille WalletConnect code", code)
            )
            Toast.makeText(
                this,
                "Opening Fluid here — unlock it and approve the connection.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(this, FluidSheetActivity::class.java)
                    .putExtra(FluidSheetActivity.EXTRA_PAIRING_URI, code)
            )
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
        ioExecutor.shutdown()
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
        ioExecutor.execute {
            val resolution = fetchAndResolve(name)
            runOnUiThread { onRegistryResolved(name, resolution) }
        }
    }

    /**
     * The registry walk (2026-11-27 fix), on [ioExecutor]:
     * every source in FluidGate.REGISTRY_SOURCES, each with
     * FluidGate's retry policy, and only bodies passing
     * FluidGate.assessRegistry count as evidence — a stale or
     * truncated copy can never again answer for the registry
     * (the old Netlify fallback held 2 of 29 names and would
     * have told real users their name does not exist). The
     * walk stops early once a valid body finds the name; the
     * freshest valid body is saved as the on-device last-good
     * copy. When NO source yields a valid body, the saved copy
     * is resolved instead and the outcome is marked fromCache,
     * so the UI labels it honestly.
     */
    private data class RegistryOutcome(
        val resolution: FluidGate.Resolution,
        val fromCache: Boolean
    )

    private fun fetchAndResolve(name: String): RegistryOutcome {
        val bodies = mutableListOf<String>()
        for (url in FluidGate.REGISTRY_SOURCES) {
            var attempt = 0
            while (attempt < FluidGate.REGISTRY_FETCH_ATTEMPTS) {
                attempt++
                val body = httpGet(url)
                if (body != null &&
                    FluidGate.assessRegistry(body) is
                    FluidGate.RegistryAssessment.Valid
                ) {
                    bodies += body
                    break
                }
                if (attempt < FluidGate.REGISTRY_FETCH_ATTEMPTS) {
                    try {
                        Thread.sleep(FluidGate.registryRetryDelayMs(attempt))
                    } catch (e: InterruptedException) {
                        return RegistryOutcome(
                            FluidGate.resolveAcrossSources(bodies, name),
                            fromCache = false
                        )
                    }
                }
            }
            if (FluidGate.resolveAcrossSources(bodies, name) is
                FluidGate.Resolution.Found
            ) {
                break
            }
        }
        // The first valid body came from the highest-priority
        // reachable source — that is the copy worth saving.
        bodies.firstOrNull()?.let {
            store.saveRegistryCache(it, System.currentTimeMillis())
        }
        val live = FluidGate.resolveAcrossSources(bodies, name)
        if (live !is FluidGate.Resolution.NoValidRegistry) {
            return RegistryOutcome(live, fromCache = false)
        }
        val cache = store.loadRegistryCache()
        if (cache != null &&
            FluidGate.assessRegistry(cache.json) is
            FluidGate.RegistryAssessment.Valid
        ) {
            return RegistryOutcome(
                FluidGate.resolveAcrossSources(listOf(cache.json), name),
                fromCache = true
            )
        }
        return RegistryOutcome(live, fromCache = false)
    }

    private fun httpGet(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun onRegistryResolved(name: String, outcome: RegistryOutcome) {
        when (val resolution = outcome.resolution) {
            is FluidGate.Resolution.Found -> {
                registryJson = resolution.registryJson
                store.savePending(
                    FluidIdentityStore.Pending(name, resolution.evmAddress)
                )
                status.text = "Printing your WalletConnect code…"
                FluidLogin.connect(
                    onPairingUri = { uri ->
                        runOnUiThread {
                            pairingCode.text = uri
                            pairingBox.visibility = View.VISIBLE
                            status.text =
                                if (outcome.fromCache) {
                                    FluidGate.cachedRegistryNotice()
                                } else {
                                    ""
                                }
                        }
                    },
                    onError = { _ ->
                        runOnUiThread {
                            busy = false
                            store.clearPending()
                            showForm(FluidGate.relayUnreachableMessage())
                        }
                    }
                )
            }
            FluidGate.Resolution.NoEvmAddress -> {
                busy = false
                showForm(FluidGate.noEvmMessage(name))
            }
            FluidGate.Resolution.NameNotFound -> {
                busy = false
                showForm(
                    if (outcome.fromCache) {
                        FluidGate.cachedNotFoundMessage(name)
                    } else {
                        FluidGate.notFoundMessage(name)
                    }
                )
            }
            FluidGate.Resolution.NoValidRegistry -> {
                busy = false
                showForm(FluidGate.registryUnreachableMessage())
            }
        }
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
                    // Referrals: the sign-in is the proof — a
                    // referral completes exactly here, against
                    // the inviter field's contents (or the
                    // pending inviter behind it).
                    ReferralStore(this).settleOnLogin(
                        pending.username, inviterInput.text?.toString()
                    )
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
