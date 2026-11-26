package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Base64

/**
 * Sol (Solana family): the cryptography and transaction
 * machinery under her SOL keyset, pinned three ways —
 * RFC 8032 §7.1's published test vectors (independently
 * confirmed against OpenSSL while this was written),
 * published base58 vectors, and an independent Python
 * implementation written for cross-checking (the PDA/ATA
 * derivations and the full signed transfer below must
 * agree byte-for-byte).
 */
class SolTest {

    /** RFC 8032 TEST 1 seed. */
    private val rfcSeed: ByteArray = Evm.hexToBytes(
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    )
    private val rfcPubHex =
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    private val rfcAddress = "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z"

    private fun hex(bytes: ByteArray): String = Evm.bytesToHex(bytes)

    // ---- base58 ----

    @Test
    fun base58Vectors() {
        // The canonical example from the base58 literature.
        assertEquals(
            "2NEpo7TZRRrLZSi2U",
            Base58.encode("Hello World!".toByteArray(Charsets.US_ASCII))
        )
        assertEquals(
            "Hello World!",
            String(Base58.decode("2NEpo7TZRRrLZSi2U")!!, Charsets.US_ASCII)
        )
        // Leading zero bytes are leading '1's.
        assertEquals("111", Base58.encode(byteArrayOf(0, 0, 0)))
        assertEquals(3, Base58.decode("111")!!.size)
        assertEquals("", Base58.encode(ByteArray(0)))
        // Strict: anything outside the alphabet fails whole.
        assertNull(Base58.decode("0"))
        assertNull(Base58.decode("O0Il"))
        assertNull(Base58.decode(""))
        // Round-trip a real key.
        val pub = Sol.publicKey(rfcSeed)
        assertTrue(pub.contentEquals(Base58.decode(Base58.encode(pub))!!))
    }

    // ---- Ed25519: RFC 8032 TEST 1 ----

    @Test
    fun rfc8032TestVector1() {
        val pub = Sol.publicKey(rfcSeed)
        assertEquals(rfcPubHex, hex(pub))
        assertEquals(rfcAddress, Sol.addressOf(rfcSeed))
        val sig = Sol.sign(rfcSeed, ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249" +
                "01555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe2465514143" +
                "8e7a100b",
            hex(sig)
        )
        assertTrue(Sol.verify(pub, ByteArray(0), sig))
    }

    @Test
    fun signVerifyRoundTripsAndRefusals() {
        val seed = ByteArray(32) { (it * 7 + 3).toByte() }
        val pub = Sol.publicKey(seed)
        val msg = "lille".toByteArray(Charsets.US_ASCII)
        val sig = Sol.sign(seed, msg)
        assertTrue(Sol.verify(pub, msg, sig))
        // Deterministic: same input, same signature.
        assertTrue(sig.contentEquals(Sol.sign(seed, msg)))
        // A different message or a tampered signature fails.
        assertFalse(Sol.verify(pub, "lille!".toByteArray(), sig))
        val tampered = sig.copyOf()
        tampered[10] = (tampered[10].toInt() xor 1).toByte()
        assertFalse(Sol.verify(pub, msg, tampered))
        assertFalse(Sol.verify(pub, msg, ByteArray(63)))
        // A different seed is a different key.
        val other = Sol.publicKey(ByteArray(32) { (it * 13 + 1).toByte() })
        assertFalse(other.contentEquals(pub))
    }

    @Test
    fun curveMembership() {
        // The base point (its canonical compressed form) is
        // on the curve; a PDA (below) is not — the whole
        // derivation stands on this distinction.
        assertTrue(
            Sol.isOnCurve(byteArrayOf(0x58) + ByteArray(31) { 0x66 })
        )
        assertTrue(Sol.isOnCurve(Sol.publicKey(rfcSeed)))
        assertFalse(Sol.isOnCurve(ByteArray(31)))
    }

    // ---- PDA / ATA derivation (cross-checked) ----

