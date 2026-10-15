package com.sarvarboltaboev.stockbuy

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup

/** Light-theme tokens from mobile_app app_semantic_colors_light.dart / app_palette.dart. */
object Ds {
    val bg = Color(0xFFFFFFFF)
    val text = Color(0xFF000000)
    val textSecondary = Color(0x66161D35)
    val accent = Color(0xFF7DC023)
    val alert = Color(0xFFEA2924)
    val red50 = Color(0xFFFF5955)
    val brand = Color(0xFFC4EE8B)
    val brandPressed = Color(0xFF7DC023)
    val grey20 = Color(0xFFF5F6F9)
    val grey30 = Color(0xFFE9EBF0)
    val grey40 = Color(0xFFDEE1E8)
    val grey60 = Color(0xFF9599A6)
    val border = Color(0x0D000000)
    val alpha20 = Color(0x1A000000)
    val warning = Color(0xFFD76D16)
    val orange = Color(0xFFFC8A2C)
    val yellow60 = Color(0xFFE7CE68)
    val scrim = Color(0x61000000)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

private fun ts(size: Int, w: Int, lh: Int, ls: Double = 0.0) = TextStyle(
    fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(w),
    lineHeight = lh.sp, letterSpacing = ls.sp, color = Ds.text,
)

/** Text styles from app_text.dart. */
object T {
    val titleExtraBoldXXL = ts(32, 800, 32, -1.0)
    val titleExtraBoldXL = ts(24, 800, 28, -1.0)
    val titleBoldL = ts(18, 700, 24, -0.3)
    val bodySemiBoldXXL = ts(16, 600, 24, -0.5)
    val bodyBoldL = ts(14, 700, 24)
    val bodySemiBoldM = ts(14, 600, 24)
    val bodyMediumS = ts(14, 500, 20)
    val captionSemiBoldM = ts(12, 600, 16)
}

@Composable
fun Ic(@DrawableRes id: Int, size: Dp = 24.dp, tint: Color? = null, modifier: Modifier = Modifier) = Image(
    painterResource(id), null, modifier.size(size), colorFilter = tint?.let { ColorFilter.tint(it) },
)

fun Modifier.tap(onClick: () -> Unit) = clickable(interactionSource = null, indication = null, onClick = onClick)

/** ds_primary_button.dart: 52h, radius 16, brand green, scale 0.98 on press. `enabled` is look only. */
@Composable
fun DsPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(150), label = "press")
    val bg = when {
        !enabled -> Ds.grey20
        pressed -> Ds.brandPressed
        else -> Ds.brand
    }
    Box(
        modifier.fillMaxWidth().height(52.dp).scale(scale).clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(interactionSource = source, indication = null, enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = Ds.text, strokeWidth = 2.dp)
        else Text(text, style = T.bodyBoldL, color = if (enabled) Ds.text else Ds.textSecondary)
    }
}

/** collapsible_sliver_app_bar.dart, resting state: 24 top, 40 title box, 16 bottom. */
@Composable
fun CompactAppBar(title: String, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(Ds.bg).statusBarsPadding()
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp).height(40.dp),
    ) {
        Ic(R.drawable.ic_arrow_left, modifier = Modifier.tap(onBack))
        Spacer(Modifier.width(16.dp))
        Text(title, style = T.bodySemiBoldXXL, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** base_scaffold.dart AppBar: back arrow left, centered title. */
@Composable
fun CenteredAppBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Ds.bg).statusBarsPadding().height(56.dp)) {
        Ic(R.drawable.ic_arrow_left, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).tap(onBack))
        Text(title, style = T.bodySemiBoldXXL, modifier = Modifier.align(Alignment.Center))
    }
}

/** app_utils.dart bottom bar: 24 sides, 16 top, system inset + 12 bottom, 1px top border. */
@Composable
fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ds.bg).topBorder().navigationBarsPadding()
            .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

fun Modifier.topBorder() = drawBehind { drawLine(Ds.border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }

@Composable
fun Divider(inset: Dp = 24.dp) =
    Box(Modifier.padding(horizontal = inset).fillMaxWidth().height(1.dp).background(Ds.border))

/** label ····· value. Dash style differs per screen (info_table / order_summary / cash balance). */
@Composable
fun DashedRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = T.bodyMediumS,
    valueStyle: TextStyle = labelStyle,
    valueColor: Color = Ds.text,
    dash: Float = 4f,
    gap: Float = 4f,
    lineColor: Color = Ds.border,
    spacing: Dp = 8.dp,
    dots: Boolean = false,
    strike: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label, style = labelStyle)
        Spacer(Modifier.width(spacing))
        Canvas(Modifier.weight(1f).padding(bottom = 6.dp).height(1.dp)) { // sits on the text baseline
            val intervals = if (dots) floatArrayOf(0.01f, 4.dp.toPx()) else floatArrayOf(dash.dp.toPx(), gap.dp.toPx())
            drawLine(
                lineColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(),
                cap = if (dots) StrokeCap.Round else StrokeCap.Butt,
                pathEffect = PathEffect.dashPathEffect(intervals),
            )
        }
        Spacer(Modifier.width(spacing))
        leading?.let { it(); Spacer(Modifier.width(4.dp)) }
        Text(
            value, style = valueStyle, color = valueColor,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
        )
    }
}

