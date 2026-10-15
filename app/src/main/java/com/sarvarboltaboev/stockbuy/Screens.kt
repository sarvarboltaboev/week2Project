package com.sarvarboltaboev.stockbuy

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BuyApp(vm: BuyViewModel) {
    val s by vm.state.collectAsState()
    var traceOpen by rememberSaveable { mutableStateOf(false) }
    var previewOpen by rememberSaveable { mutableStateOf(false) }
    val placed = s.screen == Screen.PLACED
    val sheetOpen = placed || traceOpen || previewOpen

    BackHandler(enabled = s.screen != Screen.DETAIL || s.focused != null || traceOpen || previewOpen) {
        when {
            traceOpen -> traceOpen = false
            previewOpen -> previewOpen = false
            placed -> vm.done()
            !s.submitting -> vm.back()
        }
    }

    Box(Modifier.fillMaxSize().background(Ds.bg)) {
        Box(Modifier.fillMaxSize().blur(if (sheetOpen) 8.dp else 0.dp)) {
            when (s.screen) {
                Screen.DETAIL, Screen.PLACED -> StockDetail(s, vm)
                Screen.FORM -> BuyForm(s, vm) { previewOpen = true }
                Screen.CONFIRM -> Confirm(s, vm)
            }
        }

        // learning aid: trace of every hop, not part of the real app
        if (!sheetOpen && s.focused == null) TraceButton(Tracer.steps.collectAsState().value.size) { traceOpen = true }

        s.notice?.let { msg ->
            LaunchedEffect(msg) { delay(2_500); vm.clearNotice() }
            Text(
                msg, style = T.bodyMediumS, color = Ds.bg,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 100.dp)
                    .fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ds.text).padding(16.dp),
            )
        }

        Sheet(placed, vm::done, 0.85f) { OrderPlaced(s, vm) }
        Sheet(previewOpen, { previewOpen = false }, 0.7f) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("Order Summary", style = T.titleExtraBoldXL)
                Spacer(Modifier.height(24.dp))
                DetailsTable(s, s.qty, s.price)
            }
        }
        Sheet(traceOpen, { traceOpen = false }, 0.7f) { TraceList() }
    }
}

// ---------- 1. Stock detail (stock_detail_page.dart) ----------

@Composable
private fun StockDetail(s: BuyUiState, vm: BuyViewModel) {
    val activity = LocalContext.current as? Activity
    var starred by rememberSaveable { mutableStateOf(false) }
    val up = s.change >= 0
    val changeColor = if (up) Ds.accent else Ds.alert

    Column(Modifier.fillMaxSize()) {
        CompactAppBar(s.instrument.symbol, onBack = { activity?.finish() }) {
            Ic(if (starred) R.drawable.ic_star_fill else R.drawable.ic_star, modifier = Modifier.tap { starred = !starred })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("${s.instrument.name} · Technology", style = T.captionSemiBoldM, color = Ds.textSecondary)
                Spacer(Modifier.height(8.dp))
                Text(money(s.instrument.price), style = T.titleExtraBoldXXL)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%+.2f".format(s.change), style = T.bodyMediumS, color = changeColor)
                    Spacer(Modifier.width(4.dp))
                    Text("%+.2f%%".format(s.changePct), style = T.bodyMediumS, color = changeColor)
                    Spacer(Modifier.width(4.dp))
                    Text("Today", style = T.captionSemiBoldM, color = Ds.textSecondary)
                }
                Spacer(Modifier.height(8.dp))
                MarketPill(s.marketOpen, vm::toggleMarket)
            }
            Spacer(Modifier.height(16.dp))
            Tabs()
            Spacer(Modifier.height(32.dp))
            ShariahBanner()
            Spacer(Modifier.height(25.dp))
            Chart(up)
            Spacer(Modifier.height(16.dp))
            PeriodSelector()
            Spacer(Modifier.height(24.dp))
            Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Set alert", style = T.bodySemiBoldXXL, modifier = Modifier.weight(1f))
                Ic(R.drawable.ic_plus)
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomBar { DsPrimaryButton("Buy", onClick = vm::openBuy) }
    }
}

/** pre_market_pill.dart */
@Composable
private fun MarketPill(open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(Ds.grey20).tap(onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ic(if (open) R.drawable.sun else R.drawable.moon, 16.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            if (open) "Market is open" else "Market is closed", style = T.captionSemiBoldM,
            color = if (open) Ds.orange else Ds.textSecondary,
        )
    }
}

