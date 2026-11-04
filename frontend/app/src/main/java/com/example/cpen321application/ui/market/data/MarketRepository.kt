package com.example.cpen321application.ui.market.data

class MarketRepository(private val api: MarketApiService) {
    suspend fun loadStocks(): List<MarketStock> {
        val stocks = api.getStocks().stocks
        check(stocks.isNotEmpty() && stocks.any { it.ticker == "AAPL" }) {
            "The backend returned an invalid market response."
        }
        return stocks
    }
}
