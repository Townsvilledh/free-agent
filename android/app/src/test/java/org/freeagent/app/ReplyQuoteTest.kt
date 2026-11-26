package org.freeagent.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reply-to-message (v1.0.1): the quote is stored with the
 * message, renders for both speakers, reaches the model as
 * the marked referent, and turns a fragment reply into a job
 * whose text contains the quoted content.
 */
class ReplyQuoteTest {

    @Test
    fun snippetCollapsesAndTrims() {
        val s = ReplyQuote.snippet("  hello\n\n   world,  this  is\nlong  ")
        assertEquals("hello world, this is long", s)
        val long = ReplyQuote.snippet("x".repeat(500))
        assertTrue(long.length <= ReplyQuote.SNIPPET_MAX)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun quotingAReplyQuotesItsOwnWordsNotItsQuoteBlock() {
        val replyDisplay = ReplyQuote.displayText(
            "assistant", "the original point", "and my answer to it"
        )
        // Re-quoting that reply yields its own words only.
        assertEquals("and my answer to it", ReplyQuote.snippet(replyDisplay))
    }

    @Test
    fun renderingDataForBothSpeakers() {
        assertEquals(
            "↪ Lille: the draft is ready",
            ReplyQuote.quoteBlock("assistant", "the draft is ready")
        )
        assertEquals(
            "↪ You: send it tomorrow",
            ReplyQuote.quoteBlock("user", "send it tomorrow")
        )
        assertEquals(
            "↪ Lille: the draft is ready\n\nfix this",
            ReplyQuote.displayText("assistant", "the draft is ready", "fix this")
        )
        assertEquals(
            "↪ You: send it tomorrow\n\non second thought, Friday",
            ReplyQuote.displayText(
                "user", "send it tomorrow", "on second thought, Friday"
            )
        )
    }

    @Test
    fun modelContextCarriesTheReferent() {
        val marked = ReplyQuote.markedContent(
            "assistant", "the draft is ready", "fix this"
        )
        assertTrue(marked.startsWith("[Replying to Lille: \"the draft is ready\"]"))
        assertTrue(marked.endsWith("fix this"))
        val markedUser = ReplyQuote.markedContent(
            "user", "send it tomorrow", "this one"
        )
        assertTrue(markedUser.contains("[Replying to You: \"send it tomorrow\"]"))
        assertTrue(markedUser.endsWith("this one"))
    }

    @Test
    fun quoteAndReactionSurviveTheStoreRoundTrip() {
        // The chats text is what the encrypted backup carries
        // verbatim — a serialise/parse round-trip here is the
        // backup/restore shape.
        val root = JSONObject().put("chats", org.json.JSONArray())
        val chat = JSONObject()
            .put("id", "chat-1").put("title", ChatData.DEFAULT_TITLE)
            .put("messages", org.json.JSONArray())
        root.getJSONArray("chats").put(chat)
        assertTrue(
            ChatData.appendMessage(
                root, "chat-1", "assistant", "the draft is ready",
                null, 1L
            )
        )
        assertTrue(
            ChatData.appendMessage(
                root, "chat-1", "user",
                ReplyQuote.markedContent("assistant", "the draft is ready", "fix this"),
                ReplyQuote.displayText("assistant", "the draft is ready", "fix this"),
                2L, quoteRole = "assistant", quoteText = "the draft is ready"
            )
        )
        // React to her message, then restore from the text.
        assertTrue(ChatData.setReaction(root, "chat-1", 0, "❤️"))
        val restored = JSONObject(root.toString())
        val msgs = ChatData.messages(restored, "chat-1")
        assertEquals(2, msgs.size)
        assertEquals("assistant", ReplyQuote.quotedRole(msgs[1]))
        assertEquals("the draft is ready", ReplyQuote.quotedText(msgs[1]))
        assertEquals("❤️", ReplyQuote.reaction(msgs[0]))
        // Toggling the same reaction off clears the field.
        assertTrue(ChatData.setReaction(restored, "chat-1", 0, null))
        assertNull(
            ReplyQuote.reaction(ChatData.messages(restored, "chat-1")[0])
        )
        // Out-of-range reactions are refused, not faked.
        assertTrue(!ChatData.setReaction(restored, "chat-1", 9, "👍"))
    }

    @Test
    fun fragmentReplyWithQuoteBecomesAJobCarryingTheQuote() {
        val quoteText = "I checked the group page this morning and the " +
            "pinned post still shows the old flyer with the October " +
            "date and the wrong venue name on it, and three people " +
            "have asked about it in the comments under the post " +
            "since yesterday evening, so it is starting to confuse " +
            "everyone who lands on the page"
        val marked = ReplyQuote.markedContent("assistant", quoteText, "fix this")
        val outcome = JobDetect.classify(marked, emptyList())
        assertTrue(
            "fragment + quote must classify as job-shaped, was $outcome",
            outcome is JobDetect.Outcome.StartTask ||
                outcome is JobDetect.Outcome.AskQuestion
        )
        val instruction = when (outcome) {
            is JobDetect.Outcome.StartTask -> outcome.instruction
            is JobDetect.Outcome.AskQuestion -> outcome.instruction
            else -> ""
        }
        // The job input is fragment + quote: the quoted
        // content is IN the task text, not lost.
        assertTrue(instruction.contains("fix this"))
        assertTrue(instruction.contains("pinned post still shows the old flyer"))
        // The bare fragment alone is not a job — the quote
        // is what made it one.
        assertTrue(JobDetect.classify("fix this", emptyList())
            is JobDetect.Outcome.NotJob)
    }
}
