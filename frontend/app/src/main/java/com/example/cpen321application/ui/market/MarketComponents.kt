package com.example.cpen321application.ui.market

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Red: Color @Composable get() = MaterialTheme.colorScheme.error

@Composable
internal fun MarketHeader() {
    Text("TradeOff", color = Green, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    Text("Market", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text("Explore stocks. Practice investing.", color = Muted, fontSize = 14.sp)
}

@Composable
internal fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQueryChange, singleLine = true,
        label = { Text("Search by company or ticker") },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
    )
}

@Composable
internal fun FavoritesTabs(showFavorites: Boolean, onSelect: (Boolean) -> Unit) {
    TabRow(selectedTabIndex = if (showFavorites) 1 else 0) {
        Tab(selected = !showFavorites, onClick = { onSelect(false) }, text = { Text("Stocks") })
        Tab(selected = showFavorites, onClick = { onSelect(true) }, text = { Text("Favorites") })
    }
}

@Composable
internal fun FavoritesErrorRow(message: String, onRetry: () -> Unit) {
    Text(message, color = Red)
    TextButton(onClick = onRetry) { Text("Retry favorites") }
}

@Composable
internal fun MarketFooter() {
    Text(
        "Virtual investing • Prices and charts from Alpaca IEX.\nIEX is one exchange, not the full US market.",
        color = Muted, fontSize = 12.sp,
    )
}

internal fun emptyMessage(showFavorites: Boolean, favorites: FavoritesUi): String? = when {
    showFavorites && !favorites.signedIn -> "Sign in to save your favorite stocks."
    showFavorites && favorites.busy -> "Loading favorites…"
    showFavorites && favorites.error != null -> null
    showFavorites && favorites.tickers.isEmpty() -> "Tap a star in Stocks to add a favorite."
    showFavorites -> "No favorites match your search."
    else -> "No stocks match your search."
}

@Composable
internal fun ConnectionCard(state: MarketUiState, onRetry: () -> Unit) {
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
                Text("Backend checked ${formatMarketTime(state.checkedAt)} · live server updates", color = Muted, fontSize = 11.sp)
            }
            if (issue != null) {
                Text(issue, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = onRetry, enabled = !state.refreshing) { Text("Retry connection") }
            }
        }
    }
}
