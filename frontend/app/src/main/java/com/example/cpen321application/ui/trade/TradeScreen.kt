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
import com.example.cpen321application.ApiClient
import com.example.cpen321application.ApiService
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 8_000L

@Composable
fun TradeScreen(ticker: String, apiService: ApiService? = null, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val marketApi = remember(context) { ApiClient.createMarket(context) }
    val state = remember(ticker, context, apiService, marketApi, scope) {
        TradeState(ticker, { SessionManager.isLoggedIn(context) }, apiService, marketApi, scope)
    }
    TradePolling(state)
    TradeContent(state, onBack)
}

@Composable
private fun TradePolling(state: TradeState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                coroutineScope {
                    launch { state.refreshQuote() }
                    launch { state.refreshAccount() }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }
}

@Composable
private fun TradeContent(state: TradeState, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TradeTopBar(state.ticker, onBack)
        StockHeaderCard(state.ticker, state.currentMarketPrice, state.marketChange, state.quoteFailed)
        AccountPositionCard(state.availableCash, state.ownedShares)
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
