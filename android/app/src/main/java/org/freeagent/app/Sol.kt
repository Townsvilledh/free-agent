package org.freeagent.app

import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Solana primitives (companion-wallet family 3) — the
 * deterministic cryptography and transaction machinery under
 * Lille's own SOL keyset (WALLET.md). Pure Kotlin/JVM: no
 * Android, no network, no third-party library.
 *
 * Ed25519 is implemented here from RFC 8032 (extended
 * coordinates, inversion-free until encoding): the Galaxy
 * A12's Android generation cannot be relied on to expose
 * Ed25519 through the platform providers, and her signer
 * must behave identically on every phone. SHA-512 comes
 * from the JDK. The curve constants are COMPUTED, not
 * typed (d from -121665/121666, I as 2^((q-1)/4), the base
 * point decoded from its canonical compressed form) — a
 * typed 77-digit constant is exactly how reference code
 * lies. Pinned by SolTest: the RFC 8032 §7.1 test vectors
 * (which OpenSSL independently confirms), base58 vectors,
 * and program-derived-address / associated-token-account
 * derivations and a full signed transfer cross-checked
 * against an independent implementation written for the
 * purpose.
 *
 * Also here: base58 addresses, PDA derivation (with the
 * ed25519 on-curve check it stands on), ATA derivation,
 * the legacy transaction-message compiler (account
 * ordering is the fiddly part — it follows the canonical
 * rules: fee payer first, signers before non-signers,
 * writable before readonly, privileges promoted on merge),
 * the three instruction builders she uses (system transfer,
 * SPL transfer, create-ATA), and the JSON-RPC response
 * parsers for her routes.
 *
 * A seed never rests here: seeds pass through as arguments
 * from the vault-backed signer and are never stored,
 * logged, or returned.
 */
object Sol {

    // ---------------- field & curve (RFC 8032 §5.1) ----------------

    val Q: BigInteger = BigInteger.ONE.shiftLeft(255)
        .subtract(BigInteger.valueOf(19))

    /** Group order L = 2^252 + 27742317777372353535851937790883648493. */
    val L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** d = -121665/121666 (mod q), computed, not typed. */
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .mod(Q).multiply(BigInteger.valueOf(121666).modInverse(Q)).mod(Q)

