package org.freeagent.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lille's agent wallet — the engine (v0.12, WALLET.md).
 *
 * Everything the pure components decided, made real:
 * balances over public RPC with a fallback chain, prices
 * over the keyless price chain, transaction preparation
 * from live chain state, and — the only place in the app
 * where her key is ever used — the gated signer.
 *
 * THE SIGNING DISCIPLINE (spec §5, in code):
 * - execute() calls WalletGate.decide() BEFORE it touches
 *   the key. NeedsApproval → no signature. Refused → no
 *   signature. The model layer holds no reference to this
 *   class and cannot construct an approval: userApproved
 *   becomes true only in the approval sheet's click handler
 *   (or is unnecessary when the user's own autonomous
 *   switch is on — the gate checks that itself).
 * - Her key is read from the vault inside execute(), used
 *   to sign, and zeroed. It never leaves this class.
 * - Every outcome is verified by state: a send is "sent"
 *   when an RPC route returns its hash, "confirmed" when a
 *   receipt says so — never because a call merely returned.
 *
 * All work runs on one background executor; callbacks land
 * on the main thread.
 */
class WalletEngine(context: Context) {

    private val appContext = context.applicationContext
    private val wallet = AgentWallet(appContext)
    private val store = WalletStore(appContext)
    private val identityStore = FluidIdentityStore(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // The USD price chain, shared with the Bitcoin and
    // Solana engines (WalletPrices) — one chain, one cache
    // discipline, every family priced the same way.
    private val prices = WalletPrices(client)

    data class AssetBalance(
        val token: WalletGate.Token,
        /** null = couldn't load (shown as such, never as 0). */
        val raw: BigInteger?,
        val source: String?
    )

    // The outcome types below are the wallet-wide
    // vocabulary: the Bitcoin and Solana engines report
    // through these same types, so chat and the Wallet
    // screen handle every family's sends identically.
    sealed class PrepareOutcome {
        data class Ready(val prepared: WalletGate.PreparedTx) : PrepareOutcome()
        data class Failed(val message: String) : PrepareOutcome()
    }

    sealed class SweepOutcome {
        data class Ready(val prepared: List<WalletGate.PreparedTx>) : SweepOutcome()
        data class Failed(val message: String) : SweepOutcome()
    }

    sealed class ExecOutcome {
        /** Broadcast accepted; [confirmed] true/false once a
         *  receipt was read, null if still pending. */
        data class Sent(
            val txHash: String,
            val confirmed: Boolean?,
            val message: String
        ) : ExecOutcome()

        data class Refused(val reason: String) : ExecOutcome()
        object NeedsApproval : ExecOutcome()
        data class Failed(val message: String) : ExecOutcome()
    }

    private class WalletRpcException(message: String) : Exception(message)

    // ---------------- RPC (executor thread only) ----------------

    /** Walk the chain's endpoints in order; the first that
     *  answers serves. Returns (result string, endpoint). */
    private fun rpc(
        chain: WalletGate.Chain, method: String, params: JSONArray
    ): Pair<String, String> {
        var lastError = "no route answered"
        for (url in chain.rpcUrls) {
            try {
                val body = RpcChain.requestBody(1, method, params)
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string()
                    if (text == null) {
                        lastError = "$url: empty response"
                        return@use
                    }
                    when (val parsed = RpcChain.parseResult(text)) {
                        is RpcChain.RpcResult.Ok -> return parsed.value to url
                        is RpcChain.RpcResult.Error -> lastError = parsed.message
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "network error"
            }
        }
        throw WalletRpcException(lastError)
    }

    private fun nativeBalance(chain: WalletGate.Chain, address: String): BigInteger {
        val (result, _) = rpc(
            chain, "eth_getBalance", RpcChain.paramsOf(address, "latest")
        )
        return RpcChain.parseQuantity(result)
            ?: throw WalletRpcException("unreadable balance answer")
    }

    private fun tokenBalance(
        chain: WalletGate.Chain, token: WalletGate.Token, address: String
    ): BigInteger {
        if (token.contract == null) return nativeBalance(chain, address)
        val call = JSONObject()
            .put("to", token.contract)
            .put("data", "0x" + Evm.bytesToHex(Evm.erc20BalanceOfData(address)))
        val (result, _) = rpc(chain, "eth_call", RpcChain.paramsOf(call, "latest"))
        return RpcChain.parseUint256Word(result)
            ?: throw WalletRpcException("unreadable balance answer")
    }

    private data class FeeData(
        val baseFee: BigInteger,
        val priorityFee: BigInteger,
        val maxFeePerGas: BigInteger,
        /** Legacy chains only: the single eth_gasPrice the
         *  transaction signs with. maxFeePerGas mirrors it,
         *  so worst-case fee math is one shape everywhere. */
        val gasPrice: BigInteger? = null
    )

    private fun feeData(chain: WalletGate.Chain): FeeData {
        if (chain.legacyGas) {
            // BNB Smart Chain: no fee market. One gas price,
            // read live; the transaction signs legacy-style.
            val (result, _) = rpc(chain, "eth_gasPrice", JSONArray())
            val gasPrice = RpcChain.parseQuantity(result)
                ?: throw WalletRpcException("couldn't read the network gas price")
            return FeeData(BigInteger.ZERO, gasPrice, gasPrice, gasPrice)
        }
        val (historyJson, source) = rpcWithBody(
            chain, "eth_feeHistory",
            RpcChain.paramsOf("0x1", "latest", JSONArray())
        )
        val baseFee = RpcChain.parseFeeHistoryBaseFee(historyJson)
            ?: throw WalletRpcException("couldn't read network fees from $source")
        val priority = try {
            val (p, _) = rpc(chain, "eth_maxPriorityFeePerGas", JSONArray())
            RpcChain.parseQuantity(p) ?: BigInteger.valueOf(1_000_000_000)
        } catch (e: Exception) {
            BigInteger.valueOf(1_000_000_000)
        }
        return FeeData(baseFee, priority, baseFee.multiply(BigInteger.TWO) + priority)
    }

    /** Some calls need the RAW body (their result is an
     *  object, not a string): walk endpoints and return the
     *  first body that parses as JSON at all. Callers apply
     *  their own shape checks on top. */
    private fun rpcRawBody(
        chain: WalletGate.Chain, method: String, params: JSONArray
    ): Pair<String, String> {
        var lastError = "no route answered"
        for (url in chain.rpcUrls) {
            try {
                val body = RpcChain.requestBody(1, method, params)
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string()
                    if (text != null) {
                        try {
                            JSONObject(text)
                            return text to url
                        } catch (e: Exception) {
                            lastError = "unreadable answer from $url"
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "network error"
            }
        }
        throw WalletRpcException(lastError)
    }

    /** feeHistory on top of the raw-body walk, with its own
     *  shape check (a body without a usable base fee doesn't
     *  serve, whatever else it contains). */
    private fun rpcWithBody(
        chain: WalletGate.Chain, method: String, params: JSONArray
    ): Pair<String, String> {
        var lastError = "no route answered"
        for (url in chain.rpcUrls) {
            try {
                val body = RpcChain.requestBody(1, method, params)
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string()
                    if (text != null && RpcChain.parseFeeHistoryBaseFee(text) != null) {
                        return text to url
                    }
                    lastError = "unusable fee answer from $url"
                }
            } catch (e: Exception) {
                lastError = e.message ?: "network error"
            }
        }
        throw WalletRpcException(lastError)
    }

    private fun nonce(chain: WalletGate.Chain, address: String): BigInteger {
        val (result, _) = rpc(
            chain, "eth_getTransactionCount",
            RpcChain.paramsOf(address, "pending")
        )
        return RpcChain.parseQuantity(result)
            ?: throw WalletRpcException("unreadable nonce answer")
    }

    // ---------------- balances (public) ----------------

    fun loadBalances(
        chain: WalletGate.Chain,
        done: (List<AssetBalance>) -> Unit
    ) {
        val address = wallet.address()
        if (address == null) {
            done(chain.tokens.map { AssetBalance(it, null, null) })
            return
        }
        executor.execute {
            val out = chain.tokens.map { token ->
                try {
                    val (raw, source) = balanceWithSource(chain, token, address)
                    AssetBalance(token, raw, source)
                } catch (e: Exception) {
                    AssetBalance(token, null, null)
                }
            }
            mainHandler.post { done(out) }
        }
    }

    private fun balanceWithSource(
        chain: WalletGate.Chain, token: WalletGate.Token, address: String
    ): Pair<BigInteger, String> {
        return if (token.contract == null) {
            val (result, source) = rpc(
                chain, "eth_getBalance", RpcChain.paramsOf(address, "latest")
            )
            (RpcChain.parseQuantity(result)
                ?: throw WalletRpcException("unreadable balance answer")) to source
        } else {
            val call = JSONObject()
                .put("to", token.contract)
                .put("data", "0x" + Evm.bytesToHex(Evm.erc20BalanceOfData(address)))
            val (result, source) = rpc(
                chain, "eth_call", RpcChain.paramsOf(call, "latest")
            )
            (RpcChain.parseUint256Word(result)
                ?: throw WalletRpcException("unreadable balance answer")) to source
        }
    }

    // ---------------- prepare ----------------

    /** From a parsed chat prompt (WalletPrompt). */
    fun prepareFromPrompt(
        intent: WalletPrompt.SendIntent,
        done: (PrepareOutcome) -> Unit
    ) {
        prepare(
            chainKey = intent.chainKey,
            asset = intent.asset,
            amountText = intent.amountText,
            usdAmountText = intent.usdAmountText,
            targetRaw = intent.targetRaw,
            targetIsName = intent.targetIsName,
            isSweep = false,
            sweepAmountRaw = null,
            done = done
        )
    }

    /** From the Wallet screen's send form. */
    fun prepareFromForm(
        chainKey: String,
        asset: String,
        amountText: String?,
        usdAmountText: String?,
        targetRaw: String,
        done: (PrepareOutcome) -> Unit
    ) {
        val isName = !Evm.isAddress(targetRaw)
        prepare(
            chainKey, asset, amountText, usdAmountText,
            targetRaw, isName, isSweep = false, sweepAmountRaw = null,
            done = done
        )
    }

    // ---------------- main-wallet (Fluid) sends ----------------

    sealed class FluidSendOutcome {
        data class Ready(val send: FluidTx.MainSend) : FluidSendOutcome()
        data class Failed(val message: String) : FluidSendOutcome()
    }

    /**
     * Prepare a MAIN-WALLET send (v0.13.1, FluidTx): the
     * same live preparation discipline as her own sends —
     * recipient resolved against the names registry, the
     * amount computed from live prices for the $ forms —
     * minus everything that is Fluid's business, not hers:
     * no balance check (Fluid knows its own balance), no
     * fees, no nonce (Fluid fills them; its sheet shows its
     * own live fee estimate). Nothing is signed here and
     * nothing can be: the request goes over the session and
     * Fluid's approval sheet decides.
     */
    fun prepareFluidSend(
        intent: WalletPrompt.SendIntent,
        done: (FluidSendOutcome) -> Unit
    ) {
        executor.execute {
            val outcome = try {
                FluidSendOutcome.Ready(buildFluidSend(intent))
            } catch (e: PrepareException) {
                FluidSendOutcome.Failed(e.message!!)
            } catch (e: Exception) {
                FluidSendOutcome.Failed(
                    "Lille couldn't prepare that Fluid-wallet send " +
                        "(${e.message}). Nothing was sent."
                )
            }
            mainHandler.post { done(outcome) }
        }
    }

    private fun buildFluidSend(
        intent: WalletPrompt.SendIntent
    ): FluidTx.MainSend {
        val chain = WalletGate.chainByKey(intent.chainKey)
            ?: throw PrepareException("Unknown chain. Nothing was prepared.")
        val token = WalletGate.token(chain, intent.asset)
            ?: throw PrepareException(
                "Lille can't build a ${intent.asset} send on ${chain.label} — " +
                    "she only asks Fluid for assets whose contracts she " +
                    "knows. Nothing was prepared."
            )
        val identity = identityStore.load()
            ?: throw PrepareException(
                "No Fluid name is connected, so Lille doesn't know which " +
                    "wallet to ask. Connect your Fluid name first. " +
                    "Nothing was sent."
            )
        val from = Evm.toChecksumAddress(identity.address.removePrefix("0x"))
        val (recipient, recipientLabel) =
            resolveRecipient(intent.targetRaw, intent.targetIsName)
        val price = prices.priceFor(token.priceId)
        val amountRaw = computeAmountRaw(
            token, intent.amountText, intent.usdAmountText, price
        )
        val (to, valueWei, dataHex) = FluidTx.payloadFor(token, recipient, amountRaw)
        // The payload gate, defence in depth: her own builder
        // only produces allowed shapes, and if that ever
        // changed, the refusal still fires BEFORE any request.
        FluidTx.validateCall(to, dataHex)?.let { throw PrepareException(it) }
        val send = FluidTx.MainSend(
            chainKey = chain.key,
            chainId = FluidGate.caipChainId(chain.key)
                ?: throw PrepareException("Unknown chain. Nothing was prepared."),
            tokenSymbol = token.symbol,
            amountRaw = amountRaw,
            amountDisplay = Evm.formatUnits(amountRaw, token.decimals),
            fromAddress = from,
            recipient = recipient,
            recipientLabel = recipientLabel,
            to = to,
            valueWei = valueWei,
            dataHex = dataHex,
            usdCents = WalletGate.usdCentsOf(amountRaw, token, price?.usd)
        )
        store.recordActivity(
            "PREPARED-FLUID",
            "Prepared (your Fluid wallet): ${send.amountDisplay} " +
                "${send.tokenSymbol} → $recipient on ${chain.label}"
        )
        return send
    }

    /** The amount math shared by the Fluid prepare path:
     *  token-denominated parses directly; USD-denominated
     *  converts at the live price and refuses when no price
     *  route answers (an unpriced formula never reaches a
     *  signature — the user approves concrete numbers). */
    private fun computeAmountRaw(
        token: WalletGate.Token,
        amountText: String?,
        usdAmountText: String?,
        price: PriceChain.Price?
    ): BigInteger {
        return when {
            usdAmountText != null -> {
                if (price == null) {
                    throw PrepareException(
                        "Lille couldn't price ${token.symbol} in dollars " +
                            "right now (every price route is busy), so she " +
                            "can't turn \$$usdAmountText into an amount. " +
                            "Nothing was prepared — name the amount in " +
                            "${token.symbol} itself, or try again shortly."
                    )
                }
                val usd = BigDecimal(usdAmountText)
                val tokenAmount = usd.divide(price.usd, 18, RoundingMode.HALF_UP)
                val raw = tokenAmount.multiply(BigDecimal.TEN.pow(token.decimals))
                    .setScale(0, RoundingMode.HALF_UP).toBigInteger()
                if (raw.signum() == 0) {
                    throw PrepareException(
                        "\$$usdAmountText of ${token.symbol} is too small " +
                            "to send. Nothing was prepared."
                    )
                }
                raw
            }
            amountText != null -> Evm.parseUnits(amountText, token.decimals)
                ?: throw PrepareException(
                    "\"$amountText ${token.symbol}\" isn't an amount Lille " +
                        "can send. Nothing was prepared."
                )
            else -> throw PrepareException("No amount given. Nothing was prepared.")
        }
    }

    private fun prepare(
        chainKey: String,
        asset: String,
        amountText: String?,
        usdAmountText: String?,
        targetRaw: String,
        targetIsName: Boolean,
        isSweep: Boolean,
        sweepAmountRaw: BigInteger?,
        done: (PrepareOutcome) -> Unit
    ) {
        executor.execute {
            val outcome = try {
                PrepareOutcome.Ready(
                    buildPrepared(
                        chainKey, asset, amountText, usdAmountText,
                        targetRaw, targetIsName, isSweep, sweepAmountRaw,
                        nonceOverride = null, feesOverride = null
                    )
                )
            } catch (e: WalletRpcException) {
                PrepareOutcome.Failed(
                    "Lille couldn't reach the chain to prepare that: ${e.message}. " +
                        "Nothing was sent — try again in a moment."
                )
            } catch (e: PrepareException) {
                PrepareOutcome.Failed(e.message!!)
            }
            mainHandler.post { done(outcome) }
        }
    }

    private class PrepareException(message: String) : Exception(message)

    private fun resolveRecipient(
        targetRaw: String, targetIsName: Boolean
    ): Pair<String, String> {
        if (!targetIsName) {
            return Evm.toChecksumAddress(targetRaw.removePrefix("0x")) to "address"
        }
        val name = FluidGate.normaliseName(targetRaw)
        val json = fetchRegistry()
            ?: throw PrepareException(
                "Lille couldn't reach the Fluid names registry to look up " +
                    "@$name. Nothing was prepared — try again in a moment."
            )
        return when (val found = FluidGate.lookup(json, name)) {
            is FluidGate.Lookup.Found ->
                Evm.toChecksumAddress(found.evmAddress.removePrefix("0x")) to name
            FluidGate.Lookup.NameNotFound ->
                throw PrepareException(FluidGate.notFoundMessage(name))
            FluidGate.Lookup.NoEvmAddress ->
                throw PrepareException(FluidGate.noEvmMessage(name))
            FluidGate.Lookup.RegistryInvalid ->
                throw PrepareException(FluidGate.registryUnreachableMessage())
        }
    }

    /** Registry fetch over FluidGate's source list, with the
     *  same validation the login gate applies (2026-11-27):
     *  only a body passing FluidGate.assessRegistry counts —
     *  the frozen Netlify fallback this used to fall back to
     *  holds 2 of 29 names and answered "name not found" for
     *  real users. A valid body also refreshes the login
     *  gate's on-device last-good copy. Sends still require a
     *  LIVE registry: no cache read here, an unreachable
     *  registry fails the prepare honestly. */
    private fun fetchRegistry(): String? {
        for (url in FluidGate.REGISTRY_SOURCES) {
            val body = prices.get(url) ?: continue
            if (FluidGate.assessRegistry(body) is
                FluidGate.RegistryAssessment.Valid
            ) {
                identityStore.saveRegistryCache(
                    body, System.currentTimeMillis()
                )
                return body
            }
        }
        return null
    }

    /** The whole prepare, on the executor. Throws
     *  PrepareException (user-facing, specific) or
     *  WalletRpcException (chain unreachable). */
    private fun buildPrepared(
        chainKey: String,
        asset: String,
        amountText: String?,
        usdAmountText: String?,
        targetRaw: String,
        targetIsName: Boolean,
        isSweep: Boolean,
        sweepAmountRaw: BigInteger?,
        nonceOverride: BigInteger?,
        feesOverride: FeeData?
    ): WalletGate.PreparedTx {
        val chain = WalletGate.chainByKey(chainKey)
            ?: throw PrepareException("Unknown chain. Nothing was prepared.")
        val token = WalletGate.token(chain, asset)
            ?: throw PrepareException("Lille doesn't hold $asset. Nothing was prepared.")
        val from = wallet.address()
            ?: throw PrepareException(
                "Lille doesn't have her own wallet yet — create it in " +
                    "Settings → Lille's wallet first. Nothing was sent."
            )
        val (recipient, recipientLabel) = resolveRecipient(targetRaw, targetIsName)

        val price = prices.priceFor(token.priceId)
        val amountRaw: BigInteger = when {
            sweepAmountRaw != null -> sweepAmountRaw
            usdAmountText != null -> {
                if (price == null) {
                    throw PrepareException(
                        "Lille couldn't price $asset in dollars right now " +
                            "(every price route is busy), so she can't turn " +
                            "\$$usdAmountText into an amount of $asset. Nothing " +
                            "was prepared — name the amount in $asset itself, " +
                            "or try again shortly."
                    )
                }
                val usd = BigDecimal(usdAmountText)
                val tokenAmount = usd.divide(price.usd, 18, RoundingMode.HALF_UP)
                val raw = tokenAmount.multiply(BigDecimal.TEN.pow(token.decimals))
                    .setScale(0, RoundingMode.HALF_UP).toBigInteger()
                if (raw.signum() == 0) {
                    throw PrepareException(
                        "\$$usdAmountText of $asset is too small to send. " +
                            "Nothing was prepared."
                    )
                }
                raw
            }
            amountText != null -> Evm.parseUnits(amountText, token.decimals)
                ?: throw PrepareException(
                    "\"$amountText $asset\" isn't an amount Lille can send. " +
                        "Nothing was prepared."
                )
            else -> throw PrepareException("No amount given. Nothing was prepared.")
        }

        // Balance: the amount must actually be there.
        val tokenBal = try {
            tokenBalance(chain, token, from)
        } catch (e: WalletRpcException) {
            throw PrepareException(
                "Lille couldn't read her own $asset balance on ${chain.label} " +
                    "(${e.message}). Nothing was prepared."
            )
        }
        if (amountRaw > tokenBal) {
            throw PrepareException(
                "She has ${Evm.formatUnits(tokenBal, token.decimals)} $asset on " +
                    "${chain.label} — less than the " +
                    "${Evm.formatUnits(amountRaw, token.decimals)} asked for. " +
                    "Nothing was prepared."
            )
        }

        val fees = feesOverride ?: feeData(chain)
        val gasLimit: BigInteger = if (token.contract == null) {
            BigInteger.valueOf(21_000)
        } else {
            estimateErc20Gas(chain, token, from, recipient, amountRaw)
        }
        val maxFeeWei = gasLimit.multiply(fees.maxFeePerGas)

        // The network fee comes out of her native balance.
        val nativeToken = chain.tokens[0]
        val nativeBal = if (token.contract == null) tokenBal else try {
            nativeBalance(chain, from)
        } catch (e: WalletRpcException) {
            throw PrepareException(
                "Lille couldn't read her ${nativeToken.symbol} balance on " +
                    "${chain.label} for the network fee (${e.message}). " +
                    "Nothing was prepared."
            )
        }
        val nativeNeeded =
            if (token.contract == null) amountRaw + maxFeeWei else maxFeeWei
        if (nativeBal < nativeNeeded) {
            throw PrepareException(
                "She has ${Evm.formatUnits(nativeBal, 18)} ${nativeToken.symbol} " +
                    "on ${chain.label}; this send needs up to " +
                    "${Evm.formatUnits(nativeNeeded, 18)} ${nativeToken.symbol} " +
                    (if (token.contract == null) "(amount + network fee)."
                    else "for the network fee.") +
                    " Nothing was prepared."
            )
        }

        val nativePrice = prices.priceFor(nativeToken.priceId)
        val prepared = WalletGate.PreparedTx(
            chainKey = chain.key,
            tokenSymbol = token.symbol,
            amountRaw = amountRaw,
            amountDisplay = Evm.formatUnits(amountRaw, token.decimals),
            recipient = recipient,
            recipientLabel = recipientLabel,
            usdCents = WalletGate.usdCentsOf(amountRaw, token, price?.usd),
            priceSource = price?.source,
            gasLimit = gasLimit,
            maxFeeWei = maxFeeWei,
            maxFeeUsdCents = nativePrice?.let {
                WalletGate.usdCentsOf(maxFeeWei, nativeToken, it.usd)
            },
            nonce = nonceOverride ?: nonce(chain, from),
            maxPriorityFeePerGas = fees.priorityFee,
            maxFeePerGas = fees.maxFeePerGas,
            gasPrice = fees.gasPrice,
            isSweep = isSweep
        )
        store.recordActivity(
            "PREPARED",
            "Prepared: ${prepared.amountDisplay} ${prepared.tokenSymbol} → " +
                "$recipient on ${chain.label}" +
                (if (isSweep) " (sweep-back)" else "")
        )
        return prepared
    }

    private fun estimateErc20Gas(
        chain: WalletGate.Chain,
        token: WalletGate.Token,
        from: String,
        recipient: String,
        amountRaw: BigInteger
    ): BigInteger {
        val call = JSONObject()
            .put("from", from)
            .put("to", token.contract)
            .put(
                "data",
                "0x" + Evm.bytesToHex(Evm.erc20TransferData(recipient, amountRaw))
            )
        val (result, _) = try {
            rpc(chain, "eth_estimateGas", RpcChain.paramsOf(call))
        } catch (e: WalletRpcException) {
            throw PrepareException(
                "The network couldn't estimate that send — it would likely " +
                    "be rejected (${e.message}). Nothing was prepared."
            )
        }
        val estimate = RpcChain.parseQuantity(result)
            ?: throw PrepareException(
                "The network gave an unreadable gas estimate. Nothing was prepared."
            )
        // 20% headroom, the standard wallet margin.
        return estimate.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100))
    }

    // ---------------- sweep ----------------

    fun prepareSweep(
        chain: WalletGate.Chain,
        done: (SweepOutcome) -> Unit
    ) {
        executor.execute {
            val outcome = try {
                val identity = identityStore.load()
                    ?: throw PrepareException(
                        "No Fluid identity is stored, so Lille doesn't know " +
                            "your Fluid address to sweep back to. Nothing was prepared."
                    )
                val from = wallet.address()
                    ?: throw PrepareException(
                        "Lille doesn't have her own wallet yet. Nothing to sweep."
                    )
                val fees = feeData(chain)
                var nextNonce = nonce(chain, from)
                val prepared = mutableListOf<WalletGate.PreparedTx>()
                // Tokens first (their fees are paid in native),
                // the native remainder last.
                for (token in chain.tokens) {
                    if (token.contract == null) continue
                    val bal = try {
                        tokenBalance(chain, token, from)
                    } catch (e: WalletRpcException) {
                        throw PrepareException(
                            "Lille couldn't read her ${token.symbol} balance on " +
                                "${chain.label} (${e.message}). Nothing was prepared."
                        )
                    }
                    if (bal.signum() > 0) {
                        prepared.add(
                            buildPrepared(
                                chain.key, token.symbol, null, null,
                                identity.address, targetIsName = false,
                                isSweep = true, sweepAmountRaw = bal,
                                nonceOverride = nextNonce, feesOverride = fees
                            )
                        )
                        nextNonce += BigInteger.ONE
                    }
                }
                val nativeBal = nativeBalance(chain, from)
                val nativeFee = BigInteger.valueOf(21_000).multiply(fees.maxFeePerGas)
                // Reserve the ACTUAL worst-case fees of the
                // token sweeps already prepared, not a guess.
                val tokenFees = prepared.fold(BigInteger.ZERO) { acc, p ->
                    acc + p.maxFeeWei
                }
                val nativeAmount = nativeBal - nativeFee - tokenFees
                if (nativeAmount.signum() > 0) {
                    prepared.add(
                        buildPrepared(
                            chain.key, chain.tokens[0].symbol, null, null,
                            identity.address, targetIsName = false,
                            isSweep = true, sweepAmountRaw = nativeAmount,
                            nonceOverride = nextNonce, feesOverride = fees
                        )
                    )
                }
                if (prepared.isEmpty()) {
                    throw PrepareException(
                        "Her wallet holds nothing sweepable on ${chain.label} " +
                            "(any dust there doesn't cover its own network fee). " +
                            "Nothing was prepared."
                    )
                }
                SweepOutcome.Ready(prepared)
            } catch (e: WalletRpcException) {
                SweepOutcome.Failed(
                    "Lille couldn't reach the chain to prepare the sweep: " +
                        "${e.message}. Nothing was sent."
                )
            } catch (e: PrepareException) {
                SweepOutcome.Failed(e.message!!)
            }
            mainHandler.post { done(outcome) }
        }
    }

    // ---------------- execute (the gated signer) ----------------

    /**
     * Sign + broadcast ONE prepared transaction — through
     * the gate, always. [userApproved] may be true ONLY from
     * the approval sheet's Approve handler. With the
     * autonomous switch on and the gate's Allow, the sheet is
     * skipped — the gate, not this class, decides that.
     */
    fun execute(
        prepared: WalletGate.PreparedTx,
        userApproved: Boolean,
        rawAmountConfirmed: Boolean,
        done: (ExecOutcome) -> Unit
    ) {
        executor.execute {
            val outcome = executeSync(prepared, userApproved, rawAmountConfirmed)
            mainHandler.post { done(outcome) }
        }
    }

    private fun executeSync(
        prepared: WalletGate.PreparedTx,
        userApproved: Boolean,
        rawAmountConfirmed: Boolean
    ): ExecOutcome {
        val chain = WalletGate.chainByKey(prepared.chainKey)
            ?: return ExecOutcome.Failed("Unknown chain. Nothing was sent.")
        val token = WalletGate.token(chain, prepared.tokenSymbol)
            ?: return ExecOutcome.Failed("Unknown asset. Nothing was sent.")
        val caps = store.capState()
        when (val decision = WalletGate.decide(
            prepared, caps,
            autonomousOn = store.autonomousOn(),
            userApproved = userApproved,
            rawAmountConfirmed = rawAmountConfirmed,
            nowMs = System.currentTimeMillis()
        )) {
            is WalletGate.Decision.Refused -> {
                store.recordActivity(
                    "REFUSED",
                    "Refused by the signer: ${prepared.amountDisplay} " +
                        "${prepared.tokenSymbol} — ${decision.reason}"
                )
                return ExecOutcome.Refused(decision.reason)
            }
            WalletGate.Decision.NeedsApproval -> return ExecOutcome.NeedsApproval
            WalletGate.Decision.Allow -> Unit
        }

        val key = wallet.keyBytesForSigner()
            ?: return ExecOutcome.Failed(
                "Lille couldn't read her own key from the vault — nothing " +
                    "was signed. Her wallet may need to be restored from backup."
            )
        val toBytes = Evm.hexToBytes(token.contract ?: prepared.recipient)
        val txValue =
            if (token.contract == null) prepared.amountRaw else BigInteger.ZERO
        val txData = if (token.contract == null) ByteArray(0)
        else Evm.erc20TransferData(prepared.recipient, prepared.amountRaw)
        // The chain's own fee rules decide the shape: legacy
        // chains (BNB Smart Chain) sign a type-0 EIP-155
        // transaction at the prepared gas price; every other
        // chain signs EIP-1559.
        val raw = if (prepared.gasPrice != null) {
            Evm.signTx(
                key,
                Evm.TxLegacy(
                    chainId = BigInteger.valueOf(chain.chainId),
                    nonce = prepared.nonce,
                    gasPrice = prepared.gasPrice,
                    gasLimit = prepared.gasLimit,
                    to = toBytes,
                    value = txValue,
                    data = txData
                )
            )
        } else {
            Evm.signTx(
                key,
                Evm.Tx1559(
                    chainId = BigInteger.valueOf(chain.chainId),
                    nonce = prepared.nonce,
                    maxPriorityFeePerGas = prepared.maxPriorityFeePerGas,
                    maxFeePerGas = prepared.maxFeePerGas,
                    gasLimit = prepared.gasLimit,
                    to = toBytes,
                    value = txValue,
                    data = txData
                )
            )
        }
        key.fill(0)
        val expectedHash = Evm.txHash(raw)

        // Broadcast: walk the chain's routes.
        var hash: String? = null
        var lastError = "no route answered"
        for (url in chain.rpcUrls) {
            try {
                val body = RpcChain.requestBody(
                    1, "eth_sendRawTransaction",
                    RpcChain.paramsOf("0x" + Evm.bytesToHex(raw))
                )
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string()
                    if (text != null) {
                        when (val parsed = RpcChain.parseResult(text)) {
                            is RpcChain.RpcResult.Ok -> {
                                if (RpcChain.isTxHash(parsed.value)) {
                                    hash = parsed.value
                                } else {
                                    lastError = "unreadable broadcast answer"
                                }
                            }
                            is RpcChain.RpcResult.Error -> {
                                lastError = parsed.message
                                if (parsed.message.contains("already known", true) ||
                                    parsed.message.contains("nonce too low", true)
                                ) {
                                    // A route already holds this exact
                                    // signed transaction: it IS sent.
                                    hash = expectedHash
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "network error"
            }
            if (hash != null) break
        }
        val sentHash = hash ?: return ExecOutcome.Failed(
            "The broadcast didn't land on any route ($lastError). If a route " +
                "did accept it silently, its hash would be $expectedHash — " +
                "check that hash on a block explorer before trying again."
        )

        // Record the spend against the caps (priced sends
        // only — an unpriced send was confirmed by the user's
        // own raw-amount approval on the sheet).
        prepared.usdCents?.let { cents ->
            store.recordSpend(
                WalletGate.recordSpend(store.capState(), cents, System.currentTimeMillis())
            )
        }
        val summary = "${prepared.amountDisplay} ${prepared.tokenSymbol} → " +
            "${prepared.recipient} on ${chain.label}"
        store.recordActivity("SENT", "Sent: $summary", sentHash)

        // Verify by state: poll the receipt.
        var confirmed: Boolean? = null
        for (attempt in 0 until 5) {
            try {
                Thread.sleep(6_000)
            } catch (e: InterruptedException) {
                break
            }
            val status = try {
                val (body, _) = rpcRawBody(
                    chain, "eth_getTransactionReceipt",
                    RpcChain.paramsOf(sentHash)
                )
                RpcChain.parseReceiptStatus(body)
            } catch (e: Exception) {
                null
            }
            if (status != null) {
                confirmed = status
                break
            }
        }
        return when (confirmed) {
            true -> {
                store.recordActivity("CONFIRMED", "Confirmed on chain: $summary", sentHash)
                ExecOutcome.Sent(
                    sentHash, true,
                    "Sent and confirmed: $summary. Tx: $sentHash"
                )
            }
            false -> {
                store.recordActivity(
                    "FAILED",
                    "Broadcast but reverted on chain: $summary", sentHash
                )
                ExecOutcome.Sent(
                    sentHash, false,
                    "That was broadcast but the network rejected it (reverted) — " +
                        "the amount is back in her wallet; only the network fee " +
                        "was spent. Tx: $sentHash"
                )
            }
            null -> ExecOutcome.Sent(
                sentHash, null,
                "Sent: $summary. Tx: $sentHash — confirmation is still " +
                    "pending; her activity list will show it once the network " +
                    "settles."
            )
        }
    }
}
