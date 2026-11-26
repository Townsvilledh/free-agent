package org.freeagent.app

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The wallet's USD price chain, shared by all three family
 * engines (extracted from WalletEngine, where it priced the
 * EVM wallet alone, when Bitcoin and Solana joined —
 * behaviour unchanged: the same routes, the same order,
 * the same 60-second cache; the CoinGecko call now also
 * prices bitcoin and solana).
 *
 * The chain (Fallback Law, first answer serves and is NAMED
 * in the returned Price):
 *   1. coingecko — one keyless call prices every asset
 *   2. coinbase  — keyless spot price per asset
 *   3. kraken    — keyless ticker per asset
 * No route answering means the value is UNKNOWN (null) —
 * the gate's first-class unpriced state, never a guess.
 *
 * Threading: each engine owns one instance and calls it
 * only from its own single executor thread.
 */
class WalletPrices(private val client: OkHttpClient) {

    private var priceCacheAt = 0L
    private val priceCache = mutableMapOf<String, PriceChain.Price>()

    /** A plain GET: the body on success, null on any
     *  failure. Also used by the engines for the small
     *  non-RPC reads (the Fluid names registry, Esplora). */
    fun get(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The USD price of a CoinGecko id, or null when no
     *  route could price it. */
    fun priceFor(priceId: String): PriceChain.Price? {
        val now = System.currentTimeMillis()
        if (now - priceCacheAt < 60_000) {
            priceCache[priceId]?.let { return it }
        }
        // 1. CoinGecko — one call prices every asset.
        get(
            "https://api.coingecko.com/api/v3/simple/price" +
                "?ids=ethereum,usd-coin,tether,weth,binancecoin," +
                "polygon-ecosystem-token,avalanche-2,wrapped-avax," +
                "bitcoin,solana" +
                "&vs_currencies=usd"
        )?.let { body ->
            val prices = PriceChain.parseCoinGecko(body)
            if (prices.isNotEmpty()) {
                priceCacheAt = now
                for ((id, usd) in prices) {
                    priceCache[id] = PriceChain.Price(usd, "coingecko")
                }
                priceCache[priceId]?.let { return it }
            }
        }
        // 2. Coinbase spot.
        PriceChain.coinbaseSymbol(priceId)?.let { symbol ->
            get("https://api.coinbase.com/v2/prices/$symbol-USD/spot")
                ?.let { body ->
                    PriceChain.parseCoinbase(body)?.let {
                        return PriceChain.Price(it, "coinbase")
                    }
                }
        }
        // 3. Kraken ticker.
        val pairs = PriceChain.krakenPairs(priceId)
        if (pairs.isNotEmpty()) {
            get("https://api.kraken.com/0/public/Ticker?pair=${pairs[0]}")
                ?.let { body ->
                    PriceChain.parseKraken(body, pairs)?.let {
                        return PriceChain.Price(it, "kraken")
                    }
                }
        }
        return null
    }
}
