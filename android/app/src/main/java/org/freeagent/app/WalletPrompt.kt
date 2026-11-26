package org.freeagent.app

/**
 * The deterministic wallet-prompt parser (v0.12, WALLET.md).
 *
 * Money moves ONLY through this parser: a chat message
 * becomes a wallet send if and only if it matches one of the
 * exact supported phrasings below. The free brain never
 * builds transactions and never interprets amounts — loose
 * phrasing is answered in chat as a question, never as a
 * guess. Anything that looks like a wallet send but doesn't
 * fully parse is AMBIGUOUS: Lille asks, she does not assume.
 *
 * Supported phrasings (case-insensitive; the whole message
 * must match, an optional trailing "." or "please" allowed):
 *
 *   send <amount> <ASSET> to <target> [on <chain>] [from fluid]
 *   pay  <amount> <ASSET> to <target> [on <chain>] [from fluid]
 *   send $<amount> of <ASSET> to <target> [on <chain>] [from fluid]
 *   pay  $<amount> of <ASSET> to <target> [on <chain>] [from fluid]
 *   send <amount> dollars of <ASSET> to <target> [on <chain>]
 *
 *   The optional trailing "from fluid" / "from my fluid
 *   wallet" clause (v0.13.1) chooses the SOURCE: the user's
 *   main Fluid wallet instead of Lille's own. Without it a
 *   send is hers (the default, unchanged since v0.12). A
 *   Fluid-wallet send is a different flow entirely — she
 *   prepares it and Fluid's own sheet approves it (FluidTx);
 *   her caps and her signer never touch main funds. The
 *   Fluid session speaks EVM only, so a "from fluid" clause
 *   on a send that resolves to Bitcoin or Solana is a
 *   question, never a silent fall-back to her own funds.
 *
 *   <amount>  a plain decimal: 2, 2.5, 0.05 (no commas, no
 *             exponent, no words)
 *   <ASSET>   any symbol her wallet holds (ETH, USDC, USDT,
 *             WETH, BNB, POL, AVAX, WAVAX, BTC, SOL) — checked
 *             against the chosen network at the end of the
 *             parse
 *   <target>  a 0x… address (40 hex chars), a Bitcoin
 *             address (bc1…, 1…, 3…), a Solana address
 *             (base58), @fluidname, or a bare fluidname
 *             (letters, digits, _ . -)
 *   <chain>   ethereum | mainnet | base | arbitrum |
 *             polygon | bsc | bnb | avalanche | avax |
 *             optimism | bitcoin | btc | solana
 *             (default: ethereum — except that a BTC/SOL
 *             asset or a Bitcoin/Solana-shaped target names
 *             its own family, below)
 *
 * FAMILIES: her wallet is EVM + Bitcoin + Solana. The
 * family is decided, in order, by: the "on <chain>" words
 * when given; the asset (BTC is Bitcoin's, SOL is
 * Solana's); the target's shape (a bc1… address is a
 * Bitcoin send, a base58 one a Solana send — so "send 5
 * USDC to <solana address>" is a Solana send with no chain
 * words at all); otherwise EVM. Fluid names resolve to EVM
 * addresses ONLY (the registry holds nothing else), so a
 * name target on Bitcoin/Solana is a question that says
 * exactly that — never a guess at some other address.
 *
 * The "$"/"dollars" forms are USD-denominated: the prepare
 * path converts at the live price and shows the token amount
 * it computed on the approval sheet — the user always
 * approves concrete numbers, never a formula.
 */
object WalletPrompt {

    /** Where a send's funds come from: her own companion
     *  wallet (the default), or the user's main Fluid wallet
     *  (requested over the session, approved in Fluid). */
    enum class Source { COMPANION, FLUID }

