package com.example.cpen321application.ui.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import com.example.cpen321application.ui.StockLogo
import com.example.cpen321application.ui.market.data.MarketStock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val StockSnapshotTime = SemanticsPropertyKey<String>("StockSnapshotTime")

@Composable
internal fun StockDetailsCard(ticker: String, stock: MarketStock?, connectionError: String?) {
    val colors = MaterialTheme.colorScheme
    val connected = connectionError == null && stock?.status == "ok"
    val trend = if ((stock?.percentageChange ?: 0.0) < 0) colors.error else colors.primary
    val price = stock?.currentPrice?.toDoubleOrNull()
    Card(
        modifier = Modifier.fillMaxWidth().testTag("stock-details")
            .semantics { this[StockSnapshotTime] = stock?.fetchedAt.orEmpty() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StockLogo(ticker, Modifier.size(56.dp), stock?.companyName)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(ticker, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(stock?.companyName ?: "Stock details", color = colors.onSurfaceVariant, fontSize = 13.sp)
                }
                val statusColor = if (connected) colors.primary else colors.onSurfaceVariant
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .background(statusColor.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                    Text(if (connected) "Live" else if (price != null) "Stale" else "Connecting", fontSize = 11.sp, color = statusColor)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(price?.usd() ?: "—", modifier = Modifier.testTag("stock-detail-price"), fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    Text("USD", color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 7.dp))
                }
                val change = stock?.change?.toDoubleOrNull()
                val percent = stock?.percentageChange
                if (change != null && percent != null) {
                    val sign = if (change >= 0) "+" else "−"
                    Text(
                        "$sign${kotlin.math.abs(change).usd()} (${if (percent >= 0) "+" else ""}${String.format(Locale.US, "%.2f", percent)}%)",
                        color = trend, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    )
                    Text("vs previous close", color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            StockHistoryChart(ticker, stock?.history?.points.orEmpty(), trend, stock?.history?.status)
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ALPACA · IEX", color = colors.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                val quoteTime = stock?.quoteAsOf?.let {
                    runCatching {
                        DateTimeFormatter.ofPattern("MMM d, HH:mm:ss 'UTC'", Locale.US)
                            .withZone(ZoneOffset.UTC).format(Instant.parse(it))
                    }.getOrNull()
                }
                Text(quoteTime ?: "Waiting for a quote", color = colors.onSurfaceVariant, fontSize = 10.sp)
            }
            if (!connected) {
                Text(
                    connectionError ?: if (price != null) "Showing the last available quote. Trading waits for fresh data."
                    else "Waiting for live market data…",
                    color = colors.error, fontSize = 12.sp,
                )
            }
        }
    }
}
