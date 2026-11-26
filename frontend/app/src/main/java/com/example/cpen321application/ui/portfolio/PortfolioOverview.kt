package com.example.cpen321application.ui.portfolio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal fun androidx.compose.foundation.lazy.LazyListScope.portfolioOverview(state: PortfolioState) {
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

        if (hasUnavailablePrices) {
            item { Text("Some live prices are unavailable. Totals and profit/loss are hidden until prices recover.", color = PortfolioRed) }
        }
        item {
            PortfolioSummaryCard(
                totalValue = totalPortfolioValue,
                cashBalance = cashBalance,
                totalProfitLoss = totalProfitLoss,
                totalReturnPercent = totalReturnPercent
            )
        }

        if (evaluatedHoldings.isNotEmpty() && !hasUnavailablePrices) {
            item {
                AssetAllocationCard(
                    holdings = evaluatedHoldings,
                    cashBalance = totalCash,
                    totalValue = totalPortfolioValue ?: 0.0
                )
            }
        }

    }
}

@Composable
private fun PortfolioSummaryCard(
    totalValue: Double?,
    cashBalance: Double,
    totalProfitLoss: Double?,
    totalReturnPercent: Double?
) {
    PortfolioPanel(tint = MaterialTheme.colorScheme.primaryContainer) {
        Text("Total Portfolio Value", color = PortfolioMuted, fontSize = 16.sp)
        Text(totalValue?.let(::money) ?: "Unavailable", modifier = Modifier.testTag("portfolio-total"), fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("Holdings + available virtual cash", color = PortfolioMuted, fontSize = 12.sp)
        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = PortfolioBorder)
        Text("Available Virtual Cash", color = PortfolioMuted, fontSize = 14.sp)
        Text(money(cashBalance), modifier = Modifier.testTag("portfolio-cash"), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Text("Total Unrealized Profit/Loss", color = PortfolioMuted, fontSize = 14.sp)
        ProfitLoss(totalProfitLoss, totalReturnPercent, Modifier.testTag("portfolio-profit"))
    }
}

private val AllocationColors = listOf(
    Color(0xFF00AD69), Color(0xFF82D687), Color(0xFF3984FF),
    Color(0xFFFFCE45), Color(0xFF9BA5B2), Color(0xFFE040FB),
)

@Composable
private fun AssetAllocationCard(
    holdings: List<EvaluatedHolding>,
    cashBalance: Double,
    totalValue: Double
) {
    val slices = holdings.map { it.ticker to (it.currentValue ?: return) } + ("Cash" to cashBalance)
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
