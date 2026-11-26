package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OwnerGate (v0.10.1): Lille's OWN X account is hers alone —
 * the creation task is visible/creatable only when the
 * verified Fluid login identity is the owner's. These tests
 * pin the gate the Tasks screen acts on: owner (any human
 * spelling) → shown; any other verified name → hidden; no
 * verified login at all → hidden.
 */
class OwnerGateTest {

    // ---- owner: shown ----

    @Test
    fun ownerNameSeesXAccountCreation() {
        assertTrue(OwnerGate.canCreateXAccountTask("ethuram"))
        assertTrue(OwnerGate.isOwner("ethuram"))
    }

    @Test
    fun ownerNameCaseVariantsStillOwner() {
        assertTrue(OwnerGate.canCreateXAccountTask("Ethuram"))
        assertTrue(OwnerGate.canCreateXAccountTask("ETHURAM"))
        assertTrue(OwnerGate.canCreateXAccountTask("eThUrAm"))
    }

    @Test
    fun ownerNameWithWhitespaceOrAtSignStillOwner() {
        // Names are typed by humans; the gate compares through
        // the same normalisation as the login gate itself.
        assertTrue(OwnerGate.canCreateXAccountTask("  ethuram  "))
        assertTrue(OwnerGate.canCreateXAccountTask("@ethuram"))
    }

    // ---- everyone else: hidden ----

    @Test
    fun otherVerifiedNameCannotCreateXAccountTask() {
        assertFalse(OwnerGate.canCreateXAccountTask("meeday"))
        assertFalse(OwnerGate.canCreateXAccountTask("dripbot"))
        assertFalse(OwnerGate.canCreateXAccountTask("lille"))
        assertFalse(OwnerGate.isOwner("someoneelse"))
    }

    @Test
    fun nearMissNamesAreNotOwner() {
        assertFalse(OwnerGate.canCreateXAccountTask("ethuram2"))
        assertFalse(OwnerGate.canCreateXAccountTask("ethura"))
        assertFalse(OwnerGate.canCreateXAccountTask("ethuram.eth"))
    }

    @Test
    fun noVerifiedLoginCannotCreateXAccountTask() {
        assertFalse(OwnerGate.canCreateXAccountTask(null))
        assertFalse(OwnerGate.canCreateXAccountTask(""))
        assertFalse(OwnerGate.canCreateXAccountTask("   "))
        assertFalse(OwnerGate.isOwner(null))
    }

    // ---- the owner list itself ----

    @Test
    fun ownerListIsExactlyTheCreator() {
        // The list grows only by a deliberate code change; this
        // pins its current, reviewed contents.
        assertEquals(setOf("ethuram"), OwnerGate.OWNER_USERNAMES)
    }
}
