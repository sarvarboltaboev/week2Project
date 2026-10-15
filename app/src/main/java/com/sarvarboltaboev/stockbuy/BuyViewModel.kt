package com.sarvarboltaboev.stockbuy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { DETAIL, FORM, CONFIRM, PLACED }

enum class Field { PRICE, QTY, TP, SL }

data class BuyUiState(
    val screen: Screen = Screen.DETAIL,
    val instrument: Instrument = Instrument("AAPL", "AAPL", "Apple Inc.", 189.20),
    val change: Double = 2.34,
    val changePct: Double = 1.25,
    val pockets: List<Pocket> = listOf(Pocket("p1", "Main pocket"), Pocket("p2", "Long term")),
    val pocketIndex: Int = 0,
    // buy form, defaults from advanced_buy_state.dart: Limit, Day, extended hours off, qty 1
    val type: OrderType = OrderType.LIMIT,
    val priceText: String = "",
    val qtyText: String = "1",
    val duration: Duration = Duration.DAY,
    val extendedHours: Boolean = false,
    val tpSl: Boolean = false,
    val tpOn: Boolean = false,
    val tpText: String = "",
    val slOn: Boolean = false,
    val slText: String = "",
    val focused: Field? = null,
    // frozen on Buy: what the user saw on Confirm is exactly what gets sent
    val confirmedQty: Int = 0,
    val confirmedPrice: Double = 0.0,
    val idempotencyKey: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val orderId: Long? = null,
    val placedAt: Long = 0,
    val status: OrderStatus? = null,
    val balance: Double = 1_000.0,
    val marketOpen: Boolean = true,
) {
    val qty: Int get() = qtyText.toIntOrNull() ?: 0
    val price: Double
        get() = when {
            type == OrderType.MARKET || priceText.isEmpty() -> instrument.price // empty = placeholder live price
            else -> priceText.toDoubleOrNull() ?: 0.0
        }
    val volume: Double get() = qty * price
    val total: Double get() = orderTotal(qty, price)
    val takeProfit: Double? get() = tpText.toDoubleOrNull()?.takeIf { tpSl && tpOn }
    val stopLoss: Double? get() = slText.toDoubleOrNull()?.takeIf { tpSl && slOn }
    val notEnoughMoney: Boolean get() = total > balance
    val formValid: Boolean
        get() = qty > 0 && price > 0 && !notEnoughMoney && (!tpSl || takeProfit != null || stopLoss != null)

    fun text(f: Field) = when (f) {
        Field.PRICE -> priceText
        Field.QTY -> qtyText
        Field.TP -> tpText
        Field.SL -> slText
    }
}

/** Flutter: AdvancedBuyBloc (advanced_buy_bloc.dart) + the buy part of StockBloc. */
class BuyViewModel : ViewModel() {

    private val _state = MutableStateFlow(BuyUiState())
    val state: StateFlow<BuyUiState> = _state
    private val s get() = _state.value

    private val api = FakeBackend(viewModelScope)
    private var statusJob: Job? = null

    // stock_trade_button.dart:314 _handleBuy → push Routes.advancedBuy
    fun openBuy() {
        Tracer.clear()
        Tracer.log(Layer.UI, "Buy tapped (${s.instrument.symbol} @ ${s.instrument.price})")
        _state.update { BuyUiState(balance = api.balance, marketOpen = it.marketOpen, screen = Screen.FORM) }
    }

    // ponytail: hidden dev toggle on the market pill; the real app gets this from the backend
    fun toggleMarket() {
        api.marketOpen = !api.marketOpen
        _state.update { it.copy(marketOpen = api.marketOpen) }
    }

    fun toggleType() = _state.update {
        it.copy(type = if (it.type == OrderType.LIMIT) OrderType.MARKET else OrderType.LIMIT, focused = null)
    }

    // GTC forces extended hours on, same rule as the real app
    fun setDuration(d: Duration) = _state.update { it.copy(duration = d, extendedHours = it.extendedHours || d == Duration.GTC) }

    fun setExtendedHours(v: Boolean) = _state.update {
        if (!v && it.duration == Duration.GTC) it.copy(notice = "When Time in force is GTC, you can't disable Extended hours.")
        else it.copy(extendedHours = v)
    }

