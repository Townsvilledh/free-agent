package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The family law (WALLET.md): Bitcoin and Solana spends
 * pass the SAME gate as EVM spends — same caps, same
 * approval rule, same refusals — because decide() never
 * asks which family a prepared transaction came from.
 * These tests pin that parity, the per-family approval
 * sheets, and the stub-signer proof for the new families:
 * no Allow, no signature.
 */
class WalletFamilyGateTest {

    private val now = 1_700_000_000_000L

    private fun btcPrepared(usdCents: Long? = 300) = WalletGate.PreparedTx(
        chainKey = "bitcoin",
        tokenSymbol = "BTC",
        amountRaw = BigInteger.valueOf(100_000),
        amountDisplay = "0.001",
        recipient = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
        recipientLabel = "address",
        usdCents = usdCents,
        priceSource = if (usdCents != null) "coingecko" else null,
        gasLimit = BigInteger.ZERO,
        maxFeeWei = BigInteger.valueOf(280),
        maxFeeUsdCents = 1,
        nonce = BigInteger.ZERO,
        maxPriorityFeePerGas = BigInteger.ZERO,
        maxFeePerGas = BigInteger.ZERO,
        family = WalletGate.FAMILY_BITCOIN,
        btcPlan = Btc.SpendPlan(
            inputs = listOf(Btc.Utxo("aa".repeat(32), 0, 200_000)),
            amountSats = 100_000,
            changeSats = 99_720,
            feeSats = 280,
            rateSatPerVb = BigDecimal(2)
        )
    )

    private fun solPrepared(
        usdCents: Long? = 300, rentLamports: Long = 0
    ) = WalletGate.PreparedTx(
        chainKey = "solana",
        tokenSymbol = "USDC",
        amountRaw = BigInteger.valueOf(3_000_000),
        amountDisplay = "3",
        recipient = "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z",
        recipientLabel = "address",
        usdCents = usdCents,
        priceSource = if (usdCents != null) "coingecko" else null,
        gasLimit = BigInteger.ZERO,
        maxFeeWei = BigInteger.valueOf(5_000),
        maxFeeUsdCents = 1,
        nonce = BigInteger.ZERO,
        maxPriorityFeePerGas = BigInteger.ZERO,
        maxFeePerGas = BigInteger.ZERO,
        family = WalletGate.FAMILY_SOLANA,
        solPlan = Sol.SpendPlan(
            kind = Sol.SpendKind.SPL,
            mint = Sol.USDC_MINT,
            sourceAta = ByteArray(32),
            destAta = ByteArray(32),
            createDestAta = rentLamports > 0,
            blockhash = "11111111111111111111111111111111",
            feeLamports = 5_000,
            rentLamports = rentLamports
        )
    )

    private fun evmPrepared(usdCents: Long? = 300) = WalletGate.PreparedTx(
        chainKey = "ethereum",
        tokenSymbol = "USDC",
        amountRaw = BigInteger.valueOf(3_000_000),
        amountDisplay = "3",
        recipient = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf",
        recipientLabel = "address",
        usdCents = usdCents,
        priceSource = if (usdCents != null) "coingecko" else null,
        gasLimit = BigInteger.valueOf(65_000),
        maxFeeWei = BigInteger("1000000000000000"),
        maxFeeUsdCents = 300,
        nonce = BigInteger.ZERO,
        maxPriorityFeePerGas = BigInteger.valueOf(1_000_000_000),
        maxFeePerGas = BigInteger.valueOf(30_000_000_000)
    )

    private fun families(usdCents: Long?) = listOf(
        evmPrepared(usdCents), btcPrepared(usdCents), solPrepared(usdCents)
    )

    // ---- the gate is family-blind ----

    @Test
    fun sameVerdictsOnEveryFamily() {
        // Prepare-and-ask: no approval → NeedsApproval.
        for (p in families(300)) {
            assertEquals(
                WalletGate.Decision.NeedsApproval,
                WalletGate.decide(
                    p, WalletGate.CapState(),
                    autonomousOn = false, userApproved = false,
                    rawAmountConfirmed = false, nowMs = now
                )
            )
            // The user's tap → Allow.
            assertEquals(
                WalletGate.Decision.Allow,
                WalletGate.decide(
                    p, WalletGate.CapState(),
                    autonomousOn = false, userApproved = true,
                    rawAmountConfirmed = false, nowMs = now
                )
            )
            // Autonomy within caps → Allow without a tap.
            assertEquals(
                WalletGate.Decision.Allow,
                WalletGate.decide(
                    p, WalletGate.CapState(),
                    autonomousOn = true, userApproved = false,
                    rawAmountConfirmed = false, nowMs = now
                )
            )
        }
    }

    @Test
    fun capsRefuseOnEveryFamilyAndApprovalDoesNotOverride() {
        // $999.99 against the $5 default per-tx cap: refused
        // on every family, and an approval tap does NOT
        // override a cap refusal — caps change in Settings
        // or not at all.
        for (p in families(99_999)) {
            val d = WalletGate.decide(
                p, WalletGate.CapState(),
                autonomousOn = true, userApproved = true,
                rawAmountConfirmed = true, nowMs = now
            )
            assertTrue(d is WalletGate.Decision.Refused)
        }
    }

