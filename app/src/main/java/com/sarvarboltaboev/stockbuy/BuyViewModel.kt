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

data class BuyUiState(
    val screen: Screen = Screen.DETAIL,
    val instrument: Instrument = Instrument("AAPL", "AAPL", "Apple Inc.", 189.20),
    val pockets: List<Pocket> = listOf(Pocket("p1", "Main pocket"), Pocket("p2", "Long term")),
    val pocketIndex: Int = 0,
    val qtyText: String = "1",
    val type: OrderType = OrderType.MARKET,
    val duration: Duration = Duration.DAY,
    // frozen on Continue: what the user saw on Confirm is exactly what gets sent
    val confirmedQty: Int = 0,
    val idempotencyKey: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    val orderId: Long? = null,
    val status: OrderStatus? = null,
    // backend switch
    val useReal: Boolean = false,
    val realUrl: String = "https://api.example.com",
    val realToken: String = "",
    val marketOpen: Boolean = true,
) {
    val qty: Int get() = qtyText.toIntOrNull() ?: 0
    val total: Double get() = orderTotal(qty, instrument.price)
}

/** Flutter: AdvancedBuyBloc (advanced_buy_bloc.dart) + the buy part of StockBloc. */
class BuyViewModel : ViewModel() {

    private val _state = MutableStateFlow(BuyUiState())
    val state: StateFlow<BuyUiState> = _state
    private val s get() = _state.value

    val fake = FakeBackend(viewModelScope)
    private var statusJob: Job? = null

    private fun api(): FakeApi = if (s.useReal) HttpFakeApi(s.realUrl.trimEnd('/'), s.realToken) else fake

    // stock_trade_button.dart:314 _handleBuy → push Routes.advancedBuy
    fun openBuy() {
        Tracer.clear()
        Tracer.log(Layer.UI, "BUY tapped (${s.instrument.symbol} @ ${s.instrument.price})")
        _state.update { it.copy(screen = Screen.FORM, error = null) }
    }

    fun setQty(text: String) = _state.update { it.copy(qtyText = text.filter(Char::isDigit).take(6), error = null) }
    fun setType(t: OrderType) = _state.update { it.copy(type = t) }
    fun setDuration(d: Duration) = _state.update { it.copy(duration = d) }
    fun setPocket(i: Int) = _state.update { it.copy(pocketIndex = i) }
    fun setUseReal(v: Boolean) = _state.update { it.copy(useReal = v) }
    fun setRealUrl(v: String) = _state.update { it.copy(realUrl = v) }
    fun setRealToken(v: String) = _state.update { it.copy(realToken = v) }
    fun setMarketOpen(v: Boolean) {
        fake.marketOpen = v
        _state.update { it.copy(marketOpen = v) }
    }

    // buy_order_mixin_v2.dart:436 orderPlace → validate → push confirmation
    fun continueToConfirm() {
        val err = when {
            s.qty <= 0 -> "Enter quantity"
            s.pockets.getOrNull(s.pocketIndex) == null -> "Choose a pocket" // Flutter: RangeError on empty list
            else -> null
        }
        if (err != null) return _state.update { it.copy(error = err) }
        // new key per confirmation; stays the same if Confirm is retried after an error
        val key = UUID.randomUUID().toString()
        Tracer.log(Layer.UI, "Continue → Confirm qty=${s.qty} key=${key.take(8)}…")
        _state.update { it.copy(screen = Screen.CONFIRM, confirmedQty = it.qty, idempotencyKey = key, error = null) }
    }

    // order_confirmation_page.dart Confirm → CreateBuyOrderEvent → _createBuyOrder (bloc :242)
    fun confirm() {
        // Main thread sets the flag before launch, so a second tap is dropped (bloc had no droppable()).
        if (s.submitting) return Tracer.log(Layer.VM, "Confirm tapped again while submitting → dropped")
        _state.update { it.copy(submitting = true, error = null) }

        val req = OrderRequest(
            amount = s.confirmedQty, // not a live-updated stockAmount: no stale commission race
            currentPrice = s.instrument.price,
            instrumentId = s.instrument.id,
            customerPocketId = s.pockets[s.pocketIndex].id,
            type = s.type,
            duration = s.duration,
        )
        Tracer.log(Layer.VM, "CreateBuyOrder qty=${req.amount} ${req.type.wire}/${req.duration.wire} → loading")

        viewModelScope.launch {
            try {
                val res = api().createOrderV2(req, s.idempotencyKey)
                Tracer.log(Layer.VM, "success orderId=${res.id} → OrderPlaced screen")
                _state.update { it.copy(submitting = false, orderId = res.id, status = OrderStatus.PENDING, screen = Screen.PLACED) }
                watchStatus(res.id)
            } catch (e: Exception) {
                Tracer.log(Layer.VM, "error: ${e.message}")
                _state.update { it.copy(submitting = false, error = e.message ?: "Request failed") }
            }
        }
    }

    // Flutter app does not do this at all; real path learns via push notification
    private fun watchStatus(id: Long) {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            try {
                api().orderStatus(id).collect { st -> _state.update { it.copy(status = st) } }
            } catch (e: Exception) {
                Tracer.log(Layer.VM, "status error: ${e.message}")
            }
        }
    }

    fun back() = _state.update {
        when (it.screen) {
            Screen.CONFIRM -> it.copy(screen = Screen.FORM, error = null)
            else -> it.copy(screen = Screen.DETAIL, error = null)
        }
    }

    // OrderPlacedPage close → StockBloc GetInstrumentDetails refresh
    fun done() {
        statusJob?.cancel()
        _state.update { it.copy(screen = Screen.DETAIL, orderId = null, status = null, qtyText = "1") }
    }
}
