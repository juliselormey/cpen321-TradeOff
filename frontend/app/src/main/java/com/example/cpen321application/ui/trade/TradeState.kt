package com.example.cpen321application.ui.trade

import androidx.compose.runtime.*
import com.example.cpen321application.ApiService
import com.example.cpen321application.TradingDefaults
import com.example.cpen321application.ui.market.data.MarketApiService
import com.example.cpen321application.ui.market.data.MarketStock
import com.example.cpen321application.PendingOrderItem
import com.example.cpen321application.util.runOrReportNetworkFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TradeState(
    val ticker: String,
    private val isLoggedIn: () -> Boolean,
    apiService: ApiService?,
    private val marketApi: MarketApiService,
    private val scope: CoroutineScope,
) {
    private val repository = apiService?.let(::TradeRepository)
    private val accountRefreshLock = Mutex()
    var currentMarketPrice by mutableStateOf<Double?>(null)
    var marketChange by mutableStateOf<Double?>(null)
    var quoteFailed by mutableStateOf(false)
    var availableCash by mutableDoubleStateOf(TradingDefaults.STARTING_BALANCE)
    var ownedShares by mutableDoubleStateOf(0.0)
    private var liveAvailableShares by mutableStateOf<Double?>(null)
    var isSubmitting by mutableStateOf(false)
        private set
    var selectedOrderType by mutableStateOf("MARKET")
        private set
    var quantityText by mutableStateOf("")
        private set
    var limitPriceText by mutableStateOf("")
        private set
    var pendingOrders by mutableStateOf(emptyList<PendingOrderItem>())
    var successMessage by mutableStateOf<String?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    private val sessionUsable get() = repository == null || isLoggedIn()
    val availableShares get() = if (repository != null) liveAvailableShares ?: 0.0 else ownedShares
    val quantity get() = quantityText.toDoubleOrNull() ?: 0.0
    val limitPrice get() = limitPriceText.toDoubleOrNull()
    val hasPrice get() = currentMarketPrice?.let { it.isFinite() && it > 0 } == true && !quoteFailed
    val effectivePrice get() = if (selectedOrderType == "LIMIT") limitPrice ?: currentMarketPrice else currentMarketPrice
    val totalAmount get() = quantity * (effectivePrice ?: 0.0)
    val isQuantityValid get() = validOrderAmount(quantityText)
    val isLimitPriceValid get() = selectedOrderType != "LIMIT" || validOrderAmount(limitPriceText)
    val hasEnoughCash get() = availableCash >= totalAmount
    val hasEnoughShares get() = availableShares >= quantity
    val isBuyEnabled get() = !isSubmitting && sessionUsable && hasPrice && isQuantityValid && isLimitPriceValid && hasEnoughCash
    val isSellEnabled get() = !isSubmitting && sessionUsable && hasPrice && isQuantityValid && isLimitPriceValid && hasEnoughShares
    val inputError: String? get() = when {
        quantityText.isNotEmpty() && !isQuantityValid -> if (quantity <= 0) "Please enter a valid quantity greater than 0."
            else "Quantity must be at most 1,000,000 with no more than 4 decimal places."
        selectedOrderType == "LIMIT" && limitPriceText.isNotEmpty() && !isLimitPriceValid ->
            "Limit price must be positive, at most 1,000,000, with no more than 4 decimal places."
        else -> null
    }
    val buyBlocker: String? get() = if (isQuantityValid && !hasEnoughCash)
        "Cannot Buy: Insufficient cash balance (${availableCash.usd()} available)." else null
    val sellBlocker: String? get() = when {
        !isQuantityValid || hasEnoughShares -> null
        ownedShares == 0.0 -> "Cannot Sell: You do not own any shares of $ticker."
        else -> "Cannot Sell: Insufficient available shares (${availableShares.fmt()} available; pending sells reserve shares)."
    }

    suspend fun refreshQuote() {
        runOrReportNetworkFailure({ quoteFailed = true }) { acceptQuotes(marketApi.getStocks().stocks) }
    }
    internal fun acceptQuotes(stocks: List<MarketStock>) {
        val matched = stocks.find { it.ticker.equals(ticker, ignoreCase = true) }
        val price = matched?.currentPrice?.toDoubleOrNull()?.takeIf { matched.status == "ok" && it.isFinite() && it > 0 }
        quoteFailed = price == null
        if (price != null) { currentMarketPrice = price; marketChange = matched.percentageChange }
    }
    suspend fun refreshAccount() {
        val live = repository ?: return
        if (!isLoggedIn()) return
        accountRefreshLock.withLock {
            runOrReportNetworkFailure({ reportFailure(it) }) {
                val account = live.account(ticker)
                availableCash = account.cash
                ownedShares = account.owned
                liveAvailableShares = account.available
                pendingOrders = account.orders
            }
        }
    }
    fun selectOrderType(value: String) { if (!isSubmitting && value in listOf("MARKET", "LIMIT")) selectedOrderType = value }
    fun updateQuantity(value: String) { if (!isSubmitting) { quantityText = value; clearMessages() } }
    fun updateLimitPrice(value: String) { if (!isSubmitting) { limitPriceText = value; clearMessages() } }

    fun submit(side: String) {
        if (side !in listOf("BUY", "SELL") || !(if (side == "BUY") isBuyEnabled else isSellEnabled)) return
        val order = SubmittedOrder(ticker, side, selectedOrderType, quantity, if (selectedOrderType == "LIMIT") limitPrice else null)
        if (repository == null) {
            val result = executeDemoOrder(order, requireNotNull(currentMarketPrice), availableCash, ownedShares)
            availableCash = result.cash
            ownedShares = result.owned
            resetForm()
            successMessage = "Order submitted successfully!"
            return
        }
        isSubmitting = true // Set before launching, so a second tap cannot enqueue another request.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                runOrReportNetworkFailure({ reportFailure(it) }) {
                    val result = repository.execute(order)
                    accountRefreshLock.withLock {
                        availableCash = result.cashBalance
                        ownedShares = result.ownedQuantity
                        liveAvailableShares = result.availableQuantity
                    }
                    resetForm()
                    successMessage = result.message
                    refreshAccount()
                }
            } finally { isSubmitting = false }
        }
    }
    fun cancelOrder(orderId: String) {
        val live = repository ?: return
        if (isSubmitting || !isLoggedIn()) return
        isSubmitting = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                runOrReportNetworkFailure({ reportFailure(it) }) {
                    live.cancel(orderId)
                    successMessage = "Pending order cancelled successfully"
                    refreshAccount()
                }
            } finally { isSubmitting = false }
        }
    }
    private fun reportFailure(error: Exception) {
        errorMessage = if (error is TradeRequestFailure) error.message else "Could not confirm the request. Refresh before trying again."
    }
    private fun clearMessages() {
        successMessage = null
        errorMessage = null
    }
    private fun resetForm() {
        errorMessage = null
        quantityText = ""
        limitPriceText = ""
    }
}
