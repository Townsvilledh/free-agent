package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat-output guard, pinned against the failure's
 * shapes and against healthy replies. The failure fixture
 * reproduces the live reply to "update" (2026-11-26): a
 * model staging a Q&A with itself, an invented placeholder
 * token presented as a fetched fact, speaker turns inside
 * one bubble. That exact shape must never reach the chat
 * again — and ordinary prose, including the word "update"
 * used legitimately and code fences full of angle
 * brackets, must pass untouched.
 */
class ChatOutputGuardTest {

    /** The live failure's shape, reproduced: self-dialogue
     *  speaker turns + an invented <…_LINK> placeholder. */
    private val failureShape = """
        Update: I checked on the work for you.

        Answer: The Discord server is ready. Join here: <DISCORD_INVITE_LINK>

        Question: Do you want me to continue?

        Lille: Yes, I will fetch the remaining details next.

        Please provide:
        - the channel name
        - the topic you want pinned
    """.trimIndent()

    @Test
    fun theLiveFailureShapeIsRejected() {
        val verdict = ChatOutputGuard.verdict(failureShape)
        assertTrue(verdict is ChatOutputGuard.Verdict.Rejected)
    }

    @Test
    fun placeholderTokenAloneIsRejected() {
        val verdict = ChatOutputGuard.verdict(
            "Here is the link you asked for: <DISCORD_INVITE_LINK>"
        )
        assertEquals(
            ChatOutputGuard.Verdict.Rejected("placeholder"), verdict
        )
    }

    @Test
    fun bareUnderscoreTokensAreRejected() {
        assertFalse(ChatOutputGuard.isClean("Your file is at FILE_URL now."))
        assertFalse(ChatOutputGuard.isClean("Use the ACCESS_TOKEN here."))
    }

    @Test
    fun selfDialogueNeedsTwoTurns() {
        val twoTurns = "Answer: yes.\n\nQuestion: are you sure?"
        assertEquals(
            ChatOutputGuard.Verdict.Rejected("self-dialogue"),
            ChatOutputGuard.verdict(twoTurns)
        )
        // One marker is ordinary prose — a heading, a quote.
        assertTrue(
            ChatOutputGuard.isClean("Update: the meeting moved to 3pm.")
        )
        assertTrue(
            ChatOutputGuard.isClean("Lille: that is how she signed it.")
        )
    }

    @Test
    fun healthyMultiParagraphAnswerPasses() {
        val healthy = """
            Here is the summary you asked for.

            The market moved sideways today, and the update from
            the team is that the release slips by a week. Nothing
            about that changes the plan we discussed.

            Next step: I will check again tomorrow morning and
            tell you if anything moved.
        """.trimIndent()
        assertTrue(ChatOutputGuard.isClean(healthy))
    }

    @Test
    fun codeFencesNeverFalsePositive() {
        val withCode = """
            Use a generic list and a div like this:

            ```kotlin
            val items = ArrayList<String>()
            val el = "<div class=\"card\">TODO: style me</div>"
            // <PLACEHOLDER_VALUE> goes here
            ```

            And in HTML, `<span>` wraps the text.
        """.trimIndent()
        assertTrue(ChatOutputGuard.isClean(withCode))
    }

    @Test
    fun plainLineIsItselfCleanAndPlain() {
        assertTrue(ChatOutputGuard.isClean(ChatOutputGuard.PLAIN_LINE))
        assertFalse(NarrationGuard.containsNarration(ChatOutputGuard.PLAIN_LINE))
    }
}