    /** I = 2^((q-1)/4) (mod q) — sqrt(-1), computed. */
    private val I: BigInteger = BigInteger.TWO.modPow(
        Q.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), Q
    )

    /** Extended twisted-Edwards point (X, Y, Z, T) with
     *  x = X/Z, y = Y/Z, T = XY/Z. */
    private data class Point(
        val x: BigInteger, val y: BigInteger,
        val z: BigInteger, val t: BigInteger
    )

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** The base point, decoded from its canonical compressed
     *  form (0x58, then 0x66 × 31) rather than typed digits. */
    private val BASE: Point by lazy {
        decodePoint(byteArrayOf(0x58) + ByteArray(31) { 0x66 })
            ?: throw IllegalStateException("base point must decode")
    }

    private fun pointAdd(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(Q)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(Q)
        val c = BigInteger.TWO.multiply(D).multiply(p.t).multiply(q.t).mod(Q)
        val d2 = BigInteger.TWO.multiply(p.z).multiply(q.z).mod(Q)
        val e = b.subtract(a).mod(Q)
        val f = d2.subtract(c).mod(Q)
        val g = d2.add(c).mod(Q)
        val h = b.add(a).mod(Q)
        // X3 = E·F, Y3 = G·H, Z3 = F·G, T3 = E·H.
        return Point(
            e.multiply(f).mod(Q), g.multiply(h).mod(Q),
            f.multiply(g).mod(Q), e.multiply(h).mod(Q)
        )
    }

    private fun pointDouble(p: Point): Point {
        val a = p.x.multiply(p.x).mod(Q)
        val b = p.y.multiply(p.y).mod(Q)
        val c = BigInteger.TWO.multiply(p.z).multiply(p.z).mod(Q)
        val d = a.negate().mod(Q)
        val e = p.x.add(p.y).multiply(p.x.add(p.y)).subtract(a).subtract(b).mod(Q)
        val g = d.add(b).mod(Q)
        val f = g.subtract(c).mod(Q)
        val h = d.subtract(b).mod(Q)
        // X3 = E·F, Y3 = G·H, Z3 = F·G, T3 = E·H.
        return Point(
            e.multiply(f).mod(Q), g.multiply(h).mod(Q),
            f.multiply(g).mod(Q), e.multiply(h).mod(Q)
        )
    }

    private fun scalarMult(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var bits = scalar
        while (bits.signum() > 0) {
            if (bits.testBit(0)) result = pointAdd(result, addend)
            addend = pointDouble(addend)
            bits = bits.shiftRight(1)
        }
        return result
    }

    private fun encodePoint(p: Point): ByteArray {
        val zInv = p.z.modInverse(Q)
        val x = p.x.multiply(zInv).mod(Q)
        val y = p.y.multiply(zInv).mod(Q)
        val out = leBytes(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Decode a compressed point, or null when the bytes are
     *  not a canonical on-curve point (non-canonical y, no x
     *  for this y, or the forbidden x = 0 with sign set). */
    private fun decodePoint(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val sign = (bytes[31].toInt() and 0x80) != 0
        val yBytes = bytes.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = BigInteger(1, yBytes.reversedArray())
        if (y >= Q) return null
        val u = y.multiply(y).subtract(BigInteger.ONE).mod(Q)
        val v = D.multiply(y).multiply(y).add(BigInteger.ONE).mod(Q)
        // x = (u v^3) (u v^7)^((q-5)/8)  (mod q)
        val v3 = v.multiply(v).multiply(v).mod(Q)
        val v7 = v3.multiply(v3).multiply(v).mod(Q)
        var x = u.multiply(v3).multiply(
            u.multiply(v7).modPow(
                Q.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8)), Q
            )
        ).mod(Q)
        val vx2 = v.multiply(x).multiply(x).mod(Q)
        when {
            vx2 == u -> Unit
            vx2 == u.negate().mod(Q) -> x = x.multiply(I).mod(Q)
            else -> return null
        }
        if (x.signum() == 0 && sign) return null
        if (x.testBit(0) != sign) x = Q.subtract(x)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(Q))
    }

    /** True when [bytes] is a valid compressed ed25519
     *  point — the check program-derived addresses must
     *  FAIL (a PDA is an address with no key, so it must
     *  sit off the curve). */
    fun isOnCurve(bytes: ByteArray): Boolean = decodePoint(bytes) != null

    private fun leBytes(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray().reversedArray() // to LE
        val out = ByteArray(size)
        val copy = minOf(raw.size, size)
        System.arraycopy(raw, 0, out, 0, copy)
        return out
    }

    private fun leInt(bytes: ByteArray): BigInteger =
        BigInteger(1, bytes.reversedArray())

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    // ---------------- keys, signing (RFC 8032) ----------------

    private fun clampScalar(h: ByteArray): BigInteger {
        val a = h.copyOfRange(0, 32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = (a[31].toInt() and 63).toByte()
        a[31] = (a[31].toInt() or 64).toByte()
        return leInt(a)
    }

    /** Her Solana public key (32 bytes) for a 32-byte seed. */
    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val h = sha512(seed)
        val a = clampScalar(h)
        return encodePoint(scalarMult(BASE, a))
    }

    /** Her Solana address: base58 of the public key. */
    fun addressOf(seed: ByteArray): String = Base58.encode(publicKey(seed))

    /** RFC 8032 signing: deterministic, no randomness. */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val h = sha512(seed)
        val a = clampScalar(h)
        val pub = encodePoint(scalarMult(BASE, a))
        val r = leInt(sha512(h.copyOfRange(32, 64) + message)).mod(L)
        val rEncoded = encodePoint(scalarMult(BASE, r))
        val k = leInt(sha512(rEncoded + pub + message)).mod(L)
        val s = r.add(k.multiply(a)).mod(L)
        return rEncoded + leBytes(s, 32)
    }

    /** Verify an Ed25519 signature — used by the tests to
     *  prove the signer end to end, and available to any
     *  future verify-before-trust path. */
    fun verify(pubkey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (pubkey.size != 32 || signature.size != 64) return false
        val aPoint = decodePoint(pubkey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val rPoint = decodePoint(rBytes) ?: return false
        val s = leInt(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val k = leInt(sha512(rBytes + pubkey + message)).mod(L)
        val left = scalarMult(BASE, s)
        val right = pointAdd(rPoint, scalarMult(aPoint, k))
        // Projective equality: X1/Z1 == X2/Z2 and Y1/Z1 == Y2/Z2.
        return left.x.multiply(right.z).mod(Q) ==
            right.x.multiply(left.z).mod(Q) &&
            left.y.multiply(right.z).mod(Q) ==
                right.y.multiply(left.z).mod(Q)
    }

    // ---------------- addresses & programs ----------------

    fun isAddress(text: String): Boolean {
        val t = text.trim()
        if (t.length < 32 || t.length > 44) return false
        val decoded = Base58.decode(t) ?: return false
        return decoded.size == 32
    }

    /** Decode a base58 public key to its 32 bytes, or null. */
    fun pubkeyBytes(text: String): ByteArray? {
        val decoded = Base58.decode(text.trim()) ?: return null
        return if (decoded.size == 32) decoded else null
    }

    val SYSTEM_PROGRAM: ByteArray = ByteArray(32)
    val TOKEN_PROGRAM: ByteArray =
        Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")!!
    val ATA_PROGRAM: ByteArray =
        Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")!!

    const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"

    /** The network's fixed fee per signature, in lamports —
     *  the fallback when the fee route can't price a built
     *  message (getFeeForMessage normally answers with
     *  exactly this × signature count). */
    const val LAMPORTS_PER_SIGNATURE: Long = 5_000

    /** Rent-exempt minimum for a token account (165 bytes) —
     *  the fallback when the rent route can't answer
     *  (getMinimumBalanceForRentExemption normally serves). */
    const val TOKEN_ACCOUNT_RENT_LAMPORTS: Long = 2_039_280

    // ---------------- PDA / ATA derivation ----------------

    private val PDA_MARKER = "ProgramDerivedAddress".toByteArray(Charsets.US_ASCII)

    /** One PDA candidate: sha256(seeds ‖ programId ‖
     *  marker), valid only when the hash is OFF the curve
     *  (no private key exists for it). Null when on-curve. */
    fun createProgramAddress(
        seeds: List<ByteArray>, programId: ByteArray
    ): ByteArray? {
        var data = ByteArray(0)
        for (seed in seeds) data += seed
        data += programId
        data += PDA_MARKER
        val hash = Btc.sha256(data)
        return if (isOnCurve(hash)) null else hash
    }

    /** The canonical address + bump: bump 255 down to 0,
     *  the first off-curve candidate wins. */
    fun findProgramAddress(
        seeds: List<ByteArray>, programId: ByteArray
    ): Pair<ByteArray, Int> {
        for (bump in 255 downTo 0) {
            val withBump = seeds + listOf(byteArrayOf(bump.toByte()))
            createProgramAddress(withBump, programId)?.let { return it to bump }
        }
        throw IllegalStateException("no viable program address bump")
    }

    /** The associated token account address for (owner,
     *  mint): PDA of [owner, token program, mint] under
     *  the ATA program. */
    fun associatedTokenAddress(owner: ByteArray, mint: ByteArray): ByteArray =
        findProgramAddress(listOf(owner, TOKEN_PROGRAM, mint), ATA_PROGRAM).first

    // ---------------- instructions & messages ----------------

    data class AccountMeta(
        val pubkey: ByteArray,
        val signer: Boolean,
        val writable: Boolean
    )

    data class Instruction(
        val programId: ByteArray,
        val accounts: List<AccountMeta>,
        val data: ByteArray
    )

    /** System-program transfer of [lamports]. */
    fun systemTransfer(
        from: ByteArray, to: ByteArray, lamports: Long
    ): Instruction = Instruction(
        programId = SYSTEM_PROGRAM,
        accounts = listOf(
            AccountMeta(from, signer = true, writable = true),
            AccountMeta(to, signer = false, writable = true)
        ),
        data = le32(2) + le64(lamports)
    )

    /** SPL Token transfer of [amount] base units from
     *  [sourceAta] to [destAta], authorised by [owner]. */
    fun splTransfer(
        sourceAta: ByteArray, destAta: ByteArray,
        owner: ByteArray, amount: BigInteger
    ): Instruction = Instruction(
        programId = TOKEN_PROGRAM,
        accounts = listOf(
            AccountMeta(sourceAta, signer = false, writable = true),
            AccountMeta(destAta, signer = false, writable = true),
            AccountMeta(owner, signer = true, writable = false)
        ),
        data = byteArrayOf(3) + le64(amount)
    )

    /** Create the associated token account [ata] for
     *  (owner, mint), funded by [payer]. The ATA program's
     *  current account layout (no rent sysvar — the program
     *  reads rent internally since v1.0.4). */
    fun createAssociatedTokenAccount(
        payer: ByteArray, ata: ByteArray, owner: ByteArray, mint: ByteArray
    ): Instruction = Instruction(
        programId = ATA_PROGRAM,
        accounts = listOf(
            AccountMeta(payer, signer = true, writable = true),
            AccountMeta(ata, signer = false, writable = true),
            AccountMeta(owner, signer = false, writable = false),
            AccountMeta(mint, signer = false, writable = false),
            AccountMeta(SYSTEM_PROGRAM, signer = false, writable = false),
            AccountMeta(TOKEN_PROGRAM, signer = false, writable = false)
        ),
        data = ByteArray(0)
    )

    private fun le32(v: Long): ByteArray = ByteArray(4) {
        (v ushr (8 * it)).toByte()
    }

    private fun le64(v: Long): ByteArray = ByteArray(8) {
        (v ushr (8 * it)).toByte()
    }

    private fun le64(v: BigInteger): ByteArray {
        val raw = v.toByteArray().reversedArray()
        val out = ByteArray(8)
        System.arraycopy(raw, 0, out, 0, minOf(raw.size, 8))
        return out
    }

    /** Solana's compact-u16 (shortvec) length encoding. */
    fun shortvec(n: Int): ByteArray {
        require(n >= 0) { "shortvec of negative" }
        var v = n
        val out = mutableListOf<Byte>()
        do {
            var b = v and 0x7f
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.add(b.toByte())
        } while (v != 0)
        return out.toByteArray()
    }

    /**
     * Compile a legacy transaction message: the account
     * table is built by merging every instruction's metas
     * with the fee payer, promoting privileges on merge,
     * then ordering — fee payer first, signers before
     * non-signers, writable before readonly within each
     * class (a stable sort over insertion order, the
     * canonical algorithm). The header counts the classes.
     */
    fun compileMessage(
        payer: ByteArray,
        instructions: List<Instruction>,
        blockhash: ByteArray
    ): ByteArray {
        require(blockhash.size == 32) { "blockhash must be 32 bytes" }
        val merged = mutableListOf<AccountMeta>()
        fun add(meta: AccountMeta) {
            val idx = merged.indexOfFirst { it.pubkey.contentEquals(meta.pubkey) }
            if (idx < 0) {
                merged.add(meta)
            } else {
                val old = merged[idx]
                merged[idx] = AccountMeta(
                    old.pubkey,
                    old.signer || meta.signer,
                    old.writable || meta.writable
                )
            }
        }
        add(AccountMeta(payer, signer = true, writable = true))
        for (ix in instructions) {
            add(AccountMeta(ix.programId, signer = false, writable = false))
            for (meta in ix.accounts) add(meta)
        }
        val sorted = merged.sortedWith(
            compareByDescending<AccountMeta> { it.signer }
                .thenByDescending { it.writable }
        )
        val numSigners = sorted.count { it.signer }
        val numReadonlySigned = sorted.count { it.signer && !it.writable }
        val numReadonlyUnsigned = sorted.count { !it.signer && !it.writable }
        val indexOf = HashMap<String, Int>()
        sorted.forEachIndexed { i, meta ->
            indexOf[Evm.bytesToHex(meta.pubkey)] = i
        }
        var out = byteArrayOf(
            numSigners.toByte(),
            numReadonlySigned.toByte(),
            numReadonlyUnsigned.toByte()
        )
        out += shortvec(sorted.size)
        for (meta in sorted) out += meta.pubkey
        out += blockhash
        out += shortvec(instructions.size)
        for (ix in instructions) {
            out += indexOf[Evm.bytesToHex(ix.programId)]!!.toByte()
            out += shortvec(ix.accounts.size)
            for (meta in ix.accounts) {
                out += indexOf[Evm.bytesToHex(meta.pubkey)]!!.toByte()
            }
            out += shortvec(ix.data.size)
            out += ix.data
        }
        return out
    }

    /** Sign a compiled message and serialise the whole
     *  transaction: shortvec signature count, the
     *  signatures (in account-table signer order — her key
     *  is the only signer in every transaction she builds),
     *  then the message bytes. */
    fun buildSignedTx(seed: ByteArray, message: ByteArray): ByteArray {
        val signature = sign(seed, message)
        return shortvec(1) + signature + message
    }

    // ---------------- spend plans ----------------

    enum class SpendKind { NATIVE, SPL }

    /** A fully-planned Solana spend: the blockhash it was
     *  built against, the fee the route priced for the built
     *  message, and — for an SPL send whose recipient has no
     *  token account yet — the rent her SOL will pay to open
     *  it (the create instruction rides in the same
     *  transaction). Built only by the engine's prepare path
     *  from live state; the signer rebuilds exactly this. */
    data class SpendPlan(
        val kind: SpendKind,
        /** SPL only: the mint, as base58. */
        val mint: String?,
        val sourceAta: ByteArray?,
        val destAta: ByteArray?,
        val createDestAta: Boolean,
        /** The blockhash, base58 as the route served it. */
        val blockhash: String,
        val feeLamports: Long,
        val rentLamports: Long
    )

    /** The instructions of a planned spend, in order:
     *  create the recipient's token account first (when the
     *  plan says so), then the transfer. */
    fun spendInstructions(
        plan: SpendPlan,
        fromPubkey: ByteArray,
        recipientPubkey: ByteArray,
        amountRaw: BigInteger
    ): List<Instruction> = when (plan.kind) {
        SpendKind.NATIVE -> listOf(
            systemTransfer(fromPubkey, recipientPubkey, amountRaw.toLong())
        )
        SpendKind.SPL -> {
            val out = mutableListOf<Instruction>()
            if (plan.createDestAta) {
                out.add(
                    createAssociatedTokenAccount(
                        payer = fromPubkey,
                        ata = plan.destAta!!,
                        owner = recipientPubkey,
                        mint = pubkeyBytes(plan.mint!!)!!
                    )
                )
            }
            out.add(
                splTransfer(
                    plan.sourceAta!!, plan.destAta!!,
                    fromPubkey, amountRaw
                )
            )
            out
        }
    }

    /** The compiled message of a planned spend — prepare
     *  builds it to price the fee; the signer rebuilds the
     *  identical bytes from the same plan. */
    fun spendMessage(
        plan: SpendPlan,
        fromPubkey: ByteArray,
        recipientPubkey: ByteArray,
        amountRaw: BigInteger
    ): ByteArray = compileMessage(
        payer = fromPubkey,
        instructions = spendInstructions(plan, fromPubkey, recipientPubkey, amountRaw),
        blockhash = pubkeyBytes(plan.blockhash)
            ?: throw IllegalArgumentException("unreadable blockhash")
    )

    // ---------------- JSON-RPC response parsing ----------------
    //
    // Pure parsers over response bodies, RpcChain's honesty
    // rule: an RPC error object or a malformed body parses
    // to a FAILURE value (null), never to a plausible zero.
    // The one exception is account existence, where the
    // protocol itself answers null for "does not exist" —
    // that is a fact, and it parses to false.

    /** The RPC error message in a body, or null when the
     *  body carries none. */
    fun rpcError(json: String): String? {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return "unreadable response"
        }
        val error = root.optJSONObject("error") ?: return null
        val msg = error.optString("message", "").trim()
        return if (msg.isEmpty()) "RPC error" else msg
    }

    private fun resultObject(json: String): JSONObject? {
        if (rpcError(json) != null) return null
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        return if (root.has("result") && !root.isNull("result")) {
            root.optJSONObject("result")
        } else {
            null
        }
    }

    /** getBalance: result.value in lamports. */
    fun parseLamportBalance(json: String): Long? {
        val result = resultObject(json) ?: return null
        if (!result.has("value") || result.isNull("value")) return null
        return try {
            result.getLong("value")
        } catch (e: Exception) {
            null
        }
    }

    /** getTokenAccountBalance: result.value.amount, the
     *  base-unit string. (An error body — the account does
     *  not exist — parses to null; the engine checks
     *  existence separately with getAccountInfo, so null
     *  here always means "didn't serve".) */
    fun parseTokenAmount(json: String): BigInteger? {
        val result = resultObject(json) ?: return null
        val value = result.optJSONObject("value") ?: return null
        val amount = value.optString("amount", "")
        if (amount.isEmpty()) return null
        return try {
            BigInteger(amount)
        } catch (e: Exception) {
            null
        }
    }

    /** getAccountInfo: true = the account exists, false =
     *  the chain says it does not (result.value is null —
     *  a fact, not a failure), null = didn't serve. */
    fun parseAccountExists(json: String): Boolean? {
        if (rpcError(json) != null) return null
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        if (!root.has("result")) return null
        val result = root.optJSONObject("result") ?: return null
        return !result.isNull("value")
    }

    /** getLatestBlockhash: result.value.blockhash. */
    fun parseBlockhash(json: String): String? {
        val result = resultObject(json) ?: return null
        val value = result.optJSONObject("value") ?: return null
        val hash = value.optString("blockhash", "")
        return if (hash.isEmpty()) null else hash
    }

    /** getFeeForMessage: result.value in lamports (the
     *  protocol may answer null — that parses to null and
     *  the caller falls back to the per-signature fee). */
    fun parseFeeForMessage(json: String): Long? {
        val result = resultObject(json) ?: return null
        if (!result.has("value") || result.isNull("value")) return null
        return try {
            result.getLong("value")
        } catch (e: Exception) {
            null
        }
    }

    /** getMinimumBalanceForRentExemption: the bare result
     *  is a number, not an object. */
    fun parseRentExemption(json: String): Long? {
        if (rpcError(json) != null) return null
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        if (!root.has("result") || root.isNull("result")) return null
        return try {
            root.getLong("result")
        } catch (e: Exception) {
            null
        }
    }

    enum class SignatureState { CONFIRMED, PENDING, FAILED }

    /** getSignatureStatuses for one signature:
     *  CONFIRMED (confirmed or finalized, no error),
     *  FAILED (landed with an error), PENDING (not seen
     *  yet — value[0] is null), null (didn't serve). */
    fun parseSignatureStatus(json: String): SignatureState? {
        val result = resultObject(json) ?: return null
        val value = result.optJSONArray("value") ?: return null
        if (value.length() == 0) return null
        if (value.isNull(0)) return SignatureState.PENDING
        val entry = value.optJSONObject(0) ?: return null
        if (entry.has("err") && !entry.isNull("err")) return SignatureState.FAILED
        return when (entry.optString("confirmationStatus", "")) {
            "confirmed", "finalized" -> SignatureState.CONFIRMED
            else -> SignatureState.PENDING
        }
    }
}
