package com.sarvarboltaboev.stockbuy

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FakeBackendTest {

    private fun req(qty: Int) = OrderRequest(
        amount = qty, currentPrice = 100.0, instrumentId = "AAPL",
        customerPocketId = "p1", type = OrderType.MARKET, duration = Duration.DAY,
    )

    @Test
    fun sameKeyTwiceMakesOneOrderThatCompletes() = runBlocking {
        val api = FakeBackend(scope = this, balance = 1_000.0, stepDelayMs = 0)
        val a = async { api.createOrderV2(req(3), "key-1") }
        val b = async { api.createOrderV2(req(3), "key-1") }
        assertEquals(a.await().id, b.await().id)
        assertEquals(OrderStatus.COMPLETED, api.orderStatus(a.await().id).first { it == OrderStatus.COMPLETED })
        assertEquals(1_000.0 - orderTotal(3, 100.0), api.balance, 0.001) // charged once
    }

    @Test
    fun buyAboveBalanceFails() = runBlocking {
        val api = FakeBackend(scope = this, balance = 100.0, stepDelayMs = 0)
        val e = assertThrows(ApiException::class.java) { runBlocking { api.createOrderV2(req(3), "key-2") } }
        assertEquals("ABORTED", e.code)
    }

    @Test
    fun heldMoneyBlocksSecondOrder() = runBlocking {
        // 2 orders with different keys; only first fits in balance while it is still pending
        val api = FakeBackend(scope = this, balance = 400.0, stepDelayMs = 50)
        api.createOrderV2(req(3), "key-3")
        val e = assertThrows(ApiException::class.java) { runBlocking { api.createOrderV2(req(3), "key-4") } }
        assertEquals("ABORTED", e.code)
    }
}
