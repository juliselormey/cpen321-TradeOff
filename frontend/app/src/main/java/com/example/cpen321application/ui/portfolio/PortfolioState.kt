package com.example.cpen321application.ui.portfolio

import android.util.Log
import androidx.compose.runtime.*
import com.example.cpen321application.ApiService
import com.example.cpen321application.PortfolioSummaryResponse
import com.example.cpen321application.util.runOrReportNetworkFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PortfolioState(
    private val isLoggedIn: () -> Boolean,
    private val service: ApiService,
) {
    private val refreshLock = Mutex()
    var summary by mutableStateOf<PortfolioSummaryResponse?>(null)
    var valuationUnavailable by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    suspend fun loadPortfolioData() = refreshLock.withLock {
        if (isLoggedIn()) {
            isLoading = true
            try {
                runOrReportNetworkFailure({
                    Log.e("PortfolioScreen", "Error loading portfolio", it)
                    valuationUnavailable = true
                    errorMessage = "Network error loading portfolio. Values are unavailable."
                }) {
                    val response = service.getPortfolio()
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
