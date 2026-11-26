package com.example.cpen321application.ui.trade

import com.example.cpen321application.util.runOrReportNetworkFailure
import com.example.cpen321application.TradingDefaults
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.cpen321application.ApiService
import com.example.cpen321application.ExecuteLimitOrderRequest
import com.example.cpen321application.ExecuteMarketOrderRequest
import com.example.cpen321application.PendingOrderItem
import com.example.cpen321application.ui.market.data.MarketApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.util.Locale

private const val TAG = "TradeScreen"

private fun <T> Response<T>.bodyOrNull(): T? = if (isSuccessful) body() else null


internal class TradeState(
    val ticker: String,
    private val isLoggedIn: () -> Boolean,
    private val apiService: ApiService?,
    private val marketApi: MarketApiService,
    private val scope: CoroutineScope,
) {
    private val accountRefreshLock = Mutex()

    // null until the first successful fetch
    var currentMarketPrice by mutableStateOf<Double?>(null)
    var marketChange by mutableStateOf<Double?>(null)
    var quoteFailed by mutableStateOf(false)

    var availableCash by mutableDoubleStateOf(TradingDefaults.STARTING_BALANCE)
    var ownedShares by mutableDoubleStateOf(0.0)
    private var liveAvailableShares by mutableStateOf<Double?>(null)
    val availableShares get() = if (isLive) liveAvailableShares ?: 0.0 else ownedShares


    var selectedOrderType by mutableStateOf("MARKET") // "MARKET" or "LIMIT"
    var quantityText by mutableStateOf("")
    var limitPriceText by mutableStateOf("")
    var pendingOrders by mutableStateOf(emptyList<PendingOrderItem>())

    var successMessage by mutableStateOf<String?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    private val isLive get() = apiService != null && isLoggedIn()

    val quantity get() = quantityText.toDoubleOrNull() ?: 0.0
    val limitPrice get() = limitPriceText.toDoubleOrNull()
    val hasPrice get() = currentMarketPrice != null
    val effectivePrice: Double?
        get() = if (selectedOrderType == "LIMIT") limitPrice ?: currentMarketPrice else currentMarketPrice
    val totalAmount get() = quantity * (effectivePrice ?: 0.0)

    // Validation
    val isQuantityValid get() = quantity > 0
    val isLimitPriceValid get() = selectedOrderType != "LIMIT" || ((limitPrice ?: 0.0) > 0)
    val hasEnoughCash get() = availableCash >= totalAmount
    val hasEnoughShares get() = availableShares >= quantity
    val isBuyEnabled get() = hasPrice && isQuantityValid && isLimitPriceValid && hasEnoughCash
    val isSellEnabled get() = hasPrice && isQuantityValid && isLimitPriceValid && hasEnoughShares

    val inputError: String?
        get() = when {
            quantityText.isNotEmpty() && !isQuantityValid -> "Please enter a valid quantity greater than 0."
            selectedOrderType == "LIMIT" && limitPriceText.isNotEmpty() && !isLimitPriceValid ->
                "Please enter a valid limit price greater than 0."
            else -> null
        }

    val buyBlocker: String?
        get() = if (isQuantityValid && !hasEnoughCash) {
            "Cannot Buy: Insufficient cash balance (${availableCash.usd()} available)."
        } else null

    val sellBlocker: String?
        get() = when {
            !isQuantityValid || hasEnoughShares -> null
            ownedShares == 0.0 -> "Cannot Sell: You do not own any shares of $ticker."
            else -> "Cannot Sell: Insufficient available shares (${availableShares.fmt()} available; pending sells reserve shares)."
        }

    // Refresh

    suspend fun refreshQuote() {
        runOrReportNetworkFailure({
            Log.e(TAG, "Failed to fetch live market price", it)
            quoteFailed = true
        }) {
            val matched = marketApi.getStocks().stocks.find { it.ticker.equals(ticker, ignoreCase = true) }
            val live = matched?.currentPrice?.toDoubleOrNull()
            if (live != null) {
                currentMarketPrice = live
                matched.percentageChange?.let { marketChange = it }
                quoteFailed = false
            } else {
                quoteFailed = true
            }
        }
    }

    suspend fun refreshAccount() {
        val api = apiService ?: return
        if (!isLoggedIn()) return
        accountRefreshLock.withLock {
            runOrReportNetworkFailure({ Log.e(TAG, "Error refreshing data", it) }) {
                api.getPosition(ticker).bodyOrNull()?.let {
                    availableCash = it.cashBalance
                    ownedShares = it.ownedQuantity
                    liveAvailableShares = it.availableQuantity
                }
                api.getPendingOrders().bodyOrNull()?.let { res ->
                    pendingOrders = res.orders.filter { it.ticker.equals(ticker, ignoreCase = true) }
                }
            }
        }
    }

    // Actions

    fun selectOrderType(value: String) { selectedOrderType = value }

    fun updateQuantity(value: String) {
        quantityText = value
        clearMessages()
    }

    fun updateLimitPrice(value: String) {
        limitPriceText = value
        clearMessages()
    }

    fun submit(side: String) {
        if (!(if (side == "BUY") isBuyEnabled else isSellEnabled)) return
        if (isLive) {
            scope.launch { executeOrder(side) }
        } else {
            // Offline / demo mode: apply locally
            val direction = if (side == "BUY") 1 else -1
            availableCash -= direction * totalAmount
            if (selectedOrderType == "MARKET") ownedShares += direction * quantity
            resetForm()
            successMessage = "Order submitted successfully!"
        }
    }

    private suspend fun executeOrder(side: String) {
        val api = apiService ?: return
        val price = currentMarketPrice ?: return
        runOrReportNetworkFailure({
            Log.e(TAG, "Order execution error", it)
            errorMessage = "Network error executing order"
        }) {
            val response = if (selectedOrderType == "LIMIT") {
                api.executeLimitOrder(
                    ExecuteLimitOrderRequest(
                        ticker = ticker,
                        side = side,
                        quantity = quantity,
                        limitPrice = limitPrice ?: price
                    )
                )
            } else {
                api.executeMarketOrder(
                    ExecuteMarketOrderRequest(
                        ticker = ticker,
                        side = side,
                        quantity = quantity,
                    )
                )
            }

            val body = response.bodyOrNull()
            if (body != null) {
                accountRefreshLock.withLock {
                    availableCash = body.cashBalance
                    ownedShares = body.ownedQuantity
                    liveAvailableShares = body.availableQuantity
                }
                resetForm()
                successMessage = body.message
                refreshAccount()
            } else {
                errorMessage = response.errorBody()?.string()
                    ?: "Failed to execute ${side.lowercase(Locale.US)} order"
            }
        }
    }

    fun cancelOrder(orderId: String) {
        val api = apiService ?: return
        scope.launch {
            runOrReportNetworkFailure({
                Log.e(TAG, "Failed to cancel order", it)
                errorMessage = "Failed to cancel order"
            }) {
                if (api.cancelOrder(orderId).isSuccessful) {
                    successMessage = "Pending order cancelled successfully"
                    refreshAccount()
                } else {
                    errorMessage = "Failed to cancel order"
                }
            }
        }
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
