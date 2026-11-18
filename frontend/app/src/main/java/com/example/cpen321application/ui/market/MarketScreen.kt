package com.example.cpen321application.ui.market

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cpen321application.ApiClient
import com.example.cpen321application.ApiService
import com.example.cpen321application.ui.StockLogo
import com.example.cpen321application.ui.market.data.MarketRepository
import com.example.cpen321application.ui.market.data.MarketStock
import com.example.cpen321application.util.SessionManager
import java.io.IOException
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException

private val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Red: Color @Composable get() = MaterialTheme.colorScheme.error

@Composable
fun MarketScreen(api: ApiService, onStockSelected: (String) -> Unit, onSignInRequired: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) {
        MarketViewModel.Factory(MarketRepository(ApiClient.createMarket(context)))
    }
    val model: MarketViewModel = viewModel(factory = factory)
    val state by model.state.collectAsState()
    val tokens = remember(context) { SessionManager.observeToken(context) }
    val token by tokens.collectAsState(initial = SessionManager.getToken(context))
    val sessionToken = token
    var favorites by remember(sessionToken) { mutableStateOf<Set<String>?>(null) }
    var favoritesBusy by remember(sessionToken) { mutableStateOf(false) }
    var favoritesError by remember(sessionToken) { mutableStateOf<String?>(null) }
    var needsSignIn by remember(sessionToken) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    fun requestFavorites(request: suspend (String) -> Set<String>) {
        if (sessionToken.isNullOrEmpty() || favoritesBusy || SessionManager.getToken(context) != sessionToken) return
        favoritesBusy = true
        favoritesError = null
        needsSignIn = false
        scope.launch {
            try {
                favorites = request("Bearer $sessionToken")
            } catch (error: HttpException) {
                needsSignIn = error.code() == 401
                favoritesError = if (needsSignIn) "Sign in again to access your favorites."
                    else "Favorites request failed (HTTP ${error.code()})."
            } catch (_: IOException) {
                favoritesError = "Could not reach the favorites server."
            } catch (_: com.google.gson.JsonParseException) {
                favoritesError = "Could not read your favorites."
            } finally {
                favoritesBusy = false
            }
        }
    }
    fun loadFavorites() = requestFavorites { api.getStockFavorites(it).tickers.toSet() }

    LaunchedEffect(sessionToken) { loadFavorites() }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                model.refresh()
                delay(8_000)
            }
        }
    }
    MarketContent(
        state = state,
        favoriteTickers = favorites.orEmpty(),
        signedIn = !sessionToken.isNullOrEmpty(),
        favoritesBusy = favoritesBusy,
        favoritesError = favoritesError,
        onFavoritesRetry = { if (needsSignIn) onSignInRequired() else loadFavorites() },
        onStockSelected = onStockSelected,
        onSignInRequired = onSignInRequired,
        onFavoriteChanged = { ticker, favorite ->
            val current = favorites
            if (sessionToken.isNullOrEmpty() || needsSignIn) {
                onSignInRequired()
            } else if (current == null) {
                loadFavorites()
            } else {
                requestFavorites { authorization ->
                    if (favorite) {
                        api.addStockFavorite(authorization, ticker)
                        current + ticker
                    } else {
                        api.removeStockFavorite(authorization, ticker)
                        current - ticker
                    }
                }
            }
        },
        onOpenFavorites = {
            if (needsSignIn) onSignInRequired() else loadFavorites()
            scope.launch { model.refresh() }
        },
        onRefresh = { scope.launch { model.refresh() } },
    )
}

@Composable
private fun MarketContent(
    state: MarketUiState,
    favoriteTickers: Set<String>,
    signedIn: Boolean,
    favoritesBusy: Boolean,
    favoritesError: String?,
    onFavoritesRetry: () -> Unit,
    onStockSelected: (String) -> Unit,
    onSignInRequired: () -> Unit,
    onFavoriteChanged: (String, Boolean) -> Unit,
    onOpenFavorites: () -> Unit,
    onRefresh: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    val stocksListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()
    val stocks = state.stocks.filter {
        (!showFavorites || it.ticker in favoriteTickers) &&
            (it.ticker.contains(query, true) || it.companyName.contains(query, true))
    }
    MaterialTheme {
        LazyColumn(
            state = if (showFavorites) favoritesListState else stocksListState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("TradeOff", color = Green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Market", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("Explore stocks. Practice investing.", color = Muted, fontSize = 14.sp)
            }
            item { ConnectionCard(state, onRefresh) }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search by company or ticker") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                )
            }
            stickyHeader {
                TabRow(selectedTabIndex = if (showFavorites) 1 else 0) {
                    Tab(selected = !showFavorites, onClick = { showFavorites = false },
                        text = { Text("Stocks") })
                    Tab(
                        selected = showFavorites,
                        onClick = {
                            if (!signedIn) {
                                onSignInRequired()
                            } else if (!showFavorites) {
                                showFavorites = true
                                onOpenFavorites()
                            }
                        },
                        text = { Text("Favorites") },
                    )
                }
            }
            if (favoritesError != null) {
                item {
                    Text(favoritesError, color = Red)
                    TextButton(onClick = onFavoritesRetry) { Text("Retry favorites") }
                }
            }
            if (stocks.isEmpty()) {
                item {
                    val message = when {
                        showFavorites && !signedIn -> "Sign in to save your favorite stocks."
                        showFavorites && favoritesBusy -> "Loading favorites…"
                        showFavorites && favoritesError != null -> null
                        showFavorites && favoriteTickers.isEmpty() -> "Tap a star in Stocks to add a favorite."
                        showFavorites -> "No favorites match your search."
                        else -> "No stocks match your search."
                    }
                    if (message != null) Text(message, color = Muted, modifier = Modifier.padding(16.dp))
                }
            }
            items(stocks, key = { it.ticker }) { stock ->
                StockCard(
                    stock = stock,
                    isFavorite = stock.ticker in favoriteTickers,
                    signedIn = signedIn,
                    favoritesBusy = favoritesBusy,
                    onClick = { onStockSelected(stock.ticker) },
                    onFavoriteChanged = { onFavoriteChanged(stock.ticker, it) },
                )
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
private fun StockCard(
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                val favoriteAction = when {
                    !signedIn -> "Sign in to add ${stock.ticker} to favorites"
                    isFavorite -> "Remove ${stock.ticker} from favorites"
                    else -> "Add ${stock.ticker} to favorites"
                }
                IconToggleButton(
                    checked = isFavorite,
                    enabled = !favoritesBusy,
                    onCheckedChange = onFavoriteChanged,
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
