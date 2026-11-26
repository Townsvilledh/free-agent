package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * PriceChain (v0.12): the three keyless price routes'
 * response shapes. A body that doesn't parse yields NO
 * price — the chain falls through, and if every route
 * fails the value is honestly UNKNOWN to the gate.
 */
class PriceChainTest {

    @Test
    fun coinGeckoParses() {
        val prices = PriceChain.parseCoinGecko(
            """{"ethereum":{"usd":3200.5},"usd-coin":{"usd":1.0},"tether":{"usd":0.99}}"""
        )
        assertEquals(0, BigDecimal("3200.5").compareTo(prices["ethereum"]))
        assertEquals(0, BigDecimal("1.0").compareTo(prices["usd-coin"]))
        assertTrue(prices.containsKey("tether"))
    }

    @Test
    fun coinGeckoRejectsGarbage() {
        assertTrue(PriceChain.parseCoinGecko("not json").isEmpty())
        assertTrue(PriceChain.parseCoinGecko("""{"ethereum":{}}""").isEmpty())
        assertTrue(PriceChain.parseCoinGecko("""{"ethereum":{"usd":0}}""").isEmpty())
    }

    @Test
    fun coinbaseParses() {
        val price = PriceChain.parseCoinbase(
            """{"data":{"amount":"3200.50","base":"ETH","currency":"USD"}}"""
        )
        assertEquals(0, BigDecimal("3200.50").compareTo(price))
    }

    @Test
    fun coinbaseRejectsGarbage() {
        assertNull(PriceChain.parseCoinbase("""{"data":{}}"""))
        assertNull(PriceChain.parseCoinbase("error"))
    }

    @Test
    fun krakenParses() {
        val price = PriceChain.parseKraken(
            """{"error":[],"result":{"XETHZUSD":{"c":["3200.50000","0.005"]}}}""",
            PriceChain.krakenPairs("ethereum")
        )
        assertEquals(0, BigDecimal("3200.50000").compareTo(price))
    }

    @Test
    fun krakenRejectsGarbage() {
        assertNull(PriceChain.parseKraken("""{"error":["EQuery:Unknown asset pair"],"result":{}}""",
            PriceChain.krakenPairs("ethereum")))
        assertNull(PriceChain.parseKraken("{}", PriceChain.krakenPairs("usd-coin")))
    }

    @Test
    fun routeTablesCoverEveryPricedToken() {
        for (chain in WalletGate.CHAINS) {
            for (token in chain.tokens) {
                assertTrue(
                    "coinbase symbol for ${token.priceId}",
                    PriceChain.coinbaseSymbol(token.priceId) != null
                )
                assertTrue(
                    "kraken pairs for ${token.priceId}",
                    PriceChain.krakenPairs(token.priceId).isNotEmpty()
                )
            }
        }
    }
}
