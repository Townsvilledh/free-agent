package org.freeagent.app

/**
 * Owner gate (v0.10.1) — the pure decision component behind
 * owner-only surfaces, in the same style as FluidGate and
 * BrainPacing: no Android, no I/O, pinned by JVM tests. The
 * Android glue (the main chat's menu, MainActivity) only
 * reads the verified Fluid identity from
 * FluidIdentityStore and acts on the decision made here.
 *
 * Why this exists (XLANE.md, "Hers alone"): Lille's own X
 * account is HERS alone — created once, by her own loop, from
 * the owner's phone, and managed from that instance. It is
 * not a feature of the distributed app: a copy logged in as
 * anyone else must not expose the creation task at all — the
 * button is absent, not greyed out and not mentioned, and the
 * task cannot be created from the UI. (In a stranger's hands
 * the button would race every other copy for the same
 * handles, land the winning account in a stranger's vault and
 * session outside the owner's control, and draw X's spam
 * scrutiny onto the Lille identity itself.)
 *
 * The identity this gate reads is the one the Fluid login
 * gate (v0.10) verified — registry lookup + WalletConnect
 * session jointly proving the connected wallet owns the
 * name — never a name the user merely typed somewhere.
 */
object OwnerGate {

    /**
     * CREATOR / OWNER IDENTITIES — Lille's own accounts are
     * managed only from these.
     *
     * The verified Fluid usernames whose instance may create
     * and run Lille's OWN-account tasks (today: her X account,
     * XAccountTask). Grow this set deliberately, one reviewed
     * name at a time, in code — never derive it from user
     * input, a server response, or anything an installer can
     * change short of rebuilding the app.
     */
    val OWNER_USERNAMES: Set<String> = setOf("ethuram")

    /**
     * True when [verifiedUsername] — the name stored by the
     * Fluid login gate after verification (FluidIdentityStore)
     * — is an owner identity. The comparison goes through
     * FluidGate's normalisation (trim, lowercase, drop a
     * leading "@"), so "Ethuram" and "@ethuram" are the same
     * owner. Null or blank — no verified login — is never an
     * owner.
     */
    fun isOwner(verifiedUsername: String?): Boolean {
        if (verifiedUsername.isNullOrBlank()) return false
        return FluidGate.normaliseName(verifiedUsername) in OWNER_USERNAMES
    }

    /** The X-account creation task (button + task) is
     *  shown/creatable only on an owner instance. */
    fun canCreateXAccountTask(verifiedUsername: String?): Boolean =
        isOwner(verifiedUsername)
}
