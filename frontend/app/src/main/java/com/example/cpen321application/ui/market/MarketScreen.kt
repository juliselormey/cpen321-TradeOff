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
import kotlinx.coroutines.CoroutineScope
import retrofit2.HttpException

private val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Red: Color @Composable get() = MaterialTheme.colorScheme.error

private data class FavoritesUi(
    val tickers: Set<String>,
    val signedIn: Boolean,
    val busy: Boolean,
    val error: String?,
)

private data class MarketActions(
    val onFavoritesRetry: () -> Unit,
    val onStockSelected: (String) -> Unit,
    val onSignInRequired: () -> Unit,
    val onFavoriteChanged: (String, Boolean) -> Unit,
    val onOpenFavorites: () -> Unit,
    val onRefresh: () -> Unit,
)

@Stable
private class FavoritesController(
    private val api: ApiService,
    private val sessionToken: String?,
    private val currentToken: () -> String?,
    private val scope: CoroutineScope,
) {
    var tickers by mutableStateOf<Set<String>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var needsSignIn by mutableStateOf(false)
        private set

    val signedIn: Boolean get() = !sessionToken.isNullOrEmpty()

    fun load() = request { api.getStockFavorites(it).tickers.toSet() }

    fun change(ticker: String, favorite: Boolean, onSignInRequired: () -> Unit) {
        val current = tickers
        when {
            !signedIn || needsSignIn -> onSignInRequired()
            current == null -> load()
            else -> request { authorization ->
                if (favorite) {
                    api.addStockFavorite(authorization, ticker)
                    current + ticker
                } else {
                    api.removeStockFavorite(authorization, ticker)
                    current - ticker
                }
            }
        }
    }

    private fun request(call: suspend (String) -> Set<String>) {
        if (sessionToken.isNullOrEmpty() || busy || currentToken() != sessionToken) return
        busy = true
        error = null
        needsSignIn = false
        scope.launch {
            try {
                tickers = call("Bearer $sessionToken")
            } catch (e: HttpException) {
                needsSignIn = e.code() == 401
                error = if (needsSignIn) "Sign in again to access your favorites."
                else "Favorites request failed (HTTP ${e.code()})."
            } catch (_: IOException) {
                error = "Could not reach the favorites server."
            } catch (_: com.google.gson.JsonParseException) {
                error = "Could not read your favorites."
            } finally {
                busy = false
            }
        }
    }
}

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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val favorites = remember(token, scope) {
        FavoritesController(api, token, { SessionManager.getToken(context) }, scope)
    }

    LaunchedEffect(favorites) { favorites.load() }
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
        favorites = FavoritesUi(
            tickers = favorites.tickers.orEmpty(),
            signedIn = favorites.signedIn,
            busy = favorites.busy,
            error = favorites.error,
        ),
        actions = MarketActions(
            onFavoritesRetry = { if (favorites.needsSignIn) onSignInRequired() else favorites.load() },
            onStockSelected = onStockSelected,
            onSignInRequired = onSignInRequired,
            onFavoriteChanged = { ticker, favorite -> favorites.change(ticker, favorite, onSignInRequired) },
            onOpenFavorites = {
                if (favorites.needsSignIn) onSignInRequired() else favorites.load()
                scope.launch { model.refresh() }
            },
            onRefresh = { scope.launch { model.refresh() } },
        ),
    )
}

@Composable
private fun MarketContent(state: MarketUiState, favorites: FavoritesUi, actions: MarketActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    val stocksListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()
    val stocks = state.stocks.filter {
        (!showFavorites || it.ticker in favorites.tickers) &&
                (it.ticker.contains(query, true) || it.companyName.contains(query, true))
    }
    MaterialTheme {
        LazyColumn(
            state = if (showFavorites) favoritesListState else stocksListState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { MarketHeader() }
            item { ConnectionCard(state, actions.onRefresh) }
            item { SearchField(query) { query = it } }
            stickyHeader {
                FavoritesTabs(showFavorites) { wantFavorites ->
                    if (!wantFavorites) {
                        showFavorites = false
                    } else if (!favorites.signedIn) {
                        actions.onSignInRequired()
                    } else if (!showFavorites) {
                        showFavorites = true
                        actions.onOpenFavorites()
                    }
                }
            }
            if (favorites.error != null) {
                item { FavoritesErrorRow(favorites.error, actions.onFavoritesRetry) }
            }
            if (stocks.isEmpty()) {
                item {
                    emptyMessage(showFavorites, favorites)?.let {
                        Text(it, color = Muted, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            items(stocks, key = { it.ticker }) { stock ->
                StockCard(
                    stock = stock,
                    isFavorite = stock.ticker in favorites.tickers,
                    signedIn = favorites.signedIn,
                    favoritesBusy = favorites.busy,
                    onClick = { actions.onStockSelected(stock.ticker) },
                    onFavoriteChanged = { actions.onFavoriteChanged(stock.ticker, it) },
                )
            }
            item { MarketFooter() }
        }
    }
}

@Composable
private fun MarketHeader() {
    Text("TradeOff", color = Green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    Text("Market", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text("Explore stocks. Practice investing.", color = Muted, fontSize = 14.sp)
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQueryChange, singleLine = true,
        label = { Text("Search by company or ticker") },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun FavoritesTabs(showFavorites: Boolean, onSelect: (Boolean) -> Unit) {
    TabRow(selectedTabIndex = if (showFavorites) 1 else 0) {
        Tab(selected = !showFavorites, onClick = { onSelect(false) }, text = { Text("Stocks") })
        Tab(selected = showFavorites, onClick = { onSelect(true) }, text = { Text("Favorites") })
    }
}

@Composable
private fun FavoritesErrorRow(message: String, onRetry: () -> Unit) {
    Text(message, color = Red)
    TextButton(onClick = onRetry) { Text("Retry favorites") }
}

@Composable
private fun MarketFooter() {
    Text(
        "Virtual investing • Prices and charts from Alpaca IEX.\nIEX is one exchange, not the full US market.",
        color = Muted, fontSize = 12.sp,
    )
}

private fun emptyMessage(showFavorites: Boolean, favorites: FavoritesUi): String? = when {
    showFavorites && !favorites.signedIn -> "Sign in to save your favorite stocks."
    showFavorites && favorites.busy -> "Loading favorites…"
    showFavorites && favorites.error != null -> null
    showFavorites && favorites.tickers.isEmpty() -> "Tap a star in Stocks to add a favorite."
    showFavorites -> "No favorites match your search."
    else -> "No stocks match your search."
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
                FavoriteToggle(stock.ticker, isFavorite, signedIn, favoritesBusy, onFavoriteChanged)
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