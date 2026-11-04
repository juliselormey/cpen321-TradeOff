package com.example.cpen321application.ui.portfolio

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import com.example.cpen321application.ui.StockLogo
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
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

// Scoped to this feature until a shared product palette is agreed upon.
private val PortfolioGreen: Color @Composable get() = MaterialTheme.colorScheme.primary
private val PortfolioRed: Color @Composable get() = MaterialTheme.colorScheme.error
private val PortfolioInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val PortfolioMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val PortfolioBorder: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
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

private class PortfolioState(
    private val context: android.content.Context,
    private val service: ApiService,
    private val marketApi: com.example.cpen321application.ui.market.data.MarketApiService,
) {
    private val refreshLock = Mutex()
    var summary by mutableStateOf<PortfolioSummaryResponse?>(null)
    var stockPrices by mutableStateOf(mapOf<String, Double>())
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    suspend fun loadPortfolioData() = refreshLock.withLock {
        if (SessionManager.isLoggedIn(context)) {
            isLoading = true
            try {
                val response = service.getPortfolio()
                if (response.isSuccessful && response.body() != null) {
                    summary = response.body()
                    errorMessage = null
                } else {
                    errorMessage = "Failed to load portfolio"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: java.io.IOException) {
                Log.e("PortfolioScreen", "Error loading portfolio", e)
                errorMessage = "Network error loading portfolio"
            } catch (e: retrofit2.HttpException) {
                Log.e("PortfolioScreen", "Error loading portfolio", e)
                errorMessage = "Network error loading portfolio"
            } catch (e: com.google.gson.JsonParseException) {
                Log.e("PortfolioScreen", "Error loading portfolio", e)
                errorMessage = "Network error loading portfolio"
            } finally {
                isLoading = false
            }
        }
    }

    suspend fun refreshPrices() {
        try {
            val prices = marketApi.getStocks().stocks.mapNotNull { stock ->
                stock.currentPrice?.toDoubleOrNull()?.let { price ->
                    stock.ticker.uppercase(Locale.US) to price
                }
            }.toMap()
            stockPrices = stockPrices + prices
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: java.io.IOException) {
            Log.w("PortfolioScreen", "Error refreshing prices", e)
        } catch (e: retrofit2.HttpException) {
            Log.w("PortfolioScreen", "Error refreshing prices", e)
        } catch (e: com.google.gson.JsonParseException) {
            Log.w("PortfolioScreen", "Error refreshing prices", e)
        }
    }

    // Evaluate live values of holdings
    val evaluatedHoldings get() = summary?.holdings?.map { h ->
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

    val cashBalance get() = summary?.cashBalance ?: 10000.0
    val investedValue get() = evaluatedHoldings.sumOf { it.currentValue }
    val totalPortfolioValue get() = investedValue + cashBalance
    val totalProfitLoss get() = evaluatedHoldings.sumOf { it.profitLoss }
    val totalCostBasis get() = evaluatedHoldings.sumOf { it.costBasis }
    val totalReturnPercent get() = if (totalCostBasis > 0) (totalProfitLoss / totalCostBasis) * 100 else 0.0

    suspend fun cancelOrder(orderId: String) {
        try {
            if (service.cancelOrder(orderId).isSuccessful) loadPortfolioData()
        } catch (e: java.io.IOException) {
            Log.e("PortfolioScreen", "Error cancelling order", e)
        } catch (e: retrofit2.HttpException) {
            Log.e("PortfolioScreen", "Error cancelling order", e)
        } catch (e: com.google.gson.JsonParseException) {
            Log.e("PortfolioScreen", "Error cancelling order", e)
        }
    }
}

@Composable
fun PortfolioScreen(
    apiService: ApiService? = null,
    onBack: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onStockSelected: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context, apiService) { apiService ?: ApiClient.create(context) }
    val marketApi = remember(context) { ApiClient.createMarket(context) }
    val state = remember(context, service, marketApi) { PortfolioState(context, service, marketApi) }
    PortfolioPolling(state)
    MaterialTheme {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("portfolio-list").background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PortfolioHeader(onBack, onSignOut) }
            portfolioOverview(state)
            portfolioHoldings(state.evaluatedHoldings, onStockSelected)
            portfolioActivity(state.summary) { orderId -> scope.launch { state.cancelOrder(orderId) } }
        }
    }
}

