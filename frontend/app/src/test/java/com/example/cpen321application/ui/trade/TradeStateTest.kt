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
        val state = TradeState("AAPL", { false }, null, market, this)
        state.availableCash = 195.0
        state.updateQuantity("1")
        assertFalse(state.isBuyEnabled) // No live quote has loaded yet.
        state.refreshQuote()
        assertFalse(state.isBuyEnabled)
        assertEquals(200.0, state.totalAmount, 0.001)
        assertEquals(2.0, state.marketChange!!, 0.001)
    }

    @Test
    fun limitPriceValidationAndEstimateFollowFormEdits() = runBlocking {
        val state = TradeState("AAPL", { false }, null, market, this)
        state.updateQuantity("2")
        state.selectOrderType("LIMIT")
        state.updateLimitPrice("0")
        assertFalse(state.isBuyEnabled)
        assertNotNull(state.inputError)
        state.updateLimitPrice("150")
        assertFalse(state.isBuyEnabled) // A limit order still needs a live quote.
        state.refreshQuote()
        assertTrue(state.isBuyEnabled)
        assertNull(state.inputError)
        assertEquals(300.0, state.totalAmount, 0.001)
    }

    @Test
    fun previewBuyAndSellKeepOppositeCashAndShareDirections() = runBlocking {
        val state = TradeState("AAPL", { false }, null, market, this)
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
    fun liveSnapshotsRepriceOrderWithoutResettingInputsAndStaleDataBlocksTrading() = runBlocking {
        val state = TradeState("AAPL", { false }, null, market, this)
        state.availableCash = 250.0
        state.ownedShares = 2.0
        state.updateQuantity("1")
        val stock = market.getStocks().stocks.single()
        state.applyMarketStock(stock)
        assertTrue(state.isBuyEnabled)
        assertTrue(state.isSellEnabled)
        state.applyMarketStock(stock.copy(currentPrice = "300.0", percentageChange = 3.0))
        assertEquals(300.0, state.totalAmount, 0.001)
        assertFalse(state.isBuyEnabled)
        assertEquals("1", state.quantityText)
        assertEquals(250.0, state.availableCash, 0.001)
        state.applyMarketStock(stock.copy(status = "stale"))
        assertEquals(200.0, state.currentMarketPrice!!, 0.001)
        assertFalse(state.isSellEnabled)
        state.applyMarketStock(stock, disconnected = true)
        assertFalse(state.isBuyEnabled)
        state.applyMarketStock(stock)
        assertTrue(state.isBuyEnabled)
    }

    @Test
    fun cancelledQuoteRefreshPropagatesCancellation() = runBlocking {
        val cancelledMarket = object : MarketApiService {
            override suspend fun getStocks(): MarketResponse = throw CancellationException("stopped")
        }
        val state = TradeState("AAPL", { false }, null, cancelledMarket, this)
        try {
            state.refreshQuote()
            fail("Cancellation must propagate to lifecycle polling")
        } catch (expected: CancellationException) {
            assertEquals("stopped", expected.message)
        }
    }
}
