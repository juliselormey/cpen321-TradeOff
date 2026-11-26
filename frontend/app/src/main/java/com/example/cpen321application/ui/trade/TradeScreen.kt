package com.example.cpen321application.ui.trade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.cpen321application.ApiService
import com.example.cpen321application.util.SessionManager
import com.example.cpen321application.ui.market.MarketViewModel
import com.example.cpen321application.ui.market.MarketUiState
import com.example.cpen321application.ui.market.data.MarketRepository
import com.example.cpen321application.ui.market.data.MarketStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 8_000L

// Retrofit proxy equals() is not reflexive; Compose keys must compare these by identity.
internal class TradeServiceKey(private val service: Any?) {
    override fun equals(other: Any?) = other is TradeServiceKey && service === other.service
    override fun hashCode() = System.identityHashCode(service)
}

@Composable
internal fun rememberTradeState(
    ticker: String,
    api: ApiService?,
    market: com.example.cpen321application.ui.market.data.MarketApiService,
    scope: kotlinx.coroutines.CoroutineScope,
    isLoggedIn: () -> Boolean,
): TradeState = remember(ticker, TradeServiceKey(api), TradeServiceKey(market), scope) {
    TradeState(ticker, isLoggedIn, api, market, scope)
}

@Composable
fun TradeScreen(ticker: String, apiService: ApiService? = null, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val marketApi = remember(context) { ApiClient.createMarket(context) }
    val state = rememberTradeState(ticker, apiService, marketApi, scope) { SessionManager.isLoggedIn(context) }
    val factory = remember(TradeServiceKey(marketApi)) { MarketViewModel.Factory(MarketRepository(marketApi)) }
    val market: MarketViewModel = viewModel(key = "trade-market-$ticker", factory = factory)
    val marketState by market.state.collectAsState()
    val stock = marketState.stocks.find { it.ticker.equals(ticker, ignoreCase = true) }
    val stream = remember(context) {
        MarketStream(ApiClient.createMarketEventsClient(context), ApiClient.marketEventsUrl())
    }
    LaunchedEffect(state, stock, marketState.error) {
        state.applyMarketStock(stock, marketState.error != null)
    }
    TradeUpdates(state, market, stream)
    TradeContent(state, marketState, onBack)
}

@Composable
private fun TradeUpdates(state: TradeState, market: MarketViewModel, stream: MarketStream) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle, market, stream) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { market.observeMarket(stream) }
            launch {
                // Private account/order data has its own REST refresh; prices and charts use WS.
                while (isActive) {
                    state.refreshAccount()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }
}

@Composable
private fun TradeContent(state: TradeState, market: MarketUiState, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TradeTopBar(state.ticker, onBack)
        StockDetailsCard(
            ticker = state.ticker,
            stock = market.stocks.find { it.ticker.equals(state.ticker, ignoreCase = true) },
            connectionError = market.error,
        )
        AccountPositionCard(state.availableCash, state.ownedShares, state.accountLoaded)
        OrderEntryCard(state)
        if (state.pendingOrders.isNotEmpty()) {
            PendingOrdersCard(state.pendingOrders, state::cancelOrder)
        }
        state.errorMessage?.let {
            MessageBanner(it, Color(0xFFFFEBEE), Red) { state.errorMessage = null }
        }
        state.successMessage?.let {
            MessageBanner(it, Color(0xFFE8F5E9), Color(0xFF1B5E20)) { state.successMessage = null }
        }
    }
}

@Composable
private fun TradeTopBar(ticker: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Market")
        }
        Text(
            text = "Trade $ticker",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun MessageBanner(message: String, background: Color, foreground: Color, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                color = foreground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("Dismiss", color = foreground) }
        }
    }
}
