package com.example.cpen321application.ui.market.data

import retrofit2.http.GET

interface MarketApiService {
    @GET("api/market/stocks")
    suspend fun getStocks(): MarketResponse
}

data class MarketResponse(val stocks: List<MarketStock>)
data class MarketIssue(val code: String, val message: String)

data class MarketPricePoint(val timestamp: String, val price: Double)

data class MarketHistory(
    val points: List<MarketPricePoint>,
    val status: String,
    val dailyBarsUpdatedAt: String? = null,
    val error: MarketIssue? = null,
)

data class MarketStock(
    val ticker: String,
    val companyName: String,
    val currentPrice: String?,
    val percentageChange: Double?,
    val performancePoints: List<Float>,
    val isFavorite: Boolean,
    val source: String,
    val status: String,
    val quoteAsOf: String? = null,
    val fetchedAt: String? = null,
    val error: MarketIssue? = null,
    val change: String? = null,
    val history: MarketHistory? = null,
)

// Metadata keeps the list usable while loading. Never invent an offline price.
internal fun initialMarketStocks(): List<MarketStock> = listOf(
    Triple("AAPL", "Apple Inc.", true),
    Triple("MSFT", "Microsoft Corporation", false),
    Triple("TSLA", "Tesla, Inc.", true),
    Triple("NVDA", "NVIDIA Corporation", true),
    Triple("GOOGL", "Alphabet Inc.", false),
    Triple("AMZN", "Amazon.com, Inc.", false),
).map { (symbol, company, favorite) ->
    MarketStock(symbol, company, null, null, emptyList(), favorite, "alpaca", "unavailable")
}
