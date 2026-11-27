package org.freeagent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** v1.0.6 (supersedes the v0.12.2 contract): chat bubbles
 *  are NOT text-selectable by default.
 *
 *  The v0.12.2 default (appendLine sets selectable=true)
 *  made chat text copyable — and, proven on-device
 *  2026-11-27, it also swallowed the long-press: Android
 *  text selection took the gesture, the user got
 *  selection handles + the system Copy/Share bar, and
 *  the LilleMenu (reactions, Reply / Copy / Select /
 *  Share) never opened. The menu was unreachable.
 *
 *  The 1.0.6 contract: appendLine — the single factory
 *  every chat bubble (user, Lille, live reply) comes
 *  from — builds bubbles NON-selectable; the long-press
 *  opens the menu on stored bubbles AND the live bubble
 *  (attachLiveMenu at both creation sites); the menu's
 *  Select item is the one place selection is enabled
 *  (on that bubble only; it resets on re-render). Copy
 *  lives in the menu. The login pairing-code block (XML)
 *  stays selectable as before.
 *
 *  A plain JVM test cannot instantiate a TextView, so
 *  this pins the contract at the source. If any leg
 *  regresses, this test goes red. */
class ChatBubbleSelectionTest {

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

    private fun mainActivitySource(): String = locate(
        "src/main/java/org/freeagent/app/MainActivity.kt"
    ).readText()

    private fun appendLineBody(source: String): String {
        val start = source.indexOf("private fun appendLine")
        assertTrue("appendLine not found in MainActivity", start >= 0)
        val end = source.indexOf("\n    }\n", start)
        assertTrue("appendLine body not delimited", end > start)
        return source.substring(start, end)
    }

    @Test
    fun chatBubblesAreNotSelectableByDefault() {
        val body = appendLineBody(mainActivitySource())
        assertTrue(
            "appendLine must build bubbles non-selectable — " +
                "a selectable bubble swallows the long-press " +
                "and the message menu never opens",
            body.contains("setTextIsSelectable(false)")
        )
        assertFalse(
            "appendLine must not re-enable default selection",
            body.contains("setTextIsSelectable(true)")
        )
    }

    @Test
    fun theMenuSelectItemIsTheOneSelectionDoor() {
        val source = mainActivitySource()
        assertTrue(
            "the shared menu's Select item enables selection " +
                "on that bubble only",
            source.contains("bubble.setTextIsSelectable(true)")
        )
    }

    @Test
    fun longPressMenusHangOffStoredAndLiveBubbles() {
        val source = mainActivitySource()
        // Stored bubbles: the render loop attaches the
        // stored-message menu to every bubble.
        assertTrue(
            "stored bubbles carry the long-press menu",
            source.contains(
                "showMessageMenu(chatId, index, role, display, bubble)"
            )
        )
        // The live bubble: attachLiveMenu is called at
        // BOTH creation sites (renderChat's tail and
        // replaceCurrentReply's re-creation).
        val attachSites = source.split("attachLiveMenu(chatId,").size - 1
        assertTrue(
            "the live bubble's menu is attached at both " +
                "creation sites (found $attachSites)",
            attachSites >= 2
        )
        assertTrue(
            "the live menu exists",
            source.contains("fun showLiveMessageMenu")
        )
    }

    @Test
    fun pairingCodeBlockStaysSelectable() {
        val layout = locate(
            "src/main/res/layout/activity_login.xml"
        ).readText()
        assertTrue(
            "The login pairing-code block must stay selectable",
            layout.contains("android:textIsSelectable=\"true\"")
        )
    }
}
