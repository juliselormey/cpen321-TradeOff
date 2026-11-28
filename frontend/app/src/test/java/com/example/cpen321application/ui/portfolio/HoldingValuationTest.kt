package com.example.cpen321application.ui.portfolio

import com.example.cpen321application.ApiService
import com.example.cpen321application.PortfolioSummaryResponse
import com.google.gson.Gson
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class HoldingValuationTest {
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, _, _ ->
        error("Reading a portfolio snapshot must not fetch separate market prices")
    } as ApiService
    private fun state(json: String): PortfolioState = PortfolioState({ true }, api).also {
        it.summary = Gson().fromJson(json, PortfolioSummaryResponse::class.java)
    }
    private val snapshot = """{
        "cashBalance":9000,"startingBalance":10000,"reservedCash":1000,
        "totalValue":10432.10,"profitLoss":432.10,"returnPercent":4.321,
        "holdings":[{"ticker":"AAPL","quantity":2,"averageCost":100,
            "price":205,"value":432.10,"profitLoss":32.10,"returnPercent":7.25}],
        "allocation":[{"label":"Cash","percent":95.858,"value":10000}],
        "pendingOrders":[],"recentTrades":[]
    }"""

    @Test fun displaysServerValuesWithoutRecalculatingThem() {
        val state = state(snapshot)
        assertEquals(10432.10, state.totalPortfolioValue!!, 0.001)
        assertEquals(432.10, state.totalProfitLoss!!, 0.001)
        assertEquals(4.321, state.totalReturnPercent!!, 0.001)
        assertEquals(432.10, state.evaluatedHoldings.single().value!!, 0.001)
        assertEquals(7.25, state.evaluatedHoldings.single().returnPercent!!, 0.001)
        assertEquals(95.858, state.allocation.single().percent, 0.001)
    }

    @Test fun failedRefreshHidesOldValuationsUntilRecovery() {
        val state = state(snapshot)
        state.valuationUnavailable = true
        assertNull(state.totalPortfolioValue)
        assertNull(state.totalProfitLoss)
        assertNull(state.evaluatedHoldings.single().price)
        assertTrue(state.allocation.isEmpty())
        state.valuationUnavailable = false
        assertEquals(10432.10, state.totalPortfolioValue!!, 0.001)
    }

    @Test fun missingServerValuesNeverBecomeAStartingBalanceOrZeroProfit() {
        val state = state("""{"cashBalance":9000,"holdings":[],"pendingOrders":[],"recentTrades":[]}""")
        assertNull(state.totalPortfolioValue)
        assertNull(state.totalProfitLoss)
        assertTrue(state.hasUnavailablePrices)
        assertNull(PortfolioState({ true }, api).cashBalance)
    }
}
