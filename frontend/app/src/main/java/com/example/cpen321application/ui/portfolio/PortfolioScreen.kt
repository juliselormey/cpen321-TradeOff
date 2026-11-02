package com.example.cpen321application.ui.portfolio

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.ApiClient
import com.example.cpen321application.ApiService
import com.example.cpen321application.HoldingItemResponse
import com.example.cpen321application.PendingOrderItem
import com.example.cpen321application.PortfolioSummaryResponse
import com.example.cpen321application.TradeHistoryItemResponse
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

private val PortfolioGreen = Color(0xFF008D50)
private val PortfolioRed = Color(0xFFBE3345)
private val PortfolioInk = Color(0xFF14251E)
private val PortfolioMuted = Color(0xFF64716C)
private val PortfolioBorder = Color(0xFFE1EBE5)
private val AllocationColors = listOf(
    Color(0xFF00AD69), Color(0xFF82D687), Color(0xFF3984FF),
    Color(0xFFFFCE45), Color(0xFF9BA5B2), Color(0xFFE040FB),
)

data class EvaluatedHolding(
    val ticker: String,
    val quantity: Double,
    val averageCost: Double,
    val currentPrice: Double,
    val currentValue: Double,
    val costBasis: Double,
) {
    val profitLoss: Double get() = currentValue - costBasis
    val returnPercent: Double get() = if (costBasis > 0) (profitLoss / costBasis) * 100 else 0.0
}

@Composable
fun PortfolioScreen(
    apiService: ApiService? = null,
    onBack: () -> Unit = {},
    onSignOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var summary by remember { mutableStateOf<PortfolioSummaryResponse?>(null) }
    var stockPrices by remember { mutableStateOf(mapOf<String, Double>()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun loadPortfolioData() {
        if (SessionManager.isLoggedIn(context)) {
            isLoading = true
            try {
                // Load live prices from Market API
                val marketApi = ApiClient.createMarket(context)
                val marketRes = marketApi.getStocks()
                if (marketRes.stocks.isNotEmpty()) {
                    val priceMap = marketRes.stocks
                        .filter { it.currentPrice != null }
                        .associate { it.ticker.uppercase(Locale.US) to (it.currentPrice?.toDoubleOrNull() ?: 100.0) }
                    stockPrices = priceMap
                }

                // Load portfolio summary from Backend API
                val service = apiService ?: ApiClient.create(context)
                val response = service.getPortfolio()
                if (response.isSuccessful && response.body() != null) {
                    summary = response.body()
                    errorMessage = null
                } else {
                    errorMessage = "Failed to load portfolio"
                }
            } catch (e: Exception) {
                Log.e("PortfolioScreen", "Error loading portfolio", e)
                errorMessage = "Network error loading portfolio"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadPortfolioData()
    }

    // Evaluate live values of holdings
    val evaluatedHoldings = summary?.holdings?.map { h ->
        val price = stockPrices[h.ticker.uppercase(Locale.US)] ?: h.averageCost
        val currentValue = h.quantity * price
        val costBasis = h.quantity * h.averageCost
        EvaluatedHolding(
            ticker = h.ticker,
            quantity = h.quantity,
            averageCost = h.averageCost,
            currentPrice = price,
            currentValue = currentValue,
            costBasis = costBasis
        )
    } ?: emptyList()

    val cashBalance = summary?.cashBalance ?: 10000.0
    val investedValue = evaluatedHoldings.sumOf { it.currentValue }
    val totalPortfolioValue = investedValue + cashBalance
    val totalProfitLoss = evaluatedHoldings.sumOf { it.profitLoss }
    val totalCostBasis = evaluatedHoldings.sumOf { it.costBasis }
    val totalReturnPercent = if (totalCostBasis > 0) (totalProfitLoss / totalCostBasis) * 100 else 0.0

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PortfolioGreen,
            background = Color(0xFFF6FAF8),
            surface = Color.White,
            onSurface = PortfolioInk,
            onBackground = PortfolioInk,
        ),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                            Text("‹ Back to Market", color = PortfolioGreen, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = onSignOut, contentPadding = PaddingValues(0.dp)) {
                            Text("Sign Out", color = PortfolioRed, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trade", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Off", color = PortfolioGreen, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text("Learn. Invest. Grow.", color = PortfolioMuted, fontSize = 14.sp)
                    Text("Portfolio", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                }
            }

            if (isLoading && summary == null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PortfolioGreen)
                    }
                }
            }

            errorMessage?.let { msg ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                        Text(msg, color = PortfolioRed, modifier = Modifier.padding(16.dp), fontSize = 14.sp)
                    }
                }
            }

            item {
                PortfolioSummaryCard(
                    totalValue = totalPortfolioValue,
                    cashBalance = cashBalance,
                    totalProfitLoss = totalProfitLoss,
                    totalReturnPercent = totalReturnPercent
                )
            }

            if (evaluatedHoldings.isNotEmpty()) {
                item {
                    AssetAllocationCard(
                        holdings = evaluatedHoldings,
                        cashBalance = cashBalance,
                        totalValue = totalPortfolioValue
                    )
                }
            }

            item { SectionTitle("Holdings", "${evaluatedHoldings.size} assets") }

            if (evaluatedHoldings.isEmpty()) {
                item {
                    PortfolioPanel {
                        Text("No stock holdings yet. Use the Market tab to place trades!", color = PortfolioMuted, fontSize = 14.sp)
                    }
                }
            } else {
                items(evaluatedHoldings, key = { it.ticker }) { holding ->
                    HoldingCard(holding)
                }
            }

            // Pending Limit Orders Section
            if (summary?.pendingOrders?.isNotEmpty() == true) {
                item { SectionTitle("Pending Orders", "${summary!!.pendingOrders.size} active orders") }
                items(summary!!.pendingOrders, key = { it.id }) { order ->
                    PendingOrderCard(
                        order = order,
                        onCancel = { orderId ->
                            scope.launch {
                                try {
                                    val service = apiService ?: ApiClient.create(context)
                                    val res = service.cancelOrder(orderId)
                                    if (res.isSuccessful) {
                                        loadPortfolioData()
                                    }
                                } catch (e: Exception) {
                                    Log.e("PortfolioScreen", "Error cancelling order", e)
                                }
                            }
                        }
                    )
                }
            }

            // Recent Activity
            if (summary?.recentTrades?.isNotEmpty() == true) {
                item { SectionTitle("Recent Activity", "Executed transactions") }
                items(summary!!.recentTrades, key = { it.id }) { trade ->
                    TransactionCard(trade)
                }
            }
        }
    }
}

