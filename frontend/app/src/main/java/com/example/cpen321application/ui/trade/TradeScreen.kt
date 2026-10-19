package com.example.cpen321application.ui.trade

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun TradeScreen(ticker: String) {
    Text(text = "Trade: $ticker")
}
