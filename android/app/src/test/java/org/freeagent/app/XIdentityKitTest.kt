package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XIdentityKit (v0.10.1): the kit arrives as raw injected
 * strings (BuildConfig ← CI secrets) and is parsed here.
 * Fixture values only — the real kit never appears in tests.
 */
class XIdentityKitTest {

    @Test
    fun parsesCsvListsAndTrims() {
        val kit = XIdentityKit.fromRaw(
            " Alpha , Beta ,Gamma ",
            "  A bio.  ",
            " 1 January 1990 ",
            " mail-a.example,mail-b.example "
        )
        assertEquals(listOf("Alpha", "Beta", "Gamma"), kit.handles)
        assertEquals("A bio.", kit.bio)
        assertEquals("1 January 1990", kit.dob)
        assertEquals(listOf("mail-a.example", "mail-b.example"), kit.mailboxes)
        assertTrue(kit.isPresent())
    }

    @Test
    fun emptyInjectionIsMissing() {
        val kit = XIdentityKit.fromRaw("", "", "", "")
        assertFalse(kit.isPresent())
        assertTrue(kit.handles.isEmpty())
        assertTrue(kit.mailboxes.isEmpty())
    }

    @Test
    fun partialKitCountsAsMissing() {
        // Her signup must never improvise half an identity.
        assertFalse(
            XIdentityKit.fromRaw("Alpha", "A bio.", "", "mail.example")
                .isPresent()
        )
        assertFalse(
            XIdentityKit.fromRaw("", "A bio.", "1 January 1990", "mail.example")
                .isPresent()
        )
        assertFalse(
            XIdentityKit.fromRaw("Alpha", "  ", "1 January 1990", "mail.example")
                .isPresent()
        )
        assertFalse(
            XIdentityKit.fromRaw("Alpha", "A bio.", "1 January 1990", " , ")
                .isPresent()
        )
    }

    @Test
    fun blankCsvEntriesAreDropped() {
        val kit = XIdentityKit.fromRaw(
            "Alpha,, Beta,", "A bio.", "1 January 1990", "mail.example,,"
        )
        assertEquals(listOf("Alpha", "Beta"), kit.handles)
        assertEquals(listOf("mail.example"), kit.mailboxes)
        assertTrue(kit.isPresent())
    }
}
