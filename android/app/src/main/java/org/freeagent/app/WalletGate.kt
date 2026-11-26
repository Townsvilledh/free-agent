package org.freeagent.app

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Lille's agent-wallet gate (v0.12, WALLET.md) — the pure,
 * deterministic half of her companion wallet: chain/token
 * tables, caps, the rolling daily spend log, and THE
 * decision every prepared transaction must pass before the
 * signer will touch the key.
 *
 * The law this file encodes (spec §5/§11, decided 2026-11-25):
 * caps are enforced IN THE SIGNING PATH, never by the model.
 * The brain can propose; only [decide] disposes. Its inputs
 * are a fully-built [PreparedTx], the persisted cap state,
 * and whether the user is physically present with an
 * approval — the model has no argument it can pass to change
 * the outcome.
 *
 * Money is counted in USD cents (Long) for caps, in base
 * units (BigInteger) for chain amounts, and in BigDecimal
 * for prices — never in floating point.
 */
object WalletGate {

    // ---------------- chains & tokens ----------------

    data class Token(
        val symbol: String,
        /** Contract address; null for the chain's native asset.
         *  On Solana tokens this field carries the SPL mint
         *  address (a mint is a token's identifying address
         *  there); the Solana engine reads it as the mint. */
        val contract: String?,
        val decimals: Int,
        /** CoinGecko id for the price chain. */
        val priceId: String
    )

    data class Chain(
        val key: String,
        val label: String,
        val chainId: Long,
        /** Public RPC endpoints, walked in order (Fallback
         *  Law): the first that answers serves, and results
         *  name it. All keyless/public. */
        val rpcUrls: List<String>,
        val explorerTxBase: String,
        /** The chain's tokens, NATIVE FIRST: tokens[0] is
         *  the native asset (contract == null) that network
         *  fees are paid in. The engine relies on this order. */
        val tokens: List<Token>,
        /** True when the chain prices gas the legacy way
         *  (a single eth_gasPrice, EIP-155 signed type-0
         *  transactions) instead of EIP-1559 fee markets.
         *  BNB Smart Chain is the legacy chain in the set;
         *  every other chain here runs EIP-1559. */
        val legacyGas: Boolean = false
    )

