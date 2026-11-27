package org.freeagent.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-site cards: growth with dedup, the 12-entry list cap
 * (most-recent-last), the 120-character entry cut, and the
 * renderFor framing — empty when nothing is known.
 */
class SiteCardsTest {

    private class SiteFakeIO(var stored: String? = null) : TextIO {
        override fun read(): String? = stored
        override fun write(text: String) {
            stored = text
        }
    }

    private fun stringList(arr: JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    @Test
    fun growCreatesCardAndAppendsFindings() {
        val cards = SiteCards(SiteFakeIO())
        assertNull(cards.cardFor("lu.ma"))
        cards.grow(
            "lu.ma", 100L,
            keyScreen = "Event page",
            element = "Register button",
            deadEnd = "The waitlist link loops"
        )
        val card = cards.cardFor("lu.ma")!!
        assertEquals("lu.ma", card.getString("domain"))
        assertEquals(
            listOf("Event page"), stringList(card.getJSONArray("keyScreens"))
        )
        assertEquals(
            listOf("Register button"), stringList(card.getJSONArray("elements"))
        )
        assertEquals(
            listOf("The waitlist link loops"),
            stringList(card.getJSONArray("deadEnds"))
        )
        assertEquals(100L, card.getLong("updatedAt"))
    }

    @Test
    fun growDeduplicatesAndIgnoresBlanks() {
        val cards = SiteCards(SiteFakeIO())
        cards.grow("lu.ma", 100L, keyScreen = "Event page")
        cards.grow("lu.ma", 200L, keyScreen = "Event page")
        cards.grow("lu.ma", 300L, keyScreen = "  Event page  ")
        cards.grow("lu.ma", 400L, keyScreen = "   ", element = "")
        val card = cards.cardFor("lu.ma")!!
        assertEquals(
            listOf("Event page"), stringList(card.getJSONArray("keyScreens"))
        )
        assertTrue(stringList(card.getJSONArray("elements")).isEmpty())
        assertEquals(400L, card.getLong("updatedAt"))
    }

    @Test
    fun growCapsListsAtTwelveMostRecentLast() {
        val cards = SiteCards(SiteFakeIO())
        for (i in 1..13) {
            cards.grow("example.com", i.toLong(), keyScreen = "Screen $i")
        }
        val screens = stringList(
            cards.cardFor("example.com")!!.getJSONArray("keyScreens")
        )
        assertEquals(12, screens.size)
        // The oldest fell off; the newest is last.
        assertEquals("Screen 2", screens.first())
        assertEquals("Screen 13", screens.last())
    }

    @Test
    fun growTrimsAndCutsEntriesAtOneHundredTwentyChars() {
        val cards = SiteCards(SiteFakeIO())
        cards.grow("example.com", 100L, element = "  " + "y".repeat(150) + "  ")
        val elements = stringList(
            cards.cardFor("example.com")!!.getJSONArray("elements")
        )
        assertEquals(1, elements.size)
        assertEquals(120, elements[0].length)
    }

    @Test
    fun domainsAreNormalised() {
        val cards = SiteCards(SiteFakeIO())
        cards.grow("  LU.ma ", 100L, keyScreen = "Event page")
        assertTrue(cards.cardFor("lu.ma") != null)
        assertTrue(cards.cardFor("LU.MA") != null)
    }

    @Test
    fun renderForIsEmptyWhenNoCardsExist() {
        val cards = SiteCards(SiteFakeIO())
        assertEquals("", cards.renderFor(listOf("lu.ma", "x.com")))
        assertEquals("", cards.renderFor(emptyList()))
        cards.grow("lu.ma", 100L, keyScreen = "Event page")
        // A domain without a card contributes nothing.
        assertEquals("", cards.renderFor(listOf("x.com")))
    }

    @Test
    fun renderForNamesScreensElementsAndDeadEnds() {
        val cards = SiteCards(SiteFakeIO())
        cards.grow(
            "lu.ma", 100L,
            keyScreen = "Event page", element = "Register button",
            deadEnd = "The waitlist link loops"
        )
        cards.grow("lu.ma", 200L, keyScreen = "Checkout")
        cards.grow("x.com", 300L, element = "Follow button")
        val rendered = cards.renderFor(listOf("lu.ma", "x.com"))
        assertTrue(
            rendered.contains(
                "Site notes for lu.ma (learned from earlier runs — " +
                    "a guide, not gospel):"
            )
        )
        assertTrue(rendered.contains("Screens: Event page, Checkout"))
        assertTrue(rendered.contains("Elements: Register button"))
        assertTrue(rendered.contains("Known dead-ends: The waitlist link loops"))
        assertTrue(rendered.contains("Site notes for x.com"))
        assertTrue(rendered.contains("Elements: Follow button"))
        // x.com has no screens or dead-ends learned — those
        // lines appear only once, for lu.ma.
        assertEquals(1, rendered.split("Screens:").size - 1)
        assertEquals(1, rendered.split("Known dead-ends:").size - 1)
    }
}
