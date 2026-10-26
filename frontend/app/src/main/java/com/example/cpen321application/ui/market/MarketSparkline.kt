package com.example.cpen321application.ui.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.ui.market.data.MarketPricePoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun MarketSparkline(points: List<MarketPricePoint>, color: Color) {
    val ordered = remember(points) {
        points.mapNotNull { point ->
            val epoch = runCatching { Instant.parse(point.timestamp).toEpochMilli() }.getOrNull()
            if (epoch != null && point.price.isFinite() && point.price > 0) epoch to point.price else null
        }.distinctBy { it.first }.sortedBy { it.first }
    }
    if (ordered.size < 2) return
    val minimum = ordered.minOf { it.second }
    val range = ordered.maxOf { it.second } - minimum
    val start = ordered.first().first
    val duration = (ordered.last().first - start).toDouble()
    Canvas(Modifier.fillMaxWidth().height(72.dp).padding(vertical = 8.dp).semantics {
        contentDescription = "AAPL price history from Alpaca IEX, ${ordered.size} real price points"
    }) {
        fun point(index: Int): Offset {
            val (timestamp, price) = ordered[index]
            return Offset(
                (size.width * (timestamp - start) / duration).toFloat(),
                if (range == 0.0) size.height / 2 else (size.height * (1 - (price - minimum) / range)).toFloat(),
            )
        }
        for (index in 0 until ordered.lastIndex) {
            drawLine(color, point(index), point(index + 1), strokeWidth = 2.dp.toPx())
        }
    }
    val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneOffset.UTC)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatter.format(Instant.ofEpochMilli(start)), color = Color.Gray, fontSize = 10.sp)
        Text(formatter.format(Instant.ofEpochMilli(ordered.last().first)), color = Color.Gray, fontSize = 10.sp)
    }
}
