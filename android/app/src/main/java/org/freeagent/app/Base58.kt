package org.freeagent.app

/**
 * Base58 (Bitcoin alphabet) — the encoding two of her
 * wallet's families speak: Bitcoin's Base58Check addresses
 * and every Solana address / signature / hash. Pure Kotlin,
 * no Android, no third-party code.
 *
 * Decoding is STRICT: any character outside the alphabet
 * fails the whole decode (null), never a partial result —
 * an address parser that guesses is a funds-loss bug.
 * Pinned by BtcTest / SolTest against published vectors.
 */
object Base58 {

    private const val ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private val INDEX: Map<Char, Int> =
        ALPHABET.withIndex().associate { it.value to it.index }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var zeros = 0
        while (zeros < bytes.size && bytes[zeros].toInt() == 0) zeros++
        // Convert the big-endian byte array to base58 digits,
        // least significant digit first.
        val digits = mutableListOf<Int>()
        for (i in zeros until bytes.size) {
            var carry = bytes[i].toInt() and 0xff
            for (j in digits.indices) {
                carry += digits[j] shl 8
                digits[j] = carry % 58
                carry /= 58
            }
            while (carry > 0) {
                digits.add(carry % 58)
                carry /= 58
            }
        }
        val sb = StringBuilder()
        repeat(zeros) { sb.append('1') }
        for (j in digits.indices.reversed()) sb.append(ALPHABET[digits[j]])
        return sb.toString()
    }

    /** Decode, or null when any character is outside the
     *  alphabet (or the string is empty). Leading '1's are
     *  leading zero bytes, exactly as encoded. */
    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return null
        var zeros = 0
        while (zeros < text.length && text[zeros] == '1') zeros++
        // Bytes, least significant first.
        val bytes = mutableListOf<Int>()
        for (i in zeros until text.length) {
            var carry = INDEX[text[i]] ?: return null
            for (j in bytes.indices) {
                carry += bytes[j] * 58
                bytes[j] = carry and 0xff
                carry = carry shr 8
            }
            while (carry > 0) {
                bytes.add(carry and 0xff)
                carry = carry shr 8
            }
        }
        val out = ByteArray(zeros + bytes.size)
        for (j in bytes.indices) {
            out[out.size - 1 - j] = bytes[j].toByte()
        }
        return out
    }
}
