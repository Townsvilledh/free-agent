package org.freeagent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The slim main-page chrome (v1.0.1, Ethuram's screenshot
 * verdict: the text buttons were "too bold"): the header
 * controls are small ICON buttons, and her wallet lives
 * on the main page as a card. A JVM test cannot render a
 * layout, so — the ChatBubbleSelectionTest pattern — the
 * contract is pinned at the source.
 */
class MainPageChromeTest {

    private fun locate(relativeFromModule: String): File {
        val candidates = listOf(
            relativeFromModule,
            "app/$relativeFromModule",
            "android/app/$relativeFromModule"
        )
        for (path in candidates) {
            val f = File(path)
            if (f.isFile) return f
        }
        fail("Could not locate $relativeFromModule from ${File(".").absolutePath}")
        throw AssertionError("unreachable")
    }

    private val layout by lazy {
        locate("src/main/res/layout/activity_main.xml").readText()
    }

    private val main by lazy {
        locate(
            "src/main/java/org/freeagent/app/MainActivity.kt"
        ).readText()
    }

    @Test
    fun headerControlsAreIconsNotTextButtons() {
        // The three destinations are ImageButtons carrying
        // icon drawables and content descriptions.
        for ((id, icon) in listOf(
            "chatsButton" to "ic_chats",
            "newChatButton" to "ic_new_chat",
            "settingsButton" to "ic_settings"
        )) {
            val at = layout.indexOf("@+id/$id")
            assertTrue("$id missing from the header", at >= 0)
            val block = layout.substring((at - 200).coerceAtLeast(0), at + 400)
            assertTrue("$id must be an ImageButton", block.contains("ImageButton"))
            assertTrue("$id must carry $icon", block.contains("@drawable/$icon"))
            assertTrue("$id must be described", block.contains("contentDescription"))
        }
        // No header control carries a text label any more.
        assertFalse(layout.contains("android:text=\"@string/chats\""))
        assertFalse(layout.contains("android:text=\"@string/new_chat\""))
        assertFalse(layout.contains("android:text=\"@string/settings\""))
    }

    @Test
    fun walletCardLivesOnTheMainPage() {
        assertTrue(layout.contains("@+id/walletCard"))
        assertTrue(layout.contains("@+id/walletCardText"))
        // Tapping the card opens the full Wallet screen,
        // and the card refreshes from the engines.
        assertTrue(main.contains("R.id.walletCard"))
        assertTrue(main.contains("WalletActivity::class.java"))
        assertTrue(main.contains("refreshWalletCard()"))
        // The settings icon reaches Settings.
        assertTrue(main.contains("R.id.settingsButton"))
        assertTrue(main.contains("SettingsActivity::class.java"))
    }
}
