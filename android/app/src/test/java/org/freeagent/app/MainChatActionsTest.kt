package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main-chat menu's X split (2026-11-26): LILLE'S
 * account actions are owner-only and state-driven; the
 * user's OWN connect is for everyone. The wording says
 * whose account each action is about — her creation is
 * never labelled as the user's.
 */
class MainChatActionsTest {

    @Test
    fun ownerWithoutRecordSeesCreateHersAndConnectMine() {
        val actions = MainChatActions.packagedActions(
            isOwner = true, lilleXVerified = false
        )
        assertEquals(
            listOf(
                MainChatActions.Action.CREATE_X_ACCOUNT,
                MainChatActions.Action.CONNECT_X_ACCOUNT
            ),
            actions
        )
        assertEquals(
            "Create Lille's X account",
            MainChatActions.Action.CREATE_X_ACCOUNT.menuTitle
        )
    }

    @Test
    fun ownerWithVerifiedRecordSeesConnectHersNeverCreate() {
        val actions = MainChatActions.packagedActions(
            isOwner = true, lilleXVerified = true
        )
        assertTrue(
            actions.contains(MainChatActions.Action.CONNECT_LILLE_X_ACCOUNT)
        )
        assertFalse(
            actions.contains(MainChatActions.Action.CREATE_X_ACCOUNT)
        )
        assertEquals(
            "Connect Lille's X account",
            MainChatActions.Action.CONNECT_LILLE_X_ACCOUNT.menuTitle
        )
        // His own connect is still there, unmerged.
        assertTrue(actions.contains(MainChatActions.Action.CONNECT_X_ACCOUNT))
    }

    @Test
    fun nonOwnerSeesConnectOnlyWhateverTheRecord() {
        for (verified in listOf(false, true)) {
            val actions = MainChatActions.packagedActions(
                isOwner = false, lilleXVerified = verified
            )
            assertEquals(
                listOf(MainChatActions.Action.CONNECT_X_ACCOUNT), actions
            )
        }
    }

    @Test
    fun menuIsPackagedJobsOnlyNow() {
        // v1.0.1 slim chrome: the wallet has its own card on
        // the main page and settings its own header icon —
        // the ⋯ menu carries only the packaged jobs.
        val menu = MainChatActions.menuActions(
            isOwner = false, lilleXVerified = false
        )
        assertEquals(
            listOf(MainChatActions.Action.CONNECT_X_ACCOUNT), menu
        )
        val ownerMenu = MainChatActions.menuActions(
            isOwner = true, lilleXVerified = false
        )
        assertEquals(
            listOf(
                MainChatActions.Action.CREATE_X_ACCOUNT,
                MainChatActions.Action.CONNECT_X_ACCOUNT
            ),
            ownerMenu
        )
    }

    @Test
    fun noLabelConfusesHersWithTheUsers() {
        for (action in MainChatActions.Action.values()) {
            assertFalse(
                "label must not mislabel: ${action.menuTitle}",
                action.menuTitle.contains("create my", ignoreCase = true)
            )
        }
        assertEquals(
            "Connect my X account",
            MainChatActions.Action.CONNECT_X_ACCOUNT.menuTitle
        )
    }
}