/** filter_bar.dart pills: 40h, full radius, black selected. */
@Composable
private fun Tabs() {
    Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Overview", "Key stats", "News").forEachIndexed { i, label ->
            val shape = RoundedCornerShape(100.dp)
            Box(
                Modifier.height(40.dp).clip(shape)
                    .then(if (i == 0) Modifier.background(Ds.text) else Modifier.border(1.dp, Ds.alpha20, shape))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = T.captionSemiBoldM, color = if (i == 0) Ds.bg else Ds.text) }
        }
    }
}

/** stock_detail_shariah_banner.dart, compliant colors. */
@Composable
private fun ShariahBanner() {
    Row(
        Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(44.dp))
            .background(Color(0xFFEDFDD6)).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ic(R.drawable.ic_stock_compliant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Shariah Classification", style = T.captionSemiBoldM)
            Text("Compliant", style = T.bodySemiBoldXXL, color = Ds.accent)
        }
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(42.dp)).background(Color(0xFFE3F9C7)).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text("More", style = T.captionSemiBoldM) }
    }
}

// ponytail: fixed demo points; the real chart comes from the price-history API
private val chartPoints = listOf(185.1f, 185.9f, 185.4f, 186.6f, 186.2f, 187.4f, 186.9f, 187.8f, 188.6f, 188.1f, 189.0f, 188.7f, 189.2f)

@Composable
private fun Chart(up: Boolean) {
    Canvas(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(220.dp)) {
        val min = chartPoints.min()
        val max = chartPoints.max()
        val path = Path()
        chartPoints.forEachIndexed { i, v ->
            val x = size.width * i / (chartPoints.size - 1)
            val y = size.height * (1 - (v - min) / (max - min)) * 0.9f + size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (up) Ds.text else Ds.alert, style = Stroke(2.dp.toPx()))
        drawLine(Ds.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }
}

/** chart_period_selector.dart */
@Composable
private fun PeriodSelector() {
    var selected by rememberSaveable { mutableStateOf(0) }
    Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
        listOf("1D", "1W", "1M", "6M", "YTD", "1Y", "5Y", "All").forEachIndexed { i, p ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(44.dp)).background(if (i == selected) Ds.text else Color.Transparent)
                    .tap { selected = i }.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(p, style = T.captionSemiBoldM, color = if (i == selected) Ds.bg else Ds.textSecondary) }
        }
    }
}

// ---------- 2. Buy form (order_body.dart) ----------

