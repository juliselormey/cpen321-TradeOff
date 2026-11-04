package com.example.cpen321application.ui.trade

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.example.cpen321application.ApiClient
import com.example.cpen321application.ApiService
import com.example.cpen321application.ExecuteLimitOrderRequest
import com.example.cpen321application.ExecuteMarketOrderRequest
import com.example.cpen321application.PendingOrderItem
import com.example.cpen321application.util.SessionManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.util.Locale

data class TradeStockInfo(
    val ticker: String,
    val companyName: String,
    val price: Double,
    val change: Double
)

private val stockCatalog = mapOf(
    "AAPL" to TradeStockInfo("AAPL", "Apple Inc.", 189.43, 1.25),
    "MSFT" to TradeStockInfo("MSFT", "Microsoft Corporation", 415.26, 0.82),
    "TSLA" to TradeStockInfo("TSLA", "Tesla Inc.", 242.84, -2.14),
    "NVDA" to TradeStockInfo("NVDA", "NVIDIA Corporation", 138.07, 1.91),
    "GOOGL" to TradeStockInfo("GOOGL", "Alphabet Inc.", 242.84, -1.03),
    "AMZN" to TradeStockInfo("AMZN", "Amazon.com, Inc.", 178.29, 2.19),
)

fun getStockInfo(ticker: String): TradeStockInfo {
    return stockCatalog[ticker.uppercase(Locale.US)] ?: TradeStockInfo(
        ticker = ticker.ifEmpty { "UNKNOWN" },
        companyName = "Stock Security",
        price = 100.00,
        change = 0.00
    )
}

