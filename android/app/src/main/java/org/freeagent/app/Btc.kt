package org.freeagent.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Bitcoin primitives (companion-wallet family 2) — the
 * deterministic half of Lille's own BTC capability: hashes,
 * her native-segwit (P2WPKH) address, address decoding for
 * every standard mainnet form, UTXO selection under
 * Bitcoin's real rules (dust, change, fee by vsize), and
 * BIP-143 signing. Pure Kotlin/JVM: no Android, no network,
 * no third-party library. The secp256k1 point maths is
 * Evm's (proven, test-pinned); everything Bitcoin-specific
 * lives here and is pinned by BtcTest against published
 * vectors — the RIPEMD-160 test strings, the BIP-173
 * bech32 examples, the key-1 address chain — and against
 * an independent implementation of BIP-143 written for
 * cross-checking (the scenario in BtcTest).
 *
 * RIPEMD-160 is implemented in full below: Android's
 * MessageDigest does not provide it, and HASH160
 * (RIPEMD160 ∘ SHA256) is how Bitcoin addresses are made.
 *
 * A private key never rests here: keys pass through as
 * arguments from the vault-backed signer and are never
 * stored, logged, or returned.
 */
object Btc {

    // ---------------- hashes ----------------

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256d(data: ByteArray): ByteArray = sha256(sha256(data))

    fun hash160(data: ByteArray): ByteArray = ripemd160(sha256(data))

    // ---------------- RIPEMD-160 ----------------
    //
    // The reference construction (Dobbertin–Bosselaers–
    // Preneel): 80 steps in two parallel lines, the left
    // line applying f1..f5 in order, the right line in
    // reverse, combined into the 5-word state at the end.

