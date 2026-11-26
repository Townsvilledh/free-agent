package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * Main-wallet transaction requests (v0.13.1 — "sign where
 * you stand", WALLET.md) — the pure half: what Lille may
 * ask the user's Fluid wallet to sign over the WalletConnect
 * session, and the exact words of every refusal.
 *
 * The shape of the feature: Lille prepares a send whose
 * SOURCE is the user's main Fluid wallet. She builds the
 * transaction, sends an eth_sendTransaction session request,
 * and Fluid's OWN approval sheet (rendered in the embedded
 * Fluid sheet inside Lille) is where the user approves.
 * Fluid signs and broadcasts; Lille only reports the result
 * — the transaction hash, or Fluid's answer verbatim.
 *
 * THE DISCIPLINES, in code:
 * - Main funds are NEVER hers: no caps, no autonomous path,
 *   no approval she can manufacture. Every main-wallet send
 *   is approved by the user in Fluid's sheet, every time.
 *   Her companion wallet (WalletGate/WalletEngine) is a
 *   separate flow and stays the default source.
 * - Payload discipline: Fluid decodes what it is asked to
 *   sign, and Lille asks only for what Fluid can decode —
 *   a plain native send, or an ERC-20 transfer /
 *   transferFrom / approve call. Anything else (contract
 *   creation, an unknown function selector, a malformed
 *   call) is refused HERE, before any request is sent,
 *   with the reason in plain language.
 * - Degradation is honest: a session that granted only
 *   personal_sign (older Fluid behaviour, or a narrowed
 *   approval) still proves identity — the login works —
 *   but transaction requests report UNAVAILABLE with the
 *   remedy named. Nothing crashes, nothing pretends.
 */
object FluidTx {

    /** One prepared main-wallet send, ready to be requested
     *  over the session. Built by WalletEngine's Fluid
     *  prepare path from live state (recipient resolved,
     *  amount computed); carries everything the request and
     *  the user's chat summary need. */
    data class MainSend(
        val chainKey: String,
        /** CAIP-2 chain the request is sent on. */
        val chainId: String,
        val tokenSymbol: String,
        val amountRaw: BigInteger,
        /** Human amount exactly as prepared, e.g. "2.5". */
        val amountDisplay: String,
        /** The session account the funds leave from — the
         *  verified Fluid identity's address. */
        val fromAddress: String,
        /** Final recipient (checksummed). */
        val recipient: String,
        /** "address", or the Fluid name the user typed. */
        val recipientLabel: String,
        /** The transaction's `to`: the recipient for a
         *  native send, the token contract for an ERC-20
         *  send. */
        val to: String,
        /** Wei carried by the transaction itself: the amount
         *  for a native send, zero for an ERC-20 send. */
        val valueWei: BigInteger,
        /** Calldata, "0x" for a plain native send. */
        val dataHex: String,
        /** USD value when a price route priced it (shown on
         *  the chat summary; Fluid's sheet shows its own
         *  numbers, including its live fee estimate). */
        val usdCents: Long?
    )

    /** What a live session actually granted, read from the
     *  SDK's session record by FluidLogin: the methods on
     *  the eip155 namespace and the chain ids (numeric part
     *  of the CAIP-10 accounts) the identity's account
     *  appears on. */
    data class Granted(
        val methods: Set<String>,
        val accountChains: Set<Long>
    )

    /**
     * Can this session carry a main-wallet send on
     * [chainKey]? It needs BOTH the eth_sendTransaction
     * method granted AND the identity's account present on
     * that chain. A personal_sign-only session fails the
     * first test — that is the designed degradation, not an
     * error.
     */
    fun txAvailable(granted: Granted, chainKey: String): Boolean {
        if (FluidGate.METHOD_SEND_TRANSACTION !in granted.methods) return false
        val chain = WalletGate.chainByKey(chainKey) ?: return false
        return chain.chainId in granted.accountChains
    }

