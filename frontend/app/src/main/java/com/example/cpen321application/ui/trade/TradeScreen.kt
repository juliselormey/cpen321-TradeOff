package com.example.cpen321application.ui.trade

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.cpen321application.ApiClient
import com.example.cpen321application.ApiService
import com.example.cpen321application.ExecuteLimitOrderRequest
import com.example.cpen321application.ExecuteMarketOrderRequest
import com.example.cpen321application.PendingOrderItem
import com.example.cpen321application.ui.market.data.MarketApiService
import com.example.cpen321application.util.SessionManager
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.Locale

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private const val TAG = "TradeScreen"
private const val POLL_INTERVAL_MS = 8_000L

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val DECIMAL = Regex("^\\d*\\.?\\d*$")

private fun Double.fmt() = String.format(Locale.US, "%.2f", this)
private fun Double.usd() = "$${fmt()}"

private fun <T> Response<T>.bodyOrNull(): T? = if (isSuccessful) body() else null

private inline fun <T> runOrReportNetworkFailure(onError: (Exception) -> Unit, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    onError(e); null
} catch (e: HttpException) {
    onError(e); null
} catch (e: JsonParseException) {
    onError(e); null
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

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

    var availableCash by mutableDoubleStateOf(10000.00)
    var ownedShares by mutableDoubleStateOf(0.0)

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
    val hasEnoughShares get() = ownedShares >= quantity
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
            else -> "Cannot Sell: Insufficient shares (You own ${ownedShares.fmt()} shares)."
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
                        pricePerShare = price,
                        limitPrice = limitPrice ?: price
                    )
                )
            } else {
                api.executeMarketOrder(
                    ExecuteMarketOrderRequest(
                        ticker = ticker,
                        side = side,
                        quantity = quantity,
                        pricePerShare = price
                    )
                )
            }

            val body = response.bodyOrNull()
            if (body != null) {
                accountRefreshLock.withLock {
                    availableCash = body.cashBalance
                    ownedShares = body.ownedQuantity
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

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Components
// ---------------------------------------------------------------------------

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
private fun StockHeaderCard(ticker: String, price: Double?, change: Double?, quoteFailed: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ticker, fontSize = 16.sp, color = Color.Gray)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(price?.usd() ?: "--", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                val positive = (change ?: 0.0) >= 0
                Text(
                    text = change?.let { "${if (positive) "+" else ""}${it.fmt()}%" } ?: "--",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        change == null -> Color.Gray
                        positive -> Green
                        else -> Red
                    }
                )
            }
            if (price == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!quoteFailed) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = if (quoteFailed) "Couldn't load price. Retrying…" else "Fetching live price…",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountPositionCard(availableCash: Double, ownedShares: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your Position & Cash", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Available Cash", fontSize = 13.sp, color = Color.Gray)
                    Text(availableCash.usd(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Owned Shares", fontSize = 13.sp, color = Color.Gray)
                    Text(ownedShares.fmt(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OrderEntryCard(state: TradeState) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Order Details", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OrderTypeSelector(state.selectedOrderType, state::selectOrderType)
            OrderInputFields(state)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Estimated Total", fontSize = 15.sp, color = Color.Gray)
                Text(
                    text = if (state.hasPrice) state.totalAmount.usd() else "--",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            OrderValidation(state)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TradeButton("BUY", Green, Color(0xFFA5D6A7), state.isBuyEnabled, Modifier.weight(1f)) {
                    state.submit("BUY")
                }
                TradeButton("SELL", Red, Color(0xFFEF9A9A), state.isSellEnabled, Modifier.weight(1f)) {
                    state.submit("SELL")
                }
            }
        }
    }
}

private val ORDER_TYPES = listOf("MARKET" to "Market Order", "LIMIT" to "Limit Order")

@Composable
private fun OrderTypeSelector(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ORDER_TYPES.forEach { (type, label) ->
            Button(
                onClick = { onSelect(type) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == type) MaterialTheme.colorScheme.primary else Color.LightGray
                )
            ) { Text(label) }
        }
    }
}

@Composable
private fun OrderInputFields(state: TradeState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DecimalField(state.quantityText, "Quantity (Shares)", "e.g. 10", state::updateQuantity)
        if (state.selectedOrderType == "LIMIT") {
            DecimalField(
                value = state.limitPriceText,
                label = "Limit Price ($)",
                placeholder = "e.g. ${state.currentMarketPrice?.fmt() ?: "--"}",
                onChange = state::updateLimitPrice
            )
        }
    }
}

@Composable
private fun DecimalField(value: String, label: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.matches(DECIMAL)) onChange(it) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun OrderValidation(state: TradeState) {
    val inputError = state.inputError
    if (inputError != null) {
        Text(inputError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    } else {
        listOfNotNull(state.buyBlocker, state.sellBlocker).forEach {
            Text(it, color = Red, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PendingOrdersCard(orders: List<PendingOrderItem>, onCancelOrder: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Active Pending Orders", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            orders.forEach { order ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${order.side} ${order.quantity} ${order.ticker} @ ${(order.limitPrice ?: 0.0).usd()}",
                            fontWeight = FontWeight.Bold,
                            color = if (order.side == "BUY") Green else Red,
                            fontSize = 14.sp
                        )
                        Text("Limit Order • ${order.status}", fontSize = 12.sp, color = Color.Gray)
                    }
                    OutlinedButton(onClick = { onCancelOrder(order.id) }, shape = RoundedCornerShape(8.dp)) {
                        Text("Cancel", fontSize = 12.sp, color = Red)
                    }
                }
            }
        }
    }
}

@Composable
private fun TradeButton(
    label: String,
    color: Color,
    disabledColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = disabledColor,
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(50.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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