package org.freeagent.app

/**
 * UI SCREENS (v1.0.13) — the EXECUTION table for UI
 * commands: which screens she actually has, the token
 * the IntentContract names each by, and the one line
 * stated once the navigation has been performed.
 *
 * This is deliberately NOT a parser (Ethuram's ruling,
 * 2026-11-28: no new sentence grammars). Whether the
 * user's words are a UI command — "bring up mu fluid
 * wallet", "just call the ui", "show me the wallet
 * thing" — is the brain's classification under the
 * IntentContract (COMMAND + target token); this table
 * is what the glue executes when the verdict lands:
 * the navigation is performed FIRST (the activity
 * opens), and the reply is this table's line, composed
 * from the performed fact — never narrated ahead of
 * it. Born of the 2026-11-28 screenshot, where "bring
 * up mu fluid wallet" got a narrated launch ("I'm
 * launching the Fluid wallet UI now. You should see
 * the wallet screen appear…") and nothing opened. His
 * verdict: "This is just hallucinations."
 *
 * The targets, and the shell surface each opens:
 * - WALLET — her own wallet view (WalletActivity).
 * - FLUID — the connected main wallet's surface: the
 *   embedded Fluid sheet (FluidSheetActivity), which
 *   with no pairing pending simply loads Fluid itself.
 * - SETTINGS — SettingsActivity.
 * - CHATS — the chats list (ChatListActivity).
 * The browser panel is not here: it has its own
 * deterministic commands (BrowserControl open/close),
 * an existing command path that predates the contract.
 *
 * Pure; pinned by Lille113Test.
 */
object UiScreens {

    enum class Target { WALLET, FLUID, SETTINGS, CHATS }

    /** What executing a target means, minus the act
     *  itself (the glue performs the navigation): the
     *  [target] and the one line stated after it. */
    data class UiPlan(val target: Target, val reply: String)

    const val WALLET_LINE = "Wallet open."
    const val FLUID_LINE = "Fluid wallet open."
    const val SETTINGS_LINE = "Settings open."
    const val CHATS_LINE = "Chats open."

    /** The honest line for a COMMAND verdict naming a
     *  screen she does not have: what she has, by name —
     *  never a narration of the missing one. */
    const val UNKNOWN_LINE =
        "I don't have that screen. The screens I can " +
            "bring up by name are my wallet, the Fluid " +
            "wallet, Settings and your chats."

    /** The contract's target tokens → screens. A token
     *  outside this table is a screen she does not have
     *  (the glue answers [UNKNOWN_LINE]). */
    fun targetForToken(token: String): Target? =
        when (token.trim().lowercase()) {
            "wallet" -> Target.WALLET
            "fluid" -> Target.FLUID
            "settings" -> Target.SETTINGS
            "chats" -> Target.CHATS
            else -> null
        }

    fun planFor(target: Target): UiPlan = UiPlan(
        target,
        when (target) {
            Target.WALLET -> WALLET_LINE
            Target.FLUID -> FLUID_LINE
            Target.SETTINGS -> SETTINGS_LINE
            Target.CHATS -> CHATS_LINE
        }
    )
}
