package org.freeagent.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Pins for the main-wallet transaction lane (v0.13.1):
 * the widened session proposal, the honest degradation on
 * a sign-in-only session, the payload discipline (refusals
 * fire BEFORE anything is sent), and the #wc= deep link.
 */
class FluidTxTest {

    private val address = "0x1111111111111111111111111111111111111111"
    private val otherAddress = "0x2222222222222222222222222222222222222222"

    private fun allChainIds(): Set<Long> =
        WalletGate.CHAINS.map { it.chainId }.toSet()

    private fun fullGrant() = FluidTx.Granted(
        methods = setOf(
            FluidGate.METHOD_PERSONAL_SIGN,
            FluidGate.METHOD_SEND_TRANSACTION
        ),
        accountChains = allChainIds()
    )

    private fun sampleSend(
        token: WalletGate.Token = WalletGate.ETHEREUM.tokens[0],
        chain: WalletGate.Chain = WalletGate.ETHEREUM,
        amountRaw: BigInteger = BigInteger("1000000000000000000")
    ): FluidTx.MainSend {
        val (to, value, data) = FluidTx.payloadFor(token, address, amountRaw)
        return FluidTx.MainSend(
            chainKey = chain.key,
            chainId = "eip155:${chain.chainId}",
            tokenSymbol = token.symbol,
            amountRaw = amountRaw,
            amountDisplay = Evm.formatUnits(amountRaw, token.decimals),
            fromAddress = otherAddress,
            recipient = address,
            recipientLabel = "address",
            to = to,
            valueWei = value,
            dataHex = data,
            usdCents = null
        )
    }

    // ---------------- the proposal ----------------

    @Test
    fun proposalAsksForSignAndSendOnEveryWalletChain() {
        assertEquals(
            listOf("personal_sign", "eth_sendTransaction"),
            FluidGate.proposalMethods()
        )
        // Exactly her companion wallet's chains, in order.
        assertEquals(
            WalletGate.CHAINS.map { "eip155:${it.chainId}" },
            FluidGate.proposalChains()
        )
        assertEquals(
            listOf(
                "eip155:1", "eip155:8453", "eip155:42161", "eip155:137",
                "eip155:56", "eip155:43114", "eip155:10"
            ),
            FluidGate.proposalChains()
        )
    }

    // ---------------- degradation ----------------

    @Test
    fun signInOnlySessionDegradesHonestly() {
        // An older Fluid (or a narrowed approval) grants
        // personal_sign alone: login still works, but no
        // chain may offer a transaction request.
        val granted = FluidTx.Granted(
            methods = setOf(FluidGate.METHOD_PERSONAL_SIGN),
            accountChains = allChainIds()
        )
        for (chain in WalletGate.CHAINS) {
            assertFalse(
                "tx must be unavailable on ${chain.key}",
                FluidTx.txAvailable(granted, chain.key)
            )
            val reason = FluidTx.txUnavailableReason(granted, chain.key)
            assertNotNull(reason)
            assertTrue(reason!!.contains("sign-in only"))
            assertTrue(reason.contains("Nothing was sent."))
        }
    }

    @Test
    fun fullGrantEnablesEveryChain() {
        val granted = fullGrant()
        for (chain in WalletGate.CHAINS) {
            assertTrue(FluidTx.txAvailable(granted, chain.key))
            assertNull(FluidTx.txUnavailableReason(granted, chain.key))
        }
    }

    @Test
    fun grantWithoutTheAccountOnAChainDegradesPerChain() {
        // Methods granted, but the account only appears on
        // Ethereum: Base degrades, Ethereum works.
        val granted = FluidTx.Granted(
            methods = setOf(
                FluidGate.METHOD_PERSONAL_SIGN,
                FluidGate.METHOD_SEND_TRANSACTION
            ),
            accountChains = setOf(1L)
        )
        assertTrue(FluidTx.txAvailable(granted, WalletGate.ETHEREUM.key))
        assertFalse(FluidTx.txAvailable(granted, WalletGate.BASE.key))
        val reason = FluidTx.txUnavailableReason(granted, WalletGate.BASE.key)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Base"))
    }