    val ETHEREUM = Chain(
        key = "ethereum",
        label = "Ethereum",
        chainId = 1L,
        rpcUrls = listOf(
            "https://ethereum-rpc.publicnode.com",
            "https://eth.llamarpc.com",
            "https://eth.drpc.org"
        ),
        explorerTxBase = "https://etherscan.io/tx/",
        tokens = listOf(
            Token("ETH", null, 18, "ethereum"),
            Token("USDC", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6, "usd-coin"),
            Token("USDT", "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6, "tether"),
            Token("WETH", "0xC02aaA39b223FE8D0F0a5e259C7F724aBf0C44212998", 18, "weth")
        )
    )

    val BASE = Chain(
        key = "base",
        label = "Base",
        chainId = 8453L,
        rpcUrls = listOf(
            "https://mainnet.base.org",
            "https://base-rpc.publicnode.com",
            "https://base.llamarpc.com"
        ),
        explorerTxBase = "https://basescan.org/tx/",
        tokens = listOf(
            Token("ETH", null, 18, "ethereum"),
            Token("USDC", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", 6, "usd-coin"),
            Token("USDT", "0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2", 6, "tether"),
            Token("WETH", "0x4200000000000000000000000000000000000006", 18, "weth")
        )
    )

    // Token contracts below were verified ON CHAIN on
    // 2026-11-26 (decimals() and symbol() read from each
    // chain's own public RPC) — never copied from memory.
    // Note the traps the verification caught: the stables
    // on BNB Smart Chain are 18-decimal (not 6), and
    // Polygon's native asset is POL (the chain's gas token
    // since the 2024 upgrade; MATIC is the retired name).

    val ARBITRUM = Chain(
        key = "arbitrum",
        label = "Arbitrum",
        chainId = 42161L,
        rpcUrls = listOf(
            "https://arb1.arbitrum.io/rpc",
            "https://arbitrum-rpc.publicnode.com",
            "https://arbitrum.drpc.org"
        ),
        explorerTxBase = "https://arbiscan.io/tx/",
        tokens = listOf(
            Token("ETH", null, 18, "ethereum"),
            Token("USDC", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", 6, "usd-coin"),
            Token("USDT", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", 6, "tether"),
            Token("WETH", "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", 18, "weth")
        )
    )

    val POLYGON = Chain(
        key = "polygon",
        label = "Polygon",
        chainId = 137L,
        rpcUrls = listOf(
            "https://polygon-bor-rpc.publicnode.com",
            "https://polygon.drpc.org",
            "https://polygon.llamarpc.com"
        ),
        explorerTxBase = "https://polygonscan.com/tx/",
        tokens = listOf(
            Token("POL", null, 18, "polygon-ecosystem-token"),
            Token("USDC", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", 6, "usd-coin"),
            Token("USDT", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", 6, "tether"),
            Token("WETH", "0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619", 18, "weth")
        )
    )

    val BSC = Chain(
        key = "bsc",
        label = "BNB Smart Chain",
        chainId = 56L,
        rpcUrls = listOf(
            "https://bsc-dataseed.bnbchain.org",
            "https://bsc-rpc.publicnode.com",
            "https://bsc.drpc.org"
        ),
        explorerTxBase = "https://bscscan.com/tx/",
        tokens = listOf(
            Token("BNB", null, 18, "binancecoin"),
            // 18 decimals on BSC — verified on chain; the
            // 6-decimal assumption from other chains would
            // misprice every BSC stable send by 10^12.
            Token("USDC", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", 18, "usd-coin"),
            Token("USDT", "0x55d398326f99059fF775485246999027B3197955", 18, "tether")
        ),
        // BNB Chain has no EIP-1559 fee market: gas is one
        // eth_gasPrice and transactions sign the legacy way.
        legacyGas = true
    )

    val AVALANCHE = Chain(
        key = "avalanche",
        label = "Avalanche",
        chainId = 43114L,
        rpcUrls = listOf(
            "https://api.avax.network/ext/bc/C/rpc",
            "https://avalanche-c-chain-rpc.publicnode.com",
            "https://avalanche.drpc.org"
        ),
        explorerTxBase = "https://snowtrace.io/tx/",
        tokens = listOf(
            Token("AVAX", null, 18, "avalanche-2"),
            Token("USDC", "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E", 6, "usd-coin"),
            Token("USDT", "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", 6, "tether"),
            Token("WAVAX", "0xB31f66AA3C1e785363F0875A1B74E27b85FD66c7", 18, "wrapped-avax")
        )
    )

    val OPTIMISM = Chain(
        key = "optimism",
        label = "Optimism",
        chainId = 10L,
        rpcUrls = listOf(
            "https://mainnet.optimism.io",
            "https://optimism-rpc.publicnode.com",
            "https://optimism.drpc.org"
        ),
        explorerTxBase = "https://optimistic.etherscan.io/tx/",
        tokens = listOf(
            Token("ETH", null, 18, "ethereum"),
            Token("USDC", "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85", 6, "usd-coin"),
            Token("USDT", "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", 6, "tether"),
            Token("WETH", "0x4200000000000000000000000000000000000006", 18, "weth")
        )
    )

    val CHAINS: List<Chain> = listOf(
        ETHEREUM, BASE, ARBITRUM, POLYGON, BSC, AVALANCHE, OPTIMISM
    )

    fun chainByKey(key: String): Chain? = CHAINS.firstOrNull { it.key == key }

    fun token(chain: Chain, symbol: String): Token? =
        chain.tokens.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

    // ---------------- wallet families ----------------
    //
    // The companion wallet is ONE wallet with three keysets:
    // the EVM keyset above, a Bitcoin keyset, and a Solana
    // keyset (AgentWallet holds all three under the same
    // vault discipline — Ethuram's ruling: her wallet must
    // be Fluid-shaped). The gate — caps, the daily total,
    // the approval rule — is family-blind on purpose: a
    // prepared spend from any family passes the same
    // decide() below, so no family can grow a weaker gate.

    const val FAMILY_EVM = "evm"
    const val FAMILY_BITCOIN = "bitcoin"
    const val FAMILY_SOLANA = "solana"

    /** Bitcoin's one asset: BTC itself, 8 decimals. */
    val BITCOIN_TOKENS: List<Token> = listOf(
        Token("BTC", null, 8, "bitcoin")
    )

    /** Solana's assets: native SOL (9 decimals) and the two
     *  dollar stables as SPL tokens (6 decimals each),
     *  identified by mint (carried in the contract field). */
    val SOLANA_TOKENS: List<Token> = listOf(
        Token("SOL", null, 9, "solana"),
        Token("USDC", Sol.USDC_MINT, 6, "usd-coin"),
        Token("USDT", Sol.USDT_MINT, 6, "tether")
    )

    /** A family's token by symbol (EVM resolves per chain
     *  via [token]; this covers the two non-EVM families). */
    fun familyToken(family: String, symbol: String): Token? =
        when (family) {
            FAMILY_BITCOIN -> BITCOIN_TOKENS
            FAMILY_SOLANA -> SOLANA_TOKENS
            else -> null
        }?.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

    /** The display name of a network: an EVM chain's label,
     *  or the family networks'. Prepared transactions carry
     *  "bitcoin"/"solana" as their chain key. */
    fun networkLabel(chainKey: String): String =
        chainByKey(chainKey)?.label ?: when (chainKey) {
            FAMILY_BITCOIN -> "Bitcoin"
            FAMILY_SOLANA -> "Solana"
            else -> chainKey
        }

    val SUPPORTED_SYMBOLS: Set<String> =
        (CHAINS.flatMap { c -> c.tokens.map { it.symbol } } +
            BITCOIN_TOKENS.map { it.symbol } +
            SOLANA_TOKENS.map { it.symbol }).toSet()

    // ---------------- caps ----------------

    const val DEFAULT_PER_TX_CAP_CENTS: Long = 500     // $5
    const val DEFAULT_DAILY_CAP_CENTS: Long = 2_000    // $20
    const val DAY_MS: Long = 86_400_000L

    /** "No limit" — the user's own choice in Wallet settings
     *  (Ethuram's correction, 2026-11-26: caps are the user's
     *  dial, not a cage). The $5/$20 values above are only
     *  first-run defaults; the user can raise either cap to
     *  any amount or switch it off entirely, and whatever
     *  they set is still enforced HERE, deterministically —
     *  that enforcement is what makes a chosen limit real.
     *  With a cap off, the wallet's balance is the only
     *  limit left (plus the approval/autonomy rules, which
     *  caps never governed anyway). */
    const val NO_LIMIT: Long = Long.MAX_VALUE

    fun isUnlimited(cents: Long): Boolean = cents >= NO_LIMIT

    /** One recorded spend, for the rolling 24-hour window. */
    data class Spend(val atMs: Long, val usdCents: Long)

    data class CapState(
        val perTxCapCents: Long = DEFAULT_PER_TX_CAP_CENTS,
        val dailyCapCents: Long = DEFAULT_DAILY_CAP_CENTS,
        val spends: List<Spend> = emptyList()
    )

    /** USD cents spent inside the rolling 24 h window
     *  ending at [nowMs]. Entries exactly 24 h old have aged
     *  out (strictly younger counts). */
    fun spentInWindow(state: CapState, nowMs: Long): Long =
        state.spends.filter { nowMs - it.atMs < DAY_MS && it.atMs <= nowMs }
            .sumOf { it.usdCents }

    /** The state after recording a spend — the caller
     *  persists the result. Aged-out entries are pruned so
     *  the stored log stays small. */
    fun recordSpend(state: CapState, usdCents: Long, nowMs: Long): CapState =
        state.copy(
            spends = state.spends.filter { nowMs - it.atMs < DAY_MS } +
                Spend(nowMs, usdCents)
        )

    sealed class CapCheck {
        object Ok : CapCheck()
        data class OverPerTx(val capCents: Long) : CapCheck()
        data class OverDaily(val capCents: Long, val alreadySpentCents: Long) : CapCheck()
    }

    fun checkCaps(state: CapState, amountCents: Long, nowMs: Long): CapCheck {
        if (amountCents > state.perTxCapCents) return CapCheck.OverPerTx(state.perTxCapCents)
        val spent = spentInWindow(state, nowMs)
        if (spent + amountCents > state.dailyCapCents) {
            return CapCheck.OverDaily(state.dailyCapCents, spent)
        }
        return CapCheck.Ok
    }

    // ---------------- prepared transactions ----------------

    /**
     * A fully-built transaction, ready for the gate. Built
     * ONLY by the deterministic prepare path (WalletEngine):
     * live balances/fees/nonce already read, recipient
     * already resolved. The brain never constructs one — it
     * can only ask for one via the strict prompt parser.
     *
     * [usdCents] is null when no price route could price the
     * amount: the value is then UNKNOWN, which the gate
     * treats as a hard stop for autonomous mode and as a
     * "confirm the raw amount yourself" case for
     * prepare-and-ask.
     */
    data class PreparedTx(
        val chainKey: String,
        val tokenSymbol: String,
        /** Base units to move (token units, or wei for native). */
        val amountRaw: BigInteger,
        /** Human amount exactly as prepared, e.g. "2.5". */
        val amountDisplay: String,
        val recipient: String,          // checksummed address
        /** How the recipient was named: "address" or the
         *  Fluid name the user typed (for the sheet). */
        val recipientLabel: String,
        val usdCents: Long?,
        /** The price route that priced it ("coingecko", …),
         *  or null when unpriced. */
        val priceSource: String?,
        val gasLimit: BigInteger,
        /** Worst-case network fee, in wei. */
        val maxFeeWei: BigInteger,
        val maxFeeUsdCents: Long?,
        val nonce: BigInteger,
        val maxPriorityFeePerGas: BigInteger,
        val maxFeePerGas: BigInteger,
        /** Legacy chains only (BNB Smart Chain): the single
         *  eth_gasPrice the transaction signs with, and the
         *  signal to the signer to build a legacy (type-0,
         *  EIP-155) transaction. Null on EIP-1559 chains,
         *  where the two fee fields above rule. On a legacy
         *  chain maxFeePerGas mirrors this value so the
         *  worst-case fee math (gasLimit × price) is the
         *  same shape on every chain. */
        val gasPrice: BigInteger? = null,
        /** True for a sweep-back to the user's own Fluid
         *  address — still prepare-and-ask, always. */
        val isSweep: Boolean = false,
        /** The wallet family this spend belongs to
         *  (FAMILY_EVM for everything the EVM engine builds).
         *  For the other families the EVM-only fields above
         *  (gasLimit, nonce, fee-per-gas) are zero and unused;
         *  [maxFeeWei] carries the family's worst-case fee in
         *  its native base unit (sats / lamports) so the
         *  sheet and the value maths keep one shape. */
        val family: String = FAMILY_EVM,
        /** Bitcoin only: the UTXO plan the signer rebuilds
         *  exactly (inputs, change, fee). */
        val btcPlan: Btc.SpendPlan? = null,
        /** Solana only: the spend plan the signer rebuilds
         *  exactly (kind, ATAs, blockhash, fee, rent). */
        val solPlan: Sol.SpendPlan? = null
    )

    // ---------------- the gate ----------------

    sealed class Decision {
        /** The signer may sign and broadcast. */
        object Allow : Decision()

        /** No signature without the user's approval tap on
         *  the approval sheet for THIS prepared transaction. */
        object NeedsApproval : Decision()

        /** The signer refuses outright; [reason] is the
         *  plain-language message shown to the user. An
         *  approval tap does NOT override a refusal — caps
         *  are changed in Settings, by the user, or not at
         *  all. */
        data class Refused(val reason: String) : Decision()
    }

    /**
     * THE signing-path gate. Called by the signer with:
     * - [prepared] the built transaction,
     * - [caps] the persisted cap state,
     * - [autonomousOn] the user's settings switch (default
     *   OFF; settable only in Settings, never from a prompt),
     * - [userApproved] true only when the user tapped Approve
     *   on the approval sheet for this exact transaction,
     * - [rawAmountConfirmed] the sheet's extra explicit
     *   confirmation, required when the value is unpriced.
     */
    fun decide(
        prepared: PreparedTx,
        caps: CapState,
        autonomousOn: Boolean,
        userApproved: Boolean,
        rawAmountConfirmed: Boolean,
        nowMs: Long
    ): Decision {
        val usd = prepared.usdCents
        if (usd == null) {
            // Unpriced: autonomous signing is impossible (the
            // caps are denominated in USD), and an approval
            // only counts with the raw amount explicitly
            // confirmed on the sheet.
            return when {
                autonomousOn && !userApproved ->
                    Decision.Refused(
                        "Lille couldn't price this amount in dollars, so she " +
                            "can't check it against your caps — autonomous " +
                            "spending is off for unpriced amounts. Nothing was sent."
                    )
                !userApproved -> Decision.NeedsApproval
                !rawAmountConfirmed ->
                    Decision.Refused(
                        "This amount couldn't be priced in dollars. Confirm " +
                            "the raw amount itself on the approval sheet, or " +
                            "cancel — nothing was sent."
                    )
                else -> Decision.Allow
            }
        }
        if (prepared.isSweep) {
            // Sweep-back is ALWAYS prepare-and-ask, even with
            // the autonomous switch on: emptying her wallet
            // is the user's act, never hers alone. It is also
            // exempt from the caps: the caps bind what she
            // SPENDS, and a sweep returns the user's own money
            // to the user's own verified address — capping it
            // could trap funds in her wallet, which the design
            // forbids ("empty her wallet back to me" is one
            // tap, spec §5).
            return if (userApproved) Decision.Allow else Decision.NeedsApproval
        }
        when (val check = checkCaps(caps, usd, nowMs)) {
            is CapCheck.OverPerTx -> return Decision.Refused(
                "That is ${formatUsd(usd)}, over your per-transaction cap of " +
                    "${formatUsd(check.capCents)} for Lille's wallet. The signer " +
                    "refused it — raise the cap in Wallet settings if you mean " +
                    "it. Nothing was sent."
            )
            is CapCheck.OverDaily -> return Decision.Refused(
                "That would take Lille's spending to " +
                    "${formatUsd(check.alreadySpentCents + usd)} in 24 hours, " +
                    "over your daily cap of ${formatUsd(check.capCents)}. The " +
                    "signer refused it. Nothing was sent."
            )
            CapCheck.Ok -> Unit
        }
        if (autonomousOn || userApproved) return Decision.Allow
        return Decision.NeedsApproval
    }

    // ---------------- values ----------------

    /** USD cents for [amountRaw] of [token] at [priceUsd]
     *  (dollars per whole token). Null price → null cents. */
    fun usdCentsOf(amountRaw: BigInteger, token: Token, priceUsd: BigDecimal?): Long? {
        if (priceUsd == null) return null
        val amount = BigDecimal(amountRaw)
            .divide(BigDecimal.TEN.pow(token.decimals), 18, RoundingMode.HALF_UP)
        return amount.multiply(priceUsd)
            .multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }

    fun formatUsd(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    /** The approval sheet, in plain language — one text the
     *  chat dialog and the Wallet screen both show, so the
     *  user always approves the same words the signer saw. */
    fun approvalSheetText(p: PreparedTx, fromAddress: String): String {
        val sb = StringBuilder()
        if (p.isSweep) sb.append("Return funds to your Fluid wallet\n\n")
        sb.append("Lille will send ${p.amountDisplay} ${p.tokenSymbol}")
        sb.append("\nfrom her own wallet ($fromAddress)")
        sb.append("\nto ${p.recipient}")
        if (p.recipientLabel != "address") {
            sb.append(" (@${p.recipientLabel}, from the Fluid names registry)")
        }
        sb.append("\non ${networkLabel(p.chainKey)}.")
        if (p.usdCents != null) {
            sb.append("\n\nValue: about ${formatUsd(p.usdCents)}")
            if (p.priceSource != null) sb.append(" (priced by ${p.priceSource})")
            sb.append(".")
        } else {
            sb.append(
                "\n\nValue: Lille couldn't price this in dollars right now, " +
                    "so your caps can't be checked on it. Approving means you " +
                    "confirm the raw amount above yourself."
            )
        }
        val feeUsd = p.maxFeeUsdCents?.let { " (about ${formatUsd(it)})" } ?: ""
        when (p.family) {
            FAMILY_BITCOIN -> sb.append(
                "\nNetwork fee: ${Evm.formatUnits(p.maxFeeWei, 8)} BTC" +
                    feeUsd + ", paid from her wallet."
            )
            FAMILY_SOLANA -> {
                sb.append(
                    "\nNetwork fee: ${Evm.formatUnits(p.maxFeeWei, 9)} SOL" +
                        feeUsd + ", paid from her wallet."
                )
                val rent = p.solPlan?.rentLamports ?: 0L
                if (rent > 0) {
                    sb.append(
                        "\nPlus one-time rent of " +
                            "${Evm.formatUnits(BigInteger.valueOf(rent), 9)} SOL " +
                            "to open the recipient's ${p.tokenSymbol} account — " +
                            "the deposit the network holds for that account."
                    )
                }
            }
            else -> {
                val nativeSymbol =
                    chainByKey(p.chainKey)?.tokens?.firstOrNull()?.symbol ?: "ETH"
                sb.append(
                    "\nNetwork fee: up to ${Evm.formatUnits(p.maxFeeWei, 18)} " +
                        "$nativeSymbol" + feeUsd + ", paid from her wallet."
                )
            }
        }
        sb.append("\n\nThis spends from LILLE'S wallet only — your Fluid wallet is never touched.")
        return sb.toString()
    }
}
