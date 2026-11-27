package org.freeagent.app

import org.json.JSONObject

/**
 * Fluid login gate (v0.10) — the pure decision component behind
 * Lille's startup login, in the same style as BrainPacing:
 * no Android, no I/O, pinned by JVM tests. The Android glue
 * (LoginActivity, FluidLogin, FluidIdentityStore) only fetches
 * inputs and acts on the decisions made here.
 *
 * The feature (LOGIN.md): Lille is free, and every Lille user
 * is a Fluid user with a Fluid name. At first run the user
 * types their Fluid username; Lille generates a WalletConnect
 * pairing code; the user pastes it into Fluid (More →
 * WalletConnect) and approves; Lille then verifies that the
 * wallet that connected actually OWNS the typed name — the
 * connected eip155 account must equal the name's `evm` address
 * in the published Fluid names registry (names.json).
 *
 * WalletConnect itself only ever yields an ADDRESS, never a
 * name — this registry check is what ties the two together.
 *
 * Honest framing: this is an identity + promotion gate, not a
 * security wall. Lille is open source; the gate decides what
 * the shipped app does, it does not make the app tamper-proof.
 */
object FluidGate {

    /** Canonical published registry (Fluid's own wallet-core
     *  NAMES_REGISTRY_URL). */
    const val REGISTRY_URL = "https://fluidwallet.dpdns.org/names.json"

    /** Live mirrors of the SAME published registry file, on
     *  independent hosts/CDNs: the fluid-wallet repo's gh-pages
     *  branch served raw from GitHub, and the same file via the
     *  jsDelivr CDN. (townsvilledh.github.io/fluid-wallet/
     *  names.json is NOT a mirror: GitHub Pages 301-redirects it
     *  to the canonical custom domain, so it fails exactly when
     *  the canonical host does — verified live 2026-11-27.)
     *
     *  The old fallback — fluidwallet.netlify.app — was REMOVED
     *  (2026-11-27): Netlify's build froze when the team's
     *  credits cap hit, and it now serves a 606-byte snapshot
     *  holding 2 names (dripbot, ethuam) while the live registry
     *  holds 29+. A "fallback" that cannot validate any current
     *  user is worse than none: it answers NameNotFound for real
     *  users — a silent wrong answer. Every source below is
     *  additionally gated by [assessRegistry], so a stale or
     *  truncated copy is never trusted even if a host regresses.
     *
     *  All three sources were verified live on 2026-11-27:
     *  canonical 200 direct (32 names), GitHub raw 200 direct
     *  (32 names, byte-identical), jsDelivr 200 direct (29
     *  names — a slightly older CDN cache, still a valid live
     *  copy; [resolveAcrossSources] never lets an older valid
     *  copy settle a just-published name as not-found). */
    const val REGISTRY_MIRROR_GITHUB_RAW =
        "https://raw.githubusercontent.com/Townsvilledh/fluid-wallet/gh-pages/names.json"
    const val REGISTRY_MIRROR_JSDELIVR =
        "https://cdn.jsdelivr.net/gh/Townsvilledh/fluid-wallet@gh-pages/names.json"

    /** Every registry source, in the order the login glue
     *  tries them: canonical first, then the live mirrors. */
    val REGISTRY_SOURCES: List<String> = listOf(
        REGISTRY_URL, REGISTRY_MIRROR_GITHUB_RAW, REGISTRY_MIRROR_JSDELIVR
    )

    /** Fetch policy for one source (the glue applies it):
     *  up to [REGISTRY_FETCH_ATTEMPTS] attempts per source,
     *  waiting [registryRetryDelayMs] between them. Sized for
     *  slow mobile networks: a single dropped connection must
     *  not dead-end a login while other live copies exist. */
    const val REGISTRY_FETCH_ATTEMPTS = 2
    fun registryRetryDelayMs(failedAttempt: Int): Long = 1500L * failedAttempt

    /** Smallest name count a fetched body may carry and still
     *  be trusted as the registry. The live registry held 29
     *  names on 2026-11-27 and only grows; the frozen Netlify
     *  snapshot holds 2. A body below this floor is a stale or
     *  truncated copy — never evidence about any name. */
    const val MIN_PLAUSIBLE_REGISTRY_NAMES = 10