    /** Why a main-wallet send can't be requested on this
     *  session/chain, in plain language, with the remedy.
     *  Null when [txAvailable] is true. */
    fun txUnavailableReason(granted: Granted, chainKey: String): String? {
        if (txAvailable(granted, chainKey)) return null
        val chainLabel = WalletGate.chainByKey(chainKey)?.label ?: chainKey
        return when {
            FluidGate.METHOD_SEND_TRANSACTION !in granted.methods ->
                "Your Fluid connection was approved for sign-in only, so " +
                    "Lille can't ask it to send. Reconnect your Fluid name " +
                    "and approve the wider connection request — sign-in " +
                    "plus transaction requests — and main-wallet sends " +
                    "work here. (Disconnect in Settings → Fluid name, " +
                    "then connect again.) Nothing was sent."
            else ->
                "Your Fluid connection doesn't include your account on " +
                    "$chainLabel, so Lille can't request a send there. " +
                    "Reconnect your Fluid name and approve the connection " +
                    "with every chain included. Nothing was sent."
        }
    }

    // ---------------- payload discipline ----------------

    /** The ERC-20 selectors Fluid can decode, computed from
     *  their signatures — never hand-typed hex. */
    fun transferSelector(): String =
        "0x" + Evm.bytesToHex(Evm.selector("transfer(address,uint256)"))

    fun transferFromSelector(): String =
        "0x" + Evm.bytesToHex(Evm.selector("transferFrom(address,address,uint256)"))

    fun approveSelector(): String =
        "0x" + Evm.bytesToHex(Evm.selector("approve(address,uint256)"))

    /** Total calldata length (bytes) each allowed call must
     *  have exactly: selector + its 32-byte words. */
    private fun expectedDataBytes(selectorHex: String): Int? = when (selectorHex) {
        transferSelector(), approveSelector() -> 4 + 32 + 32
        transferFromSelector() -> 4 + 32 + 32 + 32
        else -> null
    }

    /**
     * THE payload gate, applied to every main-wallet request
     * before it is sent — and the only shapes Lille's own
     * builder ever produces. Returns null when the call is
     * one Fluid can decode (a plain native send, or an
     * ERC-20 transfer / transferFrom / approve of exactly
     * the right shape); otherwise the plain-language refusal,
     * which the caller shows INSTEAD of sending anything.
     */
    fun validateCall(to: String?, dataHex: String?): String? {
        if (to.isNullOrBlank()) {
            return "That request has no destination address — it would " +
                "create a contract. Lille only asks your Fluid wallet for " +
                "plain sends and ERC-20 transfers, never contract " +
                "creation. Nothing was sent."
        }
        if (!Evm.isAddress(to)) {
            return "\"$to\" isn't a wallet or contract address Lille can " +
                "read. Nothing was sent."
        }
        val data = (dataHex ?: "0x").trim()
        if (data == "0x" || data.isEmpty()) return null // plain native send
        if (!data.startsWith("0x") || data.length < 10) {
            return "That request's data isn't readable calldata. Lille " +
                "only asks your Fluid wallet for plain sends and ERC-20 " +
                "transfer, transferFrom and approve calls. Nothing was sent."
        }
        val selectorHex = data.substring(0, 10).lowercase()
        val expected = expectedDataBytes(selectorHex)
            ?: return "That request calls a function Lille can't verify " +
                "($selectorHex). She only asks your Fluid wallet for plain " +
                "sends and ERC-20 transfer, transferFrom and approve " +
                "calls — anything else is refused before it is sent. " +
                "Nothing was sent."
        val actualBytes = (data.length - 2) / 2
        if ((data.length - 2) % 2 != 0 || actualBytes != expected) {
            return "That ERC-20 call is the wrong shape (it should be " +
                "$expected bytes of calldata). Lille won't ask your Fluid " +
                "wallet to sign a malformed call. Nothing was sent."
        }
        return null
    }

