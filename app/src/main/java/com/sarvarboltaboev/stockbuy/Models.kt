package com.sarvarboltaboev.stockbuy

data class Instrument(val id: String, val symbol: String, val name: String, val price: Double)

data class Pocket(val id: String, val name: String)

enum class OrderType(val wire: String) { MARKET("market"), LIMIT("limit") }

enum class Duration(val wire: String) { DAY("day"), GTC("gtc") }

/** Body of POST /v2/orders. Mirrors BillingOrderRequest (Flutter) and billing.OrderRequest (proto). */
data class OrderRequest(
    val operationId: String = "1", // 1 = buy
    val currencyId: String = "1",
    val amount: Int,
    val currentPrice: Double,
    val instrumentId: String,
    val customerPocketId: String,
    val type: OrderType,
    val duration: Duration,
    val allowInPrePost: Boolean = false,
)

/** Mirrors billing.OrderResponse: the server only returns the id, order is still pending. */
data class OrderResponse(val id: Long)

/** Mirrors core_service models/order_status.go. */
enum class OrderStatus { PENDING, PROCESSING, COMPLETED, CANCELED, FAILED }

class ApiException(val code: String, message: String) : Exception(message)