    /** Whether a fetched body is a registry worth trusting. */
    sealed class RegistryAssessment {
        data class Valid(val nameCount: Int) : RegistryAssessment()
        object Invalid : RegistryAssessment()
    }

    /**
     * Assess a fetched registry body BEFORE trusting it:
     * it must parse as a JSON object and carry at least
     * [MIN_PLAUSIBLE_REGISTRY_NAMES] names. This is what stops
     * a frozen mirror's 2-name snapshot from ever again
     * answering lookups (2026-11-27 defect).
     */
    fun assessRegistry(registryJson: String): RegistryAssessment {
        val root = try {
            JSONObject(registryJson)
        } catch (e: Exception) {
            return RegistryAssessment.Invalid
        }
        val count = root.length()
        return if (count >= MIN_PLAUSIBLE_REGISTRY_NAMES) {
            RegistryAssessment.Valid(count)
        } else {
            RegistryAssessment.Invalid
        }
    }

    /** Outcome of resolving a name across every fetched
     *  source body (see [resolveAcrossSources]). */
    sealed class Resolution {
        data class Found(val evmAddress: String, val registryJson: String) :
            Resolution()
        object NoEvmAddress : Resolution()
        object NameNotFound : Resolution()
        object NoValidRegistry : Resolution()
    }

    /**
     * Resolve [rawName] across the bodies fetched from the
     * registry sources, in source order. Only bodies that pass
     * [assessRegistry] count as evidence:
     * - the first valid body that FINDS the name wins (a valid
     *   body that lacks the name does not settle it — a later,
     *   fresher mirror may carry a just-published name);
     * - NameNotFound is concluded only when at least one valid
     *   body was checked and none carried the name;
     * - when nothing fetched is valid, the honest outcome is
     *   NoValidRegistry (the caller may still try its on-device
     *   saved copy — that decision, and its honest labelling,
     *   live in the glue and the messages below).
     */
    fun resolveAcrossSources(bodies: List<String>, rawName: String): Resolution {
        var sawValid = false
        var sawNoEvm = false
        for (body in bodies) {
            if (assessRegistry(body) !is RegistryAssessment.Valid) continue
            sawValid = true
            when (val found = lookup(body, rawName)) {
                is Lookup.Found -> return Resolution.Found(found.evmAddress, body)
                Lookup.NoEvmAddress -> sawNoEvm = true
                else -> {}
            }
        }
        return when {
            sawNoEvm -> Resolution.NoEvmAddress
            sawValid -> Resolution.NameNotFound
            else -> Resolution.NoValidRegistry
        }
    }

    /** Fluid's WalletConnect is EVM-only in v1. The login
     *  itself needs only chain eip155:1 and personal_sign —
     *  the least privilege that proves wallet control.
     *
     *  v0.13.1 widens the session PROPOSAL (see
     *  [proposalChains] / [proposalMethods]): the session
     *  also asks for eth_sendTransaction on every chain her
     *  companion wallet operates on, so a main-wallet send
     *  can be requested over the same session later
     *  ("sign where you stand", WALLET.md). Fluid has
     *  accepted eth_sendTransaction over WalletConnect all
     *  along — the earlier gate was Lille's own minimal
     *  proposal: WalletKit rejects any method the session
     *  never granted. Identity verification is unchanged:
     *  it still reads the connected eip155 account (any
     *  chain's spelling of it) against the registry.
     *
     *  A wallet may approve LESS than proposed (an older
     *  Fluid, or a user-narrowed approval): the login then
     *  still works, and FluidTx degrades transaction
     *  requests to an honest "unavailable" — never a crash,
     *  never a silent failure. */
    const val CHAIN_ID = "eip155:1"
    const val NAMESPACE = "eip155"
    const val METHOD_PERSONAL_SIGN = "personal_sign"
    const val METHOD_SEND_TRANSACTION = "eth_sendTransaction"

    /** Every chain the session proposal asks for, as CAIP-2
     *  strings — exactly her companion wallet's EVM chains
     *  (WalletGate.CHAINS), in the same order. */
    fun proposalChains(): List<String> =
        WalletGate.CHAINS.map { "$NAMESPACE:${it.chainId}" }