    // ---------------- payload discipline ----------------

    @Test
    fun selectorsAreTheErc20Ones() {
        assertEquals("0xa9059cbb", FluidTx.transferSelector())
        assertEquals("0x23b872dd", FluidTx.transferFromSelector())
        assertEquals("0x095ea7b3", FluidTx.approveSelector())
    }

    @Test
    fun plainNativeSendPasses() {
        assertNull(FluidTx.validateCall(address, "0x"))
        val send = sampleSend()
        assertEquals(address, send.to)
        assertEquals(BigInteger("1000000000000000000"), send.valueWei)
        assertEquals("0x", send.dataHex)
        assertNull(FluidTx.validateCall(send.to, send.dataHex))
    }

    @Test
    fun erc20TransferPassesAndCarriesNoValue() {
        val usdc = WalletGate.token(WalletGate.ETHEREUM, "USDC")!!
        val send = sampleSend(token = usdc, amountRaw = BigInteger("2500000"))
        assertEquals(usdc.contract, send.to)
        assertEquals(BigInteger.ZERO, send.valueWei)
        assertTrue(send.dataHex.startsWith(FluidTx.transferSelector()))
        assertNull(FluidTx.validateCall(send.to, send.dataHex))
    }

    @Test
    fun transferFromAndApproveShapesPass() {
        val word = "00".repeat(32)
        val transferFrom = FluidTx.transferFromSelector() +
            word + word + word
        assertNull(FluidTx.validateCall(address, transferFrom))
        val approve = FluidTx.approveSelector() + word + word
        assertNull(FluidTx.validateCall(address, approve))
    }

    @Test
    fun contractCreationIsRefusedBeforeSending() {
        val refusal = FluidTx.validateCall(null, "0x")
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("contract"))
        assertNotNull(FluidTx.validateCall("", "0x"))
        assertNotNull(FluidTx.validateCall("   ", "0x"))
    }

    @Test
    fun unknownSelectorIsRefusedBeforeSending() {
        val data = "0xdeadbeef" + "00".repeat(64)
        val refusal = FluidTx.validateCall(address, data)
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("Nothing was sent."))
    }

    @Test
    fun malformedCallsAreRefused() {
        // Right selector, wrong length (one word short).
        val short = FluidTx.transferSelector() + "00".repeat(32)
        assertNotNull(FluidTx.validateCall(address, short))
        // Too short to even hold a selector.
        assertNotNull(FluidTx.validateCall(address, "0x1234"))
        // Not an address at all.
        assertNotNull(FluidTx.validateCall("not-an-address", "0x"))
    }

    // ---------------- request params ----------------

    @Test
    fun txParamsCarryFromToValueData() {
        val send = sampleSend()
        val params = JSONArray(FluidTx.txParamsJson(send))
        assertEquals(1, params.length())
        val tx = params.getJSONObject(0)
        assertEquals(otherAddress, tx.getString("from"))
        assertEquals(address, tx.getString("to"))
        // 1 ETH in wei, hex quantity.
        assertEquals("0xde0b6b3a7640000", tx.getString("value"))
        assertEquals("0x", tx.getString("data"))
    }

    // ---------------- the deep link ----------------

    @Test
    fun deepLinkEncodesThePairingUri() {
        val uri = "wc:7f6e@2?relay-protocol=irn&symKey=abcd"
        assertEquals(
            "https://fluidwallet.dpdns.org/#wc=" +
                "wc%3A7f6e%402%3Frelay-protocol%3Dirn%26symKey%3Dabcd",
            FluidTx.deepLink(uri)
        )
    }

    @Test
    fun encodeUriComponentMatchesJavaScript() {
        // The unreserved set passes through untouched…
        assertEquals("aZ09-_.!~*'()", FluidTx.encodeUriComponent("aZ09-_.!~*'()"))
        // …spaces are %20 (never +), and UTF-8 is per-byte.
        assertEquals("a%20b", FluidTx.encodeUriComponent("a b"))
        assertEquals("%E2%82%BF", FluidTx.encodeUriComponent("₿"))
    }
}