    fun setTpSl(v: Boolean) = _state.update { it.copy(tpSl = v, focused = null, error = null) }
    fun toggleTp() = _state.update { it.copy(tpOn = !it.tpOn) }
    fun toggleSl() = _state.update { it.copy(slOn = !it.slOn) }
    fun nextPocket() = _state.update { it.copy(pocketIndex = (it.pocketIndex + 1) % it.pockets.size) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun focus(f: Field?) = _state.update {
        // first tap on an empty price fills it with the live price
        val price = if (f == Field.PRICE && it.priceText.isEmpty()) "%.2f".format(it.instrument.price) else it.priceText
        it.copy(
            focused = f, priceText = price, error = null,
            tpOn = it.tpOn || f == Field.TP, slOn = it.slOn || f == Field.SL,
        )
    }

    fun stepPrice(delta: Double) = _state.update {
        val p = maxOf(0.0, (it.priceText.toDoubleOrNull() ?: it.instrument.price) + delta)
        it.copy(priceText = "%.2f".format(p), error = null)
    }

    fun stepQty(delta: Int) = _state.update { it.copy(qtyText = maxOf(1, it.qty + delta).toString(), error = null) }

    fun key(c: Char) = edit { t ->
        val decimals = t.substringAfter('.', "").length
        when {
            c == '.' && ('.' in t || s.focused == Field.QTY) -> t
            c != '.' && '.' in t && decimals >= 2 -> t
            t.length >= 9 -> t
            t == "0" && c != '.' -> c.toString()
            t.isEmpty() && c == '.' -> "0."
            else -> t + c
        }
    }

    fun backspace() = edit { it.dropLast(1) }

    // +1% +2% +3% (TP) or -1% -2% -3% (SL) from the order price
    fun quick(i: Int) {
        val f = s.focused ?: return
        val pct = (i + 1) / 100.0 * if (f == Field.SL) -1 else 1
        edit { "%.2f".format(s.price * (1 + pct)) }
    }

    private fun edit(change: (String) -> String) = _state.update {
        when (it.focused) {
            Field.PRICE -> it.copy(priceText = change(it.priceText))
            Field.QTY -> it.copy(qtyText = change(it.qtyText))
            Field.TP -> it.copy(tpText = change(it.tpText))
            Field.SL -> it.copy(slText = change(it.slText))
            null -> it
        }.copy(error = null)
    }

    // buy_order_mixin_v2.dart:436 orderPlace → validate → push confirmation
    fun continueToConfirm() {
        val err = when {
            s.qty <= 0 -> "Enter a valid quantity."
            s.price <= 0 -> "Enter a valid price per stock."
            s.tpSl && s.takeProfit == null && s.stopLoss == null -> "Enter at least one Take Profit or Stop Loss value."
            s.notEnoughMoney -> "Not enough money to purchase"
            else -> null
        }
        if (err != null) {
            Tracer.log(Layer.UI, "Buy → validation: $err")
            return _state.update { it.copy(error = err, focused = null) }
        }
        // new key per confirmation; stays the same if Confirm is retried after an error
        val key = UUID.randomUUID().toString()
        Tracer.log(Layer.UI, "Buy → Confirm qty=${s.qty} price=${s.price} key=${key.take(8)}…")
        _state.update {
            it.copy(
                screen = Screen.CONFIRM, focused = null, error = null,
                confirmedQty = it.qty, confirmedPrice = it.price, idempotencyKey = key,
            )
        }
    }

    // order_confirmation_page.dart Confirm → CreateBuyOrderEvent → _createBuyOrder (bloc :242)
    fun confirm() {
        // Main thread sets the flag before launch, so a second tap is dropped (bloc had no droppable()).
        if (s.submitting) return Tracer.log(Layer.VM, "Confirm tapped again while submitting → dropped")
        _state.update { it.copy(submitting = true, error = null) }

        val req = OrderRequest(
            amount = s.confirmedQty, // not a live-updated stockAmount: no stale commission race
            currentPrice = s.confirmedPrice,
            instrumentId = s.instrument.id,
            customerPocketId = s.pockets[s.pocketIndex].id,
            type = s.type,
            duration = s.duration,
            allowInPrePost = s.extendedHours,
            allowTpSl = s.tpSl,
            takeProfit = s.takeProfit,
            stopLoss = s.stopLoss,
        )
        Tracer.log(Layer.VM, "CreateBuyOrder qty=${req.amount} ${req.type.wire}/${req.duration.wire} → loading")

        viewModelScope.launch {
            try {
                val res = api.createOrderV2(req, s.idempotencyKey)
                Tracer.log(Layer.VM, "success orderId=${res.id} → OrderPlaced sheet")
                _state.update {
                    it.copy(
                        submitting = false, orderId = res.id, status = OrderStatus.PENDING,
                        placedAt = System.currentTimeMillis(), screen = Screen.PLACED,
                    )
                }
                watchStatus(res.id)
            } catch (e: Exception) {
                Tracer.log(Layer.VM, "error: ${e.message}")
                _state.update { it.copy(submitting = false, error = e.message ?: "Request failed") }
            }
        }
    }

    // Flutter app does not do this; the real path learns via push notification
    private fun watchStatus(id: Long) {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            api.orderStatus(id).collect { st -> _state.update { it.copy(status = st, balance = api.balance) } }
        }
    }

    fun back() = _state.update {
        when {
            it.focused != null -> it.copy(focused = null)
            it.screen == Screen.CONFIRM -> it.copy(screen = Screen.FORM, error = null)
            else -> it.copy(screen = Screen.DETAIL, error = null)
        }
    }

    // OrderPlacedPage close → StockBloc GetInstrumentDetails refresh
    fun done() = _state.update { it.copy(screen = Screen.DETAIL, balance = api.balance) }
}
