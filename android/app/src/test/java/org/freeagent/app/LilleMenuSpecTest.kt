package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ONE shared menu surface (v1.0.2): pins the style
 * numbers every popup menu is built from, so the surfaces
 * cannot drift back into pieced-together chrome — chunky
 * grey boxes for reactions, full-width caps bars for
 * actions (Ethuram on the shipped menu: "it's off, should
 * be sleek, not like vibecoded stuff"). The Android
 * rendering in LilleMenu reads ONLY these constants.
 */
class LilleMenuSpecTest {

    @Test
    fun cardIsOneCompactRoundedSurface() {
        assertEquals(16, LilleMenuSpec.CARD_CORNER_DP)
        assertEquals(8, LilleMenuSpec.CARD_PADDING_DP)
        // House tonal surface + subtle border (bg_card's
        // language), never a grey system fill.
        assertEquals("#26231F", LilleMenuSpec.CARD_TOP_HEX)
        assertEquals("#1B1A18", LilleMenuSpec.CARD_BOTTOM_HEX)
        assertEquals("#37332D", LilleMenuSpec.CARD_STROKE_HEX)
        assertEquals("#F5EFE7", LilleMenuSpec.TEXT_HEX)
    }

    @Test
    fun reactionsAreBareGlyphsWithGenerousTargets() {
        // A 44 dp invisible tap target, glyph-sized text —
        // the cell carries NO background/box constants at
        // all: there is nothing in the spec to draw a tile
        // with.
        assertEquals(44, LilleMenuSpec.REACTION_CELL_DP)
        assertTrue(LilleMenuSpec.REACTION_TEXT_SP >= 18f)
    }

    @Test
    fun actionRowsAreSlimTextNotBars() {
        assertEquals(15f, LilleMenuSpec.ROW_TEXT_SP)
        assertTrue(LilleMenuSpec.ROW_PADDING_V_DP in 8..14)
    }

    @Test
    fun everyShippedMenuTitleIsSentenceCase() {
        for (item in MessageActions.ITEMS) {
            assertTrue(
                "menu title not sentence case: ${item.title}",
                LilleMenuSpec.isSentenceCase(item.title)
            )
        }
        for (action in MainChatActions.Action.values()) {
            assertTrue(
                "menu title not sentence case: ${action.menuTitle}",
                LilleMenuSpec.isSentenceCase(action.menuTitle)
            )
        }
        assertFalse(LilleMenuSpec.isSentenceCase("REPLY"))
        assertTrue(LilleMenuSpec.isSentenceCase("Reply"))
        assertTrue(LilleMenuSpec.isSentenceCase("Connect my X account"))
    }

    @Test
    fun reactionsRowIsTheAgreedSetPlusAffordance() {
        assertEquals(
            listOf("👍", "❤️", "😂", "😮", "😢", "🙏"),
            MessageActions.REACTIONS
        )
    }
}
