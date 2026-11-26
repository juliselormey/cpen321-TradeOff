package com.example.cpen321application.ui.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.example.cpen321application.PendingOrderItem
import com.example.cpen321application.PortfolioSummaryResponse
import com.example.cpen321application.TradeHistoryItemResponse

internal val TransactionHistoryIds = SemanticsPropertyKey<List<String>>("TransactionHistoryIds")

internal fun androidx.compose.foundation.lazy.LazyListScope.portfolioActivity(
    summary: PortfolioSummaryResponse?, onCancel: (String) -> Unit,
) {
    val pendingOrders = summary?.pendingOrders.orEmpty()
    if (pendingOrders.isNotEmpty()) {
        item { SectionTitle("Pending Orders", "${pendingOrders.size} active orders") }
        items(pendingOrders, key = { it.id }) { order -> PendingOrderCard(order, onCancel) }
    }
    val recentTrades = summary?.recentTrades.orEmpty()
    if (recentTrades.isNotEmpty()) {
        item {
            Column(Modifier.testTag("transaction-history").semantics {
                stateDescription = "${recentTrades.size} completed transactions"
                this[TransactionHistoryIds] = recentTrades.map { it.id }
            }) { SectionTitle("Recent Activity", "Executed transactions") }
        }
        items(recentTrades, key = { it.id }) { trade -> TransactionCard(trade) }
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
    PortfolioPanel(modifier = Modifier.testTag("transaction-${trade.id}").semantics(mergeDescendants = true) {}) {
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
