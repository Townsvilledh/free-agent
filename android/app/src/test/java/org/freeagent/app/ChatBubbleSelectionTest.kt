package org.freeagent.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** v0.12.2: chat text must be copyable.
 *
 *  Chat bubbles are built programmatically in
 *  MainActivity.appendLine — and a programmatic TextView is
 *  NOT text-selectable by default, which is why neither the
 *  user's messages nor Lille's replies could be long-pressed
 *  and copied on Ethuram's phone (the layout-built views,
 *  like the login pairing-code block, carry
 *  android:textIsSelectable in XML and always worked).
 *
 *  A plain JVM test cannot instantiate a TextView, so this
 *  pins the contract at the source: appendLine — the single
 *  factory every chat bubble (user, Lille, live reply,
 *  queued, waiting-status) comes from — must enable
 *  selection, and the pairing-code block must stay
 *  selectable. If either regresses, this test goes red. */
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

    @Test
    fun chatBubblesAreTextSelectable() {
        val source = locate(
            "src/main/java/org/freeagent/app/MainActivity.kt"
        ).readText()
        val start = source.indexOf("private fun appendLine")
        assertTrue("appendLine not found in MainActivity", start >= 0)
        val end = source.indexOf("\n    }\n", start)
        assertTrue("appendLine body not delimited", end > start)
        val body = source.substring(start, end)
        assertTrue(
            "appendLine must call setTextIsSelectable(true) — " +
                "chat bubbles are otherwise not copyable",
            body.contains("setTextIsSelectable(true)")
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
