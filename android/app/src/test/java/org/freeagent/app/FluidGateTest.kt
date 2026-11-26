package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FluidGate (v0.10): the Fluid login gate's decisions —
 * registry lookup, session verification, startup decision.
 *
 * The registry fixture below is a RECORDED EXCERPT of the live
 * Fluid names registry (https://fluidwallet.dpdns.org/names.json,
 * fetched 2026-11-26): ethuram / dripbot / meeday carry their
 * real published addresses (long non-EVM fields trimmed). One
 * entry is SYNTHETIC, marked as such: "btconlyname" publishes
 * no evm address, pinning the honest NoEvmAddress failure —
 * every real name in the live registry currently carries one,
 * so the case can only be fixtured synthetically.
 */
class FluidGateTest {

    companion object {
        private const val ETHURAM_EVM = "0x0EBf5563F905E3B9D58718E28E08143511DC763b"
        private const val DRIPBOT_EVM = "0x7Fe638235FaD0fdfbBcC759640d881e0fa62135B"
        private const val MEEDAY_EVM = "0xc675C7A6fdC261eF546D526726A2d262f54E1051"

        private val REGISTRY = """
            {
              "ethuram": {
                "evm": "$ETHURAM_EVM",
                "btc": "bc1qh0eydm0klss3y0j79d3mctcvkpxu7xrzyfzqdw",
                "algo": "IHRNG3GNVJILY66Y6D5V3NS6BSOBACXJGHAUSAR37S77JS4HRPEDWWWSC4",
                "sol": "3fk5FVGSAJLoKHrp51a3CNygtZ97L6oxCvYfTqwnZNbr"
              },
              "dripbot": {
                "evm": "$DRIPBOT_EVM",
                "btc": "bc1qxtn2gkquphkhazvy6x8ny8tejxm530xlcskqqn"
              },
              "meeday": { "evm": "$MEEDAY_EVM" },
              "btconlyname": { "btc": "bc1qsyntheticfixture0000000000000000000" }
            }
        """
    }

    // ---- name normalisation ----

    @Test
    fun nameNormalisationTrimsLowercasesAndDropsAt() {
        assertEquals("ethuram", FluidGate.normaliseName("  Ethuram "))
        assertEquals("ethuram", FluidGate.normaliseName("@ethuram"))
        assertEquals("", FluidGate.normaliseName("   "))
    }

    // ---- registry lookup ----

    @Test
    fun lookupFindsRecordedNames() {
        assertEquals(
            FluidGate.Lookup.Found(ETHURAM_EVM),
            FluidGate.lookup(REGISTRY, "ethuram")
        )
        assertEquals(
            FluidGate.Lookup.Found(MEEDAY_EVM),
            FluidGate.lookup(REGISTRY, "MEEDAY")
        )
    }

    @Test
    fun lookupUnknownNameFailsHonestly() {
        assertEquals(
            FluidGate.Lookup.NameNotFound,
            FluidGate.lookup(REGISTRY, "nosuchname")
        )
        assertEquals(
            FluidGate.Lookup.NameNotFound,
            FluidGate.lookup(REGISTRY, "")
        )
    }

    @Test
    fun lookupNameWithoutEvmFailsHonestly() {
        assertEquals(
            FluidGate.Lookup.NoEvmAddress,
            FluidGate.lookup(REGISTRY, "btconlyname")
        )
    }

    @Test
    fun lookupBrokenRegistryFailsHonestly() {
        assertEquals(
            FluidGate.Lookup.RegistryInvalid,
            FluidGate.lookup("this is not json", "ethuram")
        )
    }

    // ---- CAIP-10 handling ----

    @Test
    fun accountAddressParsesCaip10() {
        assertEquals(ETHURAM_EVM, FluidGate.accountAddress("eip155:1:$ETHURAM_EVM"))
        assertNull(FluidGate.accountAddress("eip155:1"))
        assertNull(FluidGate.accountAddress("not-an-account"))
    }

    @Test
    fun eip155AddressPicksTheEvmAccount() {
        val accounts = listOf(
            "bip122:000000000019d6689c085ae165831e93:bc1qxyz",
            "eip155:1:$DRIPBOT_EVM"
        )
        assertEquals(DRIPBOT_EVM, FluidGate.eip155Address(accounts))
        assertNull(FluidGate.eip155Address(listOf("bip122:00:bc1qxyz")))
        assertNull(FluidGate.eip155Address(emptyList()))
    }

    // ---- verification ----

    @Test
    fun verifyAcceptsTheOwningWallet() {
        val result = FluidGate.verify(
            REGISTRY, "ethuram", listOf("eip155:1:$ETHURAM_EVM")
        )
        assertEquals(FluidGate.Verify.Verified(ETHURAM_EVM), result)
    }