    data class SendIntent(
        /** Token amount as typed (token-denominated forms). */
        val amountText: String?,
        /** USD amount as typed ($ / dollars forms). */
        val usdAmountText: String?,
        val asset: String,              // uppercase symbol
        val targetRaw: String,          // as typed
        val targetIsName: Boolean,
        val chainKey: String,           // WalletGate chain key
        val source: Source = Source.COMPANION,
        /** The wallet family: WalletGate.FAMILY_EVM (the
         *  default), FAMILY_BITCOIN, or FAMILY_SOLANA. For
         *  the non-EVM families chainKey carries the family
         *  name ("bitcoin" / "solana"). */
        val family: String = WalletGate.FAMILY_EVM
    )

    sealed class Result {
        data class Parsed(val intent: SendIntent) : Result()
        /** Looks like a wallet send but doesn't fully parse —
         *  [question] is what Lille asks back. */
        data class Ambiguous(val question: String) : Result()
        /** Not a wallet prompt at all — ordinary chat. */
        object NotWallet : Result()
    }

    private val ASSETS = WalletGate.SUPPORTED_SYMBOLS

    private val TOKEN_FORM = Regex(
        """^(?:send|pay)\s+(\d+(?:\.\d+)?)\s+([a-zA-Z]+)\s+to\s+(\S+)(?:\s+on\s+([a-zA-Z]+))?(\s+from\s+(?:my\s+)?fluid(?:\s+wallet)?)?$"""
    )
    private val USD_FORM = Regex(
        """^(?:send|pay)\s+(?:\$(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s+dollars)\s+of\s+([a-zA-Z]+)\s+to\s+(\S+)(?:\s+on\s+([a-zA-Z]+))?(\s+from\s+(?:my\s+)?fluid(?:\s+wallet)?)?$"""
    )
    private val NAME_RE = Regex("""^[A-Za-z0-9_.\-]{2,32}$""")

