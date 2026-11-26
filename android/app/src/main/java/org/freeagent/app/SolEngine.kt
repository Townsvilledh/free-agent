package org.freeagent.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
 * Lille's Solana wallet — the engine (WALLET.md).
 *
 * The family discipline, third time: balances over ≥2
 * public RPC routes with the serving source named (an SPL
 * balance reads through the owner's associated token
 * account — a missing ATA is a TRUE zero, stated by the
 * chain itself, never a failed read dressed as one),
 * prepare from live state only (blockhash, fee for the
 * built message, ATA rent when the recipient has no token
 * account yet), and the ONLY place her Solana seed is ever
 * used — the gated signer, WalletGate.decide() before the
 * key, exactly as the other families.
 *
 * A prepared send pins its blockhash: if the network has
 * moved past it by broadcast time, the send fails honestly
 * ("prepare it again") rather than being silently rebuilt
 * into a transaction the user never saw — the same
 * discipline as the EVM engine's prepared nonce.
 *
 * Outcomes are the wallet-wide types declared in
 * WalletEngine. No sweep: the recorded sweep destination
 * is an EVM address (WALLET.md).
 */
class SolEngine(context: Context) {

    private val appContext = context.applicationContext
    private val wallet = AgentWallet(appContext)
    private val store = WalletStore(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prices = WalletPrices(client)

    companion object {
        /** Public mainnet RPC routes, walked in order. */
        val RPC_ROUTES = listOf(
            "https://api.mainnet-beta.solana.com",
            "https://solana-rpc.publicnode.com",
            "https://solana.drpc.org"
        )
    }

    private class RouteException(message: String) : Exception(message)
    private class PrepareException(message: String) : Exception(message)

    // ---------------- RPC (executor thread only) ----------------

    private fun rpcPost(base: String, method: String, params: JSONArray): String? {
        return try {
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", method)
                .put("params", params)
                .toString()
            val request = Request.Builder()
                .url(base)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Walk the routes for one call; the first whose body
     *  [parse] accepts serves, and is returned with the
     *  value. An RPC error body never serves. */
    private fun <T> walkRpc(
        method: String, params: JSONArray, parse: (String) -> T?
    ): Pair<T, String> {
        var lastError = "no route answered"
        for (base in RPC_ROUTES) {
            val body = rpcPost(base, method, params)
            if (body == null) {
                lastError = "$base didn't answer"
                continue
            }
            val error = Sol.rpcError(body)
            if (error != null) {
                lastError = error
                continue
            }
            val value = parse(body)
            if (value != null) return value to base
            lastError = "$base sent an unreadable answer"
        }
        throw RouteException(lastError)
    }

    private fun solBalance(address: String): Pair<Long, String> =
        walkRpc("getBalance", JSONArray().put(address)) {
            Sol.parseLamportBalance(it)
        }

    private fun accountExists(address: String): Pair<Boolean, String> =
        walkRpc(
            "getAccountInfo",
            JSONArray().put(address)
                .put(JSONObject().put("encoding", "base64"))
        ) { Sol.parseAccountExists(it) }

    private fun tokenAmount(ataAddress: String): Pair<BigInteger, String> =
        walkRpc("getTokenAccountBalance", JSONArray().put(ataAddress)) {
            Sol.parseTokenAmount(it)
        }

    private fun latestBlockhash(): String =
        walkRpc("getLatestBlockhash", JSONArray()) {
            Sol.parseBlockhash(it)
        }.first

    /** The fee for a built message, from the first route
     *  that prices it; the network's fixed per-signature
     *  fee when none can (her transactions carry exactly
     *  one signature, so the fallback is exact, not a
     *  guess). */
    private fun feeForMessage(message: ByteArray): Long {
        val encoded = Base64.encodeToString(message, Base64.NO_WRAP)
        for (base in RPC_ROUTES) {
            val body = rpcPost(
                base, "getFeeForMessage", JSONArray().put(encoded)
            ) ?: continue
            if (Sol.rpcError(body) != null) continue
            Sol.parseFeeForMessage(body)?.let { return it }
        }
        return Sol.LAMPORTS_PER_SIGNATURE
    }

    private fun tokenAccountRent(): Long {
        for (base in RPC_ROUTES) {
            val body = rpcPost(
                base, "getMinimumBalanceForRentExemption",
                JSONArray().put(165)
            ) ?: continue
            if (Sol.rpcError(body) != null) continue
            Sol.parseRentExemption(body)?.let { return it }
        }
        return Sol.TOKEN_ACCOUNT_RENT_LAMPORTS
    }

    // ---------------- balances (public) ----------------

    fun loadBalances(
        done: (List<WalletEngine.AssetBalance>) -> Unit
    ) {
        val address = wallet.solAddress()
        if (address == null) {
            done(WalletGate.SOLANA_TOKENS.map {
                WalletEngine.AssetBalance(it, null, null)
            })
            return
        }
        executor.execute {
            val out = WalletGate.SOLANA_TOKENS.map { token ->
                try {
                    balanceOf(token, address)
                } catch (e: Exception) {
                    WalletEngine.AssetBalance(token, null, null)
                }
            }
            mainHandler.post { done(out) }
        }
    }

    /** One asset's balance with its serving route. Throws
     *  RouteException when no route could serve — the
     *  caller renders that as "couldn't load", never 0.
     *  The exception is an SPL account that doesn't exist:
     *  the chain states that fact, and it IS a zero. */
    private fun balanceOf(
        token: WalletGate.Token, ownerAddress: String
    ): WalletEngine.AssetBalance {
        if (token.contract == null) {
            val (lamports, source) = solBalance(ownerAddress)
            return WalletEngine.AssetBalance(
                token, BigInteger.valueOf(lamports), source
            )
        }
        val ownerBytes = Sol.pubkeyBytes(ownerAddress)
            ?: throw RouteException("her own address doesn't parse")
        val mintBytes = Sol.pubkeyBytes(token.contract)
            ?: throw RouteException("unreadable mint for ${token.symbol}")
        val ata = Base58.encode(Sol.associatedTokenAddress(ownerBytes, mintBytes))
        val (exists, source) = accountExists(ata)
        if (!exists) {
            // No token account = the chain's own statement
            // that she holds none of this asset.
            return WalletEngine.AssetBalance(token, BigInteger.ZERO, source)
        }
        val (amount, amountSource) = tokenAmount(ata)
        return WalletEngine.AssetBalance(token, amount, amountSource)
    }

    // ---------------- prepare ----------------

    fun prepareFromPrompt(
        intent: WalletPrompt.SendIntent,
        done: (WalletEngine.PrepareOutcome) -> Unit
    ) {
        prepare(
            asset = intent.asset,
            amountText = intent.amountText,
            usdAmountText = intent.usdAmountText,
            targetRaw = intent.targetRaw,
            done = done
        )
    }

    fun prepareFromForm(
        asset: String,
        amountText: String?,
        usdAmountText: String?,
        targetRaw: String,
        done: (WalletEngine.PrepareOutcome) -> Unit
    ) {
        prepare(asset, amountText, usdAmountText, targetRaw, done)
    }

    private fun prepare(
        asset: String,
        amountText: String?,
        usdAmountText: String?,
        targetRaw: String,
        done: (WalletEngine.PrepareOutcome) -> Unit
    ) {
        executor.execute {
            val outcome = try {
                WalletEngine.PrepareOutcome.Ready(
                    buildPrepared(asset, amountText, usdAmountText, targetRaw)
                )
            } catch (e: RouteException) {
                WalletEngine.PrepareOutcome.Failed(
                    "Lille couldn't reach Solana to prepare that: " +
                        "${e.message}. Nothing was sent — try again in a moment."
                )
            } catch (e: PrepareException) {
                WalletEngine.PrepareOutcome.Failed(e.message!!)
            }
            mainHandler.post { done(outcome) }
        }
    }

    private fun buildPrepared(
        asset: String,
        amountText: String?,
        usdAmountText: String?,
        targetRaw: String
    ): WalletGate.PreparedTx {
        val token = WalletGate.familyToken(WalletGate.FAMILY_SOLANA, asset)
            ?: throw PrepareException(
                "Lille doesn't hold $asset on Solana — there she holds " +
                    WalletGate.SOLANA_TOKENS.joinToString(", ") { it.symbol } +
                    ". Nothing was prepared."
            )
        val from = wallet.solAddress()
            ?: throw PrepareException(
                "Lille doesn't have her Solana wallet yet — create it in " +
                    "Settings → Lille's wallet first. Nothing was sent."
            )
        val fromBytes = Sol.pubkeyBytes(from)
            ?: throw PrepareException("Her own Solana address doesn't parse. Nothing was prepared.")
        val recipient = targetRaw.trim()
        val recipientBytes = Sol.pubkeyBytes(recipient)
            ?: throw PrepareException(
                if (recipient.startsWith("@") || recipient.contains(" ")) {
                    "Fluid names resolve to EVM addresses only, so a name " +
                        "can't receive on Solana. Paste the Solana address " +
                        "itself. Nothing was prepared."
                } else {
                    "Lille couldn't read \"$recipient\" as a Solana address. " +
                        "Nothing was prepared."
                }
            )

        val price = prices.priceFor(token.priceId)
        val amountRaw: BigInteger = when {
            usdAmountText != null -> {
                if (price == null) {
                    throw PrepareException(
                        "Lille couldn't price ${token.symbol} in dollars right " +
                            "now (every price route is busy), so she can't turn " +
                            "\$$usdAmountText into an amount. Nothing was " +
                            "prepared — name the amount in ${token.symbol} " +
                            "itself, or try again shortly."
                    )
                }
                val tokenAmount = BigDecimal(usdAmountText)
                    .divide(price.usd, 18, RoundingMode.HALF_UP)
                val raw = tokenAmount.multiply(BigDecimal.TEN.pow(token.decimals))
                    .setScale(0, RoundingMode.HALF_UP).toBigInteger()
                if (raw.signum() == 0) {
                    throw PrepareException(
                        "\$$usdAmountText of ${token.symbol} is too small to " +
                            "send. Nothing was prepared."
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

        val blockhash = try {
            latestBlockhash()
        } catch (e: RouteException) {
            throw PrepareException(
                "Lille couldn't read Solana's current state (${e.message}). " +
                    "Nothing was prepared."
            )
        }
        val solToken = WalletGate.SOLANA_TOKENS[0]
        val (solLamports, _) = try {
            solBalance(from)
        } catch (e: RouteException) {
            throw PrepareException(
                "Lille couldn't read her own SOL balance (${e.message}). " +
                    "Nothing was prepared."
            )
        }

        val plan: Sol.SpendPlan
        if (token.contract == null) {
            // Native SOL.
            if (amountRaw > BigInteger.valueOf(Long.MAX_VALUE)) {
                throw PrepareException("That amount is beyond what she could ever hold. Nothing was prepared.")
            }
            val draft = Sol.SpendPlan(
                kind = Sol.SpendKind.NATIVE,
                mint = null, sourceAta = null, destAta = null,
                createDestAta = false,
                blockhash = blockhash, feeLamports = 0, rentLamports = 0
            )
            val fee = feeForMessage(
                Sol.spendMessage(draft, fromBytes, recipientBytes, amountRaw)
            )
            val amountLamports = amountRaw.toLong()
            if (amountLamports + fee > solLamports) {
                throw PrepareException(
                    "She has ${Evm.formatUnits(BigInteger.valueOf(solLamports), 9)} SOL; " +
                        "this send needs ${Evm.formatUnits(amountRaw, 9)} plus a " +
                        "network fee of ${Evm.formatUnits(BigInteger.valueOf(fee), 9)} SOL. " +
                        "Nothing was prepared."
                )
            }
            plan = draft.copy(feeLamports = fee)
        } else {
            // SPL token: route through the ATAs.
            val mintBytes = Sol.pubkeyBytes(token.contract)
                ?: throw PrepareException("Unreadable mint for ${token.symbol}. Nothing was prepared.")
            val sourceAta = Sol.associatedTokenAddress(fromBytes, mintBytes)
            val destAta = Sol.associatedTokenAddress(recipientBytes, mintBytes)
            val sourceAtaText = Base58.encode(sourceAta)
            val destAtaText = Base58.encode(destAta)

            val (sourceExists, _) = try {
                accountExists(sourceAtaText)
            } catch (e: RouteException) {
                throw PrepareException(
                    "Lille couldn't check her own ${token.symbol} account " +
                        "(${e.message}). Nothing was prepared."
                )
            }
            val tokenBalance = if (!sourceExists) {
                BigInteger.ZERO
            } else {
                try {
                    tokenAmount(sourceAtaText).first
                } catch (e: RouteException) {
                    throw PrepareException(
                        "Lille couldn't read her own ${token.symbol} balance " +
                            "(${e.message}). Nothing was prepared."
                    )
                }
            }
            if (amountRaw > tokenBalance) {
                throw PrepareException(
                    "She has ${Evm.formatUnits(tokenBalance, token.decimals)} " +
                        "${token.symbol} on Solana — less than the " +
                        "${Evm.formatUnits(amountRaw, token.decimals)} asked for. " +
                        "Nothing was prepared."
                )
            }
            val (destExists, _) = try {
                accountExists(destAtaText)
            } catch (e: RouteException) {
                throw PrepareException(
                    "Lille couldn't check the recipient's ${token.symbol} " +
                        "account (${e.message}). Nothing was prepared."
                )
            }
            val createDestAta = !destExists
            val rent = if (createDestAta) tokenAccountRent() else 0L
            val draft = Sol.SpendPlan(
                kind = Sol.SpendKind.SPL,
                mint = token.contract,
                sourceAta = sourceAta, destAta = destAta,
                createDestAta = createDestAta,
                blockhash = blockhash, feeLamports = 0, rentLamports = rent
            )
            val fee = feeForMessage(
                Sol.spendMessage(draft, fromBytes, recipientBytes, amountRaw)
            )
            if (fee + rent > solLamports) {
                throw PrepareException(
                    "This send needs ${Evm.formatUnits(BigInteger.valueOf(fee + rent), 9)} SOL " +
                        "from her wallet (network fee" +
                        (if (createDestAta) " plus the rent to open the " +
                            "recipient's ${token.symbol} account" else "") +
                        "), and she has " +
                        "${Evm.formatUnits(BigInteger.valueOf(solLamports), 9)} SOL. " +
                        "Nothing was prepared."
                )
            }
            plan = draft.copy(feeLamports = fee)
        }

        val solPrice = if (token.contract == null) price else prices.priceFor(solToken.priceId)
        val prepared = WalletGate.PreparedTx(
            chainKey = WalletGate.FAMILY_SOLANA,
            tokenSymbol = token.symbol,
            amountRaw = amountRaw,
            amountDisplay = Evm.formatUnits(amountRaw, token.decimals),
            recipient = recipient,
            recipientLabel = "address",
            usdCents = WalletGate.usdCentsOf(amountRaw, token, price?.usd),
            priceSource = price?.source,
            gasLimit = BigInteger.ZERO,
            maxFeeWei = BigInteger.valueOf(plan.feeLamports),
            maxFeeUsdCents = solPrice?.let {
                WalletGate.usdCentsOf(
                    BigInteger.valueOf(plan.feeLamports), solToken, it.usd
                )
            },
            nonce = BigInteger.ZERO,
            maxPriorityFeePerGas = BigInteger.ZERO,
            maxFeePerGas = BigInteger.ZERO,
            gasPrice = null,
            isSweep = false,
            family = WalletGate.FAMILY_SOLANA,
            solPlan = plan
        )
        store.recordActivity(
            "PREPARED",
            "Prepared: ${prepared.amountDisplay} ${token.symbol} → " +
                "$recipient on Solana"
        )
        return prepared
    }

    // ---------------- execute (the gated signer) ----------------

    fun execute(
        prepared: WalletGate.PreparedTx,
        userApproved: Boolean,
        rawAmountConfirmed: Boolean,
        done: (WalletEngine.ExecOutcome) -> Unit
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
    ): WalletEngine.ExecOutcome {
        val plan = prepared.solPlan
            ?: return WalletEngine.ExecOutcome.Failed(
                "That prepared send has no Solana plan behind it. Nothing was sent."
            )
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
                return WalletEngine.ExecOutcome.Refused(decision.reason)
            }
            WalletGate.Decision.NeedsApproval ->
                return WalletEngine.ExecOutcome.NeedsApproval
            WalletGate.Decision.Allow -> Unit
        }

        val seed = wallet.solSeedBytesForSigner()
            ?: return WalletEngine.ExecOutcome.Failed(
                "Lille couldn't read her own Solana key from the vault — " +
                    "nothing was signed. Her wallet may need to be restored " +
                    "from backup."
            )
        val fromBytes = Sol.publicKey(seed)
        val recipientBytes = Sol.pubkeyBytes(prepared.recipient)
            ?: run {
                seed.fill(0)
                return WalletEngine.ExecOutcome.Failed(
                    "The recipient no longer reads as a Solana address. " +
                        "Nothing was signed."
                )
            }
        val message = Sol.spendMessage(plan, fromBytes, recipientBytes, prepared.amountRaw)
        val rawTx = Sol.buildSignedTx(seed, message)
        seed.fill(0)
        // The signature is the transaction's id on Solana:
        // the first 64 bytes after the count prefix.
        val signature = Base58.encode(rawTx.copyOfRange(1, 65))

        // Broadcast: walk the routes. A route accepts by
        // returning the signature; "already processed" for
        // this exact transaction also means it IS sent.
        var sentSignature: String? = null
        var lastError = "no route answered"
        var blockhashExpired = false
        val encodedTx = Base64.encodeToString(rawTx, Base64.NO_WRAP)
        for (base in RPC_ROUTES) {
            val body = rpcPost(
                base, "sendTransaction",
                JSONArray().put(encodedTx)
                    .put(JSONObject().put("encoding", "base64"))
            )
            if (body == null) {
                lastError = "$base didn't answer"
                continue
            }
            val error = Sol.rpcError(body)
            if (error == null) {
                val result = try {
                    JSONObject(body).optString("result", "")
                } catch (e: Exception) {
                    ""
                }
                if (result == signature) {
                    sentSignature = signature
                } else {
                    lastError = "unreadable broadcast answer from $base"
                }
            } else {
                lastError = error
                if (error.contains("already processed", true)) {
                    sentSignature = signature
                }
                if (error.contains("Blockhash not found", true)) {
                    blockhashExpired = true
                }
            }
            if (sentSignature != null) break
        }
        val sig = sentSignature ?: return WalletEngine.ExecOutcome.Failed(
            if (blockhashExpired) {
                "The network checkpoint (blockhash) this send was built " +
                    "against expired before it was broadcast — nothing was " +
                    "sent. Prepare it again and approve promptly; Solana " +
                    "checkpoints live about a minute."
            } else {
                "The broadcast didn't land on any route ($lastError). If a " +
                    "route did accept it silently, its signature would be " +
                    "$signature — check that signature on a block explorer " +
                    "before trying again."
            }
        )

        prepared.usdCents?.let { cents ->
            store.recordSpend(
                WalletGate.recordSpend(store.capState(), cents, System.currentTimeMillis())
            )
        }
        val summary = "${prepared.amountDisplay} ${prepared.tokenSymbol} → " +
            "${prepared.recipient} on Solana"
        store.recordActivity("SENT", "Sent: $summary", sig)

        // Verify by state: poll the signature's status.
        var confirmed: Boolean? = null
        for (attempt in 0 until 5) {
            try {
                Thread.sleep(3_000)
            } catch (e: InterruptedException) {
                break
            }
            val state = try {
                walkRpc(
                    "getSignatureStatuses",
                    JSONArray().put(JSONArray().put(sig))
                ) { Sol.parseSignatureStatus(it) }.first
            } catch (e: Exception) {
                null
            }
            when (state) {
                Sol.SignatureState.CONFIRMED -> {
                    confirmed = true
                    break
                }
                Sol.SignatureState.FAILED -> {
                    confirmed = false
                    break
                }
                else -> Unit
            }
        }
        return when (confirmed) {
            true -> {
                store.recordActivity("CONFIRMED", "Confirmed on chain: $summary", sig)
                WalletEngine.ExecOutcome.Sent(
                    sig, true,
                    "Sent and confirmed: $summary. Signature: $sig"
                )
            }
            false -> {
                store.recordActivity(
                    "FAILED",
                    "Broadcast but failed on chain: $summary", sig
                )
                WalletEngine.ExecOutcome.Sent(
                    sig, false,
                    "That was broadcast but the network rejected it — the " +
                        "amount is back in her wallet; only the network fee " +
                        "was spent. Signature: $sig"
                )
            }
            null -> WalletEngine.ExecOutcome.Sent(
                sig, null,
                "Sent: $summary. Signature: $sig — confirmation is still " +
                    "pending; her activity list will show it once the " +
                    "network settles."
            )
        }
    }
}
