package com.example.cpen321application.ui.trade

import android.util.Log
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
import com.example.cpen321application.ApiService
import com.example.cpen321application.ExecuteTradeRequest
import com.example.cpen321application.util.SessionManager
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

@Composable
fun TradeScreen(
    ticker: String,
    apiService: ApiService? = null,
    onBack: () -> Unit = {}
) {
    val stock = getStockInfo(ticker)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Account & position state
    var availableCash by remember { mutableDoubleStateOf(10000.00) }
    var ownedShares by remember { mutableDoubleStateOf(0.0) }

    // Order form state
    var quantityText by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val quantity = quantityText.toDoubleOrNull() ?: 0.0
    val totalAmount = quantity * stock.price

    // Load initial position and cash balance from backend API
    LaunchedEffect(stock.ticker) {
        if (apiService != null && SessionManager.isLoggedIn(context)) {
            try {
                val response = apiService.getPosition(stock.ticker)
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    availableCash = body.cashBalance
                    ownedShares = body.ownedQuantity
                } else {
                    Log.e("TradeScreen", "Failed to fetch position: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("TradeScreen", "Error fetching position", e)
            }
        }
    }

    // Validation conditions
    val isQuantityValid = quantity > 0
    val hasEnoughCash = availableCash >= totalAmount
    val isBuyEnabled = isQuantityValid && hasEnoughCash

    val hasEnoughShares = ownedShares >= quantity
    val isSellEnabled = isQuantityValid && hasEnoughShares

    val handleBuy = {
        if (isBuyEnabled) {
            if (apiService != null && SessionManager.isLoggedIn(context)) {
                scope.launch {
                    try {
                        val response = apiService.executeTrade(
                            ExecuteTradeRequest(
                                ticker = stock.ticker,
                                type = "BUY",
                                quantity = quantity,
                                pricePerShare = stock.price
                            )
                        )
                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            availableCash = body.cashBalance
                            ownedShares = body.ownedQuantity
                            successMessage = body.message
                            errorMessage = null
                            quantityText = ""
                        } else {
                            errorMessage = response.errorBody()?.string() ?: "Failed to execute buy order"
                        }
                    } catch (e: Exception) {
                        Log.e("TradeScreen", "Buy execution error", e)
                        errorMessage = "Network error executing trade"
                    }
                }
            } else {
                availableCash -= totalAmount
                ownedShares += quantity
                successMessage = "Successfully bought ${String.format(Locale.US, "%.2f", quantity)} shares of ${stock.ticker} for $${String.format(Locale.US, "%.2f", totalAmount)}!"
                errorMessage = null
                quantityText = ""
            }
        }
    }

    val handleSell = {
        if (isSellEnabled) {
            if (apiService != null && SessionManager.isLoggedIn(context)) {
                scope.launch {
                    try {
                        val response = apiService.executeTrade(
                            ExecuteTradeRequest(
                                ticker = stock.ticker,
                                type = "SELL",
                                quantity = quantity,
                                pricePerShare = stock.price
                            )
                        )
                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            availableCash = body.cashBalance
                            ownedShares = body.ownedQuantity
                            successMessage = body.message
                            errorMessage = null
                            quantityText = ""
                        } else {
                            errorMessage = response.errorBody()?.string() ?: "Failed to execute sell order"
                        }
                    } catch (e: Exception) {
                        Log.e("TradeScreen", "Sell execution error", e)
                        errorMessage = "Network error executing trade"
                    }
                }
            } else {
                availableCash += totalAmount
                ownedShares -= quantity
                successMessage = "Successfully sold ${String.format(Locale.US, "%.2f", quantity)} shares of ${stock.ticker} for $${String.format(Locale.US, "%.2f", totalAmount)}!"
                errorMessage = null
                quantityText = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Navigation Header
        TradeTopBar(ticker = stock.ticker, onBack = onBack)

        Spacer(modifier = Modifier.height(16.dp))

        // Stock Header Card
        StockHeaderCard(stock = stock)

        Spacer(modifier = Modifier.height(16.dp))

        // Account & Position Card
        AccountPositionCard(availableCash = availableCash, ownedShares = ownedShares)

        Spacer(modifier = Modifier.height(20.dp))

        // Order Entry Card
        OrderEntryCard(
            quantityText = quantityText,
            onQuantityChange = { newValue ->
                quantityText = newValue
                successMessage = null
                errorMessage = null
            },
            stockPrice = stock.price,
            ticker = stock.ticker,
            quantity = quantity,
            availableCash = availableCash,
            ownedShares = ownedShares,
            isBuyEnabled = isBuyEnabled,
            isSellEnabled = isSellEnabled,
            onBuy = handleBuy,
            onSell = handleSell
        )

        // Error Notification Banner
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

        // Success Notification Banner
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
fun OrderEntryCard(
    quantityText: String,
    onQuantityChange: (String) -> Unit,
    stockPrice: Double,
    ticker: String,
    quantity: Double,
    availableCash: Double,
    ownedShares: Double,
    isBuyEnabled: Boolean,
    isSellEnabled: Boolean,
    onBuy: () -> Unit,
    onSell: () -> Unit
) {
    val totalAmount = quantity * stockPrice
    val isQuantityValid = quantity > 0
    val hasEnoughCash = availableCash >= totalAmount
    val hasEnoughShares = ownedShares >= quantity

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Order Details",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = quantityText,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                        onQuantityChange(newValue)
                    }
                },
                label = { Text("Quantity (Shares)") },
                placeholder = { Text("e.g. 10") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

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

            // Validation Messages
            if (quantityText.isNotEmpty() && !isQuantityValid) {
                Text(
                    text = "Please enter a valid quantity greater than 0.",
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
                    "Cannot Sell: You do not own any shares of $ticker."
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

            Spacer(modifier = Modifier.height(16.dp))

            // Separate Action Buttons Container
            TradeActionButtons(
                onBuy = onBuy,
                onSell = onSell,
                isBuyEnabled = isBuyEnabled,
                isSellEnabled = isSellEnabled
            )
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
