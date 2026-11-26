package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The main-page wallet card (v1.0.1): balances at a glance
 * with the balances law in miniature — a real value, a
 * true zero, or an honest "couldn't load"; a failed read
 * NEVER renders as a number, and unheld tokens are not
 * listed.
 */
class WalletSummaryTest {

    private fun token(symbol: String, contract: String?, decimals: Int) =
        WalletGate.Token(symbol, contract, decimals, "test")

    private fun balance(
        symbol: String, contract: String?, decimals: Int, raw: BigInteger?
    ) = WalletEngine.AssetBalance(token(symbol, contract, decimals), raw, null)

    @Test
    fun trueZeroNativeShowsAsZero() {
        val line = WalletSummary.familyLine(
            "Bitcoin",
            WalletSummary.Family.Loaded(
                listOf(balance("BTC", null, 8, BigInteger.ZERO))
            )
        )
        assertEquals("Bitcoin · BTC 0", line)
    }

    @Test
    fun failedNativeReadNeverRendersAsANumber() {
        val line = WalletSummary.familyLine(
            "Bitcoin",
            WalletSummary.Family.Loaded(
                listOf(balance("BTC", null, 8, null))
            )
        )
        assertEquals("Bitcoin · couldn't load", line)
        assertFalse(line.contains("0"))
    }

    @Test
    fun heldTokensJoinUnheldAndFailedAreOmitted() {
        val line = WalletSummary.familyLine(
            "Ethereum",
            WalletSummary.Family.Loaded(
                listOf(
                    balance("ETH", null, 18, BigInteger("1500000000000000000")),
                    balance("USDC", "0xabc", 6, BigInteger("2500000")),
                    // unheld: omitted entirely
                    balance("USDT", "0xdef", 6, BigInteger.ZERO)
                )
            )
        )
        assertEquals("Ethereum · ETH 1.5 · USDC 2.5", line)
    }

    @Test
    fun partialTokenFailureIsDeclaredNotHidden() {
        val line = WalletSummary.familyLine(
            "Solana",
            WalletSummary.Family.Loaded(
                listOf(
                    balance("SOL", null, 9, BigInteger("2000000000")),
                    balance("USDC", "mint", 6, null)
                )
            )
        )
        assertTrue(line.startsWith("Solana · SOL 2"))
        assertTrue(line.contains("some tokens couldn't load"))
        // The failed token never appears with a figure.
        assertFalse(line.contains("USDC"))
    }

    @Test
    fun statesRenderPlainly() {
        assertEquals(
            "Bitcoin · not set up",
            WalletSummary.familyLine("Bitcoin", WalletSummary.Family.NotSetUp)
        )
        assertEquals(
            "Solana · loading…",
            WalletSummary.familyLine("Solana", WalletSummary.Family.Loading)
        )
        assertEquals(
            "Ethereum · couldn't load",
            WalletSummary.familyLine(
                "Ethereum", WalletSummary.Family.Loaded(emptyList())
            )
        )
    }

    @Test
    fun cardTextJoinsFamiliesInOrder() {
        val text = WalletSummary.cardText(
            listOf(
                "Ethereum" to WalletSummary.Family.Loaded(
                    listOf(balance("ETH", null, 18, BigInteger.ZERO))
                ),
                "Bitcoin" to WalletSummary.Family.NotSetUp,
                "Solana" to WalletSummary.Family.Loading
            )
        )
        assertEquals(
            "Ethereum · ETH 0\nBitcoin · not set up\nSolana · loading…",
            text
        )
    }
}
