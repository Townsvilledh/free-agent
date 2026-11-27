package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-chat browser panel's state machine: visibility
 * follows the work state, takeover/handback states are
 * distinct, and another chat's lane shows as one plain
 * waiting line — never as this chat's panel.
 */
class BrowserPanelTest {

    private fun state(
        taskState: String?,
        browser: Boolean = true,
        mine: Boolean = false,
        other: Boolean = false,
        hold: Boolean = false
    ): BrowserPanel.State = BrowserPanel.stateFor(
        taskState, browser, mine, other, hold
    )

    @Test
    fun hiddenWhenNoBrowserWork() {
        assertEquals(BrowserPanel.State.HIDDEN, state(null))
        assertEquals(
            BrowserPanel.State.HIDDEN,
            state(TaskStates.RUNNING, browser = false, mine = true)
        )
        assertEquals(
            BrowserPanel.State.HIDDEN,
            state(TaskStates.COMPLETED, mine = true)
        )
        assertEquals(
            BrowserPanel.State.HIDDEN,
            state(TaskStates.QUEUED)
        )
    }

    @Test
    fun watchWhileHerWorkHoldsTheLane() {
        assertEquals(
            BrowserPanel.State.WATCH,
            state(TaskStates.RUNNING, mine = true)
        )
    }

    @Test
    fun parkedShowsHerBrowserForHisStep() {
        assertEquals(
            BrowserPanel.State.PARKED,
            state(TaskStates.PARKED_AT_WALL, mine = true)
        )
        // Even if the lane bookkeeping lapsed (process
        // restart), a parked step still surfaces the panel —
        // the step happens here.
        assertEquals(
            BrowserPanel.State.PARKED,
            state(TaskStates.PARKED_AT_WALL)
        )
    }

    @Test
    fun takeoverIsItsOwnState() {
        assertEquals(
            BrowserPanel.State.USER_HOLD,
            state(TaskStates.PAUSED, hold = true)
        )
        // A pause that is not a takeover shows no panel.
        assertEquals(
            BrowserPanel.State.HIDDEN,
            state(TaskStates.PAUSED, hold = false)
        )
    }

    @Test
    fun otherChatsLaneIsOnePlainWaitingLine() {
        assertEquals(
            BrowserPanel.State.WAITING_OTHER,
            state(TaskStates.RUNNING, mine = false, other = true)
        )
        assertEquals(
            BrowserPanel.State.WAITING_OTHER,
            state(TaskStates.QUEUED, mine = false, other = true)
        )
        assertFalse(
            NarrationGuard.containsNarration(BrowserPanel.WAITING_OTHER_LINE)
        )
    }

    @Test
    fun labelsArePlain() {
        for (label in listOf(
            BrowserPanel.WATCH_LABEL,
            BrowserPanel.PARKED_LABEL,
            BrowserPanel.USER_HOLD_LABEL,
            BrowserPanel.WAITING_OTHER_LINE
        )) {
            assertFalse(NarrationGuard.containsNarration(label))
        }
    }

    // ---------- dismissal (v1.0.3) ----------

    @Test
    fun closeHidesThePanelAndShowsTheIndicator() {
        // Close is a view choice: the panel hides, the slim
        // indicator carries the browser's presence, and the
        // state machine's task facts are untouched (the
        // dismissal never reaches the task record — it is
        // an input to visibility, nothing more).
        assertFalse(
            BrowserPanel.panelVisible(BrowserPanel.State.WATCH, true)
        )
        assertTrue(
            BrowserPanel.indicatorShown(BrowserPanel.State.WATCH, true)
        )
        assertFalse(
            BrowserPanel.panelVisible(
                BrowserPanel.State.WAITING_OTHER, true
            )
        )
        assertTrue(
            BrowserPanel.indicatorShown(
                BrowserPanel.State.WAITING_OTHER, true
            )
        )
        assertTrue(
            BrowserPanel.indicatorShown(
                BrowserPanel.State.USER_HOLD, true
            )
        )
    }

    @Test
    fun indicatorTapReopensThePanel() {
        // Not dismissed: the panel shows, no indicator.
        assertTrue(
            BrowserPanel.panelVisible(BrowserPanel.State.WATCH, false)
        )
        assertFalse(
            BrowserPanel.indicatorShown(BrowserPanel.State.WATCH, false)
        )
    }

    @Test
    fun parkWhileHiddenReopensThePanelOnItsOwn() {
        // A park overrides dismissal — when she needs him,
        // the panel is back in front of him at the parked
        // page, and the indicator steps aside.
        assertTrue(
            BrowserPanel.panelVisible(BrowserPanel.State.PARKED, true)
        )
        assertFalse(
            BrowserPanel.indicatorShown(BrowserPanel.State.PARKED, true)
        )
        assertTrue(
            BrowserPanel.panelVisible(BrowserPanel.State.PARKED, false)
        )
    }

    @Test
    fun hiddenStateShowsNeitherPanelNorIndicator() {
        assertFalse(
            BrowserPanel.panelVisible(BrowserPanel.State.HIDDEN, true)
        )
        assertFalse(
            BrowserPanel.indicatorShown(BrowserPanel.State.HIDDEN, true)
        )
        assertFalse(
            BrowserPanel.panelVisible(BrowserPanel.State.HIDDEN, false)
        )
    }

    @Test
    fun controlLabelsAreThePinnedPlainNames() {
        assertEquals("Take over", BrowserPanel.TAKE_OVER_LABEL)
        assertEquals("Done — hand back", BrowserPanel.HAND_BACK_LABEL)
        assertEquals("Expand", BrowserPanel.EXPAND_LABEL)
        assertEquals("Shrink", BrowserPanel.SHRINK_LABEL)
        assertTrue(BrowserPanel.CLOSE_LABEL.contains("keeps working"))
        assertTrue(BrowserPanel.INDICATOR_LINE.contains("tap to view"))
        for (label in listOf(
            BrowserPanel.TAKE_OVER_LABEL,
            BrowserPanel.HAND_BACK_LABEL,
            BrowserPanel.EXPAND_LABEL,
            BrowserPanel.SHRINK_LABEL,
            BrowserPanel.CLOSE_LABEL,
            BrowserPanel.INDICATOR_LINE
        )) {
            assertFalse(NarrationGuard.containsNarration(label))
        }
    }
}
