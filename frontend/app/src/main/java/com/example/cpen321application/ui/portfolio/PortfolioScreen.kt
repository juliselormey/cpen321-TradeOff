package com.example.cpen321application.ui.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.ApiClient
import com.example.cpen321application.ApiService
import com.example.cpen321application.ui.market.data.MarketStream
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

private class PortfolioServiceKey(private val service: ApiService) {
    override fun equals(other: Any?) = other is PortfolioServiceKey && service === other.service
    override fun hashCode() = System.identityHashCode(service)
}

@Composable
fun PortfolioScreen(
    apiService: ApiService? = null,
    onBack: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onStockSelected: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = apiService ?: remember(context) { ApiClient.create(context) }
    val state = remember(context, PortfolioServiceKey(service)) {
        PortfolioState({ com.example.cpen321application.util.SessionManager.isLoggedIn(context) }, service)
    }
    PortfolioLiveUpdates(state)
    MaterialTheme {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("portfolio-list").background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PortfolioHeader(onBack, onSignOut) }
            portfolioOverview(state)
            portfolioHoldings(state.evaluatedHoldings, onStockSelected)
            portfolioActivity(state.summary) { orderId -> scope.launch { state.cancelOrder(orderId) } }
        }
    }
}

@Composable
private fun PortfolioLiveUpdates(state: PortfolioState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    val stream = remember(context) {
        MarketStream(ApiClient.createMarketEventsClient(context), ApiClient.marketEventsUrl())
    }
    LaunchedEffect(lifecycle, state, stream) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.observeMarket(stream)
        }
    }
}

@Composable
private fun PortfolioHeader(onBack: () -> Unit, onSignOut: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹ Back to Market", color = PortfolioGreen, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onSignOut, contentPadding = PaddingValues(0.dp)) {
                Text("Sign Out", color = PortfolioRed, fontWeight = FontWeight.Bold)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trade", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Text("Off", color = PortfolioGreen, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text("Learn. Invest. Grow.", color = PortfolioMuted, fontSize = 14.sp)
        Text("Portfolio", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
    }

}
