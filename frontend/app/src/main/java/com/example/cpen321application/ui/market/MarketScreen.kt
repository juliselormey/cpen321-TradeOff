package com.example.cpen321application.ui.market

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cpen321application.ApiClient
import com.example.cpen321application.R
import com.example.cpen321application.ui.market.data.MarketRepository
import com.example.cpen321application.ui.market.data.MarketStock
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Red: Color @Composable get() = MaterialTheme.colorScheme.error

@Composable
fun MarketScreen(onStockSelected: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) {
        MarketViewModel.Factory(MarketRepository(ApiClient.createMarket(context)))
    }
    val model: MarketViewModel = viewModel(factory = factory)
    val state by model.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                model.refresh()
                delay(8_000)
            }
        }
    }
    MarketContent(state, onStockSelected, onRetry = { scope.launch { model.refresh() } })
}

@Composable
private fun MarketContent(state: MarketUiState, onStockSelected: (String) -> Unit, onRetry: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val stocks = state.stocks.filter {
        it.ticker.contains(query, true) || it.companyName.contains(query, true)
    }
    val favorites = stocks.filter { it.isFavorite }
    MaterialTheme {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("TradeOff", color = Green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Market", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("Explore stocks. Practice investing.", color = Muted, fontSize = 14.sp)
            }
            item { ConnectionCard(state, onRetry) }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search by company or ticker") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                )
            }
            item {
                Text("My Favorites", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (favorites.isEmpty()) {
                item { Text("No matching favorites.", color = Muted, fontSize = 12.sp) }
            }
            items(favorites, key = { "favorite:${it.ticker}" }) { stock ->
                StockCard(stock, onClick = { onStockSelected(stock.ticker) })
            }
            item {
                Text("All Stocks", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${stocks.size} stocks • USD", color = Muted, fontSize = 12.sp)
            }
            if (stocks.isEmpty()) {
                item { Text("No stocks match your search.", color = Muted, modifier = Modifier.padding(16.dp)) }
            }
            items(stocks, key = { "all:${it.ticker}" }) { stock ->
                StockCard(stock, onClick = { onStockSelected(stock.ticker) })
            }
            item {
                Text("Virtual investing • Prices and charts from Alpaca IEX.\nIEX is one exchange, not the full US market.", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: MarketUiState, onRetry: () -> Unit) {
    val available = state.stocks.count { it.status == "ok" && it.fetchedAt != null }
    val issue = state.error ?: state.stocks.firstNotNullOfOrNull { it.error?.message }
    val connected = issue == null && available == state.stocks.size && available > 0
    Card(colors = CardDefaults.cardColors(containerColor = if (issue == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (connected) "Alpaca IEX connected · $available stocks" else if (issue != null) "Market connection needs attention" else "Connecting to market…",
                    modifier = Modifier.weight(1f), color = if (issue == null) Green else Red,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                )
                if (state.refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text("$available/${state.stocks.size} quotes available · shared backend cache", color = Muted, fontSize = 12.sp)
            if (state.checkedAt != null) {
                Text("Backend checked ${time(state.checkedAt)} · refresh every 8s", color = Muted, fontSize = 11.sp)
            }
            if (issue != null) {
                Text(issue, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = onRetry, enabled = !state.refreshing) { Text("Retry connection") }
            }
        }
    }
}

@Composable
private fun StockCard(stock: MarketStock, onClick: () -> Unit) {
    val provider = stock.source == "alpaca"
    val positive = (stock.percentageChange ?: 0.0) >= 0
    val accent = if (positive) Green else Red
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StockLogo(stock)
                Column(Modifier.weight(1f)) {
                    Text(stock.ticker, fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp)
                    Text(stock.companyName, color = Muted, fontSize = 12.sp)
                }
                Text(if (provider) "ALPACA · IEX" else "SAMPLE", color = if (provider) Green else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(price(stock.currentPrice), color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Text(stock.percentageChange?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "—", color = accent, fontWeight = FontWeight.SemiBold)
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

private fun time(value: String, date: Boolean = false): String = runCatching {
    DateTimeFormatter.ofPattern(if (date) "MMM d, HH:mm:ss 'UTC'" else "HH:mm:ss 'UTC'", Locale.US)
        .withZone(ZoneOffset.UTC).format(Instant.parse(value))
}.getOrDefault("Unknown time")

@Composable
private fun StockHistory(stock: MarketStock, accent: Color) {
    stock.quoteAsOf?.let { Text("Quote: ${time(it, date = true)}", color = Muted, fontSize = 11.sp) }
    stock.fetchedAt?.let { Text("Fetched: ${time(it)}", color = Muted, fontSize = 11.sp) }
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

@Composable
private fun StockLogo(stock: MarketStock) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (stock.ticker == "AAPL") Color(0xFF111111) else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val logo = when (stock.ticker) {
            "AAPL" -> R.drawable.ic_stock_apple
            "MSFT" -> R.drawable.ic_stock_microsoft
            "TSLA" -> R.drawable.ic_stock_tesla
            "NVDA" -> R.drawable.ic_stock_nvidia
            "GOOGL" -> R.drawable.ic_stock_google
            "AMZN" -> R.drawable.ic_stock_amazon
            else -> null
        }
        if (logo != null) Image(
            painterResource(logo), "${stock.companyName} logo",
            Modifier.fillMaxSize().padding(
                if (stock.ticker in listOf("TSLA", "NVDA", "AMZN")) 0.dp else 9.dp,
            ),
        )
        else Text(stock.ticker.take(1), color = Green, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}
