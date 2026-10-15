package com.sarvarboltaboev.stockbuy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Client side of BillingService. Flutter: FakeApi.billing (billing_client.g.dart). */
interface FakeApi {
    suspend fun createOrderV2(req: OrderRequest, idempotencyKey: String): OrderResponse
    fun orderStatus(id: Long): Flow<OrderStatus>
}

/** Real backend. POST is sent once, never retried: a retry without server dedup = duplicate order. */
class HttpFakeApi(private val rootUrl: String, private val token: String) : FakeApi {

    override suspend fun createOrderV2(req: OrderRequest, idempotencyKey: String): OrderResponse =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("operation_id", req.operationId)
                .put("currency_id", req.currencyId)
                .put("amount", req.amount)
                .put("current_price", req.currentPrice)
                .put("instrument_id", req.instrumentId)
                .put("customer_pocket_id", req.customerPocketId)
                .put("type", req.type.wire)
                .put("duration", req.duration.wire)
                .put("allow_in_pre_post", req.allowInPrePost)
            Tracer.log(Layer.HTTP, "POST $rootUrl/v2/orders Idempotency-Key=${idempotencyKey.take(8)}…")
            val json = request("POST", "/v2/orders", idempotencyKey, body.toString())
            OrderResponse(json.getString("id").toLong()) // proto int64 comes as a JSON string
        }

    // ponytail: polling every 3s; backend has no order-status stream, push notifications cover it in the real app
    override fun orderStatus(id: Long): Flow<OrderStatus> = flow {
        while (true) {
            val json = withContext(Dispatchers.IO) { request("GET", "/v2/orders/$id", null, null) }
            val status = OrderStatus.valueOf(json.optString("status", "pending").uppercase())
            emit(status)
            if (status != OrderStatus.PENDING && status != OrderStatus.PROCESSING) break
            delay(3_000)
        }
    }

    private fun request(method: String, path: String, idempotencyKey: String?, body: String?): JSONObject {
        val conn = URL(rootUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            // core_service ignores this today; it is what server-side dedup should key on
            idempotencyKey?.let { conn.setRequestProperty("Idempotency-Key", it) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Tracer.log(Layer.HTTP, "$method $path → $code")
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (code !in 200..299) throw ApiException(code.toString(), json.optString("message", "HTTP $code"))
            return json
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * In-process copy of core_service's buy path, so the whole trace runs without a server.
 * Each block names the Go code it stands for.
 */
class FakeBackend(
    private val scope: CoroutineScope,
    var balance: Double = 1_000.0,
    var marketOpen: Boolean = true,
    private val stepDelayMs: Long = 600,
) : FakeApi {

    private var held = 0.0 // pending ledger rows (the "hold")
    private var nextId = 101L
    private val byKey = mutableMapOf<String, Long>()
    private val statuses = mutableMapOf<Long, MutableStateFlow<OrderStatus>>()

    // ponytail: one global lock serializes all creates; fixes the double-spend race the real
    // balance check has (READ COMMITTED, no lock). Real fix there: SELECT ... FOR UPDATE per customer.
    private val txLock = Mutex()

    override suspend fun createOrderV2(req: OrderRequest, idempotencyKey: String): OrderResponse {
        Tracer.log(Layer.HTTP, "POST /v2/orders Idempotency-Key=${idempotencyKey.take(8)}…")
        step()

        // apps/api/api.go:280 → gin /v2/*any → grpc-gateway gwMux
        val requestId = UUID.randomUUID().toString().take(8)
        Tracer.log(Layer.GATEWAY, "x-request-id=$requestId → grpc BillingService.CreateOrderV2")

        // middleware/auth.go:133 → JWT, session, 2FA, UserID into context
        Tracer.log(Layer.AUTH, "JWT ok, user=42")
        step()

        return txLock.withLock {
            // Not in core_service today: dedup on the client key, so retry/double-tap returns same order
            byKey[idempotencyKey]?.let {
                Tracer.log(Layer.GRPC, "Idempotency-Key seen → return existing order #$it, no new order")
                return@withLock OrderResponse(it)
            }

            // services/billing_service.go:2396 CreateOrderV2 validation
            if (req.amount <= 0) throw fail(Layer.GRPC, "INVALID_ARGUMENT", "amount must be > 0")
            val price = req.currentPrice // real server: market orders take the price from the Redis offer hash
            Tracer.log(Layer.GRPC, "validate ok: qty=${req.amount} ${req.type.wire}/${req.duration.wire}, market ${if (marketOpen) "OPEN" else "CLOSED"}")
            step()

            // db/sqlc/exec_tx.go:8 executeTransaction → orders_tx.go mainCreateOrderLogic → strategy_buy.go:102
            Tracer.log(Layer.TX, "executeTransaction BEGIN (statement_timeout 30s)")
            val total = orderTotal(req.amount, price)
            val available = balance - held
            if (available < total) {
                Tracer.log(Layer.TX, "ROLLBACK")
                throw fail(Layer.TX, "ABORTED", "balance not enough: %.2f < %.2f".format(available, total))
            }
            val id = nextId++
            Tracer.log(Layer.TX, "balance %.2f ≥ %.2f → INSERT order #$id status=pending".format(available, total))
            held += total
            Tracer.log(Layer.TX, "TransactionsInsertMany: pending ledger rows hold %.2f".format(total))
            statuses[id] = MutableStateFlow(OrderStatus.PENDING)
            byKey[idempotencyKey] = id
            step()

            if (marketOpen) {
                // orders_tx.go:259: real code calls fix_service INSIDE the open tx (risk: broker has order, DB may not)
                Tracer.log(Layer.FIX, "placeFIXOrder #$id (inside tx in core_service: risky)")
                Tracer.log(Layer.TX, "COMMIT")
                Tracer.log(Layer.ASYNQ, "enqueue background:orders action=order_placed")
            } else {
                Tracer.log(Layer.TX, "COMMIT")
                // billing_service.go:2852 schedulePendingOrderPlacement
                Tracer.log(Layer.ASYNQ, "enqueue background:place_order TaskID=order_id_$id ProcessAt=market open")
            }
            scope.launch { runWorkers(id, total) }

            Tracer.log(Layer.GRPC, "return OrderResponse{id=$id} (still pending)")
            OrderResponse(id)
        }
    }

    override fun orderStatus(id: Long): Flow<OrderStatus> =
        statuses[id] ?: throw ApiException("NOT_FOUND", "order $id")

    /** transport/workers/background: what asynq does after the HTTP call already returned. */
    private suspend fun runWorkers(id: Long, total: Double) {
        val status = statuses.getValue(id)
        if (!marketOpen) {
            step(3)
            // background/place_order.go:27 PlaceOrder
            Tracer.log(Layer.WORKER, "PlaceOrder #$id: redis lock, status=pending, no fix_order_id → FixPlaceOrder")
            Tracer.log(Layer.ASYNQ, "enqueue background:orders action=order_placed")
        }
        step()
        // background/orders.go:24 Orders (same task the CMS observer pushes)
        Tracer.log(Layer.WORKER, "Orders #$id order_placed → push \"Order placed\"")

        step(2)
        // background/fix_service.go:60 FixOrdersUpdate → update_fix_orders_tx.go LockOrderForUpdate
        Tracer.log(Layer.WORKER, "order_update #$id partially_filled → processing")
        status.value = OrderStatus.PROCESSING

        step(2)
        txLock.withLock {
            held -= total
            balance -= total
        }
        Tracer.log(Layer.WORKER, "order_update #$id filled → completed, ledger settled, balance %.2f".format(balance))
        status.value = OrderStatus.COMPLETED
        Tracer.log(Layer.WORKER, "Orders #$id completed → drop portfolio cache, push \"Order completed\"")
    }

    private suspend fun step(times: Int = 1) = delay(stepDelayMs * times)

    private fun fail(layer: Layer, code: String, msg: String): ApiException {
        Tracer.log(layer, "$code: $msg")
        return ApiException(code, msg)
    }
}