@Composable
private fun BuyForm(s: BuyUiState, vm: BuyViewModel, onPreview: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CompactAppBar("Buy", onBack = vm::back)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                FormHeader(s)
                Spacer(Modifier.height(24.dp))

                LabeledRow(
                    "Order type",
                    info = if (s.type == OrderType.LIMIT) "Limit order" to "Set the exact price you want to buy or sell at. Your order only executes at that price or better."
                    else "Market order" to "Buy right away at the best available current price.",
                ) {
                    Text(if (s.type == OrderType.LIMIT) "Limit" else "Market", style = T.bodySemiBoldXXL, modifier = Modifier.tap(vm::toggleType))
                }
                Divider()
                if (s.type == OrderType.LIMIT) {
                    LabeledRow("Price", labelColor = if (s.focused == Field.PRICE) Ds.accent else Ds.text) {
                        Stepper(
                            text = if (s.priceText.isEmpty()) "" else "$" + s.priceText,
                            placeholder = money(s.instrument.price),
                            focused = s.focused == Field.PRICE,
                            minusEnabled = s.price > 0.5,
                            error = s.error == "Enter a valid price per stock.",
                            onTap = { vm.focus(Field.PRICE) },
                            onMinus = { vm.stepPrice(-0.5) },
                            onPlus = { vm.stepPrice(0.5) },
                        )
                    }
                    Divider()
                }
                LabeledRow("Quantity", labelColor = if (s.focused == Field.QTY) Ds.accent else Ds.text) {
                    Stepper(
                        text = s.qtyText, placeholder = "0", focused = s.focused == Field.QTY,
                        minusEnabled = s.qty > 1, error = s.error == "Enter a valid quantity.",
                        onTap = { vm.focus(Field.QTY) }, onMinus = { vm.stepQty(-1) }, onPlus = { vm.stepQty(1) },
                    )
                }
                Divider()
                LabeledRow(
                    "Time in force",
                    info = if (s.duration == Duration.DAY) "Day order" to "Your order is active for the current trading day only. If it doesn't fill by market close, it's automatically canceled."
                    else "Good till cancel (GTC)" to "Your order stays active until it's filled or canceled, up to 90 days. No need to resubmit every day.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        TifOption("Day", s.duration == Duration.DAY) { vm.setDuration(Duration.DAY) }
                        TifOption("GTC", s.duration == Duration.GTC) { vm.setDuration(Duration.GTC) }
                    }
                }
                Divider()

                val errorBelow = s.notEnoughMoney || s.error != null
                DashedRow(
                    "Cash balance", money(s.balance), dots = true,
                    valueColor = if (s.balance < 0) Ds.red50 else Ds.text,
                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = if (errorBelow) 0.dp else 24.dp),
                )
                if (s.notEnoughMoney) {
                    Row(Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp)) {
                        Text("Not enough money to purchase", style = T.bodyMediumS, color = Ds.alert)
                        Spacer(Modifier.width(8.dp))
                        Text("Deposit", style = T.bodyMediumS, color = Ds.accent)
                    }
                }
                s.error?.takeIf { it != "Not enough money to purchase" }?.let {
                    Text(it, style = T.bodyMediumS, color = Ds.red50, modifier = Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp))
                }
                Spacer(Modifier.height(24.dp))

                ToggleRow("Extended hours", "Includes Pre-market and Post-market", s.extendedHours, vm::setExtendedHours)
                Divider()
                Spacer(Modifier.height(16.dp))
                ToggleRow(
                    "Take Profit or Stop Loss",
                    "You can set TP/SL now but it'll activate the next trading day in line with T+1 rules.",
                    s.tpSl, vm::setTpSl,
                )
                if (s.tpSl) {
                    Spacer(Modifier.height(16.dp))
                    TpSlRow("Take profit", s.tpOn, s.tpText, s.focused == Field.TP, Ds.accent, s.price, vm::toggleTp) { vm.focus(Field.TP) }
                    Divider()
                    TpSlRow("Stop loss", s.slOn, s.slText, s.focused == Field.SL, Ds.alert, s.price, vm::toggleSl) { vm.focus(Field.SL) }
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.height(8.dp))
                Divider()

                LabeledRow("Pocket") {
                    Row(Modifier.tap(vm::nextPocket), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.pockets[s.pocketIndex].name, style = T.bodyMediumS)
                        if (s.pockets.size > 1) Ic(R.drawable.ic_arrow_down)
                    }
                }
                Divider()
                Spacer(Modifier.height(16.dp))
                Column(Modifier.padding(horizontal = 24.dp)) {
                    SummaryRow("Volume", money(s.volume))
                    SummaryRow("Commission", money(commission(s.volume)))
                    SummaryRow("Total", money(s.total), bold = true)
                }
                Spacer(Modifier.height(if (s.focused != null) 330.dp else 16.dp))
            }
            BottomBar {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Ds.text).tap(onPreview),
                    contentAlignment = Alignment.Center,
                ) { Ic(R.drawable.ic_visibility, tint = Ds.bg) }
                Spacer(Modifier.width(8.dp))
                DsPrimaryButton("Buy", Modifier.weight(1f), enabled = s.formValid, onClick = vm::continueToConfirm)
            }
        }

        AnimatedVisibility(
            s.focused != null, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(280)) { it }, exit = slideOutVertically(tween(280)) { it },
        ) {
            val f = s.focused
            Numpad(
                allowDot = f != Field.QTY,
                quick = when (f) {
                    Field.TP -> listOf("+1%", "+2%", "+3%").map { it to Ds.accent }
                    Field.SL -> listOf("-1%", "-2%", "-3%").map { it to Ds.alert }
                    else -> emptyList()
                },
                onQuick = vm::quick, onKey = vm::key, onBackspace = vm::backspace, onDone = { vm.focus(null) },
            )
        }
    }
}

/** order_stock_header.dart */
@Composable
private fun FormHeader(s: BuyUiState) {
    Row(Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(s.instrument.symbol, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.instrument.symbol, style = T.bodySemiBoldXXL)
            Text(s.instrument.name, style = T.captionSemiBoldM, color = Ds.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("CURRENT PRICE", style = T.captionSemiBoldM, color = Ds.textSecondary)
            Spacer(Modifier.height(4.dp))
            Text(money(s.instrument.price), style = T.titleExtraBoldXL)
        }
    }
}