    fun parse(rawMessage: String): Result {
        var text = rawMessage.trim()
        if (text.endsWith(".")) text = text.dropLast(1).trim()
        if (text.lowercase().endsWith(" please")) {
            text = text.dropLast(" please".length).trim()
        }
        val low = text.lowercase()
        // Send-shaped means: it opens with send/pay AND it
        // smells of money (a supported asset, a number, a $).
        // "send me the report" is ordinary chat for the
        // brain; "send USDC to @x" is a wallet prompt that
        // happens to be missing its amount.
        val moneySmell = ASSETS.any { sym ->
            Regex("""\b${sym}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        } || Regex("""\d""").containsMatchIn(text) || text.contains("$") ||
            low.contains("dollar")
        val looksLikeSend = ((low.startsWith("send ") || low.startsWith("pay ") ||
            low == "send" || low == "pay") && moneySmell)
        if (!looksLikeSend) return Result.NotWallet

        TOKEN_FORM.matchEntire(text)?.let { m ->
            val (amount, assetRaw, target, chainRaw) = m.destructured
            val source = if (m.groupValues[5].isNotEmpty()) {
                Source.FLUID
            } else {
                Source.COMPANION
            }
            return finish(amount, null, assetRaw, target, chainRaw, source)
        }
        USD_FORM.matchEntire(text)?.let { m ->
            val usd = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val assetRaw = m.groupValues[3]
            val target = m.groupValues[4]
            val chainRaw = m.groupValues[5]
            val source = if (m.groupValues[6].isNotEmpty()) {
                Source.FLUID
            } else {
                Source.COMPANION
            }
            return finish(null, usd, assetRaw, target, chainRaw, source)
        }

        // It began like a send but matched neither form: ask,
        // with the specific gap named where we can see it.
        val assetMentioned = ASSETS.any { sym ->
            Regex("""\b${sym}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
        val amountMentioned = Regex("""\d""").containsMatchIn(text)
        val question = when {
            !amountMentioned ->
                "I can send from my own wallet, but I couldn't see an amount. " +
                    "Try: \"send 2 USDC to @name\" — amount, asset, then who it's for."
            !assetMentioned ->
                "I can send ${assetList()} from my own wallet — which " +
                    "asset did you mean? Try: \"send 2 USDC to @name\"."
            else ->
                "I want to be exact with money, and that phrasing isn't one I " +
                    "can parse safely. Try: \"send 2 USDC to @name\", " +
                    "\"send \$3 of ETH to 0x…\", optionally ending \"on base\"."
        }
        return Result.Ambiguous(question)
    }

    /** Every symbol she holds anywhere, for messages. */
    private fun assetList(): String = ASSETS.sorted().joinToString(", ")

    /** Every chain name she answers to, for messages. */
    private fun chainList(): String =
        WalletGate.CHAINS.joinToString(", ") { it.label } + ", Bitcoin, Solana"

    /** What a target string IS, by shape — decided before
     *  any family logic, because the family decision reads
     *  it. Order matters: an EVM address is unambiguous
     *  (0x…); a Bitcoin address carries a checksum (bech32
     *  or Base58Check), so it is tested before the looser
     *  Solana shape (any base58 string decoding to exactly
     *  32 bytes — a name can be at most 32 characters, so a
     *  33+ character base58 target is never a name). */
    private enum class TargetKind {
        EVM_ADDRESS, BTC_ADDRESS, SOL_ADDRESS, NAME, GARBAGE
    }

    private fun classifyTarget(targetRaw: String): TargetKind {
        if (Evm.isAddress(targetRaw)) return TargetKind.EVM_ADDRESS
        if (Btc.decodeAddress(targetRaw) != null) return TargetKind.BTC_ADDRESS
        if (Sol.isAddress(targetRaw)) return TargetKind.SOL_ADDRESS
        val isName = (targetRaw.startsWith("@") || NAME_RE.matches(targetRaw)) &&
            targetRaw.any { it.isLetter() }
        if (isName) return TargetKind.NAME
        return TargetKind.GARBAGE
    }

    private fun finish(
        amountText: String?,
        usdAmountText: String?,
        assetRaw: String,
        targetRaw: String,
        chainRaw: String,
        source: Source = Source.COMPANION
    ): Result {
        val asset = assetRaw.uppercase()
        if (asset !in ASSETS) {
            return Result.Ambiguous(
                "I can only send ${assetList()} from my own wallet — " +
                    "\"$assetRaw\" isn't one I hold. Nothing was prepared."
            )
        }
        val targetKind = classifyTarget(targetRaw)
        val chainWord = chainRaw.lowercase()
        // The family, in order: the explicit network words;
        // the asset (BTC/SOL name their own family); the
        // target's shape; otherwise EVM.
        val explicitFamily = when (chainWord) {
            "" -> null
            "bitcoin", "btc" -> WalletGate.FAMILY_BITCOIN
            "solana" -> WalletGate.FAMILY_SOLANA
            "ethereum", "mainnet", "base", "arbitrum", "polygon",
            "bsc", "bnb", "avalanche", "avax", "optimism" ->
                WalletGate.FAMILY_EVM
            else -> return Result.Ambiguous(
                "I can send on ${chainList()} — \"$chainRaw\" isn't a " +
                    "network my wallet is on. Nothing was prepared."
            )
        }
        val family = explicitFamily ?: when {
            asset == "BTC" || targetKind == TargetKind.BTC_ADDRESS ->
                WalletGate.FAMILY_BITCOIN
            asset == "SOL" || targetKind == TargetKind.SOL_ADDRESS ->
                WalletGate.FAMILY_SOLANA
            else -> WalletGate.FAMILY_EVM
        }
        // A main-wallet (Fluid) send rides the WalletConnect
        // session, which speaks EVM only. "From fluid" on a
        // Bitcoin/Solana send is a question — never a silent
        // fall-back to spending her own companion funds.
        if (source == Source.FLUID && family != WalletGate.FAMILY_EVM) {
            return Result.Ambiguous(
                "A send from your Fluid wallet goes over the Fluid " +
                    "connection, which handles EVM networks only — I " +
                    "can't send on $family from it. Without \"from " +
                    "fluid\" I can send it from my own wallet. " +
                    "Nothing was prepared."
            )
        }
        return when (family) {
            WalletGate.FAMILY_BITCOIN -> finishBitcoin(
                amountText, usdAmountText, asset, targetRaw, targetKind
            )
            WalletGate.FAMILY_SOLANA -> finishSolana(
                amountText, usdAmountText, asset, targetRaw, targetKind
            )
            else -> finishEvm(
                amountText, usdAmountText, asset, targetRaw, chainWord,
                targetKind, source
            )
        }
    }

    /** The shared amount checks: a parsed decimal that is
     *  zero (or unparseable at the asset's decimals) is a
     *  question, not a transaction. Null = amounts are sane. */
    private fun amountProblem(
        amountText: String?,
        usdAmountText: String?,
        asset: String,
        token: WalletGate.Token
    ): Result.Ambiguous? {
        if (amountText != null) {
            val raw = Evm.parseUnits(amountText, token.decimals)
            if (raw == null || raw.signum() == 0) {
                return Result.Ambiguous(
                    "\"$amountText $asset\" isn't an amount I can send — check " +
                        "the number (at most ${token.decimals} decimal places, " +
                        "and more than zero). Nothing was prepared."
                )
            }
        }
        if (usdAmountText != null) {
            val usd = usdAmountText.toBigDecimalOrNull()
            if (usd == null || usd.signum() == 0) {
                return Result.Ambiguous(
                    "\"\$$usdAmountText\" isn't a dollar amount I can use. " +
                        "Nothing was prepared."
                )
            }
        }
        return null
    }

    private fun finishBitcoin(
        amountText: String?,
        usdAmountText: String?,
        asset: String,
        targetRaw: String,
        targetKind: TargetKind
    ): Result {
        val token = WalletGate.familyToken(WalletGate.FAMILY_BITCOIN, asset)
            ?: return Result.Ambiguous(
                "On Bitcoin I hold only BTC — $asset lives on a different " +
                    "network in my wallet. Nothing was prepared."
            )
        when (targetKind) {
            TargetKind.BTC_ADDRESS -> Unit
            TargetKind.NAME -> return Result.Ambiguous(
                "Fluid names resolve to EVM addresses only, so I can't " +
                    "send Bitcoin to a name — paste the Bitcoin address " +
                    "itself (bc1…, 1…, or 3…). Nothing was prepared."
            )
            TargetKind.EVM_ADDRESS -> return Result.Ambiguous(
                "That looks like an Ethereum-style address, not a Bitcoin " +
                    "one. Nothing was prepared."
            )
            TargetKind.SOL_ADDRESS -> return Result.Ambiguous(
                "That looks like a Solana address, not a Bitcoin one. " +
                    "Nothing was prepared."
            )
            TargetKind.GARBAGE -> return Result.Ambiguous(
                "I couldn't read \"$targetRaw\" as a Bitcoin address. " +
                    "Bitcoin addresses look like bc1…, 1…, or 3…. " +
                    "Nothing was prepared."
            )
        }
        amountProblem(amountText, usdAmountText, asset, token)?.let { return it }
        return Result.Parsed(
            SendIntent(
                amountText = amountText,
                usdAmountText = usdAmountText,
                asset = asset,
                targetRaw = targetRaw,
                targetIsName = false,
                chainKey = WalletGate.FAMILY_BITCOIN,
                family = WalletGate.FAMILY_BITCOIN
            )
        )
    }

    private fun finishSolana(
        amountText: String?,
        usdAmountText: String?,
        asset: String,
        targetRaw: String,
        targetKind: TargetKind
    ): Result {
        val token = WalletGate.familyToken(WalletGate.FAMILY_SOLANA, asset)
            ?: return Result.Ambiguous(
                "I don't hold $asset on Solana — there I hold " +
                    WalletGate.SOLANA_TOKENS.joinToString(", ") { it.symbol } +
                    ". Nothing was prepared."
            )
        when (targetKind) {
            TargetKind.SOL_ADDRESS -> Unit
            TargetKind.NAME -> return Result.Ambiguous(
                "Fluid names resolve to EVM addresses only, so I can't " +
                    "send on Solana to a name — paste the Solana address " +
                    "itself. Nothing was prepared."
            )
            TargetKind.BTC_ADDRESS -> return Result.Ambiguous(
                "That looks like a Bitcoin address, not a Solana one. " +
                    "Nothing was prepared."
            )
            TargetKind.EVM_ADDRESS -> return Result.Ambiguous(
                "That looks like an Ethereum-style address, not a Solana " +
                    "one. Nothing was prepared."
            )
            TargetKind.GARBAGE -> return Result.Ambiguous(
                "I couldn't read \"$targetRaw\" as a Solana address. " +
                    "Nothing was prepared."
            )
        }
        amountProblem(amountText, usdAmountText, asset, token)?.let { return it }
        return Result.Parsed(
            SendIntent(
                amountText = amountText,
                usdAmountText = usdAmountText,
                asset = asset,
                targetRaw = targetRaw,
                targetIsName = false,
                chainKey = WalletGate.FAMILY_SOLANA,
                family = WalletGate.FAMILY_SOLANA
            )
        )
    }

    private fun finishEvm(
        amountText: String?,
        usdAmountText: String?,
        asset: String,
        targetRaw: String,
        chainWord: String,
        targetKind: TargetKind,
        source: Source = Source.COMPANION
    ): Result {
        val chainKey = when (chainWord) {
            "", "ethereum", "mainnet" -> WalletGate.ETHEREUM.key
            "base" -> WalletGate.BASE.key
            "arbitrum" -> WalletGate.ARBITRUM.key
            "polygon" -> WalletGate.POLYGON.key
            "bsc", "bnb" -> WalletGate.BSC.key
            "avalanche", "avax" -> WalletGate.AVALANCHE.key
            "optimism" -> WalletGate.OPTIMISM.key
            else -> return Result.Ambiguous(
                "I can send on ${chainList()} — \"$chainWord\" isn't a chain " +
                    "my wallet is on. Nothing was prepared."
            )
        }
        // The asset must exist ON THE CHOSEN CHAIN, not just
        // somewhere in her wallet: BNB is hers on BNB Smart
        // Chain and meaningless on Ethereum.
        val chainForAsset = WalletGate.chainByKey(chainKey)!!
        val token = WalletGate.token(chainForAsset, asset)
            ?: return Result.Ambiguous(
                "I don't hold $asset on ${chainForAsset.label} — on that " +
                    "chain I hold " +
                    chainForAsset.tokens.joinToString(", ") { it.symbol } +
                    ". Nothing was prepared."
            )
        when (targetKind) {
            TargetKind.EVM_ADDRESS, TargetKind.NAME -> Unit
            TargetKind.BTC_ADDRESS -> return Result.Ambiguous(
                "That looks like a Bitcoin address — on " +
                    "${chainForAsset.label} I need a 0x… address or a " +
                    "Fluid name. Nothing was prepared."
            )
            TargetKind.SOL_ADDRESS -> return Result.Ambiguous(
                "That looks like a Solana address — on " +
                    "${chainForAsset.label} I need a 0x… address or a " +
                    "Fluid name. Nothing was prepared."
            )
            TargetKind.GARBAGE -> return Result.Ambiguous(
                "I couldn't read \"$targetRaw\" as a wallet address or a Fluid " +
                    "name. An address starts 0x…; a Fluid name looks like @name. " +
                    "Nothing was prepared."
            )
        }
        amountProblem(amountText, usdAmountText, asset, token)?.let { return it }
        return Result.Parsed(
            SendIntent(
                amountText = amountText,
                usdAmountText = usdAmountText,
                asset = asset,
                targetRaw = targetRaw,
                targetIsName = targetKind == TargetKind.NAME,
                chainKey = chainKey,
                source = source
            )
        )
    }
}
