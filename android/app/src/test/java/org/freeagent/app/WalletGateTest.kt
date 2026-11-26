package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * WalletGate (v0.12): caps, the rolling daily window, and
 * THE signing-path decision — including a stub signer that
 * proves no signature can happen without the gate's Allow.
 * The model is not in this file and cannot get into it:
 * decide() takes only built transactions, persisted cap
 * state, the user's settings switch, and the user's own
 * approval.
 */
class WalletGateTest {

    private val now = 1_700_000_000_000L
    private val usdc = WalletGate.token(WalletGate.ETHEREUM, "USDC")!!

    private fun prepared(
        usdCents: Long? = 300,
        isSweep: Boolean = false
    ) = WalletGate.PreparedTx(
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
        maxFeePerGas = BigInteger.valueOf(30_000_000_000),
        isSweep = isSweep
    )

    // ---- caps ----

    @Test
    fun perTxCapBoundary() {
        val caps = WalletGate.CapState()
        assertEquals(
            WalletGate.CapCheck.Ok,
            WalletGate.checkCaps(caps, 500, now)
        )
        assertTrue(
            WalletGate.checkCaps(caps, 501, now) is WalletGate.CapCheck.OverPerTx
        )
    }

    @Test
    fun dailyCapAccumulates() {
        var caps = WalletGate.CapState()
        caps = WalletGate.recordSpend(caps, 1_600, now)
        assertEquals(1_600L, WalletGate.spentInWindow(caps, now))
        // 1600 + 500 = 2100 > 2000 daily, amount within per-tx.
        assertTrue(
            WalletGate.checkCaps(caps, 500, now) is WalletGate.CapCheck.OverDaily
        )
        // Exactly at the line (1500 + 500 = 2000) is allowed.
        var caps2 = WalletGate.CapState()
        caps2 = WalletGate.recordSpend(caps2, 1_500, now)
        assertEquals(
            WalletGate.CapCheck.Ok,
            WalletGate.checkCaps(caps2, 500, now)
        )
    }

    @Test
    fun dailyWindowRollsOffAt24h() {
        var caps = WalletGate.CapState()
        caps = WalletGate.recordSpend(caps, 1_900, now)
        val later = now + WalletGate.DAY_MS
        // Exactly 24 h later the old spend has aged out.
        assertEquals(0L, WalletGate.spentInWindow(caps, later))
        assertEquals(
            WalletGate.CapCheck.Ok,
            WalletGate.checkCaps(caps, 500, later)
        )
        // One millisecond before, it still counts.
        assertEquals(1_900L, WalletGate.spentInWindow(caps, later - 1))
    }

    @Test
    fun dailyTotalsSurviveAsState() {
        // The state is a plain value: persist it, reload it,
        // the totals are the same (the store glue serialises
        // exactly this shape).
        var caps = WalletGate.CapState(perTxCapCents = 700, dailyCapCents = 3_000)
        caps = WalletGate.recordSpend(caps, 250, now)
        caps = WalletGate.recordSpend(caps, 250, now + 1_000)
        assertEquals(500L, WalletGate.spentInWindow(caps, now + 2_000))
        assertEquals(700L, caps.perTxCapCents)
    }

