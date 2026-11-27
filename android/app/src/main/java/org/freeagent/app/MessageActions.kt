package org.freeagent.app

/**
 * The long-press message menu (v1.0.1, patterned on the Muse
 * chat Ethuram screenshotted): a reactions row over Reply /
 * Copy / Share, on ANY bubble — his or hers.
 *
 * v1.0.7: Select is GONE. Ethuram's standing order — chat
 * text is never selectable, handled exactly like the Muse
 * chat's own menu — so there is no selection door in the
 * menu and no code path in the chat enables selection on a
 * bubble. Copy is how text leaves a bubble.
 *
 * Reactions: one per message from him. Tapping another emoji
 * replaces it; tapping the same one clears it. The reaction
 * lives on the message record (ReplyQuote.REACTION_FIELD), so
 * it persists across restart and rides the backup.
 *
 * Pure model — MainActivity renders it in the house language
 * (true black, tonal surfaces, no gloss).
 */
object MessageActions {

    /** The primary reactions row (his screenshot's set). */
    val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    /** Behind the "+" affordance: a modest extended set. */
    val MORE_REACTIONS =
        listOf("🔥", "🎉", "👏", "💯", "✅", "👀", "💡", "🙌")

    enum class Item(val title: String) {
        REPLY("Reply"),
        COPY("Copy"),
        SHARE("Share")
    }

    val ITEMS: List<Item> = Item.values().toList()

    /**
     * The toggle rule: same emoji again clears; a different
     * one replaces; none yet sets.
     */
    fun toggleReaction(current: String?, tapped: String): String? =
        if (current == tapped) null else tapped
}
