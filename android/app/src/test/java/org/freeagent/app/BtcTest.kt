package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Btc (Bitcoin family): the cryptography and money rules
 * under her BTC keyset, pinned three ways — published test
 * vectors (the RIPEMD-160 test suite, the BIP-173 examples,
 * the key-1 address chain every Bitcoin course teaches),
 * an independent Python implementation of BIP-143 written
 * for cross-checking (the spend scenario below must agree
 * byte-for-byte: sighash, signed transaction, txid), and
 * the selection rules (dust, change, fees) worked by hand.
 */
class BtcTest {

    /** Private key 1 — the most published key in Bitcoin. */
    private val key1: ByteArray =
        ByteArray(32).also { it[31] = 1 }

    private fun hex(bytes: ByteArray): String = Evm.bytesToHex(bytes)

    // ---- RIPEMD-160: the published test suite ----

    @Test
    fun ripemd160PublishedVectors() {
        val vectors = listOf(
            "" to "9c1185a5c5e9fc54612808977ee8f548b2258d31",
            "a" to "0bdc9d2d256b3ee9daae347be6f4dc835a467ffe",
            "abc" to "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc",
            "message digest" to "5d0689ef49d2fae572b881b123a85ffa21595f36",
            "abcdefghijklmnopqrstuvwxyz" to
                "f71c27109c692c1b56bbdceb5b9d2865b3708dbc",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to
                "12a053384a9c0c88e405a06c27dcf49ada62eb2b",
            "The quick brown fox jumps over the lazy dog" to
                "37f332f68db77bd9d7edd4969571ad671cf9dd3b"
        )
        for ((input, expected) in vectors) {
            assertEquals(
                "ripemd160 of \"$input\"",
                expected, hex(Btc.ripemd160(input.toByteArray(Charsets.US_ASCII)))
            )
        }
        // The million-'a' vector: padding across many blocks.
        assertEquals(
            "52783243c1697bdbe16d37f97f68f08325dc1528",
            hex(Btc.ripemd160(ByteArray(1_000_000) { 'a'.code.toByte() }))
        )
    }

    // ---- the key-1 address chain ----

