package org.freeagent.app

import android.app.Application
import android.content.Context
import com.walletconnect.android.Core
import com.walletconnect.android.CoreClient
import com.walletconnect.android.relay.ConnectionType
import com.walletconnect.sign.client.Sign
import com.walletconnect.sign.client.SignClient

/**
 * WalletConnect dapp side of the Fluid login gate (v0.10,
 * LOGIN.md) — a thin wrapper over the official WalletConnect
 * Kotlin SDK (com.walletconnect:sign). All gate DECISIONS live
 * in the pure FluidGate component; this object only speaks the
 * protocol:
 *
 * - init(): CoreClient + SignClient against the relay, using
 *   Ethuram's existing Reown projectId (the "Fluid Wallet"
 *   project from Fluid's WalletGuide work — his own project,
 *   reused for his own companion app).
 * - connect(): proposes a session requesting ONLY the eip155
 *   namespace, ONLY chain eip155:1, ONLY the personal_sign
 *   method. No transaction method is ever requested — the
 *   session exists to prove which wallet owns a Fluid name,
 *   nothing more.
 * - The SDK persists the session itself; gate checks read it
 *   back from local storage, so a remembered login needs no
 *   network round-trip.
 *
 * Approval can land while the login screen is dead (the user
 * approves inside Fluid; Android may reclaim Lille meanwhile),
 * so verification is reconcilable after the fact:
 * savePending() before connecting, reconcilePending() from any
 * screen once a live session exists.
 */
object FluidLogin {

    /** Ethuram's existing Reown project ("Fluid Wallet",
     *  created for Fluid's WalletGuide listing). A projectId is
     *  a public client identifier, not a credential. */
    const val PROJECT_ID = "220705e3c4dc16fd3c8aaf353b2e0daa"
    private const val RELAY_URL = "wss://relay.walletconnect.com?projectId=$PROJECT_ID"

    interface Listener {
        fun onApproved(accounts: List<String>)
        fun onRejected()
        fun onSessionDeleted()
        fun onError(message: String)
    }

    @Volatile
    var listener: Listener? = null

    /** In-flight personal_sign requests (v0.11 backup key),
     *  keyed by the request id the SDK assigns. The session's
     *  ONLY method is personal_sign, and backup is its only
     *  caller — responses are routed here, never to the UI
     *  listener. Callbacks receive (signature, error). */
    private val signCallbacks =
        java.util.concurrent.ConcurrentHashMap<Long, (String?, String?) -> Unit>()

    @Volatile
    private var initialised = false

    @Volatile
    var initError: String? = null
        private set

    private val delegate = object : SignClient.DappDelegate {
        override fun onSessionApproved(approvedMessage: Sign.Model.ApprovedSession) {
            listener?.onApproved(approvedMessage.accounts)
        }

        override fun onSessionRejected(rejectedMessage: Sign.Model.RejectedSession) {
            listener?.onRejected()
        }

        override fun onSessionUpdate(updatedSession: Sign.Model.UpdatedSession) {}

        override fun onSessionExtend(session: Sign.Model.Session) {}

        override fun onSessionEvent(sessionEvent: Sign.Model.SessionEvent) {}

        override fun onSessionDelete(deletedSession: Sign.Model.DeletedSession) {
            listener?.onSessionDeleted()
        }

        override fun onSessionRequestResponse(response: Sign.Model.SessionRequestResponse) {
            // v0.11: route personal_sign results (the backup
            // key flow) to their waiting callers by request id.
            // sign 2.21.0 shape (verified against the SDK
            // sources): SessionRequestResponse is a data class
            // wrapping a sealed JsonRpcResponse.
            when (val result = response.result) {
                is Sign.Model.SessionRequestResponse.JsonRpcResult ->
                    signCallbacks.remove(result.id)?.invoke(result.result, null)
                is Sign.Model.SessionRequestResponse.JsonRpcError ->
                    signCallbacks.remove(result.id)?.invoke(
                        null, result.message.ifEmpty { "Signing was refused." }
                    )
            }
        }

        // sign 2.21.0's DappDelegate has no proposal-expiry
        // callback: an expired pairing simply never approves,
        // and the waiting screen's Cancel + fresh Connect is
        // the honest path (LOGIN.md says so).

        override fun onConnectionStateChange(state: Sign.Model.ConnectionState) {}

        override fun onError(error: Sign.Model.Error) {
            listener?.onError(error.throwable.message ?: "WalletConnect error.")
        }
    }

