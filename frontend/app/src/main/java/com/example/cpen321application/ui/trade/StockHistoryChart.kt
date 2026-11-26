package com.example.cpen321application.ui.trade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.ui.market.data.MarketPricePoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

internal data class ChartObservation(val time: Long, val price: Double)

/** Use only real observations, with time-proportional spacing and no synthetic candles. */
internal fun chartObservations(points: List<MarketPricePoint>, days: Int): List<ChartObservation> {
    val ordered = points.mapNotNull { point ->
        val time = runCatching { Instant.parse(point.timestamp).toEpochMilli() }.getOrNull()
        if (time != null && point.price.isFinite() && point.price > 0) ChartObservation(time, point.price) else null
    }.associateBy { it.time }.values.sortedBy { it.time }
    val latest = ordered.lastOrNull()?.time ?: return emptyList()
    val start = latest - days * 86_400_000L
    return ordered.filter { it.time >= start }
}

@Composable
internal fun StockHistoryChart(ticker: String, points: List<MarketPricePoint>, color: Color, status: String?) {
    var days by rememberSaveable(ticker) { mutableIntStateOf(30) }
    var selectedTime by remember(ticker, days) { mutableStateOf<Long?>(null) }
    val observations = remember(points, days) { chartObservations(points, days) }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Price history", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "1D", 7 to "1W", 30 to "1M").forEach { (period, label) ->
                    FilterChip(
                        selected = days == period,
                        onClick = { days = period },
                        label = { Text(label, fontSize = 11.sp) },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
        }
        if (observations.size < 2) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Text("Not enough historical data for this period yet.", color = colors.onSurfaceVariant, fontSize = 13.sp)
            }
        } else {
            val first = observations.first()
            val last = observations.last()
            val selected = selectedTime?.let { time -> observations.minByOrNull { abs(it.time - time) } } ?: last
            val low = observations.minOf { it.price }
            val high = observations.maxOf { it.price }
            val padding = max((high - low) * 0.12, high * 0.001)
            val bottom = low - padding
            val top = high + padding
            val duration = (last.time - first.time).toDouble()
            val labelFormat = remember { DateTimeFormatter.ofPattern("MMM d, HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selected.price.usd(), fontWeight = FontWeight.SemiBold, color = color, fontSize = 15.sp)
                Text(labelFormat.format(Instant.ofEpochMilli(selected.time)), color = colors.onSurfaceVariant, fontSize = 10.sp)
            }
            val gridColor = colors.outlineVariant.copy(alpha = 0.5f)
            Row(Modifier.fillMaxWidth().height(218.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(
                    Modifier.weight(1f).fillMaxHeight().testTag("stock-detail-chart")
                        .semantics { contentDescription = "$ticker price history from Alpaca IEX, ${observations.size} real price points. Tap or drag to inspect." }
                        .pointerInput(observations) {
                            detectTapGestures { offset ->
                                val time = first.time + (offset.x / size.width).coerceIn(0f, 1f) * duration
                                selectedTime = observations.minByOrNull { abs(it.time - time) }?.time
                            }
                        }
                        .pointerInput(observations) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                val time = first.time + (change.position.x / size.width).coerceIn(0f, 1f) * duration
                                selectedTime = observations.minByOrNull { abs(it.time - time) }?.time
                            }
                        }
                ) {
                    fun position(point: ChartObservation) = Offset(
                        ((point.time - first.time) / duration * size.width).toFloat(),
                        ((top - point.price) / (top - bottom) * size.height).toFloat(),
                    )
                    for (line in 0..4) {
                        val y = size.height * line / 4
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    }
                    val path = Path().apply {
                        observations.forEachIndexed { index, point ->
                            val p = position(point)
                            if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                        }
                    }
                    val area = Path().apply {
                        addPath(path)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.20f), color.copy(alpha = 0.01f))))
                    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    val marker = position(selected)
                    if (selectedTime != null) drawLine(gridColor, Offset(marker.x, 0f), Offset(marker.x, size.height), strokeWidth = 1.dp.toPx())
                    drawCircle(color.copy(alpha = 0.14f), 7.dp.toPx(), marker)
                    drawCircle(color, 3.dp.toPx(), marker)
                }
                Column(Modifier.fillMaxHeight().width(59.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                    for (line in 0..4) Text((top - (top - bottom) * line / 4).usd(), fontSize = 9.sp, color = colors.onSurfaceVariant)
                }
            }
            val axisFormat = DateTimeFormatter.ofPattern(if (days == 1) "HH:mm" else "MMM d", Locale.US).withZone(ZoneOffset.UTC)
            Row(Modifier.fillMaxWidth().padding(end = 67.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(first.time, first.time + (last.time - first.time) / 2, last.time).forEach {
                    Text(axisFormat.format(Instant.ofEpochMilli(it)), color = colors.onSurfaceVariant, fontSize = 10.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Period low", color = colors.onSurfaceVariant, fontSize = 10.sp)
                    Text(low.usd(), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Period high", color = colors.onSurfaceVariant, fontSize = 10.sp)
                    Text(high.usd(), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
            }
        }
        Text(
            if (status == "stale") "History is temporarily stale. Showing the last available data."
            else "Daily history + latest trades · UTC",
            color = if (status == "stale") colors.error else colors.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}
