package com.example.cpen321application.ui.portfolio

import com.example.cpen321application.TradingDefaults
import com.example.cpen321application.util.runOrReportNetworkFailure
import android.util.Log
import androidx.compose.runtime.*
import com.example.cpen321application.HoldingItemResponse
import com.example.cpen321application.ApiService
import com.example.cpen321application.PortfolioSummaryResponse
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

data class EvaluatedHolding(
    val ticker: String,
    val quantity: Double,
    val averageCost: Double,
    val currentPrice: Double?,
    val currentValue: Double?,
    val costBasis: Double,
) {
    val profitLoss: Double? get() = currentValue?.minus(costBasis)
    val returnPercent: Double? get() = profitLoss?.let { if (costBasis > 0) (it / costBasis) * 100 else 0.0 }
}

internal class PortfolioState(
    private val context: android.content.Context,
    private val service: ApiService,
    private val marketApi: com.example.cpen321application.ui.market.data.MarketApiService,
) {
    private val refreshLock = Mutex()
    var summary by mutableStateOf<PortfolioSummaryResponse?>(null)
    var stockPrices by mutableStateOf(mapOf<String, Double>())
    var valuationUnavailable by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    suspend fun loadPortfolioData() = refreshLock.withLock {
        if (SessionManager.isLoggedIn(context)) {
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

    suspend fun refreshPrices() {
        runOrReportNetworkFailure({
            Log.w("PortfolioScreen", "Error refreshing prices", it)
            stockPrices = emptyMap()
        }) {
            stockPrices = marketApi.getStocks().stocks.mapNotNull { stock ->
                stock.currentPrice?.toDoubleOrNull()
                    ?.takeIf { stock.status == "ok" && it.isFinite() && it > 0 }
                    ?.let { stock.ticker.uppercase(Locale.US) to it }
            }.toMap()
        }
    }

    // Evaluate live values of holdings
    val evaluatedHoldings get() = summary?.holdings?.map { holding ->
        evaluateHolding(holding, if (valuationUnavailable) null else stockPrices[holding.ticker.uppercase(Locale.US)])
    } ?: emptyList()

    val cashBalance get() = summary?.cashBalance ?: TradingDefaults.STARTING_BALANCE
    // Pending BUY orders reserve cash; it remains part of the user's assets.
    val totalCash get() = cashBalance + (summary?.reservedCash ?: 0.0)
    val hasUnavailablePrices get() = valuationUnavailable || evaluatedHoldings.any { it.currentPrice == null }
    val investedValue: Double? get() = if (hasUnavailablePrices) null else evaluatedHoldings.sumOf { it.currentValue ?: 0.0 }
    val totalPortfolioValue get() = investedValue?.plus(totalCash)
    val totalProfitLoss: Double? get() = if (hasUnavailablePrices) null else evaluatedHoldings.sumOf { it.profitLoss ?: 0.0 }
    val totalCostBasis get() = evaluatedHoldings.sumOf { it.costBasis }
    val totalReturnPercent: Double? get() = totalProfitLoss?.let { if (totalCostBasis > 0) (it / totalCostBasis) * 100 else 0.0 }

    suspend fun cancelOrder(orderId: String) {
        runOrReportNetworkFailure({ Log.e("PortfolioScreen", "Error cancelling order", it) }) {
            if (service.cancelOrder(orderId).isSuccessful) loadPortfolioData()
        }
    }
}

/** Missing quotes remain unknown; acquisition cost is never a substitute for a market price. */
internal fun evaluateHolding(holding: HoldingItemResponse, price: Double?): EvaluatedHolding {
    val livePrice = price?.takeIf { it.isFinite() && it > 0 }
    return EvaluatedHolding(
        ticker = holding.ticker,
        quantity = holding.quantity,
        averageCost = holding.averageCost,
        currentPrice = livePrice,
        currentValue = livePrice?.let { holding.quantity * it },
        costBasis = holding.quantity * holding.averageCost,
    )
}
