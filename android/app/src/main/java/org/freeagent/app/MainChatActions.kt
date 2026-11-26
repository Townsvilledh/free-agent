package org.freeagent.app

/**
 * The main chat's organised actions (2026-11-26, THE MAIN
 * PAGE IS THE MAIN CHAT): packaged jobs live IN the main
 * chat as menu actions — their whole life (start, work
 * state, the parked human step on the in-chat browser
 * panel, the graded report) happens in the thread they
 * were started from. They used to live as buttons on the
 * Tasks screen; that screen is gone.
 *
 * The X actions are a SPLIT, and the wording says whose
 * account each one is about:
 *
 * - [Action.CREATE_X_ACCOUNT] / [Action.CONNECT_LILLE_X_ACCOUNT]
 *   concern LILLE'S OWN account. They exist ONLY for the
 *   verified owner login (OwnerGate — her account is hers
 *   alone, created and managed from the owner's instance).
 *   For every other login they are simply absent — not
 *   greyed, not mentioned. Which of the two the owner
 *   sees is STATE-DRIVEN (LilleXState): no verified
 *   account on record → Create; once creation is verified
 *   and her handle is on record → Connect, forever after
 *   (the record rides the encrypted backup).
 * - [Action.CONNECT_X_ACCOUNT] is every user's OWN
 *   account: they sign in themselves in her parked
 *   session; she never sees their password.
 *
 * Pinned by MainChatActionsTest + OwnerGateTest +
 * LilleXStateTest.
 */
object MainChatActions {

    enum class Action(val menuTitle: String) {
        CREATE_X_ACCOUNT("Create Lille's X account"),
        CONNECT_LILLE_X_ACCOUNT("Connect Lille's X account"),
        CONNECT_X_ACCOUNT("Connect my X account")
    }

    /** The packaged-job actions for the main chat's menu,
     *  in display order. [isOwner] is OwnerGate's verdict
     *  on the verified Fluid login; [lilleXVerified] is
     *  LilleXState's verdict on her creation record. */
    fun packagedActions(
        isOwner: Boolean,
        lilleXVerified: Boolean
    ): List<Action> {
        val out = mutableListOf<Action>()
        if (isOwner) {
            out.add(
                if (lilleXVerified) {
                    Action.CONNECT_LILLE_X_ACCOUNT
                } else {
                    Action.CREATE_X_ACCOUNT
                }
            )
        }
        out.add(Action.CONNECT_X_ACCOUNT)
        return out
    }

    /** The ⋯ menu holds ONLY the packaged jobs: the wallet
     *  has its own card on the main page and settings its
     *  own header icon (v1.0.1 slim chrome), so neither
     *  rides the menu any more. */
    fun menuActions(
        isOwner: Boolean,
        lilleXVerified: Boolean
    ): List<Action> = packagedActions(isOwner, lilleXVerified)
}
