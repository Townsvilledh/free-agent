package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * JSON-RPC plumbing (v0.12) — the pure half of the wallet's
 * chain reads and broadcasts. WalletEngine walks a chain's
 * public RPC endpoints in order (WalletGate.Chain.rpcUrls);
 * the first endpoint that answers serves, and every result
 * names it. These functions only build request bodies and
 * parse response bodies — no I/O, JVM-pinned.
 *
 * The honesty rule lives here too: an RPC error object, a
 * malformed body, or a missing result all parse to a
 * FAILURE value, never to a plausible-looking zero. A
 * balance that "couldn't load" is a different fact from a
 * balance of 0, and the UI shows them differently.
 */
object RpcChain {

    sealed class RpcResult {
        data class Ok(val value: String, val source: String = "") : RpcResult()
        data class Error(val message: String) : RpcResult()
    }

    fun requestBody(id: Int, method: String, params: JSONArray): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()

    fun paramsOf(vararg items: Any): JSONArray {
        val arr = JSONArray()
        for (item in items) arr.put(item)
        return arr
    }

    /** Parse a JSON-RPC response body into its result string
     *  (hex quantity, hash, hex data…), or the error's
     *  message. */
    fun parseResult(json: String): RpcResult {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return RpcResult.Error("unreadable response")
        }
        val error = root.optJSONObject("error")
        if (error != null) {
            val msg = error.optString("message", "").trim()
            return RpcResult.Error(if (msg.isEmpty()) "RPC error" else msg)
        }
        if (!root.has("result") || root.isNull("result")) {
            return RpcResult.Error("no result in response")
        }
        return RpcResult.Ok(root.get("result").toString())
    }

    /** A hex quantity ("0x1a2b") as a BigInteger; null when
     *  the string isn't one. */
    fun parseQuantity(hex: String): BigInteger? {
        val t = hex.trim()
        if (!t.startsWith("0x") || t.length <= 2) {
            return if (t == "0x0") BigInteger.ZERO else null
        }
        return try {
            BigInteger(t.substring(2), 16)
        } catch (e: Exception) {
            null
        }
    }

    fun quantityHex(value: BigInteger): String = "0x" + value.toString(16)

    /** An eth_call result (32-byte word) as a BigInteger —
     *  the ERC-20 balanceOf shape. */
    fun parseUint256Word(hexData: String): BigInteger? {
        val t = hexData.trim()
        if (!t.startsWith("0x") || t.length != 66) return null
        return try {
            BigInteger(t.substring(2), 16)
        } catch (e: Exception) {
            null
        }
    }

    /** eth_feeHistory: {"baseFeePerGas":["0x…","0x…"], …} —
     *  the LAST entry is the next block's base fee; the
     *  signer uses it as the current estimate. */
    fun parseFeeHistoryBaseFee(json: String): BigInteger? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        if (root.optJSONObject("error") != null) return null
        val result = root.optJSONObject("result") ?: return null
        val fees = result.optJSONArray("baseFeePerGas") ?: return null
        if (fees.length() == 0) return null
        return parseQuantity(fees.optString(fees.length() - 1, ""))
    }

    /** A transaction receipt's status: true = success
     *  (0x1), false = reverted (0x0), null = not a receipt
     *  body / not mined yet shape. */
    fun parseReceiptStatus(json: String): Boolean? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        if (root.optJSONObject("error") != null) return null
        if (!root.has("result") || root.isNull("result")) return null
        val receipt = root.optJSONObject("result") ?: return null
        return when (receipt.optString("status", "")) {
            "0x1" -> true
            "0x0" -> false
            else -> null
        }
    }

    /** A broadcast result is a 0x-prefixed 32-byte hash. */
    fun isTxHash(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("0x") || t.length != 66) return false
        return try {
            Evm.hexToBytes(t); true
        } catch (e: Exception) {
            false
        }
    }
}
