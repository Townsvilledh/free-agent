package org.freeagent.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lille's Bitcoin wallet — the engine (WALLET.md).
 *
 * The EVM engine's discipline, carried to Bitcoin exactly:
 * balances and fees over ≥2 public routes with the serving
 * source named, prepare from live state only, and the ONLY
 * place her BTC key is ever used — the gated signer, which
 * calls WalletGate.decide() BEFORE it touches the key.
 * NeedsApproval → no signature. Refused → no signature.
 * The model layer holds no reference to this class.
 *
 * Routes are Esplora-shaped (the API mempool.space and
 * blockstream.info both serve): her balance IS her UTXO
 * set — the same read prepares and displays, so the number
 * on screen is the number a send is planned against, never
 * two sources that can disagree. Fees are priced per route
 * (each route's own fee endpoint), the UTXO-serving route
 * first.
 *
 * Outcomes are the wallet-wide types declared in
 * WalletEngine, so chat and the Wallet screen handle every
 * family identically.
 *
 * No sweep here: the sweep-back destination on record is
 * the user's Fluid EVM address, and a Bitcoin send to an
 * EVM address is meaningless — rather than invent a
 * destination, Bitcoin (like Solana) simply has no sweep
 * until a same-family destination exists (WALLET.md).
 */
class BtcEngine(context: Context) {

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
        /** Esplora-shaped public routes, walked in order. */
        val ROUTES = listOf(
            "https://mempool.space/api",
            "https://blockstream.info/api"
        )
    }

    private class RouteException(message: String) : Exception(message)
    private class PrepareException(message: String) : Exception(message)

    // ---------------- routes (executor thread only) ----------------

    /** Her UTXO set from the first route that serves a
     *  parseable answer, with the route that served. A
     *  route answering garbage does not serve — the next
     *  one is tried (Fallback Law). */
    private fun fetchUtxos(address: String): Pair<List<Btc.Utxo>, String> {
        var lastError = "no route answered"
        for (base in ROUTES) {
            val body = prices.get("$base/address/$address/utxo")
            if (body == null) {
                lastError = "$base didn't answer"
                continue
            }
            val utxos = Btc.parseUtxos(body)
            if (utxos == null) {
                lastError = "$base sent an unreadable answer"
                continue
            }
            return utxos to base
        }
        throw RouteException(lastError)
    }

    /** The fee rate (sat/vB) from one route's own fee
     *  endpoint, or null when that route can't price. */
    private fun feeRateFrom(base: String): BigDecimal? {
        return if (base.contains("mempool")) {
            prices.get("$base/v1/fees/recommended")
                ?.let { Btc.parseMempoolFeeRate(it) }
        } else {
            prices.get("$base/fee-estimates")
                ?.let { Btc.parseBlockstreamFeeRate(it) }
        }
    }

    /** The fee rate, preferring the route that served the
     *  UTXOs (its view of the mempool priced the set she is
     *  spending from), then the other route. */
    private fun fetchFeeRate(preferBase: String): BigDecimal {
        val order = listOf(preferBase) + (ROUTES - preferBase)
        for (base in order) {
            feeRateFrom(base)?.let { return it }
        }
        throw RouteException("no route could price the network fee")
    }

    /** Confirmation state from the first route that gives a
     *  parseable answer about this tx: true = confirmed,
     *  false = known but unconfirmed, null = no route could
     *  say (still pending from her point of view). */
    private fun fetchConfirmed(txid: String): Boolean? {
        for (base in ROUTES) {
            val body = prices.get("$base/tx/$txid") ?: continue
            return Btc.parseTxConfirmed(body, txid) ?: continue
        }
        return null
    }

    // ---------------- balances (public) ----------------

    fun loadBalances(
        done: (List<WalletEngine.AssetBalance>) -> Unit
    ) {
        val token = WalletGate.BITCOIN_TOKENS[0]
        val address = wallet.btcAddress()
        if (address == null) {
            done(listOf(WalletEngine.AssetBalance(token, null, null)))
            return
        }
        executor.execute {
            val balance = try {
                val (utxos, source) = fetchUtxos(address)
                WalletEngine.AssetBalance(
                    token,
                    BigInteger.valueOf(utxos.sumOf { it.valueSats }),
                    source
                )
            } catch (e: Exception) {
                WalletEngine.AssetBalance(token, null, null)
            }
            mainHandler.post { done(listOf(balance)) }
        }
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
                    "Lille couldn't reach Bitcoin to prepare that: " +
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
        val token = WalletGate.familyToken(WalletGate.FAMILY_BITCOIN, asset)
            ?: throw PrepareException(
                "Lille doesn't hold $asset on Bitcoin — there she holds " +
                    "only BTC. Nothing was prepared."
            )
        val from = wallet.btcAddress()
            ?: throw PrepareException(
                "Lille doesn't have her Bitcoin wallet yet — create it in " +
                    "Settings → Lille's wallet first. Nothing was sent."
            )
        val recipient = targetRaw.trim()
        val decoded = Btc.decodeAddress(recipient)
            ?: throw PrepareException(
                if (recipient.startsWith("@") || recipient.contains(" ")) {
                    "Fluid names resolve to EVM addresses only, so a name " +
                        "can't receive Bitcoin. Paste the Bitcoin address " +
                        "itself (bc1…, 1…, or 3…). Nothing was prepared."
                } else {
                    "Lille couldn't read \"$recipient\" as a Bitcoin address. " +
                        "She can pay bc1…, 1…, and 3… mainnet addresses " +
                        "(taproot bc1p… isn't supported yet). Nothing was prepared."
                }
            )

        val (utxos, utxoSource) = try {
            fetchUtxos(from)
        } catch (e: RouteException) {
            throw PrepareException(
                "Lille couldn't read her own Bitcoin balance (${e.message}). " +
                    "Nothing was prepared."
            )
        }
        val rate = fetchFeeRate(utxoSource)
        val price = prices.priceFor(token.priceId)

        val amountRaw: BigInteger = when {
            usdAmountText != null -> {
                if (price == null) {
                    throw PrepareException(
                        "Lille couldn't price BTC in dollars right now " +
                            "(every price route is busy), so she can't turn " +
                            "\$$usdAmountText into an amount of BTC. Nothing " +
                            "was prepared — name the amount in BTC itself, " +
                            "or try again shortly."
                    )
                }
                val tokenAmount = BigDecimal(usdAmountText)
                    .divide(price.usd, 18, RoundingMode.HALF_UP)
                val raw = tokenAmount.multiply(BigDecimal.TEN.pow(token.decimals))
                    .setScale(0, RoundingMode.HALF_UP).toBigInteger()
                if (raw.signum() == 0) {
                    throw PrepareException(
                        "\$$usdAmountText of BTC is too small to send. " +
                            "Nothing was prepared."
                    )
                }
                raw
            }
            amountText != null -> Evm.parseUnits(amountText, token.decimals)
                ?: throw PrepareException(
                    "\"$amountText BTC\" isn't an amount Lille can send. " +
                        "Nothing was prepared."
                )
            else -> throw PrepareException("No amount given. Nothing was prepared.")
        }
        if (amountRaw > BigInteger.valueOf(Long.MAX_VALUE)) {
            throw PrepareException("That amount is beyond what she could ever hold. Nothing was prepared.")
        }
        val amountSats = amountRaw.toLong()

        val totalSats = utxos.sumOf { it.valueSats }
        if (amountSats > totalSats) {
            throw PrepareException(
                "She has ${Evm.formatUnits(BigInteger.valueOf(totalSats), 8)} BTC — " +
                    "less than the ${Evm.formatUnits(amountRaw, 8)} asked for. " +
                    "Nothing was prepared."
            )
        }

        val plan = when (val selection = Btc.selectUtxos(
            utxos, amountSats, decoded.outputVsize, rate
        )) {
            is Btc.Selection.Planned -> selection.plan
            is Btc.Selection.Insufficient ->
                throw PrepareException(selection.message)
        }

        val prepared = WalletGate.PreparedTx(
            chainKey = WalletGate.FAMILY_BITCOIN,
            tokenSymbol = token.symbol,
            amountRaw = amountRaw,
            amountDisplay = Evm.formatUnits(amountRaw, token.decimals),
            recipient = recipient,
            recipientLabel = "address",
            usdCents = WalletGate.usdCentsOf(amountRaw, token, price?.usd),
            priceSource = price?.source,
            gasLimit = BigInteger.ZERO,
            maxFeeWei = BigInteger.valueOf(plan.feeSats),
            maxFeeUsdCents = price?.let {
                WalletGate.usdCentsOf(BigInteger.valueOf(plan.feeSats), token, it.usd)
            },
            nonce = BigInteger.ZERO,
            maxPriorityFeePerGas = BigInteger.ZERO,
            maxFeePerGas = BigInteger.ZERO,
            gasPrice = null,
            isSweep = false,
            family = WalletGate.FAMILY_BITCOIN,
            btcPlan = plan
        )
        store.recordActivity(
            "PREPARED",
            "Prepared: ${prepared.amountDisplay} BTC → $recipient on Bitcoin"
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
        val plan = prepared.btcPlan
            ?: return WalletEngine.ExecOutcome.Failed(
                "That prepared send has no Bitcoin plan behind it. Nothing was sent."
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
                        "BTC — ${decision.reason}"
                )
                return WalletEngine.ExecOutcome.Refused(decision.reason)
            }
            WalletGate.Decision.NeedsApproval ->
                return WalletEngine.ExecOutcome.NeedsApproval
            WalletGate.Decision.Allow -> Unit
        }

        val key = wallet.btcKeyBytesForSigner()
            ?: return WalletEngine.ExecOutcome.Failed(
                "Lille couldn't read her own Bitcoin key from the vault — " +
                    "nothing was signed. Her wallet may need to be restored " +
                    "from backup."
            )
        val recipientScript = Btc.decodeAddress(prepared.recipient)?.scriptPubKey()
            ?: run {
                key.fill(0)
                return WalletEngine.ExecOutcome.Failed(
                    "The recipient no longer reads as a Bitcoin address. " +
                        "Nothing was signed."
                )
            }
        val changeScript = Btc.ownScript(key)
        val raw = Btc.buildSignedTx(key, plan, recipientScript, changeScript)
        key.fill(0)
        val rawHex = Evm.bytesToHex(raw)
        val expectedTxid = Btc.txidOf(plan, recipientScript, changeScript)

        // Broadcast: walk the routes. A route accepts by
        // answering 200 with the txid itself (Esplora's
        // contract); an "already in mempool" answer for this
        // exact transaction also means it IS sent.
        var sentTxid: String? = null
        var lastError = "no route answered"
        for (base in ROUTES) {
            try {
                val request = Request.Builder()
                    .url("$base/tx")
                    .post(rawHex.toRequestBody("text/plain".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string()?.trim() ?: ""
                    if (resp.isSuccessful && text.equals(expectedTxid, true)) {
                        sentTxid = expectedTxid
                    } else if (text.contains(expectedTxid, true) ||
                        text.contains("already", true)
                    ) {
                        sentTxid = expectedTxid
                    } else {
                        lastError = text.take(160).ifEmpty {
                            "HTTP ${resp.code} from $base"
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "network error"
            }
            if (sentTxid != null) break
        }
        val txid = sentTxid ?: return WalletEngine.ExecOutcome.Failed(
            "The broadcast didn't land on any route ($lastError). If a route " +
                "did accept it silently, its id would be $expectedTxid — " +
                "check that id on a block explorer before trying again."
        )

        prepared.usdCents?.let { cents ->
            store.recordSpend(
                WalletGate.recordSpend(store.capState(), cents, System.currentTimeMillis())
            )
        }
        val summary = "${prepared.amountDisplay} BTC → ${prepared.recipient} on Bitcoin"
        store.recordActivity("SENT", "Sent: $summary", txid)

        // Verify by state: poll for the block confirmation.
        // Bitcoin blocks land about every ten minutes, so a
        // short poll almost always reports "still pending" —
        // which is the truth, said plainly.
        var confirmed = false
        for (attempt in 0 until 3) {
            try {
                Thread.sleep(5_000)
            } catch (e: InterruptedException) {
                break
            }
            if (fetchConfirmed(txid) == true) {
                confirmed = true
                break
            }
        }
        return if (confirmed) {
            store.recordActivity("CONFIRMED", "Confirmed on chain: $summary", txid)
            WalletEngine.ExecOutcome.Sent(
                txid, true,
                "Sent and confirmed: $summary. Tx: $txid"
            )
        } else {
            WalletEngine.ExecOutcome.Sent(
                txid, null,
                "Sent: $summary. Tx: $txid — Bitcoin confirmation takes a " +
                    "block (about ten minutes); her activity list will show " +
                    "it once the network settles."
            )
        }
    }
}