    private val R_LEFT = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
        4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13
    )
    private val R_RIGHT = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
        12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11
    )
    private val S_LEFT = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
        9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6
    )
    private val S_RIGHT = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
        8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11
    )
    private val K_LEFT = intArrayOf(
        0x00000000, 0x5a827999, 0x6ed9eba1,
        0x8f1bbcdc.toInt(), 0xa953fd4e.toInt()
    )
    private val K_RIGHT = intArrayOf(
        0x50a28be6, 0x5c4dd124, 0x6d703ef3, 0x7a6d76e9, 0x00000000
    )

    private fun rmdF(round: Int, x: Int, y: Int, z: Int): Int = when (round) {
        0 -> x xor y xor z
        1 -> (x and y) or (x.inv() and z)
        2 -> (x or y.inv()) xor z
        3 -> (x and z) or (y and z.inv())
        else -> x xor (y or z.inv())
    }

    private fun rol(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    fun ripemd160(message: ByteArray): ByteArray {
        // Padding: 0x80, zeros, then the bit length as a
        // 64-bit little-endian integer; blocks of 64 bytes.
        val bitLen = message.size.toLong() * 8
        val padLen = ((56 - (message.size + 1) % 64) + 64) % 64
        val padded = ByteArray(message.size + 1 + padLen + 8)
        System.arraycopy(message, 0, padded, 0, message.size)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = (bitLen ushr (8 * i)).toByte()
        }

        var h0 = 0x67452301
        var h1 = 0xefcdab89.toInt()
        var h2 = 0x98badcfe.toInt()
        var h3 = 0x10325476
        var h4 = 0xc3d2e1f0.toInt()

        val x = IntArray(16)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val j = offset + 4 * i
                x[i] = (padded[j].toInt() and 0xff) or
                    ((padded[j + 1].toInt() and 0xff) shl 8) or
                    ((padded[j + 2].toInt() and 0xff) shl 16) or
                    ((padded[j + 3].toInt() and 0xff) shl 24)
            }
            var al = h0; var bl = h1; var cl = h2; var dl = h3; var el = h4
            var ar = h0; var br = h1; var cr = h2; var dr = h3; var er = h4
            for (j in 0 until 80) {
                val round = j / 16
                var t = rol(
                    al + rmdF(round, bl, cl, dl) + x[R_LEFT[j]] + K_LEFT[round],
                    S_LEFT[j]
                ) + el
                al = el; el = dl; dl = rol(cl, 10); cl = bl; bl = t
                t = rol(
                    ar + rmdF(4 - round, br, cr, dr) + x[R_RIGHT[j]] + K_RIGHT[round],
                    S_RIGHT[j]
                ) + er
                ar = er; er = dr; dr = rol(cr, 10); cr = br; br = t
            }
            val t = h1 + cl + dr
            h1 = h2 + dl + er
            h2 = h3 + el + ar
            h3 = h4 + al + br
            h4 = h0 + bl + cr
            h0 = t
            offset += 64
        }

        val out = ByteArray(20)
        val words = intArrayOf(h0, h1, h2, h3, h4)
        for (i in 0 until 5) {
            for (j in 0 until 4) {
                out[4 * i + j] = (words[i] ushr (8 * j)).toByte()
            }
        }
        return out
    }

    // ---------------- keys & her address ----------------

    /** The compressed public key for a 32-byte private key,
     *  via Evm's point maths. */
    fun compressedPubkey(privateKey: ByteArray): ByteArray {
        val point = Evm.publicPoint(privateKey)
        val x = toBytes32(point.x)
        val prefix = if (point.y.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix.toByte()) + x
    }

    private fun toBytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val copy = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - copy, out, 32 - copy, copy)
        return out
    }

    /** Her receive address: native segwit (P2WPKH, BIP-173
     *  bech32, witness version 0 over HASH160 of the
     *  compressed pubkey) on mainnet. The only form she
     *  receives to — one address type, no ambiguity. */
    fun addressOf(privateKey: ByteArray): String =
        bech32EncodeSegwit("bc", 0, hash160(compressedPubkey(privateKey)))

    /** The scriptPubKey her own UTXOs carry. */
    fun ownScript(privateKey: ByteArray): ByteArray =
        byteArrayOf(0x00, 0x14) + hash160(compressedPubkey(privateKey))

    // ---------------- Base58Check ----------------

    fun base58CheckEncode(version: Int, payload: ByteArray): String {
        val body = byteArrayOf(version.toByte()) + payload
        val checksum = sha256d(body).copyOfRange(0, 4)
        return Base58.encode(body + checksum)
    }

    /** (version, payload) or null: bad characters, bad
     *  length, or a checksum mismatch all fail closed. */
    fun base58CheckDecode(text: String): Pair<Int, ByteArray>? {
        val raw = Base58.decode(text) ?: return null
        if (raw.size < 5) return null
        val body = raw.copyOfRange(0, raw.size - 4)
        val checksum = raw.copyOfRange(raw.size - 4, raw.size)
        val expected = sha256d(body).copyOfRange(0, 4)
        if (!checksum.contentEquals(expected)) return null
        return (body[0].toInt() and 0xff) to body.copyOfRange(1, body.size)
    }

    // ---------------- Bech32 (BIP-173) ----------------

    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val BECH32_INDEX: Map<Char, Int> =
        BECH32_CHARSET.withIndex().associate { it.value to it.index }

    private fun bech32Polymod(values: List<Int>): Int {
        var chk = 1
        val gen = intArrayOf(
            0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
        )
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code ushr 5 } + listOf(0) + hrp.map { it.code and 31 }

    /** 8-bit → 5-bit regrouping. With [pad] false the
     *  leftover must be zero and short (BIP-173 decoding
     *  rule) — anything else returns null. */
    private fun convertBits(
        data: List<Int>, from: Int, to: Int, pad: Boolean
    ): List<Int>? {
        var acc = 0
        var bits = 0
        val out = mutableListOf<Int>()
        val maxv = (1 shl to) - 1
        val maxAcc = (1 shl (from + to - 1)) - 1
        for (value in data) {
            if (value < 0 || (value ushr from) != 0) return null
            acc = ((acc shl from) or value) and maxAcc
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else {
            if (bits >= from) return null
            if (((acc shl (to - bits)) and maxv) != 0) return null
        }
        return out
    }

    /** Encode a segwit address (BIP-173: bech32, checksum
     *  constant 1 — witness version 0 only; taproot's
     *  bech32m is a different encoding and not used here). */
    fun bech32EncodeSegwit(hrp: String, version: Int, program: ByteArray): String {
        val data = mutableListOf(version)
        data.addAll(
            convertBits(program.map { it.toInt() and 0xff }, 8, 5, true)!!
        )
        val polymod = bech32Polymod(hrpExpand(hrp) + data + List(6) { 0 }) xor 1
        val checksum = (0 until 6).map { (polymod ushr (5 * (5 - it))) and 31 }
        val sb = StringBuilder()
        sb.append(hrp).append('1')
        for (v in data + checksum) sb.append(BECH32_CHARSET[v])
        return sb.toString()
    }

    /** Decode a bech32 string to (hrp, witness version,
     *  witness program), or null on any BIP-173 violation:
     *  mixed case, bad charset, bad checksum, bad padding.
     *  All-uppercase and all-lowercase are both legal. */
    fun bech32DecodeSegwit(text: String): Triple<String, Int, ByteArray>? {
        val lower = text.lowercase()
        if (text != lower && text != text.uppercase()) return null
        if (lower.length < 8 || lower.length > 90) return null
        val split = lower.lastIndexOf('1')
        if (split < 1 || split + 7 > lower.length) return null
        val hrp = lower.substring(0, split)
        val dataPart = lower.substring(split + 1)
        val data = dataPart.map { BECH32_INDEX[it] ?: return null }
        if (bech32Polymod(hrpExpand(hrp) + data) != 1) return null
        val payload = data.dropLast(6)
        if (payload.isEmpty()) return null
        val version = payload[0]
        if (version > 16) return null
        val program5 = payload.drop(1)
        val program = convertBits(program5, 5, 8, false) ?: return null
        if (program.size < 2 || program.size > 40) return null
        if (version == 0 && program.size != 20 && program.size != 32) return null
        return Triple(
            hrp, version,
            program.map { it.toByte() }.toByteArray()
        )
    }

    // ---------------- address decoding ----------------

    /**
     * A decoded recipient address: the scriptPubKey a send
     * to it pays, and the vsize its output occupies (fee
     * maths needs it before anything is built). Only
     * mainnet forms she may pay: P2PKH, P2SH, P2WPKH,
     * P2WSH. Taproot (bc1p…) is NOT decodable here — BIP-173
     * version-0 bech32 cannot carry it, and pretending
     * otherwise would be worse than refusing: the prepare
     * path names the refusal plainly.
     */
    sealed class DecodedAddress {
        abstract val outputVsize: Int
        abstract fun scriptPubKey(): ByteArray

        data class P2PKH(val hash: ByteArray) : DecodedAddress() {
            override val outputVsize = 34
            override fun scriptPubKey(): ByteArray =
                byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14) + hash +
                    byteArrayOf(0x88.toByte(), 0xac.toByte())
        }

        data class P2SH(val hash: ByteArray) : DecodedAddress() {
            override val outputVsize = 32
            override fun scriptPubKey(): ByteArray =
                byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
        }

        data class P2WPKH(val program: ByteArray) : DecodedAddress() {
            override val outputVsize = 31
            override fun scriptPubKey(): ByteArray =
                byteArrayOf(0x00, 0x14) + program
        }

        data class P2WSH(val program: ByteArray) : DecodedAddress() {
            override val outputVsize = 43
            override fun scriptPubKey(): ByteArray =
                byteArrayOf(0x00, 0x20) + program
        }
    }

    /** Decode any mainnet address she may pay, or null.
     *  Testnet (m/n/2…, tb1…) never decodes: her wallet is
     *  mainnet money. */
    fun decodeAddress(text: String): DecodedAddress? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("bc1") || t.startsWith("BC1")) {
            val (hrp, version, program) = bech32DecodeSegwit(t) ?: return null
            if (hrp != "bc" || version != 0) return null
            return when (program.size) {
                20 -> DecodedAddress.P2WPKH(program)
                32 -> DecodedAddress.P2WSH(program)
                else -> null
            }
        }
        val (version, payload) = base58CheckDecode(t) ?: return null
        if (payload.size != 20) return null
        return when (version) {
            0x00 -> DecodedAddress.P2PKH(payload)
            0x05 -> DecodedAddress.P2SH(payload)
            else -> null
        }
    }

    fun isAddress(text: String): Boolean = decodeAddress(text) != null

    // ---------------- UTXOs, selection, fees ----------------

    /** One unspent output of hers, as an Esplora route
     *  served it. [txid] is display order (big-endian hex),
     *  as APIs and explorers show it. */
    data class Utxo(val txid: String, val vout: Int, val valueSats: Long)

    /** Bitcoin's dust threshold for a P2WPKH output: below
     *  this, an output costs more to spend than it is worth
     *  and relays may refuse it. Change below dust is never
     *  created — it folds into the fee instead. */
    const val DUST_SATS: Long = 546

    /** Vsize accounting (BIP-141 weights / 4, rounded up):
     *  a P2WPKH input is 68 vB, a P2WPKH output 31, a P2PKH
     *  output 34, a P2SH output 32, a P2WSH output 43, and
     *  the fixed transaction overhead (version, segwit
     *  marker/flag, counts, locktime) 10. */
    const val INPUT_VSIZE: Long = 68
    const val OVERHEAD_VSIZE: Long = 10
    const val CHANGE_VSIZE: Long = 31

    /** The fee for [inputs] inputs and the given output
     *  vsizes at [rateSatPerVb] sat/vB — rounded UP: a fee
     *  estimate must never round below what the plan
     *  actually pays. */
    fun feeFor(
        inputCount: Int,
        outputVsizes: List<Int>,
        rateSatPerVb: BigDecimal
    ): Long {
        val vsize = OVERHEAD_VSIZE + INPUT_VSIZE * inputCount +
            outputVsizes.sum()
        return rateSatPerVb.multiply(BigDecimal(vsize))
            .setScale(0, java.math.RoundingMode.CEILING)
            .toLong()
    }

    data class SpendPlan(
        val inputs: List<Utxo>,
        val amountSats: Long,
        /** 0 when the change would be dust: the plan then
         *  has no change output and the remainder is fee. */
        val changeSats: Long,
        val feeSats: Long,
        val rateSatPerVb: BigDecimal
    )

    sealed class Selection {
        data class Planned(val plan: SpendPlan) : Selection()
        data class Insufficient(val availableSats: Long, val message: String) :
            Selection()
    }

    /**
     * Choose the UTXOs for a send of [amountSats] to an
     * output of [recipientOutputVsize], change (if any)
     * returning to her own P2WPKH address. Largest-first —
     * simple, deterministic, and it minimises the input
     * count (hence the fee) with the fewest moving parts to
     * audit. The fee is recomputed as inputs accrete: a
     * plan that covers amount + fee with dust-or-better
     * change keeps the change output; one that covers
     * amount + the no-change fee but whose change would be
     * dust drops the change output and folds the remainder
     * into the fee (never exceeding what the with-change
     * plan would have paid in total outlay).
     */
    fun selectUtxos(
        utxos: List<Utxo>,
        amountSats: Long,
        recipientOutputVsize: Int,
        rateSatPerVb: BigDecimal
    ): Selection {
        if (amountSats < DUST_SATS) {
            return Selection.Insufficient(
                utxos.sumOf { it.valueSats },
                "That amount is below Bitcoin's dust limit " +
                    "($DUST_SATS sats) — the network would treat the " +
                    "output as spam. Nothing was prepared."
            )
        }
        val sorted = utxos.sortedWith(
            compareByDescending<Utxo> { it.valueSats }
                .thenBy { it.txid }
                .thenBy { it.vout }
        )
        val picked = mutableListOf<Utxo>()
        var sum = 0L
        for (utxo in sorted) {
            picked.add(utxo)
            sum += utxo.valueSats
            val feeWithChange = feeFor(
                picked.size,
                listOf(recipientOutputVsize, CHANGE_VSIZE.toInt()),
                rateSatPerVb
            )
            if (sum >= amountSats + feeWithChange) {
                val change = sum - amountSats - feeWithChange
                if (change >= DUST_SATS) {
                    return Selection.Planned(
                        SpendPlan(picked.toList(), amountSats, change,
                            feeWithChange, rateSatPerVb)
                    )
                }
                // Change would be dust: fall through to the
                // no-change check below with the same inputs.
            }
            val feeNoChange = feeFor(
                picked.size, listOf(recipientOutputVsize), rateSatPerVb
            )
            if (sum >= amountSats + feeNoChange) {
                return Selection.Planned(
                    SpendPlan(picked.toList(), amountSats, 0L,
                        sum - amountSats, rateSatPerVb)
                )
            }
        }
        val feeMin = feeFor(1, listOf(recipientOutputVsize), rateSatPerVb)
        return Selection.Insufficient(
            sum,
            "She has $sum sats available; sending $amountSats sats " +
                "needs the amount plus a network fee (about $feeMin sats " +
                "at this rate, more with more inputs). Nothing was prepared."
        )
    }

    // ---------------- signing (BIP-143) ----------------

    private fun le32(v: Long): ByteArray = ByteArray(4) {
        (v ushr (8 * it)).toByte()
    }

    private fun le64(v: Long): ByteArray = ByteArray(8) {
        (v ushr (8 * it)).toByte()
    }

    private fun varint(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte()) +
            byteArrayOf(n.toByte(), (n ushr 8).toByte())
        n <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + le32(n)
        else -> byteArrayOf(0xff.toByte()) + le64(n)
    }

    /** One output's serialisation: value ‖ varint(script
     *  length) ‖ script. */
    private fun serializeOutput(valueSats: Long, script: ByteArray): ByteArray =
        le64(valueSats) + varint(script.size.toLong()) + script

    /** The outputs of a planned spend, in order: recipient
     *  first, change (when the plan keeps it) second. */
    fun planOutputs(
        plan: SpendPlan, recipientScript: ByteArray, changeScript: ByteArray
    ): List<Pair<Long, ByteArray>> {
        val out = mutableListOf(plan.amountSats to recipientScript)
        if (plan.changeSats > 0) out.add(plan.changeSats to changeScript)
        return out
    }

    /** An input's outpoint: the txid in wire order (the
     *  display hex reversed) ‖ vout little-endian. */
    private fun outpoint(utxo: Utxo): ByteArray =
        Evm.hexToBytes(utxo.txid).reversedArray() + le32(utxo.vout.toLong())

    /**
     * The BIP-143 signature hash for spending [input] (one
     * of [plan]'s inputs, all of them P2WPKH of hers) on
     * [outputs]: the segwit digest committing to every
     * outpoint, every sequence, every output, and this
     * input's P2PK-style scriptCode, amount, and sequence.
     * Transaction version 2, sequence 0xffffffff, locktime
     * 0, SIGHASH_ALL — the one shape she signs.
     */
    fun sighash(
        plan: SpendPlan,
        input: Utxo,
        outputs: List<Pair<Long, ByteArray>>,
        scriptCode: ByteArray
    ): ByteArray {
        val hashPrevouts = sha256d(
            plan.inputs.fold(ByteArray(0)) { acc, u -> acc + outpoint(u) }
        )
        val hashSequence = sha256d(
            plan.inputs.fold(ByteArray(0)) { acc, _ -> acc + le32(0xffffffffL) }
        )
        val hashOutputs = sha256d(
            outputs.fold(ByteArray(0)) { acc, (v, s) ->
                acc + serializeOutput(v, s)
            }
        )
        val preimage = le32(2) +
            hashPrevouts + hashSequence +
            outpoint(input) +
            varint(scriptCode.size.toLong()) + scriptCode +
            le64(input.valueSats) +
            le32(0xffffffffL) +
            hashOutputs +
            le32(0) +
            le32(1) // SIGHASH_ALL
        return sha256d(preimage)
    }

    /** The P2PK scriptCode BIP-143 signs over for a P2WPKH
     *  input: 76 a9 14 <hash160(pubkey)> 88 ac. */
    fun scriptCodeFor(privateKey: ByteArray): ByteArray =
        byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14) +
            hash160(compressedPubkey(privateKey)) +
            byteArrayOf(0x88.toByte(), 0xac.toByte())

    private fun derEncode(v: BigInteger): ByteArray {
        var raw = v.toByteArray()
        // BigInteger's two's-complement form already carries
        // the sign pad DER needs; strip redundant leading
        // zeros but keep one when the top bit demands it.
        var start = 0
        while (start < raw.size - 1 && raw[start].toInt() == 0 &&
            (raw[start + 1].toInt() and 0x80) == 0
        ) start++
        if (start > 0) raw = raw.copyOfRange(start, raw.size)
        return raw
    }

    fun derSignature(r: BigInteger, s: BigInteger): ByteArray {
        val rb = derEncode(r)
        val sb = derEncode(s)
        val body = byteArrayOf(0x02, rb.size.toByte()) + rb +
            byteArrayOf(0x02, sb.size.toByte()) + sb
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /**
     * Build and sign the whole transaction for [plan]:
     * segwit serialisation (marker 00, flag 01), empty
     * scriptSigs, one witness per input (signature ‖
     * pubkey), version 2, locktime 0. Signing is Evm's
     * RFC-6979 ECDSA — deterministic, no randomness at the
     * moment of signing.
     */
    fun buildSignedTx(
        privateKey: ByteArray,
        plan: SpendPlan,
        recipientScript: ByteArray,
        changeScript: ByteArray
    ): ByteArray {
        val outputs = planOutputs(plan, recipientScript, changeScript)
        val scriptCode = scriptCodeFor(privateKey)
        val pubkey = compressedPubkey(privateKey)
        var tx = le32(2) + byteArrayOf(0x00, 0x01)
        tx += varint(plan.inputs.size.toLong())
        for (input in plan.inputs) {
            tx += outpoint(input)
            tx += varint(0) // empty scriptSig (segwit)
            tx += le32(0xffffffffL)
        }
        tx += varint(outputs.size.toLong())
        for ((value, script) in outputs) {
            tx += serializeOutput(value, script)
        }
        for (input in plan.inputs) {
            val digest = sighash(plan, input, outputs, scriptCode)
            val sig = Evm.sign(privateKey, digest)
            val sigWithType = derSignature(sig.r, sig.s) + byteArrayOf(0x01)
            tx += varint(2)
            tx += varint(sigWithType.size.toLong()) + sigWithType
            tx += varint(pubkey.size.toLong()) + pubkey
        }
        tx += le32(0)
        return tx
    }

    /** The transaction id (display hex) of a serialised
     *  signed transaction: the double-SHA256 of the
     *  witness-stripped form, reversed — computed here from
     *  the plan rather than by re-parsing the raw bytes. */
    fun txidOf(
        plan: SpendPlan,
        recipientScript: ByteArray,
        changeScript: ByteArray
    ): String {
        val outputs = planOutputs(plan, recipientScript, changeScript)
        var stripped = le32(2)
        stripped += varint(plan.inputs.size.toLong())
        for (input in plan.inputs) {
            stripped += outpoint(input)
            stripped += varint(0)
            stripped += le32(0xffffffffL)
        }
        stripped += varint(outputs.size.toLong())
        for ((value, script) in outputs) {
            stripped += serializeOutput(value, script)
        }
        stripped += le32(0)
        return Evm.bytesToHex(sha256d(stripped).reversedArray())
    }

    // ---------------- Esplora route parsing ----------------
    //
    // Pure parsers over route response bodies, shared by the
    // engine and pinned by tests. The honesty rule matches
    // RpcChain's: a malformed body parses to null (didn't
    // serve), NEVER to a plausible-looking zero.

    /** GET /address/:addr/utxo — a JSON array of
     *  {txid, vout, value, status}. Unconfirmed UTXOs are
     *  included: her own incoming change is spendable, and
     *  the alternative (hiding real balance) is the worse
     *  lie. Order is the route's; selection re-sorts. */
    fun parseUtxos(json: String): List<Utxo>? {
        val arr = try {
            JSONArray(json)
        } catch (e: Exception) {
            return null
        }
        val out = mutableListOf<Utxo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return null
            val txid = o.optString("txid", "")
            if (txid.length != 64) return null
            try {
                Evm.hexToBytes(txid)
            } catch (e: Exception) {
                return null
            }
            if (!o.has("vout") || !o.has("value")) return null
            val vout = o.optInt("vout", -1)
            val value = o.optLong("value", -1)
            if (vout < 0 || value < 0) return null
            out.add(Utxo(txid.lowercase(), vout, value))
        }
        return out
    }

    /** mempool.space GET /v1/fees/recommended: sat/vB
     *  estimates per confirmation target. She prices with
     *  halfHourFee (next ~3 blocks) — not the cheapest
     *  (hourFee can stall for hours), not the fastest. */
    fun parseMempoolFeeRate(json: String): BigDecimal? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        if (!root.has("halfHourFee")) return null
        val rate = try {
            BigDecimal(root.get("halfHourFee").toString())
        } catch (e: Exception) {
            return null
        }
        return if (rate.signum() > 0) rate else null
    }

    /** blockstream.info GET /fee-estimates: a JSON object
     *  mapping confirmation targets (blocks) to sat/vB.
     *  She takes the first target at or beyond 3 blocks in
     *  ascending order (the same patience as halfHourFee),
     *  else the slowest listed. */
    fun parseBlockstreamFeeRate(json: String): BigDecimal? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val targets = mutableListOf<Pair<Int, BigDecimal>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val target = key.toIntOrNull() ?: continue
            val rate = try {
                BigDecimal(root.get(key).toString())
            } catch (e: Exception) {
                null
            }
            if (rate != null && rate.signum() > 0) targets.add(target to rate)
        }
        if (targets.isEmpty()) return null
        val sorted = targets.sortedBy { it.first }
        return (sorted.firstOrNull { it.first >= 3 } ?: sorted.last()).second
    }

    /** GET /tx/:txid — confirmation is
     *  status.confirmed == true. A body that doesn't say so
     *  parses to false only when it is a well-formed tx
     *  object naming this txid; anything else is null
     *  (didn't serve). */
    fun parseTxConfirmed(json: String, txid: String): Boolean? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        if (!root.optString("txid", "").equals(txid, ignoreCase = true)) {
            return null
        }
        val status = root.optJSONObject("status") ?: return null
        if (!status.has("confirmed")) return null
        return status.optBoolean("confirmed", false)
    }
}
