package org.freeagent.app

/**
 * The shared menu's style numbers (v1.0.2) — pure, pinned
 * by LilleMenuSpecTest. LilleMenu (the Android renderer)
 * reads ONLY these constants, and every popup menu in the
 * app is built from that one renderer, so the surfaces
 * cannot drift apart again: one compact floating card,
 * reactions as bare glyphs, actions as slim sentence-case
 * text rows with hairline dividers. The values are the
 * house language — the tonal surface and border of the
 * existing card drawable (bg_card), the chat's text colour.
 */
object LilleMenuSpec {
    const val CARD_CORNER_DP = 16
    const val CARD_PADDING_DP = 8
    const val ROW_TEXT_SP = 15f
    const val ROW_PADDING_H_DP = 16
    const val ROW_PADDING_V_DP = 11
    const val REACTION_TEXT_SP = 21f
    const val REACTION_CELL_DP = 44
    const val CARD_TOP_HEX = "#26231F"
    const val CARD_BOTTOM_HEX = "#1B1A18"
    const val CARD_STROKE_HEX = "#37332D"
    const val DIVIDER_HEX = "#2E2A25"
    const val TEXT_HEX = "#F5EFE7"

    /** Menu action titles are sentence case — a title that
     *  is ALL CAPS (the old bars' look) fails this check. */
    fun isSentenceCase(title: String): Boolean {
        val letters = title.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        return !letters.all { it.isUpperCase() }
    }
}