// ponytail: initials instead of the real logo image
@Composable
private fun Logo(symbol: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Ds.grey20), contentAlignment = Alignment.Center) {
        Text(symbol.take(2), style = T.bodyMediumS, color = Ds.textSecondary)
    }
}

@Composable
private fun TifOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.tap(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = T.bodySemiBoldXXL, color = if (selected) Ds.text else Ds.textSecondary)
        Spacer(Modifier.height(2.dp))
        Box(Modifier.size(26.dp, 2.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) Ds.text else Color.Transparent))
    }
}

/** order_tp_sl_item_rows.dart */
@Composable
private fun TpSlRow(
    label: String, on: Boolean, text: String, focused: Boolean, color: Color, price: Double,
    onCheck: () -> Unit, onTap: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Ic(if (on) R.drawable.ic_checked_box else R.drawable.ic_check_box, modifier = Modifier.tap(onCheck))
        Spacer(Modifier.width(12.dp))
        Text(label, style = T.bodySemiBoldXXL, color = if (focused) color else Ds.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        ValuePill(if (text.isEmpty()) "" else "$$text", "$0.00", focused, modifier = Modifier.width(122.dp), onTap = onTap)
        Spacer(Modifier.width(12.dp))
        val pct = text.toDoubleOrNull()?.takeIf { price > 0 }?.let { (it / price - 1) * 100 }
        Text(
            pct?.let { "%+.2f%%".format(it) } ?: "", style = T.captionSemiBoldM, color = color,
            textAlign = TextAlign.End, modifier = Modifier.width(52.dp),
        )
    }
}

/** order_summary_widget.dart: 3/3 dashes in grey40 @60%, 12 gaps. */
@Composable
private fun SummaryRow(label: String, value: String, bold: Boolean = false) = DashedRow(
    label, value, Modifier.padding(vertical = 8.dp),
    labelStyle = if (bold) T.bodySemiBoldXXL else T.bodyMediumS,
    dash = 3f, gap = 3f, lineColor = Ds.grey40.copy(alpha = 0.6f), spacing = 12.dp,
)

// ---------- 3. Confirmation (order_confirmation_page.dart) ----------

@Composable
private fun Confirm(s: BuyUiState, vm: BuyViewModel) {
    Column(Modifier.fillMaxSize()) {
        CenteredAppBar("Buy", onBack = { if (!s.submitting) vm.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(s.instrument.symbol, 48)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(s.instrument.symbol, style = T.bodySemiBoldXXL)
                    Text(s.instrument.name, style = T.titleBoldL, color = Ds.textSecondary)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Order Summary", style = T.titleExtraBoldXL)
            Spacer(Modifier.height(24.dp))
            DetailsTable(s, s.confirmedQty, s.confirmedPrice, showTotal = false)
            Spacer(Modifier.height(20.dp))
            Row {
                Text("Total", style = T.titleBoldL, modifier = Modifier.weight(1f))
                Text(money(orderTotal(s.confirmedQty, s.confirmedPrice)), style = T.titleBoldL)
            }
            if (s.takeProfit != null || s.stopLoss != null) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TP/SL", style = T.bodyMediumS, modifier = Modifier.weight(1f))
                    s.takeProfit?.let { Badge(money(it), Color(0xFFE3F9C7), Color(0xFF5B7735)) }
                    if (s.takeProfit != null && s.stopLoss != null) Spacer(Modifier.width(8.dp))
                    s.stopLoss?.let { Badge(money(it), Color(0xFFFBE9E9), Ds.alert) }
                }
            }
            Spacer(Modifier.height(28.dp))
            ActionRow("Edit order", R.drawable.ic_pen, Ds.text) { if (!s.submitting) vm.back() }
            Divider(inset = 0.dp)
            ActionRow("Cancel", R.drawable.ic_bin, Ds.red50) { if (!s.submitting) vm.done() }
            s.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = T.bodyMediumS, color = Ds.red50)
            }
        }
        BottomBar { DsPrimaryButton("Confirm", loading = s.submitting, onClick = vm::confirm) }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) =
    Text(text, style = T.captionSemiBoldM, color = fg, modifier = Modifier.clip(RoundedCornerShape(44.dp)).background(bg).padding(6.dp))

