package com.example.cpen321application.ui.trade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.PendingOrderItem

@Composable
internal fun PendingOrdersCard(orders: List<PendingOrderItem>, onCancelOrder: (String) -> Unit, enabled: Boolean = true) {
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
                    OutlinedButton(enabled = enabled, onClick = { onCancelOrder(order.id) }, shape = RoundedCornerShape(8.dp)) {
                        Text("Cancel", fontSize = 12.sp, color = Red)
                    }
                }
            }
        }
    }
}
