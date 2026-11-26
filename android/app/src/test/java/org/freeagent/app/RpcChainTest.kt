package org.freeagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * RpcChain (v0.12): JSON-RPC shapes for the wallet's chain
 * reads. The load-bearing property: failures parse to
 * failures — an RPC error can never come out of here
 * looking like a zero balance.
 */
class RpcChainTest {

    @Test
    fun resultParses() {
        val r = RpcChain.parseResult("""{"jsonrpc":"2.0","id":1,"result":"0x123"}""")
        assertTrue(r is RpcChain.RpcResult.Ok)
        assertEquals("0x123", (r as RpcChain.RpcResult.Ok).value)
    }

    @Test
    fun errorParsesAsErrorNeverAsValue() {
        val r = RpcChain.parseResult(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"execution reverted"}}"""
        )
        assertTrue(r is RpcChain.RpcResult.Error)
        assertEquals("execution reverted", (r as RpcChain.RpcResult.Error).message)
    }

    @Test
    fun malformedParsesAsError() {
        assertTrue(RpcChain.parseResult("garbage") is RpcChain.RpcResult.Error)
        assertTrue(RpcChain.parseResult("""{"jsonrpc":"2.0","id":1}""") is RpcChain.RpcResult.Error)
        assertTrue(
            RpcChain.parseResult("""{"jsonrpc":"2.0","id":1,"result":null}""")
                is RpcChain.RpcResult.Error
        )
    }

    @Test
    fun quantities() {
        assertEquals(BigInteger.valueOf(26), RpcChain.parseQuantity("0x1a"))
        assertEquals(BigInteger.ZERO, RpcChain.parseQuantity("0x0"))
        assertNull(RpcChain.parseQuantity("0x"))
        assertNull(RpcChain.parseQuantity("hello"))
        assertEquals("0xff", RpcChain.quantityHex(BigInteger.valueOf(255)))
    }

    @Test
    fun uint256Word() {
        val word = "0x" + "0".repeat(59) + "26e40" // 159520... no: 0x26e40 = 159296
        assertEquals(BigInteger.valueOf(0x26e40), RpcChain.parseUint256Word(word))
        assertNull(RpcChain.parseUint256Word("0x1234"))
    }

    @Test
    fun feeHistoryTakesNextBlockBaseFee() {
        val fee = RpcChain.parseFeeHistoryBaseFee(
            """{"result":{"baseFeePerGas":["0x10","0x20"],"gasUsedRatio":[0.5]}}"""
        )
        assertEquals(BigInteger.valueOf(0x20), fee)
        assertNull(RpcChain.parseFeeHistoryBaseFee("""{"error":{"message":"no"}}"""))
    }

    @Test
    fun receiptStatus() {
        assertEquals(
            true,
            RpcChain.parseReceiptStatus("""{"result":{"status":"0x1","blockNumber":"0x1"}}""")
        )
        assertEquals(
            false,
            RpcChain.parseReceiptStatus("""{"result":{"status":"0x0"}}""")
        )
        // Not mined yet: result is null → unknown, not failure.
        assertNull(RpcChain.parseReceiptStatus("""{"result":null}"""))
    }

    @Test
    fun txHashShape() {
        assertTrue(RpcChain.isTxHash("0x" + "ab".repeat(32)))
        assertFalse(RpcChain.isTxHash("0x1234"))
        assertFalse(RpcChain.isTxHash("ab".repeat(32)))
    }
}
