package com.example.cpen321application.ui.portfolio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// Scoped to this feature until a shared product palette is agreed upon.
private val PortfolioGreen = Color(0xFF008D50)
private val PortfolioRed = Color(0xFFBE3345)
private val PortfolioInk = Color(0xFF14251E)
private val PortfolioMuted = Color(0xFF64716C)
private val PortfolioBorder = Color(0xFFE1EBE5)
private val AllocationColors = listOf(
    Color(0xFF00AD69), Color(0xFF82D687), Color(0xFF3984FF),
    Color(0xFFFFCE45), Color(0xFF9BA5B2),
)

@Composable
fun PortfolioScreen(onBack: () -> Unit) {
    // Local Material theme keeps the reference's light styling legible in device dark mode
    // without changing the app theme or other feature screens.
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
                    TextButton(onClick = onBack, contentPadding = PaddingValues(vertical = 8.dp)) {
                        Text("‹ Back to home")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trade", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Off", color = PortfolioGreen, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.weight(1f))
                        Text("MOCK DATA", color = PortfolioGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Learn. Invest. Grow.", color = PortfolioMuted, fontSize = 14.sp)
                    Text("Portfolio", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                }
            }
            item { PortfolioSummary() }
            item { AssetAllocation() }
            item { SectionTitle("Holdings", "${PortfolioMockData.holdings.size} assets") }
            items(PortfolioMockData.holdings, key = { it.symbol }) { holding ->
                HoldingCard(holding)
            }
            item { SectionTitle("Recent Activity", "Latest transactions") }
            items(PortfolioMockData.transactions, key = { it.id }) { transaction ->
                TransactionCard(transaction)
            }
            item {
                Text(
                    "Virtual investments • All amounts in USD\nSample data for demonstration only.",
                    color = PortfolioMuted, fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PortfolioSummary() {
    PortfolioPanel(tint = Color(0xFFEDFAF2)) {
        Text("Total Portfolio Value", color = PortfolioMuted, fontSize = 16.sp)
        Text(money(PortfolioMockData.totalValue), fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("Holdings + available virtual cash", color = PortfolioMuted, fontSize = 12.sp)
        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = PortfolioBorder)
        Text("Available Virtual Cash", color = PortfolioMuted, fontSize = 14.sp)
        Text(money(PortfolioMockData.availableCash), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Text("Total Profit/Loss", color = PortfolioMuted, fontSize = 14.sp)
        ProfitLoss(PortfolioMockData.totalProfitLoss, PortfolioMockData.totalReturnPercent)
        Text("Unrealized • since purchase", color = PortfolioMuted, fontSize = 12.sp)
    }
}

@Composable
private fun AssetAllocation() {
    val slices = PortfolioMockData.holdings.map { it.symbol to it.currentValue } +
        ("Cash" to PortfolioMockData.availableCash)
    val total = PortfolioMockData.totalValue
    PortfolioPanel {
        SectionTitle("Asset Allocation", "")
        Text("Share of total portfolio, including cash", color = PortfolioMuted, fontSize = 12.sp)
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier.size(184.dp).padding(12.dp).semantics {
                    contentDescription = "Asset allocation donut chart. Percentages and values are listed below."
                },
            ) {
                var startAngle = -90f
                slices.forEachIndexed { index, (_, value) ->
                    val sweep = (value / total * 360).toFloat()
                    drawArc(
                        color = AllocationColors[index], startAngle = startAngle,
                        sweepAngle = sweep, useCenter = false, style = Stroke(width = 28.dp.toPx()),
                    )
                    startAngle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("5 allocations", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("100% of portfolio", color = PortfolioMuted, fontSize = 12.sp)
            }
        }
        slices.forEachIndexed { index, (symbol, value) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).background(AllocationColors[index], CircleShape))
                Text(symbol, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text(String.format(Locale.US, "%.1f%%", value / total * 100), color = PortfolioMuted, fontSize = 13.sp)
                Text(money(value), fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HoldingCard(holding: PortfolioHolding) {
    PortfolioPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(42.dp).background(Color(0xFFEEF5F0), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(holding.symbol.take(1), color = PortfolioGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(holding.symbol, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(holding.companyName, color = PortfolioMuted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${quantity(holding.shares)} shares", color = PortfolioMuted, fontSize = 14.sp)
            Text(money(holding.currentValue), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        ProfitLoss(holding.profitLoss, holding.returnPercent)
    }
}

@Composable
private fun TransactionCard(transaction: PortfolioTransaction) {
    val isBuy = transaction.side == TransactionSide.Buy
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
                Text("${if (isBuy) "Bought" else "Sold"} ${transaction.symbol}", fontWeight = FontWeight.SemiBold)
                Text(transaction.date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)), color = PortfolioMuted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("${quantity(transaction.shares)} shares @ ${money(transaction.pricePerShare)}", color = PortfolioMuted, fontSize = 14.sp)
        Text(
            "${if (isBuy) "−" else "+"}${money(transaction.total)}",
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

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PortfolioScreenPreview() {
    PortfolioScreen(onBack = {})
}