    /** Idempotent. Safe to call from any activity; a failure is
     *  recorded in [initError] instead of thrown, so the gate
     *  can degrade honestly (FluidGate.SessionState.UNKNOWN). */
    fun init(context: Context) {
        if (initialised) return
        try {
            val app = context.applicationContext as Application
            val metaData = Core.Model.AppMetaData(
                name = "Lille",
                description = "Lille — free open-source autonomous agent. " +
                    "Connect your Fluid wallet to prove your Fluid name.",
                url = "https://github.com/Townsvilledh/free-agent",
                icons = listOf(
                    "https://raw.githubusercontent.com/Townsvilledh/" +
                        "free-agent/main/assets/lille-mascot-jacket.webp"
                ),
                redirect = "org.freeagent.app://fluid-login"
            )
            CoreClient.initialize(
                relayServerUrl = RELAY_URL,
                connectionType = ConnectionType.AUTOMATIC,
                application = app,
                metaData = metaData
            ) { error ->
                initError = error.throwable.message ?: "Relay connection error."
                listener?.onError(initError ?: "Relay connection error.")
            }
            SignClient.initialize(Sign.Params.Init(CoreClient)) { error ->
                initError = error.throwable.message ?: "WalletConnect init error."
            }
            SignClient.setDappDelegate(delegate)
            initialised = true
        } catch (e: Exception) {
            initError = e.message ?: "WalletConnect failed to start."
        }
    }

    fun isInitialised(): Boolean = initialised

    /**
     * Propose a session and hand back the pairing URI
     * (wc:…) for the user to paste into Fluid. The proposal is
     * deliberately minimal: eip155 only, chain eip155:1 only,
     * personal_sign only.
     */
    fun connect(onPairingUri: (String) -> Unit, onError: (String) -> Unit) {
        if (!initialised) {
            onError(initError ?: "WalletConnect is not ready yet — try again in a moment.")
            return
        }
        val namespaces = mapOf(
            FluidGate.NAMESPACE to Sign.Model.Namespace.Proposal(
                chains = listOf(FluidGate.CHAIN_ID),
                methods = listOf(FluidGate.METHOD_PERSONAL_SIGN),
                events = emptyList()
            )
        )
        try {
            // sign 2.21.0 flow: create the pairing first (its uri
            // IS the wc: code), then propose the session on it.
            val pairing = CoreClient.Pairing.create { error ->
                onError(
                    error.throwable.message
                        ?: "Could not create a pairing code."
                )
            }
            if (pairing == null) {
                onError("Could not create a pairing code.")
                return
            }
            SignClient.connect(
                Sign.Params.Connect(
                    namespaces = namespaces,
                    pairing = pairing
                ),
                onSuccess = { onPairingUri(pairing.uri) },
                onError = { error ->
                    onError(
                        error.throwable.message
                            ?: "Could not create a pairing code."
                    )
                }
            )
        } catch (e: Exception) {
            onError(e.message ?: "Could not create a pairing code.")
        }
    }

