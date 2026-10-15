package com.sarvarboltaboev.stockbuy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BuyApp(vm: BuyViewModel) {
    val s by vm.state.collectAsState()
    BackHandler(enabled = s.screen != Screen.DETAIL && !s.submitting) {
        if (s.screen == Screen.PLACED) vm.done() else vm.back()
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (s.screen) {
                Screen.DETAIL -> StockDetail(s, vm)
                Screen.FORM -> BuyForm(s, vm)
                Screen.CONFIRM -> Confirm(s, vm)
                Screen.PLACED -> OrderPlaced(s, vm)
            }
        }
        TracePanel()
    }
}

/** Flutter: stock_detail_page.dart + TradingButtons. */
@Composable
private fun StockDetail(s: BuyUiState, vm: BuyViewModel) {
    Text(s.instrument.symbol, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(s.instrument.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("$%.2f".format(s.instrument.price), style = MaterialTheme.typography.displaySmall)
    if (!s.useReal) Text("Balance $%.2f".format(vm.fake.balance))

    HorizontalDivider()
    Toggle("Real backend", s.useReal, vm::setUseReal)
    if (s.useReal) {
        OutlinedTextField(s.realUrl, vm::setRealUrl, label = { Text("Root URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.realToken, vm::setRealToken, label = { Text("Bearer token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    } else {
        Toggle("Market open", s.marketOpen, vm::setMarketOpen)
    }
    HorizontalDivider()

    Button(onClick = vm::openBuy, modifier = Modifier.fillMaxWidth()) { Text("BUY") }
}

/** Flutter: buy_order_page_v2.dart + buy_order_mixin_v2.dart. */
@Composable
private fun BuyForm(s: BuyUiState, vm: BuyViewModel) {
    Text("Buy ${s.instrument.symbol}", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(
        s.qtyText, vm::setQty, label = { Text("Quantity") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
    Chips("Type", OrderType.entries, s.type, { it.wire }, vm::setType)
    Chips("Duration", Duration.entries, s.duration, { it.wire }, vm::setDuration)
    Chips("Pocket", s.pockets.indices.toList(), s.pocketIndex, { s.pockets[it].name }, vm::setPocket)
    Text("Total incl. fee: $%.2f".format(s.total), fontWeight = FontWeight.Bold)
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = vm::continueToConfirm, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    TextButton(onClick = vm::back) { Text("Back") }
}

/** Flutter: order_confirmation_page.dart. */
@Composable
private fun Confirm(s: BuyUiState, vm: BuyViewModel) {
    Text("Confirm order", style = MaterialTheme.typography.headlineSmall)
    Row2("Instrument", s.instrument.symbol)
    Row2("Quantity", s.confirmedQty.toString())
    Row2("Price", "$%.2f".format(s.instrument.price))
    Row2("Type / duration", "${s.type.wire} / ${s.duration.wire}")
    Row2("Pocket", s.pockets[s.pocketIndex].name)
    Row2("Total incl. fee", "$%.2f".format(orderTotal(s.confirmedQty, s.instrument.price)))
    Row2("Idempotency-Key", s.idempotencyKey.take(8) + "…")
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = vm::confirm, enabled = !s.submitting, modifier = Modifier.fillMaxWidth()) {
        if (s.submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Confirm")
    }
    // learning aid: fire two confirms back to back and watch the second one get dropped in the trace
    TextButton(onClick = { vm.confirm(); vm.confirm() }, enabled = !s.submitting) { Text("Double-tap Confirm (test)") }
    TextButton(onClick = vm::back, enabled = !s.submitting) { Text("Back") }
}

/** Flutter: OrderPlacedPage. Adds live status the Flutter app does not show. */
@Composable
private fun OrderPlaced(s: BuyUiState, vm: BuyViewModel) {
    Text("Order placed", style = MaterialTheme.typography.headlineSmall)
    Row2("Order", "#${s.orderId}")
    Row2("Status", s.status?.name?.lowercase() ?: "-")
    val order = listOf(OrderStatus.PENDING, OrderStatus.PROCESSING, OrderStatus.COMPLETED)
    val reached = order.indexOf(s.status)
    order.forEachIndexed { i, st ->
        Text((if (i <= reached) "● " else "○ ") + st.name.lowercase(), fontFamily = FontFamily.Monospace)
    }
    Button(onClick = vm::done, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

/** Live timeline of every hop. Same lines go to `adb logcat -s BuyTrace`. */
@Composable
private fun TracePanel() {
    val steps by Tracer.steps.collectAsState()
    var open by rememberSaveable { mutableStateOf(true) }
    val list = rememberLazyListState()
    LaunchedEffect(steps.size) { if (steps.isNotEmpty()) list.animateScrollToItem(steps.lastIndex) }

    Surface(color = Color(0xFF111418), contentColor = Color(0xFFE6E6E6)) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                (if (open) "▾ " else "▸ ") + "Trace (${steps.size})",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(12.dp),
            )
            if (open) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(horizontal = 12.dp), state = list) {
                    items(steps, key = { it.n }) { st ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("#${st.n}".padEnd(4), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                            Text(st.layer.name.padEnd(8), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = layerColor(st.layer))
                            Text(st.msg, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

private fun layerColor(l: Layer) = when (l) {
    Layer.UI, Layer.VM -> Color(0xFF7FD1FF)
    Layer.HTTP, Layer.GATEWAY, Layer.AUTH -> Color(0xFFFFD27F)
    Layer.GRPC, Layer.TX, Layer.FIX -> Color(0xFFFF9E7F)
    Layer.ASYNQ, Layer.WORKER -> Color(0xFF9EE59E)
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }

@Composable
private fun Row2(label: String, value: String) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, fontWeight = FontWeight.Medium)
}

@Composable
private fun <T> Chips(label: String, options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(name(o)) }) }
    }
}
