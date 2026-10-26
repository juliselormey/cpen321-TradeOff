package com.example.cpen321application.ui.market

import com.example.cpen321application.ui.market.data.MarketApiService
import com.example.cpen321application.ui.market.data.MarketRepository
import com.example.cpen321application.ui.market.data.MarketResponse
import com.example.cpen321application.ui.market.data.initialMarketStocks
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson
import com.example.cpen321application.ui.market.data.MarketHistory
import com.example.cpen321application.ui.market.data.MarketPricePoint

class MarketViewModelTest {
    @Test
    fun alpacaHistoryContractDeserializesWithoutKeysOrDirectProviderAccess() = runBlocking {
        val response = Gson().fromJson("""{
          "stocks": [{"ticker":"AAPL","companyName":"Apple Inc.","currentPrice":"200.25",
          "percentageChange":1.2,"change":"2.25","performancePoints":[198,200.25],
          "isFavorite":true,"source":"alpaca","status":"ok",
          "history":{"status":"ok","points":[
            {"timestamp":"2026-10-22T04:00:00Z","price":198},
            {"timestamp":"2026-10-23T15:00:00Z","price":200.25}]}}]
        }""", MarketResponse::class.java)
        val model = MarketViewModel(MarketRepository(object : MarketApiService {
            override suspend fun getStocks() = response
        }))
        model.refresh()
        assertEquals("alpaca", model.state.value.stocks.first().source)
        assertEquals("2.25", model.state.value.stocks.first().change)
        assertEquals(2, model.state.value.stocks.first().history?.points?.size)
    }

    @Test
    fun unavailableBackendNeverFabricatesAnApplePrice() = runBlocking {
        val model = MarketViewModel(MarketRepository(object : MarketApiService {
            override suspend fun getStocks(): MarketResponse = throw IOException("offline")
        }))
        model.refresh()
        val state = model.state.value
        assertNull(state.stocks.first().currentPrice)
        assertEquals(5, state.stocks.count { it.source == "mock" })
        assertNotNull(state.error)
        assertFalse(state.refreshing)
    }

    @Test
    fun connectionFailureRetainsLastQuoteAndMarksItStale() = runBlocking {
        var offline = false
        val stocks = initialMarketStocks().map {
            if (it.ticker == "AAPL") it.copy(
                currentPrice = "200.25", status = "ok", fetchedAt = "2026-10-23T20:00:00Z",
                history = MarketHistory(listOf(MarketPricePoint("2026-10-23T20:00:00Z", 200.25)), "ok"),
            ) else it
        }
        val model = MarketViewModel(MarketRepository(object : MarketApiService {
            override suspend fun getStocks(): MarketResponse {
                if (offline) throw IOException("offline")
                return MarketResponse(stocks)
            }
        }))
        model.refresh()
        assertEquals("200.25", model.state.value.stocks.first().currentPrice)
        assertNotNull(model.state.value.checkedAt)
        offline = true
        model.refresh()
        assertEquals("200.25", model.state.value.stocks.first().currentPrice)
        assertEquals("stale", model.state.value.stocks.first().status)
        assertEquals("stale", model.state.value.stocks.first().history?.status)
        assertEquals(stocks.first().history?.points, model.state.value.stocks.first().history?.points)
        assertEquals(stocks.drop(1), model.state.value.stocks.drop(1))
        offline = false
        model.refresh()
        assertNull(model.state.value.error)
        assertEquals("ok", model.state.value.stocks.first().status)
    }

    @Test
    fun lifecycleCancellationIsNotReportedAsAnOutage() = runBlocking {
        val model = MarketViewModel(MarketRepository(object : MarketApiService {
            override suspend fun getStocks(): MarketResponse = throw CancellationException("screen stopped")
        }))
        try {
            model.refresh()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertNull(model.state.value.error)
            assertFalse(model.state.value.refreshing)
        }
    }
}