    /** Every method the session proposal asks for:
     *  personal_sign (identity + the backup key) and
     *  eth_sendTransaction (main-wallet sends). */
    fun proposalMethods(): List<String> =
        listOf(METHOD_PERSONAL_SIGN, METHOD_SEND_TRANSACTION)

    /** The CAIP-2 chain id for a WalletGate chain key. */
    fun caipChainId(chainKey: String): String? =
        WalletGate.chainByKey(chainKey)?.let { "$NAMESPACE:${it.chainId}" }

    /** What the startup gate should do. */
    enum class Decision { OPEN, LOGIN, LOGIN_PREFILLED }

    /** Whether a WalletConnect session for the stored identity
     *  could be confirmed live, is known gone, or could not be
     *  checked at all (e.g. the WC stack failed to initialise). */
    enum class SessionState { LIVE, GONE, UNKNOWN }

    /** Result of looking a name up in the registry. */
    sealed class Lookup {
        data class Found(val evmAddress: String) : Lookup()
        object NameNotFound : Lookup()
        object NoEvmAddress : Lookup()
        object RegistryInvalid : Lookup()
    }

    /** Result of verifying a freshly approved session. */
    sealed class Verify {
        data class Verified(val address: String) : Verify()
        data class Mismatch(val connectedAddress: String) : Verify()
        object NoEip155Account : Verify()
        data class LookupFailed(val lookup: Lookup) : Verify()
    }

    /** Names are typed by humans: trim, lowercase, drop a
     *  leading "@" if they added one. Registry keys are the
     *  bare lowercase name. */
    fun normaliseName(raw: String): String =
        raw.trim().lowercase().removePrefix("@")

    /**
     * The registry address for [rawName] from a names.json
     * body. A name that exists but publishes no `evm` address
     * is a distinct, honest failure: Fluid's WalletConnect is
     * EVM-only, so such a name cannot be verified this way.
     */
    fun lookup(registryJson: String, rawName: String): Lookup {
        val name = normaliseName(rawName)
        if (name.isEmpty()) return Lookup.NameNotFound
        val root = try {
            JSONObject(registryJson)
        } catch (e: Exception) {
            return Lookup.RegistryInvalid
        }
        if (!root.has(name)) return Lookup.NameNotFound
        val entry = root.optJSONObject(name) ?: return Lookup.NoEvmAddress
        val evm = entry.optString("evm", "").trim()
        return if (evm.isEmpty()) Lookup.NoEvmAddress else Lookup.Found(evm)
    }

    /** The bare address from a CAIP-10 account string
     *  ("eip155:1:0xAbC…" → "0xAbC…"); null if malformed. */
    fun accountAddress(caip10: String): String? {
        val parts = caip10.split(":")
        return if (parts.size == 3 && parts[2].startsWith("0x")) parts[2] else null
    }

    /** The first eip155 account address in a session's account
     *  list (CAIP-10 strings), or null if the session carries
     *  no eip155 account at all. */
    fun eip155Address(accounts: List<String>): String? {
        for (account in accounts) {
            if (account.startsWith("$NAMESPACE:")) {
                accountAddress(account)?.let { return it }
            }
        }
        return null
    }

    /** EVM addresses compare case-insensitively (checksummed
     *  vs lowercase spellings are the same address). */
    fun addressesEqual(a: String, b: String): Boolean =
        a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * Verify a freshly approved session against the registry:
     * the session's eip155 account must be the very address
     * the typed name publishes. Anything else is a mismatch —
     * typing someone else's name and connecting your own
     * wallet does not open Lille.
     */
    fun verify(registryJson: String, rawName: String, accounts: List<String>): Verify {
        val connected = eip155Address(accounts) ?: return Verify.NoEip155Account
        return when (val found = lookup(registryJson, rawName)) {
            is Lookup.Found ->
                if (addressesEqual(connected, found.evmAddress)) Verify.Verified(connected)
                else Verify.Mismatch(connected)
            else -> Verify.LookupFailed(found)
        }
    }

    /** True if any of the session's eip155 accounts is the
     *  stored, already-verified address. */
    fun sessionMatchesIdentity(accounts: List<String>, storedAddress: String): Boolean =
        accounts.any { account ->
            if (!account.startsWith("$NAMESPACE:")) false
            else accountAddress(account)?.let { addressesEqual(it, storedAddress) } == true
        }

