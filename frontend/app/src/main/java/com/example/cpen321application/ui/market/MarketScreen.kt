package com.example.cpen321application.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cpen321application.ApiClient
import com.example.cpen321application.ApiService
import com.example.cpen321application.ui.market.data.MarketRepository
import com.example.cpen321application.ui.market.data.MarketStream
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.launch

private data class MarketActions(
    val onFavoritesRetry: () -> Unit,
    val onStockSelected: (String) -> Unit,
    val onSignInRequired: () -> Unit,
    val onFavoriteChanged: (String, Boolean) -> Unit,
    val onOpenFavorites: () -> Unit,
    val onRefresh: () -> Unit,
)

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

    val stream = remember(context) {
        MarketStream(ApiClient.createMarketEventsClient(context), ApiClient.marketEventsUrl())
    }
    LaunchedEffect(favorites) { favorites.load() }
    LaunchedEffect(model, lifecycle, stream) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.observeMarket(stream)
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
    // Stocks keeps every matching stock; Favorites displays only saved tickers.
    val stocks = state.stocks.filter {
        (!showFavorites || it.ticker in favorites.tickers) &&
                (it.ticker.contains(query, true) || it.companyName.contains(query, true))
    }
    MaterialTheme {
        LazyColumn(
            state = if (showFavorites) favoritesListState else stocksListState,
            modifier = Modifier.fillMaxSize().testTag("market-list")
                .semantics { stateDescription = "${stocks.size} matching stocks" }
                .background(MaterialTheme.colorScheme.background),
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