    /** The payload for a prepared send: native → the
     *  recipient, the amount as value, no data; ERC-20 →
     *  the token contract, zero value, transfer calldata. */
    fun payloadFor(
        token: WalletGate.Token,
        recipient: String,
        amountRaw: BigInteger
    ): Triple<String, BigInteger, String> {
        return if (token.contract == null) {
            Triple(recipient, amountRaw, "0x")
        } else {
            Triple(
                token.contract,
                BigInteger.ZERO,
                "0x" + Evm.bytesToHex(Evm.erc20TransferData(recipient, amountRaw))
            )
        }
    }

    /**
     * The eth_sendTransaction params, as the JSON string
     * SignClient.request carries: one transaction object
     * with from / to / value (hex quantity) / data. Gas and
     * nonce are Fluid's to fill — it is Fluid's wallet, and
     * its sheet shows its own live fee estimate.
     */
    fun txParamsJson(send: MainSend): String {
        val tx = JSONObject()
            .put("from", send.fromAddress)
            .put("to", send.to)
            .put("value", "0x" + send.valueWei.toString(16))
            .put("data", send.dataHex)
        return JSONArray().put(tx).toString()
    }

    // ---------------- the deep link ----------------

    const val FLUID_BASE_URL = "https://fluidwallet.dpdns.org"

    /**
     * Fluid's #wc= deep link (Fluid build stamp 4d7f509193,
     * 2026-11-26): opening this URL lands Fluid straight on
     * its WalletConnect panel with the pairing code prefilled
     * and the pairing started — no paste step. The pairing
     * URI is encoded with JavaScript's encodeURIComponent
     * semantics, exactly as Fluid's side reads it.
     */
    fun deepLink(wcUri: String): String =
        FLUID_BASE_URL + "/#wc=" + encodeUriComponent(wcUri)

    /** encodeURIComponent, faithfully: everything except
     *  A–Z a–z 0–9 and - _ . ! ~ * ' ( ) is percent-encoded
     *  as UTF-8 bytes, uppercase hex. */
    fun encodeUriComponent(value: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
            "abcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val ch = b.toChar()
            if (b < 128 && ch in unreserved) {
                sb.append(ch)
            } else {
                sb.append('%').append("%02X".format(b))
            }
        }
        return sb.toString()
    }

    // ---------------- plain-language texts ----------------

    /** The chat summary shown before the request goes out:
     *  what will move, from the main wallet, and where the
     *  approval happens. */
    fun summaryText(send: MainSend): String {
        val sb = StringBuilder()
        sb.append(
            "From your Fluid wallet: ${send.amountDisplay} " +
                "${send.tokenSymbol} to ${send.recipient}"
        )
        if (send.recipientLabel != "address") {
            sb.append(" (@${send.recipientLabel})")
        }
        sb.append(
            " on ${WalletGate.chainByKey(send.chainKey)?.label ?: send.chainKey}."
        )
        if (send.usdCents != null) {
            sb.append(" About ${WalletGate.formatUsd(send.usdCents)}.")
        }
        sb.append(
            "\n\nFluid is opening here — its own sheet shows the fee and " +
                "asks you to approve. Lille can't approve it for you, and " +
                "nothing moves unless you tap Approve in Fluid."
        )
        return sb.toString()
    }

    /** The result, reported in chat exactly as it landed:
     *  a hash is a hash; anything else is Fluid's own
     *  answer, verbatim (a rejection reads as a rejection). */
    fun resultText(send: MainSend, txHash: String): String =
        "Sent from your Fluid wallet: ${send.amountDisplay} " +
            "${send.tokenSymbol} to ${send.recipient}" +
            (if (send.recipientLabel != "address") " (@${send.recipientLabel})" else "") +
            " on ${WalletGate.chainByKey(send.chainKey)?.label ?: send.chainKey}." +
            "\nTransaction: $txHash"

    fun errorText(error: String): String =
        "Fluid answered: $error\nNothing was sent by Lille — if Fluid's " +
            "sheet said no, that answer above is its own."
}
