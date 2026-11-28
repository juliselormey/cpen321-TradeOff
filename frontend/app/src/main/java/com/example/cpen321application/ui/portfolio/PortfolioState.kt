package com.example.cpen321application.ui.portfolio

import android.util.Log
import androidx.compose.runtime.*
import com.example.cpen321application.ApiService
import com.example.cpen321application.PortfolioSummaryResponse
import com.example.cpen321application.util.runOrReportNetworkFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.cpen321application.ui.market.data.MarketStream
import com.example.cpen321application.ui.market.data.observeMarketRefreshes

internal class PortfolioState(
    private val isLoggedIn: () -> Boolean,
    private val service: ApiService,
) {
    private val refreshLock = Mutex()
    private var marketGeneration = 0L
    private var marketConnected = true
    suspend fun observeMarket(stream: MarketStream) = observeMarketRefreshes(stream,
        onSnapshot = { marketGeneration++; marketConnected = true },
        onDisconnect = {
            marketGeneration++
            marketConnected = false
            valuationUnavailable = true
            errorMessage = "Live market disconnected. Reconnecting…"
        },
        refresh = { loadPortfolioData() },
    )
    var summary by mutableStateOf<PortfolioSummaryResponse?>(null)
    var valuationUnavailable by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    suspend fun loadPortfolioData() = refreshLock.withLock {
        if (isLoggedIn()) {
            val generation = marketGeneration
            isLoading = true
            try {
                runOrReportNetworkFailure({
                    if (generation != marketGeneration || !marketConnected) return@runOrReportNetworkFailure
                    Log.e("PortfolioScreen", "Error loading portfolio", it)
                    valuationUnavailable = true
                    errorMessage = "Network error loading portfolio. Values are unavailable."
                }) {
                    val response = service.getPortfolio()
                    if (generation != marketGeneration || !marketConnected) return@runOrReportNetworkFailure
                    if (response.isSuccessful && response.body() != null) {
                        summary = response.body()
                        valuationUnavailable = false
                        errorMessage = null
                    } else {
                        valuationUnavailable = true
                        errorMessage = if (response.code() == 503) "Live prices are unavailable. Portfolio values cannot be calculated."
                            else "Failed to load portfolio. Values are unavailable."
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    // Values are one authoritative server snapshot, never recomputed from a second price feed.
    val evaluatedHoldings get() = summary?.holdings.orEmpty().map { holding ->
        if (valuationUnavailable) holding.copy(price = null, value = null, profitLoss = null, returnPercent = null)
        else holding
    }
    val cashBalance get() = summary?.cashBalance
    val totalPortfolioValue get() = summary?.totalValue.takeUnless { valuationUnavailable }
    val totalProfitLoss get() = summary?.profitLoss.takeUnless { valuationUnavailable }
    val totalReturnPercent get() = summary?.returnPercent.takeUnless { valuationUnavailable }
    val allocation get() = if (valuationUnavailable) emptyList() else summary?.allocation.orEmpty()
    val hasUnavailablePrices get() = valuationUnavailable || (summary != null && totalPortfolioValue == null)

    suspend fun cancelOrder(orderId: String) {
        runOrReportNetworkFailure({ Log.e("PortfolioScreen", "Error cancelling order", it) }) {
            if (service.cancelOrder(orderId).isSuccessful) loadPortfolioData()
        }
    }
}