@Composable
private fun PortfolioPolling(state: PortfolioState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (isActive) { state.loadPortfolioData(); delay(8_000) }
            }
            launch {
                while (isActive) { state.refreshPrices(); delay(8_000) }
            }
        }
    }
}

@Composable
private fun PortfolioHeader(onBack: () -> Unit, onSignOut: () -> Unit) {
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

private fun androidx.compose.foundation.lazy.LazyListScope.portfolioOverview(state: PortfolioState) {
    with(state) {
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

    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.portfolioHoldings(
    evaluatedHoldings: List<EvaluatedHolding>, onStockSelected: (String) -> Unit,
) {
    item { SectionTitle("Holdings", "${evaluatedHoldings.size} assets") }

    if (evaluatedHoldings.isEmpty()) {
        item {
            PortfolioPanel {
                Text("No stock holdings yet. Use the Market tab to place trades!", color = PortfolioMuted, fontSize = 14.sp)
            }
        }
    } else {
        items(evaluatedHoldings, key = { it.ticker }) { holding ->
            HoldingCard(holding, onClick = { onStockSelected(holding.ticker) })
        }
    }

}

private fun androidx.compose.foundation.lazy.LazyListScope.portfolioActivity(
    summary: PortfolioSummaryResponse?, onCancel: (String) -> Unit,
) {
    val pendingOrders = summary?.pendingOrders.orEmpty()
    if (pendingOrders.isNotEmpty()) {
        item { SectionTitle("Pending Orders", "${pendingOrders.size} active orders") }
        items(pendingOrders, key = { it.id }) { order -> PendingOrderCard(order, onCancel) }
    }
    val recentTrades = summary?.recentTrades.orEmpty()
    if (recentTrades.isNotEmpty()) {
        item { SectionTitle("Recent Activity", "Executed transactions") }
        items(recentTrades, key = { it.id }) { trade -> TransactionCard(trade) }
    }
}

@Composable
private fun PortfolioSummaryCard(
    totalValue: Double,
    cashBalance: Double,
    totalProfitLoss: Double,
    totalReturnPercent: Double
) {
    PortfolioPanel(tint = MaterialTheme.colorScheme.primaryContainer) {
        Text("Total Portfolio Value", color = PortfolioMuted, fontSize = 16.sp)
        Text(money(totalValue), modifier = Modifier.testTag("portfolio-total"), fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("Holdings + available virtual cash", color = PortfolioMuted, fontSize = 12.sp)
        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = PortfolioBorder)
        Text("Available Virtual Cash", color = PortfolioMuted, fontSize = 14.sp)
        Text(money(cashBalance), modifier = Modifier.testTag("portfolio-cash"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Text("Total Unrealized Profit/Loss", color = PortfolioMuted, fontSize = 14.sp)
        ProfitLoss(totalProfitLoss, totalReturnPercent, Modifier.testTag("portfolio-profit"))
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
                modifier = Modifier.fillMaxWidth().testTag("allocation-$symbol").semantics(mergeDescendants = true) {}.padding(vertical = 6.dp),
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
private fun HoldingCard(holding: EvaluatedHolding, onClick: () -> Unit) {
    PortfolioPanel(modifier = Modifier.testTag("holding-${holding.ticker}").clickable(
        role = Role.Button,
        onClickLabel = "Trade ${holding.ticker}",
        onClick = onClick,
    )) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StockLogo(holding.ticker, Modifier.size(42.dp))
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
                    if (isBuy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer, CircleShape,
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
private fun PortfolioPanel(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .background(tint, RoundedCornerShape(20.dp))
            .border(1.dp, PortfolioBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun ProfitLoss(amount: Double, percent: Double, modifier: Modifier = Modifier) {
    val sign = if (amount >= 0) "+" else "−"
    Text(
        "$sign${money(abs(amount))} ($sign${String.format(Locale.US, "%.2f", abs(percent))}%)",
        color = if (amount >= 0) PortfolioGreen else PortfolioRed,
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.US).format(value)

private fun quantity(value: Double): String = NumberFormat.getNumberInstance(Locale.US).apply {
    maximumFractionDigits = 4
}.format(value)