    /**
     * The startup decision:
     * - no stored identity → LOGIN (blank form);
     * - stored identity + live session → OPEN, from local state
     *   alone — no network round-trip, offline-first preserved;
     * - stored identity + session gone/expired → LOGIN with the
     *   username prefilled;
     * - stored identity + session uncheckable (WC stack failed
     *   to start) → OPEN: a previously verified user is never
     *   locked out of their own free app by an SDK hiccup. The
     *   gate is identity/promotion, not a security wall, and a
     *   fresh install still requires the full flow.
     */
    fun decide(storedUsername: String?, sessionState: SessionState): Decision {
        if (storedUsername.isNullOrBlank()) return Decision.LOGIN
        return when (sessionState) {
            SessionState.LIVE -> Decision.OPEN
            SessionState.UNKNOWN -> Decision.OPEN
            SessionState.GONE -> Decision.LOGIN_PREFILLED
        }
    }

    /** A plausible WalletConnect v2 pairing URI. */
    fun isPairingUri(uri: String): Boolean =
        uri.startsWith("wc:") && uri.contains("@2") && uri.contains("symKey=")

    // ---- plain-language messages (the UI shows these as-is) ----

    fun notFoundMessage(name: String): String =
        "No Fluid name \"${normaliseName(name)}\" was found in the Fluid names " +
            "registry. Check the spelling — or create the name in Fluid first " +
            "(More → Your Fluid name)."

    fun noEvmMessage(name: String): String =
        "The Fluid name \"${normaliseName(name)}\" exists but publishes no EVM " +
            "address, so it cannot be verified over WalletConnect (Fluid's " +
            "WalletConnect is EVM-only). Publish the name from Fluid so it " +
            "carries your wallet's EVM address, then try again."

    fun mismatchMessage(name: String): String =
        "That wallet doesn't own @${normaliseName(name)} — the wallet that " +
            "connected is not the wallet this Fluid name belongs to. Connect " +
            "the Fluid wallet that holds the name, or type the name that " +
            "belongs to the wallet you connected."

    fun noEip155Message(): String =
        "The wallet connected without an EVM (eip155) account, so there is " +
            "no address to verify against the Fluid name. Approve the " +
            "connection in Fluid with an EVM account included."

    fun rejectedMessage(): String =
        "The connection was rejected in Fluid — nothing was connected. " +
            "Tap Connect to print a fresh code whenever you're ready."

    fun registryUnreachableMessage(): String =
        "Lille couldn't reach the Fluid names registry just now, so the " +
            "name can't be checked. Check your connection and try again — " +
            "nothing was connected."

    /** Shown when the name was checked against the on-device
     *  saved copy because no live source answered. The login
     *  proceeds — the copy is labelled, never passed off as
     *  live (no silent wrong answers). */
    fun cachedRegistryNotice(): String =
        "Checked against Lille's saved copy of the Fluid names registry — " +
            "the live registry couldn't be reached just now."

    fun cachedNotFoundMessage(name: String): String =
        "No Fluid name \"${normaliseName(name)}\" was found in Lille's " +
            "saved copy of the Fluid names registry, and the live registry " +
            "couldn't be reached to double-check. If the name was created " +
            "recently, try again when your connection is back."

    /** The registry check passed but the WalletConnect relay
     *  step failed. The login UI shows THIS — never the SDK's
     *  raw error text (2026-11-27: a new user was shown the
     *  SDK's own "Connection error: Please check your Internet
     *  connection", which reads as a Lille failure and says
     *  nothing about which step actually failed). */
    fun relayUnreachableMessage(): String =
        "Your name checked out, but Lille couldn't reach the " +
            "WalletConnect relay to print your pairing code. Check your " +
            "connection and tap Connect again — nothing was connected."

    /** The WalletConnect stack itself failed to start (init
     *  error). The login UI shows THIS — never the SDK's raw
     *  throwable text, which stays in FluidLogin.initError for
     *  logs/traces only (audit G1, 2026-11-27). Same family as
     *  [relayUnreachableMessage]: what happened + what to do. */
    fun walletConnectStartMessage(): String =
        "WalletConnect couldn't start, so Lille can't print your " +
            "pairing code yet. Check your connection and reopen " +
            "Lille — nothing was connected."
}
