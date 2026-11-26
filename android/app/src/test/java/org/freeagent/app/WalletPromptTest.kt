package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WalletPrompt (v0.12): the ONLY road from chat words to a
 * prepared transaction. Supported phrasings parse exactly;
 * everything send-shaped but loose is a question, never a
 * guess; everything else is ordinary chat for the brain.
 */
class WalletPromptTest {

    private fun parsed(text: String): WalletPrompt.SendIntent {
        val r = WalletPrompt.parse(text)
        assertTrue("expected Parsed for '$text', got $r", r is WalletPrompt.Result.Parsed)
        return (r as WalletPrompt.Result.Parsed).intent
    }

    @Test
    fun tokenFormToAddress() {
        val i = parsed("send 2 USDC to 0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf")
        assertEquals("2", i.amountText)
        assertEquals(null, i.usdAmountText)
        assertEquals("USDC", i.asset)
        assertEquals(false, i.targetIsName)
        assertEquals("ethereum", i.chainKey)
    }

    @Test
    fun tokenFormToNameOnBase() {
        val i = parsed("send 0.5 eth to @ethuram on base")
        assertEquals("0.5", i.amountText)
        assertEquals("ETH", i.asset)
        assertEquals(true, i.targetIsName)
        assertEquals("@ethuram", i.targetRaw)
        assertEquals("base", i.chainKey)
    }

    @Test
    fun payAliasAndPleaseAndPeriod() {
        // Leading "Please" is not part of the grammar — the
        // message starts with "please", not send/pay, so it
        // is ordinary chat. (Strictness is the feature.)
        assertTrue(WalletPrompt.parse("Please pay 10 USDT to bob") is WalletPrompt.Result.NotWallet)
        val j = parsed("pay 10 USDT to bob please.")
        assertEquals("10", j.amountText)
        assertEquals("USDT", j.asset)
        assertEquals(true, j.targetIsName)
        assertEquals("ethereum", j.chainKey) // default chain
    }

    @Test
    fun usdForms() {
        val a = parsed("send \$3 of ETH to @ethuram")
        assertEquals("3", a.usdAmountText)
        assertEquals(null, a.amountText)
        assertEquals("ETH", a.asset)
        val b = parsed("send 3 dollars of WETH to @ethuram on base")
        assertEquals("3", b.usdAmountText)
        assertEquals("WETH", b.asset)
        assertEquals("base", b.chainKey)
    }

    @Test
    fun mainnetAlias() {
        val i = parsed("send 1 USDC to @a on mainnet")
        assertEquals("ethereum", i.chainKey)
    }

    @Test
    fun ambiguousCasesAsk() {
        // No amount.
        assertTrue(
            WalletPrompt.parse("send USDC to @ethuram") is WalletPrompt.Result.Ambiguous
        )
        // Unsupported asset.
        assertTrue(
            WalletPrompt.parse("send 2 DOGE to @ethuram") is WalletPrompt.Result.Ambiguous
        )
        // Unsupported chain.
        assertTrue(
            WalletPrompt.parse("send 2 USDC to @ethuram on solana")
                is WalletPrompt.Result.Ambiguous
        )
        // Zero amount.
        assertTrue(
            WalletPrompt.parse("send 0 USDC to @ethuram") is WalletPrompt.Result.Ambiguous
        )
        // Too many decimals for the asset.
        assertTrue(
            WalletPrompt.parse("send 0.0000001 USDC to @ethuram")
                is WalletPrompt.Result.Ambiguous
        )
        // Comma decimal — ambiguous in the real world, so here too.
        assertTrue(
            WalletPrompt.parse("send 2,5 USDC to @ethuram") is WalletPrompt.Result.Ambiguous
        )
        // Unreadable target.
        assertTrue(
            WalletPrompt.parse("send 2 USDC to ???") is WalletPrompt.Result.Ambiguous
        )
        // Extra words after the target break the exact form.
        assertTrue(
            WalletPrompt.parse("send 2 USDC to @ethuram for rent")
                is WalletPrompt.Result.Ambiguous
        )
    }

    // ---- the widened chain set (v0.12.4) ----

    @Test
    fun newChainNamesParse() {
        assertEquals("arbitrum", parsed("send 2 USDC to @a on arbitrum").chainKey)
        assertEquals("polygon", parsed("send 2 USDC to @a on polygon").chainKey)
        assertEquals("bsc", parsed("send 2 USDC to @a on bsc").chainKey)
        assertEquals("bsc", parsed("send 2 USDC to @a on bnb").chainKey)
        assertEquals("avalanche", parsed("send 2 USDC to @a on avalanche").chainKey)
        assertEquals("avalanche", parsed("send 2 USDC to @a on avax").chainKey)
        assertEquals("optimism", parsed("send 2 USDC to @a on optimism").chainKey)
    }