    @Test
    fun key1AddressChain() {
        val pub = Btc.compressedPubkey(key1)
        assertEquals(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            hex(pub)
        )
        val h160 = Btc.hash160(pub)
        assertEquals("751e76e8199196d454941c45d1b3a323f1433bd6", hex(h160))
        // The same key in its legacy form (Base58Check).
        assertEquals(
            "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH",
            Btc.base58CheckEncode(0x00, h160)
        )
        // And in the only form she receives to: P2WPKH.
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            Btc.addressOf(key1)
        )
        assertTrue(
            Btc.ownScript(key1).contentEquals(
                byteArrayOf(0x00, 0x14) + h160
            )
        )
    }

    // ---- bech32 / address decoding ----

    @Test
    fun bech32DecodesBip173Forms() {
        val decoded = Btc.bech32DecodeSegwit(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        )!!
        assertEquals("bc", decoded.first)
        assertEquals(0, decoded.second)
        assertEquals(
            "751e76e8199196d454941c45d1b3a323f1433bd6", hex(decoded.third)
        )
        // All-uppercase is equally legal (BIP-173).
        val upper = Btc.bech32DecodeSegwit(
            "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"
        )!!
        assertEquals(hex(decoded.third), hex(upper.third))
        // Mixed case is not.
        assertNull(
            Btc.bech32DecodeSegwit("bc1qW508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        )
        // A corrupted checksum is not (BIP-173's invalid example).
        assertNull(
            Btc.bech32DecodeSegwit("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kg3g4ty")
        )
    }

    @Test
    fun decodeAddressFormsAndRefusals() {
        val p2wpkh = Btc.decodeAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        assertTrue(p2wpkh is Btc.DecodedAddress.P2WPKH)
        assertEquals(31, p2wpkh!!.outputVsize)
        assertEquals(
            "0014751e76e8199196d454941c45d1b3a323f1433bd6",
            hex(p2wpkh.scriptPubKey())
        )

        val p2pkh = Btc.decodeAddress("1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH")
        assertTrue(p2pkh is Btc.DecodedAddress.P2PKH)
        assertEquals(34, p2pkh!!.outputVsize)
        assertEquals(
            "76a914751e76e8199196d454941c45d1b3a323f1433bd688ac",
            hex(p2pkh.scriptPubKey())
        )

        // P2SH: encoded here, decoded back — version 0x05.
        val sh = Btc.base58CheckEncode(0x05, ByteArray(20) { 0x11 })
        val p2sh = Btc.decodeAddress(sh)
        assertTrue(p2sh is Btc.DecodedAddress.P2SH)
        assertEquals(32, p2sh!!.outputVsize)

        // P2WSH: a 32-byte witness program round-trips.
        val wsh = Btc.bech32EncodeSegwit("bc", 0, ByteArray(32) { 0x22 })
        val p2wsh = Btc.decodeAddress(wsh)
        assertTrue(p2wsh is Btc.DecodedAddress.P2WSH)
        assertEquals(43, p2wsh!!.outputVsize)

        // Testnet never decodes — her wallet is mainnet money.
        assertNull(Btc.decodeAddress("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        // Taproot is refused, not misparsed (bech32m is a
        // different encoding; a version-1 program under
        // bech32's checksum constant does not validate).
        assertNull(
            Btc.decodeAddress(
                "bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297"
            )
        )
        // Garbage and other families' shapes refuse too.
        assertNull(Btc.decodeAddress("not an address"))
        assertNull(Btc.decodeAddress("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"))
        assertFalse(Btc.isAddress(""))
    }

    @Test
    fun base58CheckTamperingFails() {
        val good = Btc.base58CheckEncode(0x00, ByteArray(20) { 0x33 })
        val tampered = good.dropLast(1) +
            if (good.last() == '1') '2' else '1'
        assertNull(Btc.base58CheckDecode(tampered))
        assertNull(Btc.base58CheckDecode("0OIl")) // forbidden chars
    }

    // ---- UTXO selection: Bitcoin's real rules ----

    private fun utxo(txidByte: String, vout: Int, sats: Long) =
        Btc.Utxo(txidByte.repeat(32), vout, sats)

    @Test
    fun selectionMatchesTheCrossCheckScenario() {
        // One 100_000-sat UTXO, send 50_000 to a P2WPKH
        // output at 2 sat/vB: vsize 140, fee 280, change
        // 49_720 — the exact plan the independent BIP-143
        // implementation was fed.
        val result = Btc.selectUtxos(
            listOf(utxo("aa", 0, 100_000)),
            amountSats = 50_000,
            recipientOutputVsize = 31,
            rateSatPerVb = BigDecimal(2)
        )
        val plan = (result as Btc.Selection.Planned).plan
        assertEquals(280L, plan.feeSats)
        assertEquals(49_720L, plan.changeSats)
        assertEquals(1, plan.inputs.size)
    }

    @Test
    fun selectionFoldsDustChangeIntoTheFee() {
        // Change would be 520 sats — below the 546 dust
        // limit — so there is no change output and the
        // remainder becomes fee.
        val result = Btc.selectUtxos(
            listOf(utxo("bb", 1, 50_800)),
            amountSats = 50_000,
            recipientOutputVsize = 31,
            rateSatPerVb = BigDecimal(2)
        )
        val plan = (result as Btc.Selection.Planned).plan
        assertEquals(0L, plan.changeSats)
        assertEquals(800L, plan.feeSats)
    }

    @Test
    fun selectionAccretesInputsAndRecomputesTheFee() {
        val result = Btc.selectUtxos(
            listOf(utxo("cc", 0, 30_000), utxo("dd", 0, 30_000)),
            amountSats = 50_000,
            recipientOutputVsize = 31,
            rateSatPerVb = BigDecimal(2)
        )
        val plan = (result as Btc.Selection.Planned).plan
        assertEquals(2, plan.inputs.size)
        // vsize 10 + 2×68 + 31 + 31 = 208 → fee 416.
        assertEquals(416L, plan.feeSats)
        assertEquals(9_584L, plan.changeSats)
    }

    @Test
    fun selectionRefusals() {
        // Below dust: not a plan at any balance.
        val dust = Btc.selectUtxos(
            listOf(utxo("aa", 0, 1_000_000)),
            amountSats = 500,
            recipientOutputVsize = 31,
            rateSatPerVb = BigDecimal(2)
        )
        assertTrue(dust is Btc.Selection.Insufficient)
        // Not enough, counting the fee.
        val short = Btc.selectUtxos(
            listOf(utxo("aa", 0, 50_100)),
            amountSats = 50_000,
            recipientOutputVsize = 31,
            rateSatPerVb = BigDecimal(2)
        )
        assertTrue(short is Btc.Selection.Insufficient)
        assertEquals(
            50_100L, (short as Btc.Selection.Insufficient).availableSats
        )
    }

    @Test
    fun feeRoundsUpNeverDown() {
        // 140 vB at 1.5 sat/vB = 210 exactly; at 1.51 the
        // true fee is 211.4 — the plan must pay 212, never
        // 211 (an underpaid plan is a stuck transaction).
        assertEquals(
            212L,
            Btc.feeFor(1, listOf(31, 31), BigDecimal("1.51"))
        )
    }

    // ---- signing: byte-for-byte vs the independent implementation ----

    @Test
    fun signedTxMatchesIndependentBip143() {
        val plan = Btc.SpendPlan(
            inputs = listOf(utxo("aa", 0, 100_000)),
            amountSats = 50_000,
            changeSats = 49_720,
            feeSats = 280,
            rateSatPerVb = BigDecimal(2)
        )
        val script = Btc.ownScript(key1) // self-send
        val outputs = Btc.planOutputs(plan, script, script)
        val digest = Btc.sighash(
            plan, plan.inputs[0], outputs, Btc.scriptCodeFor(key1)
        )
        assertEquals(
            "6431efe6636e35d8777dc0c26efc4fc7ac0ee4747db53e04a2375ee430b2" +
                "bfe9",
            hex(digest)
        )
        val raw = Btc.buildSignedTx(key1, plan, script, script)
        assertEquals(
            "02000000000101aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                "aaaaaaaaaaaaaaaaaa0000000000ffffffff0250c3000000000000160014" +
                "751e76e8199196d454941c45d1b3a323f1433bd638c20000000000001600" +
                "14751e76e8199196d454941c45d1b3a323f1433bd60247304402205d2ae9" +
                "425750a9fd594f45b56a1c729ab8c09a8a5876d06a53ad3e87c0851a7702" +
                "203253ebcd5ff877bbbe6a0b984219ba6432426ca2c6a00d9e46d37a9e93" +
                "674d1801210279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d9" +
                "59f2815b16f8179800000000",
            hex(raw)
        )
        // The txid commits to the witness-STRIPPED form.
        // (The full signed transaction above hashes to
        // 97aac8ad…98516 — that is the wtxid, a different id
        // no explorer indexes as the txid. An early draft of
        // the cross-check printed the wtxid under the txid
        // label; the value pinned here is the stripped-form
        // hash, verified by re-stripping the raw bytes.)
        assertEquals(
            "a07bdda79580a0f4c7546e070e3fae8e0b04908a07bfbc74975cf87f98dc" +
                "b936",
            Btc.txidOf(plan, script, script)
        )
        // And the signature itself recovers her own address
        // over the sighash — the signer's internal proof.
        val sig = Evm.sign(key1, digest)
        assertEquals(Evm.addressOf(key1), Evm.recoverAddress(digest, sig))
    }

    // ---- Esplora route parsing: never a fake zero ----

    @Test
    fun parseUtxosHonesty() {
        val body = """
            [{"txid":"${"ab".repeat(32)}","vout":1,"value":42000,
              "status":{"confirmed":true}},
             {"txid":"${"cd".repeat(32)}","vout":0,"value":0,
              "status":{"confirmed":false}}]
        """.trimIndent()
        val utxos = Btc.parseUtxos(body)!!
        assertEquals(2, utxos.size)
        assertEquals(42_000L, utxos[0].valueSats)
        assertEquals(1, utxos[0].vout)
        assertEquals(BigInteger.ZERO, BigInteger.valueOf(utxos[1].valueSats))
        // An empty set is a fact and parses as one.
        assertEquals(0, Btc.parseUtxos("[]")!!.size)
        // Garbage and shape violations don't serve.
        assertNull(Btc.parseUtxos("not json"))
        assertNull(Btc.parseUtxos("""[{"txid":"zz","vout":0,"value":5}]"""))
        assertNull(Btc.parseUtxos("""[{"txid":"${"ab".repeat(32)}","vout":0}]"""))
        assertNull(Btc.parseUtxos("""{"error":"nope"}"""))
    }

    @Test
    fun parseFeeRates() {
        assertEquals(
            BigDecimal(7),
            Btc.parseMempoolFeeRate(
                """{"fastestFee":10,"halfHourFee":7,"hourFee":4,
                    "economyFee":2,"minimumFee":1}"""
            )
        )
        assertNull(Btc.parseMempoolFeeRate("""{"fastestFee":10}"""))
        assertNull(Btc.parseMempoolFeeRate("""{"halfHourFee":0}"""))
        // blockstream: first target at or beyond 3 blocks.
        assertEquals(
            BigDecimal("5.2"),
            Btc.parseBlockstreamFeeRate(
                """{"1":10.5,"3":5.2,"6":3.1,"25":1.0}"""
            )
        )
        // Only faster targets listed → the slowest of them.
        assertEquals(
            BigDecimal("8.0"),
            Btc.parseBlockstreamFeeRate("""{"1":12.0,"2":8.0}""")
        )
        assertNull(Btc.parseBlockstreamFeeRate("""{}"""))
    }

    @Test
    fun parseTxConfirmedHonesty() {
        val txid = "ab".repeat(32)
        assertEquals(
            true,
            Btc.parseTxConfirmed(
                """{"txid":"$txid","status":{"confirmed":true,"block_height":800000}}""",
                txid
            )
        )
        assertEquals(
            false,
            Btc.parseTxConfirmed(
                """{"txid":"$txid","status":{"confirmed":false}}""", txid
            )
        )
        // A body about a different tx, or a shapeless one,
        // says nothing at all.
        assertNull(
            Btc.parseTxConfirmed(
                """{"txid":"${"cd".repeat(32)}","status":{"confirmed":true}}""",
                txid
            )
        )
        assertNull(Btc.parseTxConfirmed("""{"txid":"$txid"}""", txid))
        assertNull(Btc.parseTxConfirmed("Transaction not found", txid))
    }
}
