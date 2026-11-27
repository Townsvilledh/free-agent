package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press message menu model (v1.0.1): the reactions
 * row from Ethuram's screenshot of the Muse chat, the
 * actions, and the one-reaction toggle rule. v1.0.7: the
 * actions are three — Select is gone; chat text is never
 * selectable (Ethuram's standing order).
 */
class MessageActionsTest {

    @Test
    fun reactionsRowMatchesTheReferenceSet() {
        assertEquals(
            listOf("👍", "❤️", "😂", "😮", "😢", "🙏"),
            MessageActions.REACTIONS
        )
        assertTrue(MessageActions.MORE_REACTIONS.isNotEmpty())
        // No overlap: "+" never duplicates the primary row.
        assertTrue(
            MessageActions.MORE_REACTIONS.none {
                it in MessageActions.REACTIONS
            }
        )
    }

    @Test
    fun menuItemsAreReplyCopyShare() {
        assertEquals(
            listOf("Reply", "Copy", "Share"),
            MessageActions.ITEMS.map { it.title }
        )
    }

    @Test
    fun oneReactionPerMessageTapAgainClears() {
        // None yet → set.
        assertEquals("👍", MessageActions.toggleReaction(null, "👍"))
        // A different one → replace.
        assertEquals("❤️", MessageActions.toggleReaction("👍", "❤️"))
        // The same one again → clear.
        assertNull(MessageActions.toggleReaction("❤️", "❤️"))
    }
}
