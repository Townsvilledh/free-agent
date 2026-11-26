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
 *   send <amount> <ASSET> to <target> [on <chain>]
 *   pay  <amount> <ASSET> to <target> [on <chain>]
 *   send $<amount> of <ASSET> to <target> [on <chain>]
 *   pay  $<amount> of <ASSET> to <target> [on <chain>]
 *   send <amount> dollars of <ASSET> to <target> [on <chain>]
 *
 *   <amount>  a plain decimal: 2, 2.5, 0.05 (no commas, no
 *             exponent, no words)
 *   <ASSET>   ETH | USDC | USDT | WETH
 *   <target>  a 0x… address (40 hex chars), @fluidname, or a
 *             bare fluidname (letters, digits, _ . -)
 *   <chain>   ethereum | mainnet | base   (default: ethereum)
 *
 * The "$"/"dollars" forms are USD-denominated: the prepare
 * path converts at the live price and shows the token amount
 * it computed on the approval sheet — the user always
 * approves concrete numbers, never a formula.
 */
object WalletPrompt {

    data class SendIntent(
        /** Token amount as typed (token-denominated forms). */
        val amountText: String?,
        /** USD amount as typed ($ / dollars forms). */
        val usdAmountText: String?,
        val asset: String,              // uppercase symbol
        val targetRaw: String,          // as typed
        val targetIsName: Boolean,
        val chainKey: String            // WalletGate chain key
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
        """^(?:send|pay)\s+(\d+(?:\.\d+)?)\s+([a-zA-Z]+)\s+to\s+(\S+)(?:\s+on\s+([a-zA-Z]+))?$"""
    )
    private val USD_FORM = Regex(
        """^(?:send|pay)\s+(?:\$(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s+dollars)\s+of\s+([a-zA-Z]+)\s+to\s+(\S+)(?:\s+on\s+([a-zA-Z]+))?$"""
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
            return finish(amount, null, assetRaw, target, chainRaw)
        }
        USD_FORM.matchEntire(text)?.let { m ->
            val usd = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val assetRaw = m.groupValues[3]
            val target = m.groupValues[4]
            val chainRaw = m.groupValues[5]
            return finish(null, usd, assetRaw, target, chainRaw)
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
                "I can send ETH, USDC, USDT or WETH from my own wallet — which " +
                    "asset did you mean? Try: \"send 2 USDC to @name\"."
            else ->
                "I want to be exact with money, and that phrasing isn't one I " +
                    "can parse safely. Try: \"send 2 USDC to @name\", " +
                    "\"send \$3 of ETH to 0x…\", optionally ending \"on base\"."
        }
        return Result.Ambiguous(question)
    }

    private fun finish(
        amountText: String?,
        usdAmountText: String?,
        assetRaw: String,
        targetRaw: String,
        chainRaw: String
    ): Result {
        val asset = assetRaw.uppercase()
        if (asset !in ASSETS) {
            return Result.Ambiguous(
                "I can only send ETH, USDC, USDT or WETH from my own wallet — " +
                    "\"$assetRaw\" isn't one I hold. Nothing was prepared."
            )
        }
        val chainKey = when (chainRaw.lowercase()) {
            "", "ethereum", "mainnet" -> WalletGate.ETHEREUM.key
            "base" -> WalletGate.BASE.key
            else -> return Result.Ambiguous(
                "I can send on Ethereum or Base — \"$chainRaw\" isn't a chain " +
                    "my wallet is on. Nothing was prepared."
            )
        }
        val isAddress = Evm.isAddress(targetRaw)
        val isName = !isAddress &&
            (targetRaw.startsWith("@") || NAME_RE.matches(targetRaw)) &&
            targetRaw.any { it.isLetter() }
        if (!isAddress && !isName) {
            return Result.Ambiguous(
                "I couldn't read \"$targetRaw\" as a wallet address or a Fluid " +
                    "name. An address starts 0x…; a Fluid name looks like @name. " +
                    "Nothing was prepared."
            )
        }
        // Amount sanity: a parsed decimal that is zero (or
        // unparseable at the asset's decimals) is a question,
        // not a transaction.
        if (amountText != null) {
            val chain = WalletGate.chainByKey(chainKey)!!
            val token = WalletGate.token(chain, asset)!!
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
        return Result.Parsed(
            SendIntent(
                amountText = amountText,
                usdAmountText = usdAmountText,
                asset = asset,
                targetRaw = targetRaw,
                targetIsName = isName,
                chainKey = chainKey
            )
        )
    }
}
