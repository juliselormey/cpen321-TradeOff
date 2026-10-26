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

// Offline samples preserve the original values. AAPL never has a fake quote.
internal fun initialMarketStocks(): List<MarketStock> = listOf(
    MarketStock("AAPL", "Apple Inc.", null, null, emptyList(), true, "alpaca", "unavailable"),
    MarketStock("MSFT", "Microsoft Corporation", "415.26", 0.82,
        listOf(407f, 410f, 409f, 413f, 415f), false, "mock", "mock"),
    MarketStock("TSLA", "Tesla Inc.", "242.84", -2.14,
        listOf(251f, 248f, 250f, 245f, 243f), true, "mock", "mock"),
    MarketStock("NVDA", "NVIDIA Corporation", "138.07", 1.91,
        listOf(132f, 134f, 133f, 136f, 138f), true, "mock", "mock"),
    MarketStock("GOOGL", "Alphabet Inc.", "242.84", -1.03,
        listOf(248f, 247f, 245f, 244f, 243f), false, "mock", "mock"),
    MarketStock("AMZN", "Amazon.com, Inc.", "178.29", 2.19,
        listOf(169f, 171f, 173f, 177f, 178f), false, "mock", "mock"),
)