internal class TradeState(
    val stock: TradeStockInfo,
    private val isLoggedIn: () -> Boolean,
    private val apiService: ApiService?,
    private val marketApi: com.example.cpen321application.ui.market.data.MarketApiService,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val accountRefreshLock = Mutex()
    var currentMarketPrice by mutableDoubleStateOf(stock.price)
    var marketChange by mutableDoubleStateOf(stock.change)

    // Account & position state
    var availableCash by mutableDoubleStateOf(10000.00)
    var ownedShares by mutableDoubleStateOf(0.0)

    // Order form state
    var selectedOrderType by mutableStateOf("MARKET") // "MARKET" or "LIMIT"
    var quantityText by mutableStateOf("")
    var limitPriceText by mutableStateOf("")
    var pendingOrders by mutableStateOf(emptyList<PendingOrderItem>())

    var successMessage by mutableStateOf<String?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    val quantity get() = quantityText.toDoubleOrNull() ?: 0.0
    val limitPrice get() = limitPriceText.toDoubleOrNull()
    val effectivePrice get() = if (selectedOrderType == "LIMIT") limitPrice ?: currentMarketPrice else currentMarketPrice
    val totalAmount get() = quantity * effectivePrice

    suspend fun refreshQuote() {
        try {
            val marketRes = marketApi.getStocks()
            val matchedStock = marketRes.stocks.find { it.ticker.equals(stock.ticker, ignoreCase = true) }
            val livePrice = matchedStock?.currentPrice?.toDoubleOrNull()
            if (livePrice != null) {
                currentMarketPrice = livePrice
                matchedStock.percentageChange?.let { marketChange = it }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: java.io.IOException) {
            Log.e("TradeScreen", "Failed to fetch live market price", e)
        } catch (e: retrofit2.HttpException) {
            Log.e("TradeScreen", "Failed to fetch live market price", e)
        } catch (e: com.google.gson.JsonParseException) {
            Log.e("TradeScreen", "Failed to fetch live market price", e)
        }
    }

    suspend fun refreshAccount() = accountRefreshLock.withLock {
        if (apiService != null && isLoggedIn()) {
            try {
                val posRes = apiService.getPosition(stock.ticker)
                if (posRes.isSuccessful && posRes.body() != null) {
                    availableCash = posRes.body()!!.cashBalance
                    ownedShares = posRes.body()!!.ownedQuantity
                }

                val ordersRes = apiService.getPendingOrders()
                if (ordersRes.isSuccessful && ordersRes.body() != null) {
                    pendingOrders = ordersRes.body()!!.orders.filter { it.ticker.equals(stock.ticker, ignoreCase = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: java.io.IOException) {
                Log.e("TradeScreen", "Error refreshing data", e)
            } catch (e: retrofit2.HttpException) {
                Log.e("TradeScreen", "Error refreshing data", e)
            } catch (e: com.google.gson.JsonParseException) {
                Log.e("TradeScreen", "Error refreshing data", e)
            }
        }
    }

    // Validation conditions
    val isQuantityValid get() = quantity > 0
    val isLimitPriceValid get() = selectedOrderType != "LIMIT" || ((limitPrice ?: 0.0) > 0)
    val hasEnoughCash get() = availableCash >= totalAmount
    val isBuyEnabled get() = isQuantityValid && isLimitPriceValid && hasEnoughCash

    val hasEnoughShares get() = ownedShares >= quantity
    val isSellEnabled get() = isQuantityValid && isLimitPriceValid && hasEnoughShares

    val activeStockInfo get() = stock.copy(price = currentMarketPrice, change = marketChange)

    val showInvalidLimitPrice get() = selectedOrderType == "LIMIT" &&
        limitPriceText.isNotEmpty() && !isLimitPriceValid

    fun selectOrderType(value: String) { selectedOrderType = value }

    fun updateQuantity(value: String) {
        quantityText = value
        successMessage = null
        errorMessage = null
    }

    fun updateLimitPrice(value: String) {
        limitPriceText = value
        successMessage = null
        errorMessage = null
    }

    fun submit(side: String) {
        val enabled = if (side == "BUY") isBuyEnabled else isSellEnabled
        if (enabled) {
            if (apiService != null && isLoggedIn()) {
                scope.launch { executeOrder(side) }
            } else {
                val direction = if (side == "BUY") 1 else -1
                availableCash -= direction * totalAmount
                if (selectedOrderType == "MARKET") ownedShares += direction * quantity
                successMessage = "Order submitted successfully!"
                resetForm()
            }
        }
    }

    private suspend fun executeOrder(side: String) {
        val apiService = apiService ?: return
        try {
            val response = if (selectedOrderType == "LIMIT") {
                apiService.executeLimitOrder(
                    ExecuteLimitOrderRequest(
                        ticker = stock.ticker,
                        side = side,
                        quantity = quantity,
                        pricePerShare = currentMarketPrice,
                        limitPrice = limitPrice ?: currentMarketPrice
                    )
                )
            } else {
                apiService.executeMarketOrder(
                    ExecuteMarketOrderRequest(
                        ticker = stock.ticker,
                        side = side,
                        quantity = quantity,
                        pricePerShare = currentMarketPrice
                    )
                )
            }
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                accountRefreshLock.withLock {
                    availableCash = body.cashBalance
                    ownedShares = body.ownedQuantity
                }
                successMessage = body.message
                errorMessage = null
                quantityText = ""
                limitPriceText = ""
                refreshAccount()
            } else {
                errorMessage = response.errorBody()?.string() ?: "Failed to execute ${side.lowercase(Locale.US)} order"
            }

        } catch (e: java.io.IOException) {
            Log.e("TradeScreen", "Order execution error", e)
            errorMessage = "Network error executing order"
        } catch (e: retrofit2.HttpException) {
            Log.e("TradeScreen", "Order execution error", e)
            errorMessage = "Network error executing order"
        } catch (e: com.google.gson.JsonParseException) {
            Log.e("TradeScreen", "Order execution error", e)
            errorMessage = "Network error executing order"
        }
    }

    private fun resetForm() {
        errorMessage = null
        quantityText = ""
        limitPriceText = ""
    }

    fun cancelOrder(orderId: String) {
        val service = apiService ?: return
        scope.launch {
            try {
                if (service.cancelOrder(orderId).isSuccessful) {
                    successMessage = "Pending order cancelled successfully"
                    refreshAccount()
                }
            } catch (e: java.io.IOException) {
                errorMessage = "Failed to cancel order"
            } catch (e: retrofit2.HttpException) {
                errorMessage = "Failed to cancel order"
            } catch (e: com.google.gson.JsonParseException) {
                errorMessage = "Failed to cancel order"
            }
        }
    }
}

@Composable
fun TradeScreen(ticker: String, apiService: ApiService? = null, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val marketApi = remember(context) { ApiClient.createMarket(context) }
    val state = remember(ticker, context, apiService, marketApi, scope) {
        TradeState(getStockInfo(ticker), { SessionManager.isLoggedIn(context) }, apiService, marketApi, scope)
    }
    TradePolling(state)
    TradeContent(state, onBack)
}

@Composable
private fun TradePolling(state: TradeState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { while (isActive) { state.refreshQuote(); delay(8_000) } }
            launch { while (isActive) { state.refreshAccount(); delay(8_000) } }
        }
    }
}

@Composable
private fun TradeContent(state: TradeState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TradeTopBar(state.stock.ticker, onBack)
        Spacer(Modifier.height(16.dp))
        StockHeaderCard(state.activeStockInfo)
        Spacer(Modifier.height(16.dp))
        AccountPositionCard(state.availableCash, state.ownedShares)
        Spacer(Modifier.height(20.dp))
        OrderEntryCard(state)
        if (state.pendingOrders.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            PendingOrdersCard(state.pendingOrders, state::cancelOrder)
        }
        TradeMessages(state)
    }
}

@Composable
private fun TradeMessages(state: TradeState) {
    with(state) {
        errorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = msg,
                        color = Color(0xFFC62828),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { errorMessage = null }) {
                        Text("Dismiss", color = Color(0xFFC62828))
                    }
                }
            }
        }

        successMessage?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            SuccessNotificationBanner(message = msg, onDismiss = { successMessage = null })
        }
    }
}

