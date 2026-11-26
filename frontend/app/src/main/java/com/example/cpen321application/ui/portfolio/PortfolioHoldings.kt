package com.example.cpen321application.ui.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.ui.StockLogo

internal fun androidx.compose.foundation.lazy.LazyListScope.portfolioHoldings(
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
            Text("${quantity(holding.quantity)} shares @ ${holding.currentPrice?.let(::money) ?: "Unavailable"}", color = PortfolioMuted, fontSize = 14.sp)
            Text(holding.currentValue?.let(::money) ?: "Unavailable", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        ProfitLoss(holding.profitLoss, holding.returnPercent)
    }
}
