package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WalletPrompt, families (Bitcoin + Solana): the same
 * exact-phrasing discipline, extended. A family is named
 * by the network words, the asset, or the target's shape;
 * conflicts and name targets on the non-EVM families are
 * questions with the reason in them — never guesses.
 * (The EVM grammar itself is pinned by WalletPromptTest.)
 */
class WalletPromptFamiliesTest {

    private val btcAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
    private val btcLegacy = "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH"
    private val solAddress = "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z"

    private fun parsed(text: String): WalletPrompt.SendIntent {
        val r = WalletPrompt.parse(text)
        assertTrue("expected Parsed for '$text', got $r", r is WalletPrompt.Result.Parsed)
        return (r as WalletPrompt.Result.Parsed).intent
    }

    private fun ambiguous(text: String) {
        val r = WalletPrompt.parse(text)
        assertTrue("expected Ambiguous for '$text', got $r", r is WalletPrompt.Result.Ambiguous)
    }

    // ---- bitcoin ----

    @Test
    fun bitcoinByAssetAndShape() {
        val i = parsed("send 0.001 BTC to $btcAddress")
        assertEquals(WalletGate.FAMILY_BITCOIN, i.family)
        assertEquals("bitcoin", i.chainKey)
        assertEquals("BTC", i.asset)
        assertEquals("0.001", i.amountText)
        assertEquals(false, i.targetIsName)
    }

    @Test
    fun bitcoinByWordsAndLegacyAddress() {
        val i = parsed("send 0.5 BTC to $btcLegacy on bitcoin")
        assertEquals(WalletGate.FAMILY_BITCOIN, i.family)
        val j = parsed("pay 0.5 BTC to $btcLegacy on btc")
        assertEquals(WalletGate.FAMILY_BITCOIN, j.family)
        val k = parsed("send \$25 of BTC to $btcAddress")
        assertEquals("25", k.usdAmountText)
        assertEquals(WalletGate.FAMILY_BITCOIN, k.family)
    }

    // ---- solana ----

    @Test
    fun solanaByAssetWordsAndShape() {
        val i = parsed("send 2 SOL to $solAddress")
        assertEquals(WalletGate.FAMILY_SOLANA, i.family)
        assertEquals("solana", i.chainKey)
        val j = parsed("send 2 SOL to $solAddress on solana")
        assertEquals(WalletGate.FAMILY_SOLANA, j.family)
        // A stablecoin send to a Solana-shaped target is a
        // Solana send with no network words at all.
        val k = parsed("send 5 USDC to $solAddress")
        assertEquals(WalletGate.FAMILY_SOLANA, k.family)
        assertEquals("USDC", k.asset)
        val l = parsed("send 5 USDT to $solAddress on solana")
        assertEquals(WalletGate.FAMILY_SOLANA, l.family)
        assertEquals("USDT", l.asset)
    }

    // ---- the questions ----

    @Test
    fun namesOnNonEvmFamiliesAsk() {
        // Fluid names resolve to EVM addresses only — on
        // Bitcoin and Solana a name is a question that says
        // so, never a resolved-anyway guess. (This is also
        // why "on solana" with a name stays Ambiguous, as
        // WalletPromptTest pinned before Solana existed:
        // the reason changed, the refusal didn't.)
        ambiguous("send 0.001 BTC to @ethuram")
        ambiguous("send 0.001 BTC to @ethuram on bitcoin")
        ambiguous("send 2 SOL to @ethuram")
        ambiguous("send 2 USDC to @ethuram on solana")
        ambiguous("send 5 SOL to @ethuram on ethereum")
    }

    @Test
    fun crossFamilyConflictsAsk() {
        // USDC exists on Solana — but not against a Bitcoin
        // address, and not on Bitcoin by word.
        ambiguous("send 5 USDC to $btcAddress")
        ambiguous("send 2 USDC to @ethuram on bitcoin")
        // An EVM-only asset against a Solana-shaped target.
        ambiguous("send 2 ETH to $solAddress")
        // BTC against a Solana-shaped target.
        ambiguous("send 1 BTC to $solAddress")
        // A Bitcoin target under an EVM network word.
        ambiguous("send 0.001 BTC to $btcAddress on ethereum")
        // SOL against an EVM address.
        ambiguous("send 2 SOL to 0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf")
    }

    @Test
    fun familyAmountPrecision() {
        // BTC has 8 decimal places: 9 is a question.
        ambiguous("send 0.000000001 BTC to $btcAddress")
        val i = parsed("send 0.00000001 BTC to $btcAddress")
        assertEquals("0.00000001", i.amountText)
        // SOL has 9: a 9-place amount parses there.
        val j = parsed("send 0.000000001 SOL to $solAddress")
        assertEquals("0.000000001", j.amountText)
        // Zero is a question on every family.
        ambiguous("send 0 BTC to $btcAddress")
        ambiguous("send 0 SOL to $solAddress")
    }

    @Test
    fun evmBehaviourUnchangedByFamilyRouting() {
        // A name target with a stablecoin still defaults to
        // EVM — the registry resolves names there.
        val i = parsed("send 5 USDC to @ethuram")
        assertEquals(WalletGate.FAMILY_EVM, i.family)
        assertEquals("ethereum", i.chainKey)
        assertEquals(true, i.targetIsName)
        // An EVM address target stays EVM.
        val j = parsed("send 5 USDC to 0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf on base")
        assertEquals(WalletGate.FAMILY_EVM, j.family)
        assertEquals("base", j.chainKey)
    }
}
