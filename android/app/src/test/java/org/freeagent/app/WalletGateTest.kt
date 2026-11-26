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
}