    @Test
    fun associatedTokenAddressMatchesIndependentDerivation() {
        val owner = Sol.pubkeyBytes(rfcAddress)!!
        val mint = Sol.pubkeyBytes(Sol.USDC_MINT)!!
        val (ata, bump) = Sol.findProgramAddress(
            listOf(owner, Sol.TOKEN_PROGRAM, mint), Sol.ATA_PROGRAM
        )
        assertEquals(255, bump)
        assertEquals(
            "HU2S9ByyqbnCD2SVfvr9qoLtDTtyTnMZoMaw1xpr6cTb",
            Base58.encode(ata)
        )
        assertEquals(
            "HU2S9ByyqbnCD2SVfvr9qoLtDTtyTnMZoMaw1xpr6cTb",
            Base58.encode(Sol.associatedTokenAddress(owner, mint))
        )
        // A PDA has no key: it must sit off the curve.
        assertFalse(Sol.isOnCurve(ata))
        // The well-known programs decode to their shapes.
        assertEquals(32, Sol.TOKEN_PROGRAM.size)
        assertTrue(Sol.SYSTEM_PROGRAM.all { it.toInt() == 0 })
        assertEquals(
            "11111111111111111111111111111111",
            Base58.encode(Sol.SYSTEM_PROGRAM)
        )
    }

    // ---- message compilation & the signed transfer ----

    @Test
    fun shortvecEncoding() {
        assertEquals("00", hex(Sol.shortvec(0)))
        assertEquals("7f", hex(Sol.shortvec(127)))
        assertEquals("8001", hex(Sol.shortvec(128)))
        assertEquals("ac02", hex(Sol.shortvec(300)))
        assertEquals("ff7e", hex(Sol.shortvec(16_255)))
    }

    @Test
    fun signedTransferMatchesIndependentImplementation() {
        // From the RFC key, to the compressed base point as
        // a distinct recipient, 1_000_000 lamports, zero
        // blockhash — the scenario the independent
        // implementation serialised and signed.
        val plan = Sol.SpendPlan(
            kind = Sol.SpendKind.NATIVE,
            mint = null, sourceAta = null, destAta = null,
            createDestAta = false,
            blockhash = "11111111111111111111111111111111",
            feeLamports = 5_000, rentLamports = 0
        )
        val from = Sol.publicKey(rfcSeed)
        val recipient = byteArrayOf(0x58) + ByteArray(31) { 0x66 }
        val message = Sol.spendMessage(
            plan, from, recipient, BigInteger.valueOf(1_000_000)
        )
        assertEquals(
            "01000103d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af02" +
                "1a68f707511a586666666666666666666666666666666666666666666666" +
                "666666666666666600000000000000000000000000000000000000000000" +
                "000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000000001020200010c0200000040420f0000000000",
            hex(message)
        )
        val tx = Sol.buildSignedTx(rfcSeed, message)
        assertEquals(
            "ATA/G+DmZ7Oyin0YBodf1U0ILuXlgoSkyOImxAB5ty8J65kt7w+8xtRmszq3" +
                "vZVbN4P3adrz8XRZ8H5oe4yohgoBAAED11qYAYKxCrfVS/7TyWQHOg7hcvPa" +
                "piMlrwIaaPcHURpYZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZgAA" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAAAAAABAgIAAQwCAAAAQEIPAAAAAAA=",
            Base64.getEncoder().encodeToString(tx)
        )
        // The signature (the tx id on Solana) agrees too,
        // and verifies over the message under her key.
        val signature = tx.copyOfRange(1, 65)
        assertEquals(
            "xwvWkxfrhCb5abiNAy3QXZfgY37eeRvGu8yYoCDV3pLz2ZGVogs5SEUdJ4nX" +
                "z5nWf4p8HLx96m3FkdW6gAm8drh",
            Base58.encode(signature)
        )
        assertTrue(Sol.verify(from, message, signature))
    }