    /** The topic of the live session that carries [address]
     *  (the verified identity's account), or null. */
    fun activeSessionTopicFor(address: String): String? {
        if (!initialised) return null
        return try {
            SignClient.getListOfActiveSessions().firstOrNull { session ->
                val accounts = session.namespaces.values.flatMap { it.accounts }
                FluidGate.sessionMatchesIdentity(accounts, address)
            }?.topic
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ask the connected Fluid wallet to personal_sign
     * [message] (v0.11, BACKUP.md): the backup key flow. The
     * message goes hex-encoded, the WalletConnect convention
     * for personal_sign; params are [message, address]. The
     * wallet shows its own approval sheet — the signature is
     * the user's to give or refuse, every time. The result is
     * delivered to [onResult] exactly once (signature or
     * error); the signature is never stored by this layer.
     */
    fun requestPersonalSign(
        message: String,
        address: String,
        onResult: (signature: String?, error: String?) -> Unit
    ) {
        if (!initialised) {
            onResult(null, initError ?: "WalletConnect is not ready yet.")
            return
        }
        val topic = activeSessionTopicFor(address)
        if (topic == null) {
            onResult(null, "No live Fluid session — reconnect your Fluid name first.")
            return
        }
        val hexMessage = "0x" + message.toByteArray(Charsets.UTF_8)
            .joinToString("") { "%02x".format(it) }
        val params = org.json.JSONArray()
            .put(hexMessage)
            .put(address)
            .toString()
        try {
            SignClient.request(
                Sign.Params.Request(
                    sessionTopic = topic,
                    method = FluidGate.METHOD_PERSONAL_SIGN,
                    params = params,
                    chainId = FluidGate.CHAIN_ID
                ),
                // Explicit parameter type picks the overload
                // whose onSuccess receives the SentRequest.
                onSuccess = { sent: Sign.Model.SentRequest ->
                    signCallbacks[sent.requestId] = onResult
                },
                onError = { error ->
                    onResult(
                        null,
                        error.throwable.message ?: "Could not send the signing request."
                    )
                }
            )
        } catch (e: Exception) {
            onResult(null, e.message ?: "Could not send the signing request.")
        }
    }

    /** All accounts of all live sessions, as CAIP-10 strings,
     *  read from the SDK's local storage — no network needed.
     *  Null when the stack isn't initialised. */
    fun sessionAccounts(): List<String>? {
        if (!initialised) return null
        return try {
            SignClient.getListOfActiveSessions().flatMap { session ->
                session.namespaces.values.flatMap { it.accounts }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The gate's session question, in FluidGate's terms. */
    fun sessionStateFor(store: FluidIdentityStore): FluidGate.SessionState {
        val identity = store.load() ?: return FluidGate.SessionState.GONE
        val accounts = sessionAccounts() ?: return FluidGate.SessionState.UNKNOWN
        return if (FluidGate.sessionMatchesIdentity(accounts, identity.address)) {
            FluidGate.SessionState.LIVE
        } else {
            FluidGate.SessionState.GONE
        }
    }

    /**
     * Complete a verification whose approval landed while no
     * login screen was alive: if a pending attempt exists and a
     * live session's account is the pending name's expected
     * address, the identity is saved and the pending record
     * cleared. Returns the verified identity, or null.
     */
    fun reconcilePending(store: FluidIdentityStore): FluidIdentityStore.Identity? {
        val pending = store.loadPending() ?: return null
        val accounts = sessionAccounts() ?: return null
        val connected = FluidGate.eip155Address(accounts) ?: return null
        if (!FluidGate.addressesEqual(connected, pending.expectedAddress)) return null
        val identity = FluidIdentityStore.Identity(pending.username, connected)
        store.save(identity)
        store.clearPending()
        return identity
    }

    /** Cancel an unused pending pairing (the user backed out
     *  of the waiting screen): disconnect every pairing that
     *  no live session is using. */
    fun cancelPendingPairing() {
        if (!initialised) return
        try {
            val sessionTopics = SignClient.getListOfActiveSessions()
                .map { it.topic }.toSet()
            CoreClient.Pairing.getPairings()
                .filter { it.topic !in sessionTopics }
                .forEach { pairing ->
                    CoreClient.Pairing.disconnect(pairing.topic) {}
                }
        } catch (e: Exception) {
            // Best effort: an orphaned pairing expires on its own.
        }
    }

    /** End every session and drop every pairing. With
     *  [forgetIdentity] the stored identity goes too — the
     *  full "Disconnect Fluid name" (Tasks screen, so the gate
     *  can be re-tested from scratch). Without it, only the
     *  pending attempt is cleared — the mismatch path, where a
     *  wrong wallet connected and must not linger as a session. */
    fun disconnectSessions(store: FluidIdentityStore, forgetIdentity: Boolean) {
        if (initialised) {
            try {
                SignClient.getListOfActiveSessions().forEach { session ->
                    SignClient.disconnect(
                        Sign.Params.Disconnect(
                            sessionTopic = session.topic
                        )
                    ) {}
                }
                CoreClient.Pairing.getPairings().forEach { pairing ->
                    CoreClient.Pairing.disconnect(pairing.topic) {}
                }
            } catch (e: Exception) {
                // Local records are cleared regardless — the gate
                // state never depends on the relay being reachable.
            }
        }
        if (forgetIdentity) store.clear()
        store.clearPending()
    }
}
