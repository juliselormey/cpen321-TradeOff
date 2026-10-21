package com.example.cpen321application.ui.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class ListedStock(
    val ticker: String,
    val companyName: String,
    val currentPrice: Double,
    val percentageChange: Double,
    val performancePoints: List<Float>,
    val isFavorite: Boolean
)

private val mockStocks = listOf(
    ListedStock(
        ticker = "AAPL",
        companyName = "Apple Inc.",
        currentPrice = 189.43,
        percentageChange = 1.25,
        performancePoints = listOf(184f, 186f, 185f, 188f, 189f),
        isFavorite = true
    ),
    ListedStock(
        ticker = "MSFT",
        companyName = "Microsoft Corporation",
        currentPrice = 415.26,
        percentageChange = 0.82,
        performancePoints = listOf(407f, 410f, 409f, 413f, 415f),
        isFavorite = false
    ),
    ListedStock(
        ticker = "TSLA",
        companyName = "Tesla Inc.",
        currentPrice = 242.84,
        percentageChange = -2.14,
        performancePoints = listOf(251f, 248f, 250f, 245f, 243f),
        isFavorite = true
    ),
    ListedStock(
        ticker = "NVDA",
        companyName = "NVIDIA Corporation",
        currentPrice = 138.07,
        percentageChange = 1.91,
        performancePoints = listOf(132f, 134f, 133f, 136f, 138f),
        isFavorite = true
    ),
    ListedStock(
        ticker = "GOOGL",
        companyName = "Alphabet Inc.",
        currentPrice = 242.84,
        percentageChange = -1.03,
        performancePoints = listOf(248f, 247f, 245f, 244f, 243f),
        isFavorite = false
    ),
    ListedStock(
        ticker = "AMZN",
        companyName = "Amazon.com, Inc.",
        currentPrice = 178.29,
        percentageChange = 2.19,
        performancePoints = listOf(169f, 171f, 173f, 177f, 178f),
        isFavorite = false
    )
)

@Composable
fun StockRow(stock: ListedStock, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable {
            onClick()
        }
    ) {
        Text(text = "${stock.ticker} - ${stock.companyName}")
        Text(text = "Price: $${stock.currentPrice}")
        Text(text = "Change: ${stock.percentageChange}%")

        Canvas(
            modifier = Modifier.fillMaxWidth().height(40.dp)
        ) {
            val points = stock.performancePoints

            if(points.size >= 2) {
                val minValue = points.minOrNull() ?: 0f
                val maxValue = points.maxOrNull() ?: 0f
                val range = maxValue - minValue

                for(i in 0 until points.size - 1) {
                    val startX = size.width * i / (points.size - 1)
                    val endX = size.width * (i + 1) / (points.size - 1)

                    val startY = if (range == 0f) {
                        size.height / 2
                    } else {
                        size.height - ((points[i] - minValue) / range * size.height)
                    }

                    val endY = if (range == 0f) {
                        size.height / 2
                    } else {
                        size.height - ((points[i + 1] - minValue) / range * size.height)
                    }

                    drawLine(
                        color = Color.Black,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY)
                    )
                }
            }
        }
    }
}

@Composable
fun MarketScreen(onStockSelected: (String) -> Unit) {
    // ui components: searching
    var searchQuery by remember {
        mutableStateOf("")
    }
    val filteredStocks = mockStocks.filter{ stock ->
        stock.companyName.contains(searchQuery, ignoreCase = true)
                || stock.ticker.contains(searchQuery, ignoreCase = true)
    }
    val favoritedStocks = filteredStocks.filter { stock ->
        stock.isFavorite.equals(true);
    }

    // actual ui
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(text = "Market")

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { newQuery ->
                searchQuery = newQuery
            },
            placeholder = {
                Text(text = "Search by company or ticker")
            },
            modifier = Modifier.fillMaxWidth()
        )

        Text(text = "My Favorites")
        favoritedStocks.forEach{ stock ->
            StockRow(
                stock = stock,
                onClick = {
                    onStockSelected(stock.ticker)
                }
            )
        }

        Text(text = "All Stocks")
        filteredStocks.forEach{ stock ->
            StockRow(
                stock = stock,
                onClick = {
                    onStockSelected(stock.ticker)
                }
            )
        }
    }
}
