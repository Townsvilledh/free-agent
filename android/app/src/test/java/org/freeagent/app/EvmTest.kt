package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Evm (v0.12): the wallet cryptography, pinned by published
 * vectors — Keccak-256 digests, the private-key-1 address,
 * ERC-20 selectors, RLP examples from the Ethereum docs —
 * plus sign→recover round-trips (a signature that doesn't
 * recover to the signer's own address is a failed build).
 */
class EvmTest {

    private val keyOne = ByteArray(32).also { it[31] = 1 }

    @Test
    fun keccakEmptyVector() {
        assertEquals(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            Evm.bytesToHex(Evm.keccak256(ByteArray(0)))
        )
    }

    @Test
    fun keccakAbcVector() {
        assertEquals(
            "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
            Evm.bytesToHex(Evm.keccak256("abc".toByteArray(Charsets.US_ASCII)))
        )
    }

    @Test
    fun privateKeyOneAddress() {
        // The best-known test vector in Ethereum.
        assertEquals(
            "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf",
            Evm.addressOf(keyOne)
        )
    }

    @Test
    fun erc20Selectors() {
        assertEquals("a9059cbb", Evm.bytesToHex(Evm.selector("transfer(address,uint256)")))
        assertEquals("70a08231", Evm.bytesToHex(Evm.selector("balanceOf(address)")))
    }

    @Test
    fun rlpVectors() {
        assertEquals("83646f67", Evm.bytesToHex(Evm.rlp("dog".toByteArray(Charsets.US_ASCII))))
        assertEquals("820400", Evm.bytesToHex(Evm.rlp(BigInteger.valueOf(1024))))
        assertEquals("80", Evm.bytesToHex(Evm.rlp(BigInteger.ZERO)))
        assertEquals("c0", Evm.bytesToHex(Evm.rlp(emptyList<Any>())))
        val nested = listOf(
            emptyList<Any>(),
            listOf(emptyList<Any>()),
            listOf(emptyList<Any>(), listOf(emptyList<Any>()))
        )
        assertEquals("c7c0c1c0c3c0c1c0", Evm.bytesToHex(Evm.rlp(nested)))
    }

    @Test
    fun signRecoverRoundTrip() {
        val hash = Evm.keccak256("lille".toByteArray(Charsets.UTF_8))
        val sig = Evm.sign(keyOne, hash)
        assertEquals(Evm.addressOf(keyOne), Evm.recoverAddress(hash, sig))
    }

    @Test
    fun signingIsDeterministic() {
        // RFC 6979: same key + hash → same signature, always.
        // (Lille's backup-key design leans on this property
        // of ECDSA signers generally; her own signer must
        // have it too.)
        val hash = Evm.keccak256("again".toByteArray(Charsets.UTF_8))
        val a = Evm.sign(keyOne, hash)
        val b = Evm.sign(keyOne, hash)
        assertEquals(a.r, b.r)
        assertEquals(a.s, b.s)
        assertEquals(a.recoveryId, b.recoveryId)
    }

    @Test
    fun signaturesAreLowS() {
        val hash = Evm.keccak256("low-s".toByteArray(Charsets.UTF_8))
        val sig = Evm.sign(keyOne, hash)
        assertTrue(sig.s <= Evm.N.shiftRight(1))
    }

    @Test
    fun signedTx1559RecoversAndIsTyped() {
        val tx = Evm.Tx1559(
            chainId = BigInteger.ONE,
            nonce = BigInteger.ZERO,
            maxPriorityFeePerGas = BigInteger.valueOf(1_000_000_000),
            maxFeePerGas = BigInteger.valueOf(30_000_000_000),
            gasLimit = BigInteger.valueOf(21_000),
            to = Evm.hexToBytes("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"),
            value = BigInteger.valueOf(1_000_000_000_000_000),
            data = ByteArray(0)
        )
        val raw = Evm.signTx(keyOne, tx)
        assertEquals(0x02, raw[0].toInt() and 0xff)
        assertTrue(Evm.txHash(raw).startsWith("0x"))
        assertEquals(66, Evm.txHash(raw).length)
        // The signature embedded in the tx is over the
        // signing hash; sign it directly and recover.
        val sig = Evm.sign(keyOne, Evm.signingHash(tx))
        assertEquals(
            Evm.addressOf(keyOne),
            Evm.recoverAddress(Evm.signingHash(tx), sig)
        )
    }

    @Test
    fun erc20TransferCalldata() {
        val data = Evm.erc20TransferData(
            "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf",
            BigInteger.valueOf(2_500_000)
        )
        assertEquals(68, data.size)
        assertEquals("a9059cbb", Evm.bytesToHex(data.copyOfRange(0, 4)))
        assertEquals(
            "0000000000000000000000007e5f4552091a69125d5dfcb7b8c2659029395bdf",
            Evm.bytesToHex(data.copyOfRange(4, 36))
        )
        assertEquals(
            BigInteger.valueOf(2_500_000),
            BigInteger(1, data.copyOfRange(36, 68))
        )
    }

    @Test
    fun parseUnitsStrict() {
        assertEquals(BigInteger.valueOf(2_500_000), Evm.parseUnits("2.5", 6))
        assertEquals(BigInteger.valueOf(2_000_000), Evm.parseUnits("2", 6))
        assertEquals(
            BigInteger("500000000000000000"),
            Evm.parseUnits("0.5", 18)
        )
        assertEquals(BigInteger.ZERO, Evm.parseUnits("0", 18))
        // Too many fractional digits: refuse, never truncate.
        assertNull(Evm.parseUnits("0.0000001", 6))
        // Ambiguous shapes: refuse.
        assertNull(Evm.parseUnits("2,5", 6))
        assertNull(Evm.parseUnits("1e3", 6))
        assertNull(Evm.parseUnits("-2", 6))
        assertNull(Evm.parseUnits("2.", 6))
        assertNull(Evm.parseUnits("", 6))
        assertNull(Evm.parseUnits("two", 6))
    }

    @Test
    fun formatUnitsRoundTrip() {
        assertEquals("2.5", Evm.formatUnits(BigInteger.valueOf(2_500_000), 6))
        assertEquals("2", Evm.formatUnits(BigInteger.valueOf(2_000_000), 6))
        assertEquals("0.5", Evm.formatUnits(BigInteger("500000000000000000"), 18))
        assertEquals("0", Evm.formatUnits(BigInteger.ZERO, 18))
        assertEquals(
            "0.021",
            Evm.formatUnits(BigInteger.valueOf(21_000), 6)
        )
    }

    @Test
    fun addressValidation() {
        assertTrue(Evm.isAddress("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"))
        assertFalse(Evm.isAddress("7E5F4552091A69125d5DfCb7b8C2659029395Bdf"))
        assertFalse(Evm.isAddress("0x123"))
        assertFalse(Evm.isAddress("0xZZ5F4552091A69125d5DfCb7b8C2659029395Bdf"))
        assertFalse(Evm.isValidPrivateKey(ByteArray(32))) // zero key
        assertTrue(Evm.isValidPrivateKey(keyOne))
    }
}