@Composable
private fun PortfolioSummaryCard(
    totalValue: Double,
    cashBalance: Double,
    totalProfitLoss: Double,
    totalReturnPercent: Double
) {
    PortfolioPanel(tint = Color(0xFFEDFAF2)) {
        Text("Total Portfolio Value", color = PortfolioMuted, fontSize = 16.sp)
        Text(money(totalValue), fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("Holdings + available virtual cash", color = PortfolioMuted, fontSize = 12.sp)
        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = PortfolioBorder)
        Text("Available Virtual Cash", color = PortfolioMuted, fontSize = 14.sp)
        Text(money(cashBalance), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Text("Total Unrealized Profit/Loss", color = PortfolioMuted, fontSize = 14.sp)
        ProfitLoss(totalProfitLoss, totalReturnPercent)
    }
}

@Composable
private fun AssetAllocationCard(
    holdings: List<EvaluatedHolding>,
    cashBalance: Double,
    totalValue: Double
) {
    val slices = holdings.map { it.ticker to it.currentValue } + ("Cash" to cashBalance)
    PortfolioPanel {
        SectionTitle("Asset Allocation", "")
        Text("Share of total portfolio", color = PortfolioMuted, fontSize = 12.sp)
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier.size(184.dp).padding(12.dp).semantics {
                    contentDescription = "Asset allocation donut chart"
                },
            ) {
                var startAngle = -90f
                slices.forEachIndexed { index, (_, value) ->
                    val sweep = if (totalValue > 0) (value / totalValue * 360).toFloat() else 0f
                    drawArc(
                        color = AllocationColors[index % AllocationColors.size],
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = 28.dp.toPx()),
                    )
                    startAngle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${slices.size} allocations", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("100% of portfolio", color = PortfolioMuted, fontSize = 12.sp)
            }
        }
        slices.forEachIndexed { index, (symbol, value) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).background(AllocationColors[index % AllocationColors.size], CircleShape))
                Text(symbol, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                val pct = if (totalValue > 0) (value / totalValue) * 100 else 0.0
                Text(String.format(Locale.US, "%.1f%%", pct), color = PortfolioMuted, fontSize = 13.sp)
                Text(money(value), fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HoldingCard(holding: EvaluatedHolding) {
    PortfolioPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(42.dp).background(Color(0xFFEEF5F0), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(holding.ticker.take(2), color = PortfolioGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(holding.ticker, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Avg Cost: ${money(holding.averageCost)}", color = PortfolioMuted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${quantity(holding.quantity)} shares @ ${money(holding.currentPrice)}", color = PortfolioMuted, fontSize = 14.sp)
            Text(money(holding.currentValue), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        ProfitLoss(holding.profitLoss, holding.returnPercent)
    }
}

@Composable
private fun PendingOrderCard(
    order: PendingOrderItem,
    onCancel: (String) -> Unit
) {
    PortfolioPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val color = if (order.side == "BUY") PortfolioGreen else PortfolioRed
                Text("${order.side} ${quantity(order.quantity)} ${order.ticker}", fontWeight = FontWeight.Bold, color = color, fontSize = 16.sp)
                Text("Limit @ ${money(order.limitPrice ?: 0.0)} • ${order.status}", color = PortfolioMuted, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { onCancel(order.id) },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Cancel", color = PortfolioRed, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TransactionCard(trade: TradeHistoryItemResponse) {
    val isBuy = trade.type == "BUY"
    PortfolioPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier.size(36.dp).background(
                    if (isBuy) Color(0xFFE0F5E9) else Color(0xFFFFE9EC), CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (isBuy) "↗" else "↙", color = if (isBuy) PortfolioGreen else PortfolioRed, fontSize = 24.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("${if (isBuy) "Bought" else "Sold"} ${trade.ticker}", fontWeight = FontWeight.SemiBold)
                Text(trade.createdAt.take(10), color = PortfolioMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("${quantity(trade.quantity)} shares @ ${money(trade.pricePerShare)}", color = PortfolioMuted, fontSize = 14.sp)
        Text(
            "${if (isBuy) "−" else "+"}${money(trade.totalAmount)}",
            color = if (isBuy) PortfolioInk else PortfolioGreen, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        if (detail.isNotEmpty()) Text(detail, color = PortfolioMuted, fontSize = 12.sp)
    }
}

@Composable
private fun PortfolioPanel(tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(tint, RoundedCornerShape(20.dp))
            .border(1.dp, PortfolioBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun ProfitLoss(amount: Double, percent: Double) {
    val sign = if (amount >= 0) "+" else "−"
    Text(
        "$sign${money(abs(amount))} ($sign${String.format(Locale.US, "%.2f", abs(percent))}%)",
        color = if (amount >= 0) PortfolioGreen else PortfolioRed,
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.US).format(value)

private fun quantity(value: Double): String = NumberFormat.getNumberInstance(Locale.US).apply {
    maximumFractionDigits = 4
}.format(value)
