package org.freeagent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** v0.12.3: the chat honesty line must stay in the chat
 *  system prompt.
 *
 *  On the community fallback route Lille answered a hackathon
 *  brief by narrating a fully completed submission — repo
 *  created, Trap Pack fetched, form submitted — none of which
 *  happened or could have (the event had not started). Chat
 *  has no tools; a chat reply must never claim an action. The
 *  prompt carries the guard in the app's own words, and this
 *  test pins it the way ChatBubbleSelectionTest pins the
 *  selectable-bubble contract: at the source, because the
 *  prompt is a string constant no JVM test can execute.
 *
 *  The constant is built from concatenated literals, so the
 *  check normalises the extracted source (quotes and `+`
 *  removed, whitespace collapsed) before looking for the
 *  phrases. */
class ChatHonestyPromptTest {

    private fun locate(relativeFromModule: String): File {
        val candidates = listOf(
            relativeFromModule, // cwd = android/app (Gradle unit tests)
            "app/$relativeFromModule", // cwd = android/
            "android/app/$relativeFromModule" // cwd = repo root
        )
        for (path in candidates) {
            val f = File(path)
            if (f.isFile) return f
        }
        fail("Could not locate $relativeFromModule from ${File(".").absolutePath}")
        throw AssertionError("unreachable")
    }

    private fun normalisedPrompt(): String {
        val source = locate("src/main/java/org/freeagent/app/MainActivity.kt").readText()
        val start = source.indexOf("SYSTEM_PROMPT")
        val end = source.indexOf("MAX_HISTORY", start)
        assertTrue("SYSTEM_PROMPT block not found", start >= 0 && end > start)
        return source.substring(start, end)
            .replace("\"", " ")
            .replace("+", " ")
            .replace(Regex("\\s+"), " ")
    }

    @Test
    fun chatPromptSaysChatCannotPerformActions() {
        assertTrue(normalisedPrompt().contains("In chat you cannot perform actions"))
    }

    @Test
    fun chatPromptForbidsClaimingUndoneWork() {
        assertTrue(
            normalisedPrompt().contains(
                "Never claim to have done, created, fetched, submitted or " +
                    "completed anything you did not actually do through a tool in a task"
            )
        )
    }

    @Test
    fun chatPromptRequiresPresentTenseStarts() {
        val prompt = normalisedPrompt()
        assertTrue(prompt.contains("say you are starting it and what happens next"))
        assertTrue(prompt.contains("never narrate a finished result"))
    }

    /** v1.0.7: the confirmation question is retired —
     *  the prompt now FORBIDS it by its exact wording
     *  (jobs are started by the app, never by asking).
     *  Up to 1.0.6 this pin asserted the opposite: the
     *  prompt instructed the model to ask it. */
    @Test
    fun chatPromptForbidsTheTaskQuestion() {
        val prompt = normalisedPrompt()
        assertTrue(prompt.contains("Never ask"))
        assertTrue(
            prompt.contains(
                "jobs are started by the app, never by asking"
            )
        )
        // The old instruction — ask it exactly, and
        // nothing more about doing it — is gone.
        assertFalse(prompt.contains("ask exactly"))
    }
}
