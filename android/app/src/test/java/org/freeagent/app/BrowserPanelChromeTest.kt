package org.freeagent.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v1.0.3: the browser panel's chrome, pinned against the
 * real layout (the MainPageChromeTest pattern). The
 * controls are small tonal ICON buttons in the shared
 * menu language — the old text bars ("Expand", "Take
 * over" as wide buttons) are gone — and the panel has a
 * close control plus the dismissed-state indicator chip.
 */
class BrowserPanelChromeTest {

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

    /** The element block carrying [id]: the tag before it
     *  through the attributes after it. */
    private fun block(id: String): String {
        val at = layout.indexOf("@+id/$id")
        assertTrue("layout carries $id", at >= 0)
        return layout.substring(
            (at - 200).coerceAtLeast(0),
            (at + 500).coerceAtMost(layout.length)
        )
    }

    @Test
    fun panelControlsAreIconButtonsNotTextBars() {
        for (id in listOf(
            "browserPanelExpand", "browserPanelAction", "browserPanelClose"
        )) {
            val b = block(id)
            assertTrue("$id is an ImageButton", b.contains("ImageButton"))
            assertFalse("$id carries no text bar", b.contains("android:text="))
        }
        assertTrue(block("browserPanelExpand").contains("@drawable/ic_expand"))
        assertTrue(block("browserPanelAction").contains("@drawable/ic_takeover"))
        assertTrue(block("browserPanelClose").contains("@drawable/ic_close"))
        // The house tonal surface, same as the header icons.
        assertTrue(block("browserPanelExpand").contains("@drawable/bg_icon_button"))
        assertTrue(block("browserPanelClose").contains("@drawable/bg_icon_button"))
        // No panel control anywhere is a plain Button with
        // the old captions.
        assertFalse(layout.contains("android:text=\"Expand\""))
        assertFalse(layout.contains("android:text=\"Take over\""))
    }

    @Test
    fun closeControlAndIndicatorChipExist() {
        // Close names its contract in its description:
        // dismissing never stops the work.
        assertTrue(
            block("browserPanelClose")
                .contains("Close — she keeps working")
        )
        // The slim chip that carries the browser's presence
        // while the panel is dismissed.
        assertTrue(block("browserChip").contains("TextView"))
        assertTrue(block("browserChip").contains("android:visibility=\"gone\""))
    }
}
