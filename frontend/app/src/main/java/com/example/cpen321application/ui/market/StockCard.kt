package com.example.cpen321application.ui.market

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.ui.StockLogo
import com.example.cpen321application.ui.market.data.MarketStock
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
private fun FavoriteToggle(
    ticker: String,
    isFavorite: Boolean,
    signedIn: Boolean,
    busy: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    val favoriteAction = when {
        !signedIn -> "Sign in to add $ticker to favorites"
        isFavorite -> "Remove $ticker from favorites"
        else -> "Add $ticker to favorites"
    }
    IconToggleButton(
        checked = isFavorite,
        enabled = !busy,
        onCheckedChange = onChanged,
        modifier = Modifier.semantics { contentDescription = favoriteAction },
    ) {
        val tint by animateColorAsState(
            targetValue = if (isFavorite) Green else Muted.copy(alpha = 0.45f),
            animationSpec = tween(durationMillis = 180),
            label = "Favorite star tint",
        )
        Icon(Icons.Rounded.Star, contentDescription = null, tint = tint)
    }
}

@Composable
internal fun StockCard(
    stock: MarketStock,
    isFavorite: Boolean,
    signedIn: Boolean,
    favoritesBusy: Boolean,
    onClick: () -> Unit,
    onFavoriteChanged: (Boolean) -> Unit,
) {
    val provider = stock.source == "alpaca"
    val positive = (stock.percentageChange ?: 0.0) >= 0
    val accent = if (positive) Green else Red
    Card(
        modifier = Modifier.fillMaxWidth().testTag("stock-${stock.ticker}").clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StockLogo(stock.ticker, companyName = stock.companyName)
                Column(Modifier.weight(1f)) {
                    Text(stock.ticker, fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp)
                    Text(stock.companyName, color = Muted, fontSize = 12.sp)
                    Text(if (provider) "ALPACA · IEX" else "SAMPLE", color = if (provider) Green else Muted,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                FavoriteToggle(stock.ticker, isFavorite, signedIn, favoritesBusy, onFavoriteChanged)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(price(stock.currentPrice), modifier = Modifier.testTag("stock-price-${stock.ticker}"), color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Text(stock.percentageChange?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "—", modifier = Modifier.testTag("stock-change-${stock.ticker}"), color = accent, fontWeight = FontWeight.SemiBold)
            }
            if (provider) {
                stock.change?.toBigDecimalOrNull()?.let { change ->
                    Text(
                        "${if (change.signum() >= 0) "+" else "−"}${price(change.abs().toPlainString())} vs previous close",
                        color = accent, fontSize = 12.sp,
                    )
                }
                Text(
                    when (stock.status) {
                        "ok" -> "Latest available quote"
                        "stale" -> "Retained quote · refresh failed"
                        else -> "Waiting for a provider quote"
                    },
                    color = if (stock.status == "ok") Green else Red, fontSize = 12.sp,
                )
                StockHistory(stock, accent)
            } else {
                Text("Market data unavailable", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

private fun price(value: String?): String = value?.toBigDecimalOrNull()?.let {
    NumberFormat.getCurrencyInstance(Locale.US).format(it)
} ?: "—"

internal fun formatMarketTime(value: String, date: Boolean = false): String = runCatching {
    DateTimeFormatter.ofPattern(if (date) "MMM d, HH:mm:ss 'UTC'" else "HH:mm:ss 'UTC'", Locale.US)
        .withZone(ZoneOffset.UTC).format(Instant.parse(value))
}.getOrDefault("Unknown time")

@Composable
private fun StockHistory(stock: MarketStock, accent: Color) {
    stock.quoteAsOf?.let { Text("Quote: ${formatMarketTime(it, date = true)}", color = Muted, fontSize = 11.sp) }
    stock.fetchedAt?.let { Text("Fetched: ${formatMarketTime(it)}", color = Muted, fontSize = 11.sp) }
    val points = stock.history?.points.orEmpty()
    if (points.size >= 2) {
        MarketSparkline(points, accent, stock.ticker)
        Text("Cached price history · IEX only", color = Muted, fontSize = 11.sp)
    } else {
        Text("Not enough historical data for a chart yet.", color = Muted, fontSize = 11.sp)
    }
    if (stock.history?.status == "stale" || stock.history?.error != null) {
        Text("Chart history may be incomplete or stale.", color = Red, fontSize = 11.sp)
    }
    stock.error?.let { Text(it.message, color = Red, fontSize = 11.sp) }
    Text("Prices may stay unchanged outside trading hours.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))

}