    @Test
    fun newNativeAssetsParseOnTheirOwnChains() {
        val bnb = parsed("send 0.25 BNB to @ethuram on bsc")
        assertEquals("BNB", bnb.asset)
        assertEquals("bsc", bnb.chainKey)
        val pol = parsed("send 25 POL to @ethuram on polygon")
        assertEquals("POL", pol.asset)
        assertEquals("polygon", pol.chainKey)
        val avax = parsed("pay 1 AVAX to @ethuram on avalanche")
        assertEquals("AVAX", avax.asset)
        assertEquals("avalanche", avax.chainKey)
        val usd = parsed("send \$5 of USDT to @ethuram on bnb")
        assertEquals("5", usd.usdAmountText)
        assertEquals("bsc", usd.chainKey)
    }

    @Test
    fun assetMustExistOnTheChosenChain() {
        // BNB is hers — on BNB Smart Chain. On Ethereum it
        // is a question, never a guess and never a crash.
        assertTrue(
            WalletPrompt.parse("send 2 BNB to @ethuram on ethereum")
                is WalletPrompt.Result.Ambiguous
        )
        assertTrue(
            WalletPrompt.parse("send 2 POL to @ethuram")
                is WalletPrompt.Result.Ambiguous
        )
        assertTrue(
            WalletPrompt.parse("send 2 WAVAX to @ethuram on base")
                is WalletPrompt.Result.Ambiguous
        )
    }

    @Test
    fun bscStablesAllow18DecimalAmounts() {
        // The BSC decimals trap, from the user's side: a
        // 7-decimal USDC amount is unparseable dust on
        // Ethereum (6 dp) and a real amount on BSC (18 dp).
        assertTrue(
            WalletPrompt.parse("send 0.0000001 USDC to @ethuram")
                is WalletPrompt.Result.Ambiguous
        )
        val i = parsed("send 0.0000001 USDC to @ethuram on bsc")
        assertEquals("0.0000001", i.amountText)
        assertEquals("bsc", i.chainKey)
    }

    // ---- the source clause (v0.13.1) ----

    @Test
    fun defaultSourceIsHerOwnWallet() {
        val i = parsed("send 2 USDC to @ethuram")
        assertEquals(WalletPrompt.Source.COMPANION, i.source)
    }

    @Test
    fun fromFluidChoosesTheMainWallet() {
        val i = parsed("send 2 USDC to @ethuram from fluid")
        assertEquals(WalletPrompt.Source.FLUID, i.source)
        assertEquals("USDC", i.asset)
        assertEquals("ethereum", i.chainKey)
    }

    @Test
    fun fromMyFluidWalletWithChain() {
        val i = parsed("send 0.5 eth to @ethuram on base from my fluid wallet")
        assertEquals(WalletPrompt.Source.FLUID, i.source)
        assertEquals("base", i.chainKey)
        assertEquals("ETH", i.asset)
    }

    @Test
    fun fromFluidOnTheUsdForm() {
        val i = parsed("send \$3 of ETH to @ethuram from fluid")
        assertEquals(WalletPrompt.Source.FLUID, i.source)
        assertEquals("3", i.usdAmountText)
    }

    @Test
    fun fromFluidToleratesPleaseAndFullStop() {
        val i = parsed("pay 1 USDT to @ethuram from fluid please")
        assertEquals(WalletPrompt.Source.FLUID, i.source)
        val j = parsed("send 2 USDC to @ethuram from my fluid wallet.")
        assertEquals(WalletPrompt.Source.FLUID, j.source)
    }

    @Test
    fun ordinaryChatIsNotWallet() {
        assertEquals(
            WalletPrompt.Result.NotWallet,
            WalletPrompt.parse("hi")
        )
        assertEquals(
            WalletPrompt.Result.NotWallet,
            WalletPrompt.parse("what is the price of ETH?")
        )
        assertEquals(
            WalletPrompt.Result.NotWallet,
            WalletPrompt.parse("please send me the report when it's ready")
        )
        assertEquals(
            WalletPrompt.Result.NotWallet,
            WalletPrompt.parse("how do I send crypto to a friend?")
        )
    }
}