    @Test
    fun userRaisedCapsAllowWhatDefaultsRefused() {
        // The caps are the user's dial: raise them and the
        // same spend the defaults refused is allowed.
        val raised = WalletGate.CapState(
            perTxCapCents = 50_000, dailyCapCents = 100_000
        )
        assertEquals(
            WalletGate.CapCheck.Ok,
            WalletGate.checkCaps(raised, 25_000, now)
        )
        val d = WalletGate.decide(
            prepared(usdCents = 25_000), raised,
            autonomousOn = true, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(WalletGate.Decision.Allow, d)
    }

    @Test
    fun capsOffAllowsAnyAmountBalanceCovers() {
        // Caps off entirely: no cap refusal at any size —
        // the approval/autonomy rules still apply (an
        // unapproved, non-autonomous spend still stops at
        // the approval sheet).
        val off = WalletGate.CapState(
            perTxCapCents = WalletGate.NO_LIMIT,
            dailyCapCents = WalletGate.NO_LIMIT
        )
        assertEquals(
            WalletGate.CapCheck.Ok,
            WalletGate.checkCaps(off, 999_999_999, now)
        )
        val needsSheet = WalletGate.decide(
            prepared(usdCents = 999_999_999), off,
            autonomousOn = false, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(WalletGate.Decision.NeedsApproval, needsSheet)
        val autonomous = WalletGate.decide(
            prepared(usdCents = 999_999_999), off,
            autonomousOn = true, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(WalletGate.Decision.Allow, autonomous)
    }

    // ---- the gate ----

    @Test
    fun noSignatureWithoutApprovalWhenAutonomousOff() {
        val d = WalletGate.decide(
            prepared(), WalletGate.CapState(),
            autonomousOn = false, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(WalletGate.Decision.NeedsApproval, d)
    }

    @Test
    fun approvalAllowsWithinCaps() {
        val d = WalletGate.decide(
            prepared(), WalletGate.CapState(),
            autonomousOn = false, userApproved = true,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(WalletGate.Decision.Allow, d)
    }

    @Test
    fun autonomousAllowsWithinCaps() {
        val d = WalletGate.decide(
            prepared(), WalletGate.CapState(),
            autonomousOn = true, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(WalletGate.Decision.Allow, d)
    }

    @Test
    fun overPerTxCapRefusedEvenWithApproval() {
        // An approval tap does NOT override a cap — caps
        // change in Settings or not at all.
        val d = WalletGate.decide(
            prepared(usdCents = 501), WalletGate.CapState(),
            autonomousOn = false, userApproved = true,
            rawAmountConfirmed = true, nowMs = now
        )
        assertTrue(d is WalletGate.Decision.Refused)
    }

    @Test
    fun overDailyCapRefusedInAutonomousMode() {
        var caps = WalletGate.CapState()
        caps = WalletGate.recordSpend(caps, 1_800, now)
        val d = WalletGate.decide(
            prepared(usdCents = 300), caps,
            autonomousOn = true, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertTrue(d is WalletGate.Decision.Refused)
    }

    @Test
    fun unpricedNeverAutonomous() {
        val d = WalletGate.decide(
            prepared(usdCents = null), WalletGate.CapState(),
            autonomousOn = true, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertTrue(d is WalletGate.Decision.Refused)
    }

    @Test
    fun unpricedNeedsRawAmountConfirmation() {
        val needs = WalletGate.decide(
            prepared(usdCents = null), WalletGate.CapState(),
            autonomousOn = false, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(WalletGate.Decision.NeedsApproval, needs)
        val refused = WalletGate.decide(
            prepared(usdCents = null), WalletGate.CapState(),
            autonomousOn = false, userApproved = true,
            rawAmountConfirmed = false, nowMs = now
        )
        assertTrue(refused is WalletGate.Decision.Refused)
        val allowed = WalletGate.decide(
            prepared(usdCents = null), WalletGate.CapState(),
            autonomousOn = false, userApproved = true,
            rawAmountConfirmed = true, nowMs = now
        )
        assertEquals(WalletGate.Decision.Allow, allowed)
    }

    @Test
    fun sweepAlwaysNeedsApprovalButSkipsCaps() {
        // Even a sweep far over the caps is the user's own
        // money coming home: allowed WITH approval…
        val big = prepared(usdCents = 100_000, isSweep = true)
        val withApproval = WalletGate.decide(
            big, WalletGate.CapState(),
            autonomousOn = true, userApproved = true,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(WalletGate.Decision.Allow, withApproval)
        // …and never without it, autonomous switch or not.
        val without = WalletGate.decide(
            big, WalletGate.CapState(),
            autonomousOn = true, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(WalletGate.Decision.NeedsApproval, without)
    }

    // ---- stub signer: the gate is the only road to a signature ----

    /** A signer that signs ONLY on Allow — the shape of
     *  WalletEngine.execute. If the gate ever said otherwise,
     *  no signature exists. */
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
    fun stubSignerProducesNoSignatureWithoutApproval() {
        val signer = StubSigner()
        signer.execute(
            prepared(), WalletGate.CapState(),
            autonomousOn = false, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        signer.execute(
            prepared(usdCents = 999_999), WalletGate.CapState(),
            autonomousOn = true, userApproved = true,
            rawAmountConfirmed = true, nowMs = now
        )
        signer.execute(
            prepared(usdCents = null), WalletGate.CapState(),
            autonomousOn = true, userApproved = false,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(0, signer.signatures)
        signer.execute(
            prepared(), WalletGate.CapState(),
            autonomousOn = false, userApproved = true,
            rawAmountConfirmed = false, nowMs = now
        )
        assertEquals(1, signer.signatures)
    }

    // ---- values ----

    @Test
    fun usdValuation() {
        // 3 USDC at $1.00 → 300 cents.
        assertEquals(
            300L,
            WalletGate.usdCentsOf(BigInteger.valueOf(3_000_000), usdc, BigDecimal("1.00"))
        )
        // 0.5 ETH at $3000 → 150000 cents.
        val eth = WalletGate.token(WalletGate.ETHEREUM, "ETH")!!
        assertEquals(
            150_000L,
            WalletGate.usdCentsOf(
                BigInteger("500000000000000000"), eth, BigDecimal("3000")
            )
        )
        // No price → no value. Never a guess.
        assertEquals(
            null,
            WalletGate.usdCentsOf(BigInteger.valueOf(3_000_000), usdc, null)
        )
    }

    @Test
    fun formatUsd() {
        assertEquals("\$5.00", WalletGate.formatUsd(500))
        assertEquals("\$0.05", WalletGate.formatUsd(5))
        assertEquals("\$20.00", WalletGate.formatUsd(2_000))
    }

    @Test
    fun approvalSheetNamesEverything() {
        val text = WalletGate.approvalSheetText(
            prepared(), "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"
        )
        assertTrue(text.contains("3 USDC"))
        assertTrue(text.contains("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"))
        assertTrue(text.contains("\$3.00"))
        assertTrue(text.contains("coingecko"))
        assertTrue(text.contains("LILLE'S wallet only"))
    }

    // ---- the widened chain set (v0.12.4) ----

    @Test
    fun chainTablePins() {
        // key, chainId, native symbol, legacy?
        val pins = listOf(
            Triple("ethereum", 1L, "ETH") to false,
            Triple("base", 8453L, "ETH") to false,
            Triple("arbitrum", 42161L, "ETH") to false,
            Triple("polygon", 137L, "POL") to false,
            Triple("bsc", 56L, "BNB") to true,
            Triple("avalanche", 43114L, "AVAX") to false,
            Triple("optimism", 10L, "ETH") to false
        )
        assertEquals(pins.size, WalletGate.CHAINS.size)
        for ((pin, legacy) in pins) {
            val (key, chainId, native) = pin
            val chain = WalletGate.chainByKey(key)!!
            assertEquals("chainId for $key", chainId, chain.chainId)
            assertEquals("legacyGas for $key", legacy, chain.legacyGas)
            // Native first, contract-free, 18 decimals, and
            // at least two public RPC routes (Fallback Law).
            val nativeToken = chain.tokens[0]
            assertEquals("native symbol for $key", native, nativeToken.symbol)
            assertEquals("native contract for $key", null, nativeToken.contract)
            assertEquals("native decimals for $key", 18, nativeToken.decimals)
            assertTrue(
                "rpc routes for $key",
                chain.rpcUrls.size >= 2 && chain.rpcUrls.all { it.startsWith("https://") }
            )
        }
        // BNB Smart Chain is the ONLY legacy-gas chain.
        assertEquals(
            listOf("bsc"),
            WalletGate.CHAINS.filter { it.legacyGas }.map { it.key }
        )
    }

    @Test
    fun newChainTokenPins() {
        // Every contract + decimals pair below was verified
        // ON CHAIN on 2026-11-26 (decimals()/symbol() read
        // from the chain's own RPC). These pins are the
        // regression net: a fat-fingered address or a
        // wrong-decimals edit fails the build, not a user.
        fun pin(chainKey: String, symbol: String, contract: String, decimals: Int) {
            val chain = WalletGate.chainByKey(chainKey)!!
            val token = WalletGate.token(chain, symbol)!!
            assertEquals("$chainKey/$symbol contract", contract, token.contract)
            assertEquals("$chainKey/$symbol decimals", decimals, token.decimals)
        }
        pin("arbitrum", "USDC", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", 6)
        pin("arbitrum", "USDT", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", 6)
        pin("arbitrum", "WETH", "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", 18)
        pin("polygon", "USDC", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", 6)
        pin("polygon", "USDT", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", 6)
        pin("polygon", "WETH", "0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619", 18)
        // BSC stables are 18-decimal. This is THE trap of
        // the widening; it stays pinned in red.
        pin("bsc", "USDC", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", 18)
        pin("bsc", "USDT", "0x55d398326f99059fF775485246999027B3197955", 18)
        pin("avalanche", "USDC", "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E", 6)
        pin("avalanche", "USDT", "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", 6)
        pin("avalanche", "WAVAX", "0xB31f66AA3C1e785363F0875A1B74E27b85FD66c7", 18)
        pin("optimism", "USDC", "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", 6)
        pin("optimism", "USDT", "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", 6)
        pin("optimism", "WETH", "0x4200000000000000000000000000000000000006", 18)
    }

    private fun preparedOn(
        chainKey: String,
        symbol: String,
        usdCents: Long? = 300,
        gasPrice: BigInteger? = null
    ) = WalletGate.PreparedTx(
        chainKey = chainKey,
        tokenSymbol = symbol,
        amountRaw = BigInteger.valueOf(3_000_000),
        amountDisplay = "3",
        recipient = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf",
        recipientLabel = "address",
        usdCents = usdCents,
        priceSource = if (usdCents != null) "coingecko" else null,
        gasLimit = BigInteger.valueOf(21_000),
        maxFeeWei = BigInteger("63000000000000"),
        maxFeeUsdCents = 20,
        nonce = BigInteger.ZERO,
        maxPriorityFeePerGas = gasPrice ?: BigInteger.valueOf(1_000_000_000),
        maxFeePerGas = gasPrice ?: BigInteger.valueOf(30_000_000_000),
        gasPrice = gasPrice
    )

    @Test
    fun gateBehavesTheSameOnEveryChain() {
        // The caps/approval law is chain-blind: the same
        // prepared value gets the same verdict on a legacy
        // chain (BSC) and an EIP-1559 chain (Arbitrum).
        for (p in listOf(
            preparedOn("bsc", "BNB", gasPrice = BigInteger.valueOf(3_000_000_000)),
            preparedOn("arbitrum", "ETH"),
            preparedOn("polygon", "POL"),
            preparedOn("avalanche", "AVAX"),
            preparedOn("optimism", "ETH")
        )) {
            val needs = WalletGate.decide(
                p, WalletGate.CapState(),
                autonomousOn = false, userApproved = false,
                rawAmountConfirmed = false, nowMs = now
            )
            assertEquals("${p.chainKey} unapproved", WalletGate.Decision.NeedsApproval, needs)
            val allowed = WalletGate.decide(
                p, WalletGate.CapState(),
                autonomousOn = false, userApproved = true,
                rawAmountConfirmed = false, nowMs = now
            )
            assertEquals("${p.chainKey} approved", WalletGate.Decision.Allow, allowed)
            val overCap = WalletGate.decide(
                p.copy(usdCents = 501), WalletGate.CapState(),
                autonomousOn = true, userApproved = true,
                rawAmountConfirmed = true, nowMs = now
            )
            assertTrue("${p.chainKey} over cap", overCap is WalletGate.Decision.Refused)
        }
    }

    @Test
    fun approvalSheetNamesTheChainsOwnNativeFee() {
        // On BNB Smart Chain the fee is BNB, not ETH — the
        // sheet must never name another chain's asset.
        val bscText = WalletGate.approvalSheetText(
            preparedOn("bsc", "BNB", gasPrice = BigInteger.valueOf(3_000_000_000)),
            "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"
        )
        assertTrue(bscText.contains("on BNB Smart Chain"))
        assertTrue(bscText.contains("up to 0.000063 BNB"))
        val avaxText = WalletGate.approvalSheetText(
            preparedOn("avalanche", "AVAX"),
            "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"
        )
        assertTrue(avaxText.contains("up to 0.000063 AVAX"))
    }

    @Test
    fun bscStableValuationUses18Decimals() {
        // 3 USDT on BSC is 3 × 10^18 base units — valued at
        // the same 300 cents as 3 × 10^6 on Ethereum. If the
        // decimals pin above ever regresses to 6, caps
        // would be checked against a value 10^12 times too
        // large; this is the money-side proof.
        val bscUsdt = WalletGate.token(WalletGate.BSC, "USDT")!!
        assertEquals(
            300L,
            WalletGate.usdCentsOf(
                BigInteger("3000000000000000000"), bscUsdt, BigDecimal("1.00")
            )
        )
    }
}
