package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
