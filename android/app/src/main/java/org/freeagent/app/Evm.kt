package org.freeagent.app

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * EVM primitives (v0.12) — the deterministic cryptography
 * under Lille's own agent wallet (WALLET.md). Pure Kotlin/JVM:
 * no Android, no network, no third-party crypto library. The
 * app ships no web3 SDK (the WalletConnect stack is for the
 * Fluid LOGIN, and its session deliberately carries no
 * transaction method), so her wallet's signing is done here,
 * from first principles, and pinned by published test vectors
 * (EvmTest): Keccak-256 digests, the private-key-1 address,
 * EIP-55 checksums, RLP vectors, the ERC-20 transfer selector,
 * and sign→recover round-trips.
 *
 * What lives here: Keccak-256, secp256k1 (key derivation,
 * RFC 6979 deterministic ECDSA, public-key recovery), RLP,
 * EIP-1559 transaction assembly/signing, ERC-20 transfer
 * calldata, and decimal↔base-unit conversion. What never
 * lives here: a private key at rest — keys pass through as
 * arguments from the vault-backed signer (WalletEngine) and
 * are never stored, logged, or returned by this object.
 */
object Evm {

    // ---------------- hex ----------------

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        var h = hex.trim()
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2)
        require(h.length % 2 == 0) { "odd-length hex" }
        val out = ByteArray(h.length / 2)
        for (i in out.indices) {
            out[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun isAddress(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("0x") || t.length != 42) return false
        return try {
            hexToBytes(t); true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- Keccak-256 ----------------
    // Original Keccak padding (domain byte 0x01), NOT FIPS
    // SHA3-256 (0x06) — Ethereum predates the standard's
    // final padding change. Rate 136 bytes, 24 rounds.

    // Round constants as unsigned bit patterns (several
    // exceed Long.MAX_VALUE, hence UL → Long bit casts).
    private val KECCAK_RC = listOf(
        0x0000000000000001UL, 0x0000000000008082UL, 0x800000000000808aUL,
        0x8000000080008000UL, 0x000000000000808bUL, 0x0000000080000001UL,
        0x8000000080008081UL, 0x8000000000008009UL, 0x000000000000008aUL,
        0x0000000000000088UL, 0x0000000080008009UL, 0x000000008000000aUL,
        0x000000008000808bUL, 0x800000000000008bUL, 0x8000000000008089UL,
        0x8000000000008003UL, 0x8000000000008002UL, 0x8000000000000080UL,
        0x000000000000800aUL, 0x800000008000000aUL, 0x8000000080008081UL,
        0x8000000000008080UL, 0x0000000080000001UL, 0x8000000080008008UL
    ).map { it.toLong() }.toLongArray()

    // Rho rotation amounts for the compact cycle form of
    // Rho+Pi below, indexed by CYCLE POSITION (the reference
    // keccakf_rotc table) — not by lane.
    private val KECCAK_ROTC = intArrayOf(
        1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14,
        27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
    )

    private val KECCAK_PI = intArrayOf(
        10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4,
        15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
    )

    fun keccak256(input: ByteArray): ByteArray {
        val rate = 136
        val state = LongArray(25)
        // pad10*1 with Keccak domain 0x01.
        val padded = ByteArray(((input.size / rate) + 1) * rate)
        System.arraycopy(input, 0, padded, 0, input.size)
        padded[input.size] = 0x01
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() or 0x80).toByte()
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until rate / 8) {
                var lane = 0L
                for (j in 0 until 8) {
                    lane = lane or ((padded[offset + i * 8 + j].toLong() and 0xff) shl (8 * j))
                }
                state[i] = state[i] xor lane
            }
            keccakF(state)
            offset += rate
        }
        val out = ByteArray(32)
        for (i in 0 until 4) {
            for (j in 0 until 8) {
                out[i * 8 + j] = ((state[i] ushr (8 * j)) and 0xff).toByte()
            }
        }
        return out
    }

    private fun keccakF(state: LongArray) {
        val bc = LongArray(5)
        for (round in 0 until 24) {
            // Theta
            for (i in 0 until 5) {
                bc[i] = state[i] xor state[i + 5] xor state[i + 10] xor
                    state[i + 15] xor state[i + 20]
            }
            for (i in 0 until 5) {
                val t = bc[(i + 4) % 5] xor java.lang.Long.rotateLeft(bc[(i + 1) % 5], 1)
                var j = 0
                while (j < 25) {
                    state[j + i] = state[j + i] xor t
                    j += 5
                }
            }
            // Rho Pi (compact cycle form)
            var t = state[1]
            for (i in 0 until 24) {
                val j = KECCAK_PI[i]
                val tmp = state[j]
                state[j] = java.lang.Long.rotateLeft(t, KECCAK_ROTC[i])
                t = tmp
            }
            // Chi
            var j = 0
            while (j < 25) {
                for (i in 0 until 5) bc[i] = state[j + i]
                for (i in 0 until 5) {
                    state[j + i] = state[j + i] xor (bc[(i + 1) % 5].inv() and bc[(i + 2) % 5])
                }
                j += 5
            }
            // Iota
            state[0] = state[0] xor KECCAK_RC[round]
        }
    }

    // ---------------- secp256k1 ----------------

    val P: BigInteger =
        BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N: BigInteger =
        BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX =
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY =
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val HALF_N: BigInteger = N.shiftRight(1)

    data class Point(val x: BigInteger, val y: BigInteger)

    private fun pointAdd(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x) {
            if (a.y != b.y || a.y.signum() == 0) return null // infinity
            return pointDouble(a)
        }
        val slope = (b.y - a.y) * (b.x - a.x).modInverse(P) % P
        val x = (slope * slope - a.x - b.x).mod(P)
        val y = (slope * (a.x - x) - a.y).mod(P)
        return Point(x, y)
    }

    private fun pointDouble(a: Point): Point? {
        if (a.y.signum() == 0) return null
        val three = BigInteger.valueOf(3)
        val slope = (three * a.x * a.x) * (BigInteger.TWO * a.y).modInverse(P) % P
        val x = (slope * slope - BigInteger.TWO * a.x).mod(P)
        val y = (slope * (a.x - x) - a.y).mod(P)
        return Point(x, y)
    }

    private fun pointMul(k: BigInteger, point: Point): Point? {
        var result: Point? = null
        var addend: Point? = point
        var bits = k
        while (bits.signum() > 0) {
            if (bits.testBit(0)) result = pointAdd(result, addend)
            addend = addend?.let { pointDouble(it) }
            bits = bits.shiftRight(1)
        }
        return result
    }

    /** A valid private key: 32 bytes, 0 < k < n. */
    fun isValidPrivateKey(key: ByteArray): Boolean {
        if (key.size != 32) return false
        val k = BigInteger(1, key)
        return k.signum() > 0 && k < N
    }

    fun publicPoint(privateKey: ByteArray): Point {
        require(isValidPrivateKey(privateKey)) { "invalid private key" }
        return pointMul(BigInteger(1, privateKey), Point(GX, GY))
            ?: throw IllegalStateException("point at infinity")
    }

    /** The 20-byte address (raw bytes) for a private key. */
    fun addressBytes(privateKey: ByteArray): ByteArray {
        val point = publicPoint(privateKey)
        val pub = ByteArray(64)
        System.arraycopy(toBytes32(point.x), 0, pub, 0, 32)
        System.arraycopy(toBytes32(point.y), 0, pub, 32, 32)
        val hash = keccak256(pub)
        return hash.copyOfRange(12, 32)
    }

    /** EIP-55 checksummed hex address for a private key. */
    fun addressOf(privateKey: ByteArray): String =
        toChecksumAddress(bytesToHex(addressBytes(privateKey)))

    /** EIP-55: uppercase a hex letter when the matching
     *  nibble of keccak(lowercase-address) is ≥ 8. */
    fun toChecksumAddress(lowerHexNoPrefix: String): String {
        val lower = lowerHexNoPrefix.lowercase().removePrefix("0x")
        val hash = bytesToHex(keccak256(lower.toByteArray(Charsets.US_ASCII)))
        val sb = StringBuilder("0x")
        for (i in lower.indices) {
            val c = lower[i]
            sb.append(if (c in 'a'..'f' && hash[i] >= '8') c.uppercaseChar() else c)
        }
        return sb.toString()
    }

    private fun toBytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val copy = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - copy, out, 32 - copy, copy)
        return out
    }

    // ---- ECDSA signing (RFC 6979 deterministic k, HMAC-SHA256) ----

    data class Signature(val r: BigInteger, val s: BigInteger, val recoveryId: Int)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** RFC 6979 §3.2 with HMAC-SHA256, as Ethereum signers
     *  use it: the same key + hash always produce the same
     *  signature — which is also what makes Lille's backup
     *  key derivation (BackupCrypto) sound. */
    fun sign(privateKey: ByteArray, hash: ByteArray): Signature {
        require(isValidPrivateKey(privateKey)) { "invalid private key" }
        require(hash.size == 32) { "hash must be 32 bytes" }
        val d = BigInteger(1, privateKey)
        val z = BigInteger(1, hash)
        // RFC 6979 key derivation.
        var v = ByteArray(32) { 0x01 }
        var k = ByteArray(32) { 0x00 }
        val bx = toBytes32(d) + toBytes32(z.mod(N))
        k = hmacSha256(k, v + byteArrayOf(0x00) + bx)
        v = hmacSha256(k, v)
        k = hmacSha256(k, v + byteArrayOf(0x01) + bx)
        v = hmacSha256(k, v)
        while (true) {
            v = hmacSha256(k, v)
            val candidate = BigInteger(1, v)
            if (candidate.signum() > 0 && candidate < N) {
                val point = pointMul(candidate, Point(GX, GY))
                    ?: throw IllegalStateException("point at infinity")
                val r = point.x.mod(N)
                if (r.signum() != 0) {
                    var s = candidate.modInverse(N) * (z + r * d) % N
                    if (s.signum() != 0) {
                        var recoveryId = (point.y.testBit(0)).let { if (it) 1 else 0 } +
                            (if (point.x >= N) 2 else 0)
                        // Low-s (EIP-2): flip s and the parity bit.
                        if (s > HALF_N) {
                            s = N - s
                            recoveryId = recoveryId xor 1
                        }
                        return Signature(r, s, recoveryId)
                    }
                }
            }
            k = hmacSha256(k, v + byteArrayOf(0x00))
            v = hmacSha256(k, v)
        }
    }

    /** Recover the signer's checksummed address from a
     *  signature over [hash] — used by the tests to prove the
     *  signer end to end, and available to any future
     *  verify-before-trust path. */
    fun recoverAddress(hash: ByteArray, sig: Signature): String {
        val r = sig.r
        val s = sig.s
        val recId = sig.recoveryId
        val x = r + N.multiply(BigInteger.valueOf((recId / 2).toLong()))
        require(x < P) { "recovery x out of range" }
        // y² = x³ + 7 (mod p); p ≡ 3 (mod 4), so y = (x³+7)^((p+1)/4).
        val ySquared = (x.multiply(x).multiply(x) + BigInteger.valueOf(7)).mod(P)
        var y = ySquared.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
        if (y.testBit(0) != ((recId and 1) == 1)) y = P - y
        val pointR = Point(x, y)
        // Q = r⁻¹ (s·R − z·G)
        val z = BigInteger(1, hash)
        val rInv = r.modInverse(N)
        val sR = pointMul(s, pointR) ?: throw IllegalStateException("sR infinity")
        val zG = pointMul(z.mod(N), Point(GX, GY))
        val negZG = zG?.let { Point(it.x, P - it.y) }
        val sum = pointAdd(sR, negZG) ?: throw IllegalStateException("sum infinity")
        val q = pointMul(rInv, sum) ?: throw IllegalStateException("Q infinity")
        val pub = ByteArray(64)
        System.arraycopy(toBytes32(q.x), 0, pub, 0, 32)
        System.arraycopy(toBytes32(q.y), 0, pub, 32, 32)
        val digest = keccak256(pub)
        return toChecksumAddress(bytesToHex(digest.copyOfRange(12, 32)))
    }

    // ---------------- RLP ----------------

    /** RLP-encode a string/byte-string, a non-negative integer
     *  (minimal big-endian; 0 is the empty string), or a list
     *  of already-encodable items. */
    fun rlp(item: Any): ByteArray = when (item) {
        is ByteArray -> rlpBytes(item)
        is BigInteger -> {
            require(item.signum() >= 0) { "RLP cannot encode negative integers" }
            if (item.signum() == 0) rlpBytes(ByteArray(0))
            else {
                var raw = item.toByteArray()
                if (raw.size > 1 && raw[0].toInt() == 0) raw = raw.copyOfRange(1, raw.size)
                rlpBytes(raw)
            }
        }
        is List<*> -> {
            var payload = ByteArray(0)
            for (child in item) payload += rlp(child!!)
            rlpHeader(0xc0, payload.size) + payload
        }
        else -> throw IllegalArgumentException("unsupported RLP item: $item")
    }

    private fun rlpBytes(bytes: ByteArray): ByteArray {
        if (bytes.size == 1 && (bytes[0].toInt() and 0xff) < 0x80) return bytes
        return rlpHeader(0x80, bytes.size) + bytes
    }

    private fun rlpHeader(offset: Int, length: Int): ByteArray {
        return if (length < 56) {
            byteArrayOf((offset + length).toByte())
        } else {
            val lenBytes = BigInteger.valueOf(length.toLong()).toByteArray()
                .let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
            byteArrayOf((offset + 55 + lenBytes.size).toByte()) + lenBytes
        }
    }

    // ---------------- transactions (EIP-1559, type 2) ----------------

    /** The fields of a type-2 transaction, in signing order. */
    data class Tx1559(
        val chainId: BigInteger,
        val nonce: BigInteger,
        val maxPriorityFeePerGas: BigInteger,
        val maxFeePerGas: BigInteger,
        val gasLimit: BigInteger,
        val to: ByteArray,          // 20 bytes; empty for contract creation (unused here)
        val value: BigInteger,      // wei (native) or 0 for ERC-20 calls
        val data: ByteArray         // empty for a plain native send
    )

    private fun unsignedFields(tx: Tx1559): List<Any> = listOf(
        tx.chainId, tx.nonce, tx.maxPriorityFeePerGas, tx.maxFeePerGas,
        tx.gasLimit, tx.to, tx.value, tx.data, emptyList<Any>()
    )

    /** keccak(0x02 ‖ rlp(unsigned fields)) — what gets signed. */
    fun signingHash(tx: Tx1559): ByteArray =
        keccak256(byteArrayOf(0x02) + rlp(unsignedFields(tx)))

    /** Sign and serialise: 0x02 ‖ rlp(fields…, yParity, r, s).
     *  Returns the raw transaction bytes for
     *  eth_sendRawTransaction. */
    fun signTx(privateKey: ByteArray, tx: Tx1559): ByteArray {
        val sig = sign(privateKey, signingHash(tx))
        val fields = unsignedFields(tx) + listOf(
            BigInteger.valueOf((sig.recoveryId and 1).toLong()), sig.r, sig.s
        )
        return byteArrayOf(0x02) + rlp(fields)
    }

    /** The transaction hash of a signed raw transaction. */
    fun txHash(rawTx: ByteArray): String = "0x" + bytesToHex(keccak256(rawTx))

    // ---------------- transactions (legacy, type 0, EIP-155) ----------------

    /** The fields of a legacy transaction, in signing order.
     *  BNB Smart Chain (and only it, in Lille's chain set)
     *  still prices gas this way: one gasPrice, no fee
     *  market. Replay protection is EIP-155: the chain id
     *  is folded into v. */
    data class TxLegacy(
        val chainId: BigInteger,
        val nonce: BigInteger,
        val gasPrice: BigInteger,
        val gasLimit: BigInteger,
        val to: ByteArray,          // 20 bytes; empty for contract creation (unused here)
        val value: BigInteger,      // wei (native) or 0 for ERC-20 calls
        val data: ByteArray         // empty for a plain native send
    )

    /** The EIP-155 signing preimage fields: the six classic
     *  fields, then chainId, 0, 0. */
    private fun unsignedFields(tx: TxLegacy): List<Any> = listOf(
        tx.nonce, tx.gasPrice, tx.gasLimit, tx.to, tx.value, tx.data,
        tx.chainId, BigInteger.ZERO, BigInteger.ZERO
    )

    /** keccak(rlp(unsigned fields)) — what gets signed.
     *  Legacy transactions carry no type byte. */
    fun signingHash(tx: TxLegacy): ByteArray = keccak256(rlp(unsignedFields(tx)))

    /** Sign and serialise: rlp(six fields, v, r, s) with
     *  v = chainId × 2 + 35 + yParity (EIP-155). Returns the
     *  raw transaction bytes for eth_sendRawTransaction. */
    fun signTx(privateKey: ByteArray, tx: TxLegacy): ByteArray {
        val sig = sign(privateKey, signingHash(tx))
        val v = tx.chainId.multiply(BigInteger.TWO)
            .add(BigInteger.valueOf(35))
            .add(BigInteger.valueOf((sig.recoveryId and 1).toLong()))
        val fields = listOf(
            tx.nonce, tx.gasPrice, tx.gasLimit, tx.to, tx.value, tx.data,
            v, sig.r, sig.s
        )
        return rlp(fields)
    }

    // ---------------- ERC-20 ----------------

    /** Function selector: first 4 bytes of keccak(signature). */
    fun selector(signature: String): ByteArray =
        keccak256(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)

    /** transfer(address,uint256) calldata. */
    fun erc20TransferData(toAddress: String, amount: BigInteger): ByteArray {
        require(isAddress(toAddress)) { "bad recipient address" }
        val to = hexToBytes(toAddress)
        return selector("transfer(address,uint256)") + toBytes32(BigInteger(1, to)) +
            toBytes32(amount)
    }

    /** balanceOf(address) calldata. */
    fun erc20BalanceOfData(ownerAddress: String): ByteArray {
        require(isAddress(ownerAddress)) { "bad owner address" }
        val owner = hexToBytes(ownerAddress)
        return selector("balanceOf(address)") + toBytes32(BigInteger(1, owner))
    }

    // ---------------- amounts ----------------

    /** Parse a human decimal ("2", "2.5", "0.05") into base
     *  units at [decimals]. Strict: digits with at most one
     *  dot, no sign, no exponent, no thousands separators —
     *  anything else returns null (the caller asks, never
     *  guesses). Excess fractional digits beyond [decimals]
     *  also return null rather than silently truncating. */
    fun parseUnits(text: String, decimals: Int): BigInteger? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (!t.matches(Regex("^\\d+(\\.\\d+)?$"))) return null
        val parts = t.split(".")
        val whole = parts[0]
        val frac = if (parts.size > 1) parts[1] else ""
        if (frac.length > decimals) return null
        val fracPadded = frac.padEnd(decimals, '0')
        val combined = (whole + fracPadded).trimStart('0')
        return try {
            if (combined.isEmpty()) BigInteger.ZERO else BigInteger(combined)
        } catch (e: Exception) {
            null
        }
    }

    /** Format base units back to a plain decimal string
     *  (trailing zeros trimmed, never scientific notation). */
    fun formatUnits(value: BigInteger, decimals: Int): String {
        val negative = value.signum() < 0
        val digits = value.abs().toString().padStart(decimals + 1, '0')
        val whole = digits.substring(0, digits.length - decimals)
        val frac = if (decimals == 0) "" else digits.substring(digits.length - decimals)
            .trimEnd('0')
        val body = if (frac.isEmpty()) whole else "$whole.$frac"
        return if (negative) "-$body" else body
    }
}