@Composable
private fun ActionRow(label: String, icon: Int, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap(onClick).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = T.bodySemiBoldXXL, color = color, modifier = Modifier.weight(1f))
        Ic(icon)
    }
}

/** info_table.dart rows shared by preview, confirmation and order placed. */
@Composable
private fun DetailsTable(s: BuyUiState, qty: Int, price: Double, date: Long? = null, showTotal: Boolean = true) {
    val volume = qty * price
    val rows = buildList {
        date?.let { add("Date" to SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.US).format(Date(it))) }
        add("Order type" to if (s.type == OrderType.LIMIT) "Limit" else "Market order")
        add("Time in force" to if (s.duration == Duration.DAY) "Day" else "Good till cancel (GTC)")
        add("Price per stock" to money(price))
        add("Stock quantity" to qty.toString())
        add("Pocket" to s.pockets[s.pocketIndex].name)
        add("Volume" to money(volume))
        add("Commission" to money(commission(volume)))
    }
    rows.forEach { (k, v) -> DashedRow(k, v, Modifier.padding(vertical = 8.dp)) }
    if (showTotal) DashedRow("Total", money(orderTotal(qty, price)), Modifier.padding(vertical = 8.dp), labelStyle = T.bodySemiBoldXXL)
}

// ---------- 4. Order placed (order_placed_page.dart) ----------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.OrderPlaced(s: BuyUiState, vm: BuyViewModel) {
    val (label, color, subtitle) = when (s.status) {
        OrderStatus.COMPLETED -> Triple("Completed", Ds.accent, "Your order has been completed.")
        OrderStatus.PROCESSING -> Triple("Processing", Ds.warning, "It's partially filled. We'll notify you once it's completed.")
        OrderStatus.CANCELED, OrderStatus.FAILED -> Triple(s.status.name.lowercase().replaceFirstChar { it.uppercase() }, Ds.red50, "Your order was not completed.")
        else -> Triple("Pending", Ds.warning, "It's currently pending. We'll notify you once it's completed.")
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        Ic(R.drawable.image_clock, 72.dp)
        Spacer(Modifier.height(24.dp))
        Text("Your order for ${s.instrument.symbol} has been placed", style = T.titleExtraBoldXL)
        Spacer(Modifier.height(12.dp))
        Text(subtitle, style = T.bodySemiBoldXXL, color = Ds.textSecondary)
        Spacer(Modifier.height(32.dp))
        DashedRow("Status", label, Modifier.padding(vertical = 8.dp), valueColor = color, leading = { Ic(R.drawable.ic_clock, 16.dp, tint = color) })
        Spacer(Modifier.height(8.dp))
        DetailsTable(s, s.confirmedQty, s.confirmedPrice, date = s.placedAt)
        Spacer(Modifier.height(16.dp))
    }
    Box(Modifier.fillMaxWidth().topBorder().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        DsPrimaryButton("Close", onClick = vm::done)
    }
}

// ---------- Trace (learning aid) ----------

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TraceButton(count: Int, onClick: () -> Unit) {
    Box(
        // sits in the app bar, left of the star slot, so it never covers form controls
        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 20.dp, end = 60.dp)
            .size(32.dp).clip(CircleShape).background(Ds.text).tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("⌁", style = T.bodyBoldL, color = Ds.brand)
        if (count > 0) Text(
            count.toString(), style = T.captionSemiBoldM.copy(fontSize = 9.sp, lineHeight = 10.sp), color = Ds.text,
            modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Ds.brand).padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun TraceList() {
    val steps by Tracer.steps.collectAsState()
    Text("Trace", style = T.titleBoldL, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
    if (steps.isEmpty()) Text("Tap Buy to start a trace.", style = T.bodyMediumS, color = Ds.textSecondary, modifier = Modifier.padding(horizontal = 24.dp))
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        items(steps, key = { it.n }) { st ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("#${st.n}".padEnd(4), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Ds.grey60)
                Text(st.layer.name.padEnd(8), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = layerColor(st.layer))
                Text(st.msg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Ds.text)
            }
        }
    }
}

private fun layerColor(l: Layer) = when (l) {
    Layer.UI, Layer.VM -> Color(0xFF1F6FEB)
    Layer.HTTP, Layer.GATEWAY, Layer.AUTH -> Ds.warning
    Layer.GRPC, Layer.TX, Layer.FIX -> Ds.alert
    Layer.ASYNQ, Layer.WORKER -> Color(0xFF4C8A12)
}
