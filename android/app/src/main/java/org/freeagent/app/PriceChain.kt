package org.freeagent.app

import org.json.JSONObject
import java.math.BigDecimal

/**
 * Price-route response parsers (v0.12) — the pure half of
 * the wallet's USD price chain. The chain (Fallback Law,
 * walked by WalletEngine, first answer serves and is NAMED
 * in the result):
 *
 *   1. coingecko — GET api.coingecko.com/api/v3/simple/price
 *      (keyless public tier; rate-limited, hence the chain)
 *   2. coinbase  — GET api.coinbase.com/v2/prices/<SYM>-USD/spot
 *      (keyless public spot prices)
 *   3. kraken    — GET api.kraken.com/0/public/Ticker
 *      (keyless public tickers)
 *
 * Prices exist to value a prepared send against the user's
 * USD caps and to show an honest "about $X" on the approval
 * sheet. When NO route answers, the value is UNKNOWN — a
 * first-class state the gate (WalletGate) handles, never a
 * guessed number.
 */
object PriceChain {

    data class Price(val usd: BigDecimal, val source: String)

    /** CoinGecko simple/price body:
     *  {"ethereum":{"usd":3200.5},"usd-coin":{"usd":1.0}} —
     *  returns id → USD price. */
    fun parseCoinGecko(json: String): Map<String, BigDecimal> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = mutableMapOf<String, BigDecimal>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val entry = root.optJSONObject(id) ?: continue
            if (!entry.has("usd")) continue
            val value = try {
                BigDecimal(entry.get("usd").toString())
            } catch (e: Exception) {
                null
            }
            if (value != null && value.signum() > 0) out[id] = value
        }
        return out
    }

    /** Coinbase spot body: {"data":{"amount":"3200.50",
     *  "base":"ETH","currency":"USD"}}. */
    fun parseCoinbase(json: String): BigDecimal? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val data = root.optJSONObject("data") ?: return null
        val amount = try {
            BigDecimal(data.optString("amount", ""))
        } catch (e: Exception) {
            return null
        }
        return if (amount.signum() > 0) amount else null
    }

    /** Kraken ticker body: {"error":[],"result":{
     *  "XETHZUSD":{"c":["3200.50000","0.005"], …}}}. The pair
     *  keys are Kraken's internal names; [pairKeys] lists the
     *  acceptable keys for the asset in preference order. */
    fun parseKraken(json: String, pairKeys: List<String>): BigDecimal? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val result = root.optJSONObject("result") ?: return null
        for (key in pairKeys) {
            val pair = result.optJSONObject(key) ?: continue
            val c = pair.optJSONArray("c") ?: continue
            if (c.length() == 0) continue
            val value = try {
                BigDecimal(c.optString(0, ""))
            } catch (e: Exception) {
                null
            }
            if (value != null && value.signum() > 0) return value
        }
        return null
    }

    /** Kraken pair keys per price id, preference order. */
    fun krakenPairs(priceId: String): List<String> = when (priceId) {
        "ethereum" -> listOf("XETHZUSD", "ETHUSD")
        "usd-coin" -> listOf("USDCUSD")
        "tether" -> listOf("USDTUSD")
        "weth" -> listOf("XETHZUSD", "ETHUSD") // WETH prices as ETH
        "binancecoin" -> listOf("BNBUSD")
        "polygon-ecosystem-token" -> listOf("POLUSD")
        "avalanche-2" -> listOf("AVAXUSD")
        "wrapped-avax" -> listOf("AVAXUSD") // WAVAX prices as AVAX
        else -> emptyList()
    }

    /** Coinbase spot symbol per price id. */
    fun coinbaseSymbol(priceId: String): String? = when (priceId) {
        "ethereum", "weth" -> "ETH"
        "usd-coin" -> "USDC"
        "tether" -> "USDT"
        "binancecoin" -> "BNB"
        "polygon-ecosystem-token" -> "POL"
        "avalanche-2", "wrapped-avax" -> "AVAX"
        else -> null
    }
}
