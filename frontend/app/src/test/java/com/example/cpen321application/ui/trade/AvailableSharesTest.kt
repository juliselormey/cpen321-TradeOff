package com.example.cpen321application.ui.trade

import com.example.cpen321application.*
import com.example.cpen321application.ui.market.data.MarketApiService
import com.example.cpen321application.ui.market.data.MarketResponse
import com.google.gson.Gson
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class AvailableSharesTest {
    @Test fun pendingSellReservationsControlSellAvailability() = runBlocking {
        var available = 2.0
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, _ ->
            when (method.name) {
                "getPosition" -> Response.success(UserPositionResponse(1000.0, 10.0, 100.0, available))
                "getPendingOrders" -> Response.success(PendingOrdersResponse(emptyList()))
                else -> error("Unexpected API call: ${method.name}")
            }
        } as ApiService
        val market = object : MarketApiService {
            override suspend fun getStocks() = MarketResponse(emptyList())
        }
        val state = TradeState("AAPL", { true }, api, market, this)
        state.currentMarketPrice = 200.0
        state.updateQuantity("3")
        assertFalse(state.isSellEnabled) // Account position has not loaded.
        state.refreshAccount()
        assertEquals(10.0, state.ownedShares, 0.001)
        assertFalse(state.isSellEnabled)
        state.updateQuantity("2")
        assertTrue(state.isSellEnabled)
        available = 0.0
        state.refreshAccount()
        assertFalse(state.isSellEnabled)
        assertTrue(state.sellBlocker!!.contains("pending sells"))
    }

    @Test fun orderPayloadsSendNoClientExecutionPrice() {
        val gson = Gson()
        listOf(
            ExecuteMarketOrderRequest("AAPL", "BUY", 1.0),
            ExecuteLimitOrderRequest("AAPL", "SELL", 1.0, 150.0),
        ).forEach { assertFalse(gson.toJson(it).contains("pricePerShare")) }
        assertTrue(gson.toJson(ExecuteLimitOrderRequest("AAPL", "SELL", 1.0, 150.0)).contains("limitPrice"))
    }
}
