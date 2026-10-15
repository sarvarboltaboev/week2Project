package com.sarvarboltaboev.stockbuy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * In-process copy of core_service's buy path, so the whole trace runs without a server.
 * Each block names the Go code it stands for.
 */
class FakeBackend(
    private val scope: CoroutineScope,
    var balance: Double = 1_000.0,
    var marketOpen: Boolean = true,
    private val stepDelayMs: Long = 600,
) {

    private var held = 0.0 // pending ledger rows (the "hold")
    private var nextId = 101L
    private val byKey = mutableMapOf<String, Long>()
    private val statuses = mutableMapOf<Long, MutableStateFlow<OrderStatus>>()

    // ponytail: one global lock serializes all creates; fixes the double-spend race the real
    // balance check has (READ COMMITTED, no lock). Real fix there: SELECT ... FOR UPDATE per customer.
    private val txLock = Mutex()

    suspend fun createOrderV2(req: OrderRequest, idempotencyKey: String): OrderResponse {
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

    fun orderStatus(id: Long): Flow<OrderStatus> =
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
