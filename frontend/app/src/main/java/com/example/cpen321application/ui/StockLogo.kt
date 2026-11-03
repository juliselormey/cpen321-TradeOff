package com.example.cpen321application.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.R
import java.util.Locale

@Composable
internal fun StockLogo(ticker: String, modifier: Modifier = Modifier) {
    val symbol = ticker.uppercase(Locale.US)
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (symbol == "AAPL") Color(0xFF111111) else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val logo = when (symbol) {
            "AAPL" -> R.drawable.ic_stock_apple
            "MSFT" -> R.drawable.ic_stock_microsoft
            "TSLA" -> R.drawable.ic_stock_tesla
            "NVDA" -> R.drawable.ic_stock_nvidia
            "GOOGL" -> R.drawable.ic_stock_google
            "AMZN" -> R.drawable.ic_stock_amazon
            else -> null
        }
        if (logo != null) Image(
            painterResource(logo), null,
            Modifier.fillMaxSize().padding(
                if (symbol in listOf("TSLA", "NVDA", "AMZN")) 0.dp else 9.dp,
            ),
        )
        else Text(symbol.take(1), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}