/** order_labeled_row.dart: 24 padding, label left, control right. */
@Composable
fun LabeledRow(
    label: String,
    labelColor: Color = Ds.text,
    info: Pair<String, String>? = null,
    control: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = T.bodySemiBoldXXL, color = labelColor)
        info?.let { (title, body) ->
            Spacer(Modifier.width(8.dp))
            InfoIcon(title, body)
        }
        Spacer(Modifier.weight(1f))
        control()
    }
}

/** order_body_components.dart info popover: half width, r16, soft shadow. */
@Composable
fun InfoIcon(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        Ic(R.drawable.ic_info, 20.dp, tint = Ds.grey60, modifier = Modifier.tap { open = true })
        if (open) Popup(alignment = Alignment.TopStart, offset = IntOffset(0, 70), onDismissRequest = { open = false }) {
            val w = LocalConfiguration.current.screenWidthDp.dp / 2
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                    append(" - $body")
                },
                style = T.bodyMediumS.copy(lineHeight = 19.sp),
                modifier = Modifier.width(w).shadow(16.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x1F000000))
                    .background(Ds.bg, RoundedCornerShape(16.dp)).padding(16.dp),
            )
        }
    }
}

/** Value pill used by the price / quantity steppers and TP/SL fields. */
@Composable
fun ValuePill(
    text: String,
    placeholder: String,
    focused: Boolean,
    error: Boolean = false,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val shape = RoundedCornerShape(40.dp)
    Row(
        modifier.height(40.dp).clip(shape)
            .background(if (focused) Ds.bg else Ds.grey20)
            .then(if (focused) Modifier.border(1.dp, Ds.border, shape) else Modifier)
            .tap(onTap).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text.ifEmpty { placeholder }, style = T.bodySemiBoldM,
            color = when {
                error -> Ds.red50
                text.isEmpty() -> Ds.textSecondary
                else -> Ds.text
            },
        )
        if (focused) Box(Modifier.padding(start = 1.dp).width(2.dp).height(18.dp).background(Ds.text))
    }
}

/** Pill + round minus/plus buttons. */
@Composable
fun Stepper(
    text: String,
    placeholder: String,
    focused: Boolean,
    minusEnabled: Boolean,
    error: Boolean,
    onTap: () -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ValuePill(text, placeholder, focused, error, onTap = onTap)
        Spacer(Modifier.width(16.dp))
        RoundButton(R.drawable.ic_minus, minusEnabled, onMinus)
        Spacer(Modifier.width(8.dp))
        RoundButton(R.drawable.ic_plus, true, onPlus)
    }
}

@Composable
private fun RoundButton(@DrawableRes icon: Int, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (pressed && enabled) Ds.text else Ds.grey20)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ic(icon, tint = when {
            !enabled -> Ds.grey40
            pressed -> Ds.grey20
            else -> Ds.text
        })
    }
}

/** toggle_info_row.dart: title + subtitle left, brand-green switch right. */
@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.bodySemiBoldXXL)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = T.bodyMediumS, color = Ds.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked, onChange, Modifier.scale(0.85f),
            colors = SwitchDefaults.colors(
                checkedTrackColor = Ds.brand, checkedThumbColor = Ds.bg, checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Ds.grey30, uncheckedThumbColor = Ds.bg, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** blur_bottom_sheet.dart: scrim + white sheet sliding up, top radius 28. */
@Composable
fun BoxScope.Sheet(visible: Boolean, onDismiss: () -> Unit, heightFraction: Float, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Ds.scrim).tap(onDismiss))
    }
    AnimatedVisibility(
        visible, Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(300)) { it }, exit = slideOutVertically(tween(300)) { it },
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(heightFraction)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Ds.bg).tap {},
        ) {
            Box(
                Modifier.padding(top = 12.dp, bottom = 4.dp).align(Alignment.CenterHorizontally)
                    .size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Ds.textSecondary),
            )
            content()
        }
    }
}

/** numpad_keyboard.dart: 3×4 grid, 62h rows, green confirm column. TP/SL get a quick-percent strip. */
@Composable
fun Numpad(
    allowDot: Boolean,
    quick: List<Pair<String, Color>>,
    onQuick: (Int) -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Ds.bg).topBorder().navigationBarsPadding()) {
        if (quick.isNotEmpty()) Row(Modifier.fillMaxWidth().height(52.dp).background(Ds.grey20), verticalAlignment = Alignment.CenterVertically) {
            quick.forEachIndexed { i, (label, color) ->
                if (i > 0) Box(Modifier.size(1.dp, 32.dp).background(Ds.alpha20))
                Box(Modifier.weight(1f).fillMaxHeight().tap { onQuick(i) }, contentAlignment = Alignment.Center) {
                    Text(label, style = T.bodySemiBoldM, color = color)
                }
            }
        }
        Row {
            Column(Modifier.weight(1f)) {
                listOf("123", "456", "789", ".0<").forEach { row ->
                    Row {
                        row.forEach { c ->
                            Box(
                                Modifier.weight(1f).height(62.dp).tap {
                                    when {
                                        c == '<' -> onBackspace()
                                        c == '.' && !allowDot -> Unit
                                        else -> onKey(c)
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    c == '<' -> Ic(R.drawable.ic_backward)
                                    c == '.' && !allowDot -> Unit
                                    else -> Text(c.toString(), style = T.titleExtraBoldXL)
                                }
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.padding(top = 12.dp, end = 16.dp, bottom = 12.dp).width(72.dp).height(248.dp - 24.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Ds.brand).tap(onDone),
                contentAlignment = Alignment.Center,
            ) { Ic(R.drawable.ic_check_mark) }
        }
    }
}
