package com.example.cpen321application.ui.portfolio

import com.example.cpen321application.HoldingItemResponse
import org.junit.Assert.*
import org.junit.Test

class HoldingValuationTest {
    private val holding = HoldingItemResponse("AAPL", 2.0, 100.0)

    @Test fun missingPriceDoesNotInventZeroProfit() {
        val evaluated = evaluateHolding(holding, null)
        assertNull(evaluated.currentPrice)
        assertNull(evaluated.currentValue)
        assertNull(evaluated.profitLoss)
        assertNull(evaluated.returnPercent)
        assertEquals(200.0, evaluated.costBasis, 0.001)
    }

    @Test fun invalidPricesAreUnavailable() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0).forEach {
            assertNull(evaluateHolding(holding, it).currentValue)
        }
    }

    @Test fun recoveredQuoteRestoresRealProfit() {
        val evaluated = evaluateHolding(holding, 150.0)
        assertEquals(300.0, evaluated.currentValue!!, 0.001)
        assertEquals(100.0, evaluated.profitLoss!!, 0.001)
        assertEquals(50.0, evaluated.returnPercent!!, 0.001)
    }
}
