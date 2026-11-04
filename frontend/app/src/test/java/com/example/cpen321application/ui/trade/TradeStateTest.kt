package com.example.cpen321application.ui.trade

import com.example.cpen321application.ui.market.data.MarketApiService
import com.example.cpen321application.ui.market.data.MarketResponse
import com.example.cpen321application.ui.market.data.MarketStock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TradeStateTest {
    private val market = object : MarketApiService {
        override suspend fun getStocks() = MarketResponse(listOf(
            MarketStock("AAPL", "Apple", "200.0", 2.0, emptyList(), false, "alpaca", "ok"),
        ))
    }

    @Test
    fun quoteRefreshRecalculatesOrderAffordability() = runBlocking {
        val state = TradeState(getStockInfo("AAPL"), { false }, null, market, this)
        state.availableCash = 195.0
        state.updateQuantity("1")
        assertTrue(state.isBuyEnabled)
        state.refreshQuote()
        assertFalse(state.isBuyEnabled)
        assertEquals(200.0, state.totalAmount, 0.001)
        assertEquals(2.0, state.activeStockInfo.change, 0.001)
    }

    @Test
    fun limitPriceValidationAndEstimateFollowFormEdits() = runBlocking {
        val state = TradeState(getStockInfo("AAPL"), { false }, null, market, this)
        state.updateQuantity("2")
        state.selectOrderType("LIMIT")
        state.updateLimitPrice("0")
        assertFalse(state.isBuyEnabled)
        assertTrue(state.showInvalidLimitPrice)
        state.updateLimitPrice("150")
        assertTrue(state.isBuyEnabled)
        assertFalse(state.showInvalidLimitPrice)
        assertEquals(300.0, state.totalAmount, 0.001)
    }

    @Test
    fun previewBuyAndSellKeepOppositeCashAndShareDirections() = runBlocking {
        val state = TradeState(getStockInfo("AAPL"), { false }, null, market, this)
        state.refreshQuote()
        state.updateQuantity("2")
        state.submit("BUY")
        assertEquals(9600.0, state.availableCash, 0.001)
        assertEquals(2.0, state.ownedShares, 0.001)
        assertEquals("", state.quantityText)
        state.updateQuantity("1")
        state.submit("SELL")
        assertEquals(9800.0, state.availableCash, 0.001)
        assertEquals(1.0, state.ownedShares, 0.001)
    }

    @Test
    fun cancelledQuoteRefreshPropagatesCancellation() = runBlocking {
        val cancelledMarket = object : MarketApiService {
            override suspend fun getStocks(): MarketResponse = throw CancellationException("stopped")
        }
        val state = TradeState(getStockInfo("AAPL"), { false }, null, cancelledMarket, this)
        try {
            state.refreshQuote()
            fail("Cancellation must propagate to lifecycle polling")
        } catch (expected: CancellationException) {
            assertEquals("stopped", expected.message)
        }
    }
}