@Composable
fun TradeTopBar(
    ticker: String,
    onBack: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to Market"
            )
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
fun StockHeaderCard(stock: TradeStockInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stock.companyName,
                fontSize = 16.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "$${String.format(Locale.US, "%.2f", stock.price)}",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                val isPositive = stock.change >= 0
                val changeColor = if (isPositive) Color(0xFF2E7D32) else Color(0xFFC62828)
                val prefix = if (isPositive) "+" else ""
                Text(
                    text = "$prefix${String.format(Locale.US, "%.2f", stock.change)}%",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = changeColor
                )
            }
        }
    }
}

@Composable
fun AccountPositionCard(
    availableCash: Double,
    ownedShares: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Your Position & Cash",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Available Cash", fontSize = 13.sp, color = Color.Gray)
                    Text(
                        text = "$${String.format(Locale.US, "%.2f", availableCash)}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Owned Shares", fontSize = 13.sp, color = Color.Gray)
                    Text(
                        text = String.format(Locale.US, "%.2f", ownedShares),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderEntryCard(state: TradeState) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Order Details", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            OrderTypeSelector(state)
            OrderInputFields(state)
            OrderEstimate(state)
            OrderValidation(state)
            Spacer(Modifier.height(16.dp))
            TradeActionButtons(
                onBuy = { state.submit("BUY") }, onSell = { state.submit("SELL") },
                isBuyEnabled = state.isBuyEnabled, isSellEnabled = state.isSellEnabled,
            )
        }
    }
}

@Composable
private fun OrderTypeSelector(state: TradeState) {
    with(state) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { selectOrderType("MARKET") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedOrderType == "MARKET") MaterialTheme.colorScheme.primary else Color.LightGray
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Market Order")
            }
            Button(
                onClick = { selectOrderType("LIMIT") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedOrderType == "LIMIT") MaterialTheme.colorScheme.primary else Color.LightGray
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Limit Order")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

    }
}

@Composable
private fun OrderInputFields(state: TradeState) {
    with(state) {
        OutlinedTextField(
            value = quantityText,
            onValueChange = { newValue ->
                if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                    updateQuantity(newValue)
                }
            },
            label = { Text("Quantity (Shares)") },
            placeholder = { Text("e.g. 10") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (selectedOrderType == "LIMIT") {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = limitPriceText,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                        updateLimitPrice(newValue)
                    }
                },
                label = { Text("Limit Price ($)") },
                placeholder = { Text("e.g. ${String.format(Locale.US, "%.2f", currentMarketPrice)}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

    }
}

@Composable
private fun OrderEstimate(state: TradeState) {
    with(state) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Estimated Total", fontSize = 15.sp, color = Color.Gray)
            Text(
                text = "$${String.format(Locale.US, "%.2f", totalAmount)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

    }
}

@Composable
private fun OrderValidation(state: TradeState) {
    with(state) {
        if (quantityText.isNotEmpty() && !isQuantityValid) {
            Text(
                text = "Please enter a valid quantity greater than 0.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        } else if (showInvalidLimitPrice) {
            Text(
                text = "Please enter a valid limit price greater than 0.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        } else if (isQuantityValid && !hasEnoughCash) {
            Text(
                text = "Cannot Buy: Insufficient cash balance ($${String.format(Locale.US, "%.2f", availableCash)} available).",
                color = Color(0xFFC62828),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        } else if (isQuantityValid && !hasEnoughShares) {
            val msg = if (ownedShares == 0.0) {
                "Cannot Sell: You do not own any shares of ${stock.ticker}."
            } else {
                "Cannot Sell: Insufficient shares (You own ${String.format(Locale.US, "%.2f", ownedShares)} shares)."
            }
            Text(
                text = msg,
                color = Color(0xFFC62828),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

    }
}

@Composable
fun PendingOrdersCard(
    orders: List<PendingOrderItem>,
    onCancelOrder: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Active Pending Orders",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            orders.forEach { order ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val sideColor = if (order.side == "BUY") Color(0xFF2E7D32) else Color(0xFFC62828)
                        Text(
                            text = "${order.side} ${order.quantity} ${order.ticker} @ $${String.format(Locale.US, "%.2f", order.limitPrice ?: 0.0)}",
                            fontWeight = FontWeight.Bold,
                            color = sideColor,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Limit Order • ${order.status}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    OutlinedButton(
                        onClick = { onCancelOrder(order.id) },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Cancel", fontSize = 12.sp, color = Color(0xFFC62828))
                    }
                }
            }
        }
    }
}

@Composable
fun TradeActionButtons(
    onBuy: () -> Unit,
    onSell: () -> Unit,
    isBuyEnabled: Boolean,
    isSellEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BuyButton(onClick = onBuy, enabled = isBuyEnabled, modifier = Modifier.weight(1f))
        SellButton(onClick = onSell, enabled = isSellEnabled, modifier = Modifier.weight(1f))
    }
}

@Composable
fun BuyButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF2E7D32),
            disabledContainerColor = Color(0xFFA5D6A7),
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(50.dp)
    ) {
        Text("BUY", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun SellButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFC62828),
            disabledContainerColor = Color(0xFFEF9A9A),
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(50.dp)
    ) {
        Text("SELL", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun SuccessNotificationBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                color = Color(0xFF1B5E20),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = Color(0xFF2E7D32))
            }
        }
    }
}