    @Test
    fun unpricedRulesAreTheSameOnEveryFamily() {
        for (p in families(null)) {
            // Autonomous signing of an unpriced amount: refused.
            assertTrue(
                WalletGate.decide(
                    p, WalletGate.CapState(),
                    autonomousOn = true, userApproved = false,
                    rawAmountConfirmed = false, nowMs = now
                ) is WalletGate.Decision.Refused
            )
            // Approval without the raw-amount confirmation: refused.
            assertTrue(
                WalletGate.decide(
                    p, WalletGate.CapState(),
                    autonomousOn = false, userApproved = true,
                    rawAmountConfirmed = false, nowMs = now
                ) is WalletGate.Decision.Refused
            )
            // Approval WITH it: allowed.
            assertEquals(
                WalletGate.Decision.Allow,
                WalletGate.decide(
                    p, WalletGate.CapState(),
                    autonomousOn = false, userApproved = true,
                    rawAmountConfirmed = true, nowMs = now
                )
            )
        }
    }

    /** A signer that signs ONLY on Allow — the shape of
     *  every family engine's execute. */
    private class StubSigner {
        var signatures = 0
        fun execute(
            p: WalletGate.PreparedTx,
            caps: WalletGate.CapState,
            autonomousOn: Boolean,
            userApproved: Boolean,
            rawAmountConfirmed: Boolean,
            nowMs: Long
        ): WalletGate.Decision {
            val d = WalletGate.decide(
                p, caps, autonomousOn, userApproved, rawAmountConfirmed, nowMs
            )
            if (d == WalletGate.Decision.Allow) signatures++
            return d
        }
    }

    @Test
    fun stubSignerProducesNoBtcOrSolSignatureWithoutAllow() {
        val signer = StubSigner()
        for (p in listOf(btcPrepared(), solPrepared())) {
            signer.execute(
                p, WalletGate.CapState(),
                autonomousOn = false, userApproved = false,
                rawAmountConfirmed = false, nowMs = now
            )
            signer.execute(
                p.copy(usdCents = 99_999), WalletGate.CapState(),
                autonomousOn = true, userApproved = true,
                rawAmountConfirmed = true, nowMs = now
            )
        }
        assertEquals(0, signer.signatures)
        signer.execute(
            btcPrepared(), WalletGate.CapState(),
            autonomousOn = false, userApproved = true,
            rawAmountConfirmed = false, nowMs = now
        )
        signer.execute(
            solPrepared(), WalletGate.CapState(),
            autonomousOn = false, userApproved = true,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(2, signer.signatures)
    }

    // ---- per-family approval sheets ----

    @Test
    fun bitcoinSheetNamesBitcoinAndItsFeeInBtc() {
        val text = WalletGate.approvalSheetText(
            btcPrepared(), "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        )
        assertTrue(text.contains("0.001 BTC"))
        assertTrue(text.contains("on Bitcoin."))
        assertTrue(text.contains("Network fee: 0.0000028 BTC"))
        assertTrue(text.contains("LILLE'S wallet only"))
    }

    @Test
    fun solanaSheetNamesSolanaFeeAndRent() {
        val plain = WalletGate.approvalSheetText(
            solPrepared(), "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z"
        )
        assertTrue(plain.contains("3 USDC"))
        assertTrue(plain.contains("on Solana."))
        assertTrue(plain.contains("Network fee: 0.000005 SOL"))
        assertTrue(!plain.contains("rent"))

        val withRent = WalletGate.approvalSheetText(
            solPrepared(rentLamports = Sol.TOKEN_ACCOUNT_RENT_LAMPORTS),
            "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z"
        )
        assertTrue(withRent.contains("one-time rent of 0.00203928 SOL"))
        assertTrue(withRent.contains("recipient's USDC account"))
    }

    // ---- family tables & prices ----

    @Test
    fun familyTables() {
        assertTrue("BTC" in WalletGate.SUPPORTED_SYMBOLS)
        assertTrue("SOL" in WalletGate.SUPPORTED_SYMBOLS)
        assertEquals(
            8, WalletGate.familyToken(WalletGate.FAMILY_BITCOIN, "BTC")!!.decimals
        )
        assertEquals(
            9, WalletGate.familyToken(WalletGate.FAMILY_SOLANA, "SOL")!!.decimals
        )
        assertEquals(
            Sol.USDC_MINT,
            WalletGate.familyToken(WalletGate.FAMILY_SOLANA, "USDC")!!.contract
        )
        assertEquals(
            Sol.USDT_MINT,
            WalletGate.familyToken(WalletGate.FAMILY_SOLANA, "USDT")!!.contract
        )
        assertEquals("Bitcoin", WalletGate.networkLabel("bitcoin"))
        assertEquals("Solana", WalletGate.networkLabel("solana"))
        assertEquals("Ethereum", WalletGate.networkLabel("ethereum"))
    }

    @Test
    fun priceRoutesCoverTheNewFamilies() {
        assertEquals("BTC", PriceChain.coinbaseSymbol("bitcoin"))
        assertEquals("SOL", PriceChain.coinbaseSymbol("solana"))
        assertEquals(
            listOf("XXBTZUSD", "XBTUSD"), PriceChain.krakenPairs("bitcoin")
        )
        assertEquals(listOf("SOLUSD"), PriceChain.krakenPairs("solana"))
        // Kraken's own key for bitcoin is XXBTZUSD; the
        // fallback key parses too.
        val body = """{"error":[],"result":{"XXBTZUSD":{"c":["97000.0","0.1"]}}}"""
        assertEquals(
            BigDecimal("97000.0"),
            PriceChain.parseKraken(body, PriceChain.krakenPairs("bitcoin"))
        )
    }
}