    @Test
    fun splSpendInstructionShape() {
        val owner = Sol.publicKey(rfcSeed)
        val mint = Sol.pubkeyBytes(Sol.USDC_MINT)!!
        val sourceAta = Sol.associatedTokenAddress(owner, mint)
        val destAta = Sol.associatedTokenAddress(
            byteArrayOf(0x58) + ByteArray(31) { 0x66 }, mint
        )
        val plan = Sol.SpendPlan(
            kind = Sol.SpendKind.SPL,
            mint = Sol.USDC_MINT,
            sourceAta = sourceAta, destAta = destAta,
            createDestAta = true,
            blockhash = "11111111111111111111111111111111",
            feeLamports = 5_000, rentLamports = Sol.TOKEN_ACCOUNT_RENT_LAMPORTS
        )
        val instructions = Sol.spendInstructions(
            plan, owner, byteArrayOf(0x58) + ByteArray(31) { 0x66 },
            BigInteger.valueOf(5_000_000)
        )
        assertEquals(2, instructions.size)
        // Create first (funded by her, for the recipient),
        // then the transfer itself.
        assertTrue(instructions[0].programId.contentEquals(Sol.ATA_PROGRAM))
        assertEquals(6, instructions[0].accounts.size)
        assertTrue(instructions[1].programId.contentEquals(Sol.TOKEN_PROGRAM))
        assertEquals(3, instructions[1].data[0].toInt())
        assertEquals(
            BigInteger.valueOf(5_000_000),
            BigInteger(1, instructions[1].data.copyOfRange(1, 9).reversedArray())
        )
        // Without the create flag, only the transfer rides.
        val noCreate = Sol.spendInstructions(
            plan.copy(createDestAta = false), owner,
            byteArrayOf(0x58) + ByteArray(31) { 0x66 },
            BigInteger.valueOf(5_000_000)
        )
        assertEquals(1, noCreate.size)
    }

    @Test
    fun addressShapes() {
        assertTrue(Sol.isAddress(rfcAddress))
        assertTrue(Sol.isAddress("11111111111111111111111111111111"))
        assertFalse(Sol.isAddress("abc"))
        assertFalse(Sol.isAddress("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"))
        assertFalse(Sol.isAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
        assertNull(Sol.pubkeyBytes("!!!"))
    }

    // ---- JSON-RPC parsing: never a fake zero ----

    @Test
    fun parseBalanceAndAmounts() {
        assertEquals(
            5_000_000L,
            Sol.parseLamportBalance(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":5000000}}"""
            )
        )
        assertNull(
            Sol.parseLamportBalance(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,
                    "message":"Invalid param"}}"""
            )
        )
        assertEquals(
            BigInteger("123456789"),
            Sol.parseTokenAmount(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":{"amount":"123456789","decimals":6,
                    "uiAmount":123.456789}}}"""
            )
        )
        // The missing-account error body is NOT a zero here:
        // existence is a separate question (below).
        assertNull(
            Sol.parseTokenAmount(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,
                    "message":"could not find account"}}"""
            )
        )
        assertNull(Sol.parseLamportBalance("not json"))
    }

    @Test
    fun parseAccountExistenceIsAChainFact() {
        assertEquals(
            true,
            Sol.parseAccountExists(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":{"data":["","base64"],"executable":false,
                    "lamports":2039280,"owner":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                    "rentEpoch":0}}}"""
            )
        )
        // result.value null = the account does not exist.
        // That is the chain stating a fact (a true zero for
        // a token balance), not a failed read.
        assertEquals(
            false,
            Sol.parseAccountExists(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":null}}"""
            )
        )
        assertNull(
            Sol.parseAccountExists(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,
                    "message":"bad request"}}"""
            )
        )
    }

    @Test
    fun parseBlockhashFeeRent() {
        assertEquals(
            "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N",
            Sol.parseBlockhash(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":{"blockhash":"EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N",
                    "lastValidBlockHeight":100}}}"""
            )
        )
        assertEquals(
            5_000L,
            Sol.parseFeeForMessage(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":5000}}"""
            )
        )
        // The protocol may answer null: no fee is claimed.
        assertNull(
            Sol.parseFeeForMessage(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":null}}"""
            )
        )
        assertEquals(
            2_039_280L,
            Sol.parseRentExemption(
                """{"jsonrpc":"2.0","id":1,"result":2039280}"""
            )
        )
    }

    @Test
    fun parseSignatureStates() {
        assertEquals(
            Sol.SignatureState.CONFIRMED,
            Sol.parseSignatureStatus(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":[{"confirmationStatus":"confirmed",
                    "err":null,"slot":42}]}}"""
            )
        )
        assertEquals(
            Sol.SignatureState.FAILED,
            Sol.parseSignatureStatus(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":[{"confirmationStatus":"finalized",
                    "err":{"InstructionError":[0,"Custom"]},"slot":42}]}}"""
            )
        )
        assertEquals(
            Sol.SignatureState.PENDING,
            Sol.parseSignatureStatus(
                """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},
                    "value":[null]}}"""
            )
        )
        assertNull(
            Sol.parseSignatureStatus(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,
                    "message":"bad"}}"""
            )
        )
    }
}
