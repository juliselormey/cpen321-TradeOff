package com.example.cpen321application.ui.trade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Green = Color(0xFF2E7D32)

internal val Red = Color(0xFFC62828)

@Composable
internal fun StockHeaderCard(ticker: String, price: Double?, change: Double?, quoteFailed: Boolean) {
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
internal fun AccountPositionCard(availableCash: Double, ownedShares: Double) {
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