    @Test
    fun verifyIsCaseInsensitiveOnAddresses() {
        // Registry publishes checksummed; wallets may report lowercase.
        val result = FluidGate.verify(
            REGISTRY, "ethuram",
            listOf("eip155:1:${ETHURAM_EVM.lowercase()}")
        )
        assertTrue(result is FluidGate.Verify.Verified)
    }

    @Test
    fun verifyRejectsAnotherPersonsWallet() {
        // Typing Ethuram's name and connecting DripBot's wallet
        // must NOT open Lille.
        val result = FluidGate.verify(
            REGISTRY, "ethuram", listOf("eip155:1:$DRIPBOT_EVM")
        )
        assertEquals(FluidGate.Verify.Mismatch(DRIPBOT_EVM), result)
        assertTrue(
            FluidGate.mismatchMessage("ethuram")
                .startsWith("That wallet doesn't own @ethuram")
        )
    }

    @Test
    fun verifyRejectsSessionWithoutEip155() {
        val result = FluidGate.verify(
            REGISTRY, "ethuram", listOf("bip122:000000000019d6689c085ae165831e93:bc1qxyz")
        )
        assertEquals(FluidGate.Verify.NoEip155Account, result)
    }

    @Test
    fun verifySurfacesLookupFailures() {
        assertEquals(
            FluidGate.Verify.LookupFailed(FluidGate.Lookup.NameNotFound),
            FluidGate.verify(REGISTRY, "ghost", listOf("eip155:1:$ETHURAM_EVM"))
        )
        assertEquals(
            FluidGate.Verify.LookupFailed(FluidGate.Lookup.NoEvmAddress),
            FluidGate.verify(REGISTRY, "btconlyname", listOf("eip155:1:$ETHURAM_EVM"))
        )
    }

    @Test
    fun sessionMatchesStoredIdentity() {
        assertTrue(
            FluidGate.sessionMatchesIdentity(
                listOf("eip155:1:${ETHURAM_EVM.lowercase()}"), ETHURAM_EVM
            )
        )
        assertFalse(
            FluidGate.sessionMatchesIdentity(
                listOf("eip155:1:$DRIPBOT_EVM"), ETHURAM_EVM
            )
        )
        assertFalse(FluidGate.sessionMatchesIdentity(emptyList(), ETHURAM_EVM))
    }

    // ---- startup decision ----

    @Test
    fun decideNoIdentityGoesToLogin() {
        assertEquals(
            FluidGate.Decision.LOGIN,
            FluidGate.decide(null, FluidGate.SessionState.LIVE)
        )
        assertEquals(
            FluidGate.Decision.LOGIN,
            FluidGate.decide("  ", FluidGate.SessionState.GONE)
        )
    }

    @Test
    fun decideLiveSessionOpens() {
        assertEquals(
            FluidGate.Decision.OPEN,
            FluidGate.decide("ethuram", FluidGate.SessionState.LIVE)
        )
    }

    @Test
    fun decideGoneSessionReturnsToLoginPrefilled() {
        assertEquals(
            FluidGate.Decision.LOGIN_PREFILLED,
            FluidGate.decide("ethuram", FluidGate.SessionState.GONE)
        )
    }

    @Test
    fun decideUncheckableSessionNeverLocksOutAVerifiedUser() {
        assertEquals(
            FluidGate.Decision.OPEN,
            FluidGate.decide("ethuram", FluidGate.SessionState.UNKNOWN)
        )
    }

    // ---- pairing URI shape ----

    @Test
    fun pairingUriShape() {
        assertTrue(
            FluidGate.isPairingUri(
                "wc:7f6e504bfad60b485450578e808c35e9ca8e15802a8b0b5a2e7c9d8e@2" +
                    "?relay-protocol=irn&symKey=2fb5b97b9d0e4d1a8b7c6f5e4d3c2b1a"
            )
        )
        assertFalse(FluidGate.isPairingUri("https://fluidwallet.dpdns.org"))
        assertFalse(FluidGate.isPairingUri("wc:abc@1?relay-protocol=irn"))
    }

    // ---- constants the login flow is built on ----

    @Test
    fun sessionRequestIsEip155OnlyAndPersonalSignOnly() {
        assertEquals("eip155:1", FluidGate.CHAIN_ID)
        assertEquals("eip155", FluidGate.NAMESPACE)
        assertEquals("personal_sign", FluidGate.METHOD_PERSONAL_SIGN)
        assertEquals(
            "https://fluidwallet.dpdns.org/names.json", FluidGate.REGISTRY_URL
        )
    }
}
