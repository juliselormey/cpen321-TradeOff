package com.example.cpen321application.e2e

import com.example.cpen321application.PortfolioSummaryResponse
import org.junit.Assert.assertEquals

/** Quotes and valuations may change while viewing; persisted account records must not. */
internal fun assertTradingAccountUnchanged(before: PortfolioSummaryResponse, after: PortfolioSummaryResponse) {
    assertEquals(before.cashBalance, after.cashBalance, 0.0001)
    assertEquals(before.startingBalance, after.startingBalance, 0.0001)
    assertEquals(before.reservedCash, after.reservedCash, 0.0001)
    assertEquals(before.holdings.sortedBy { it.ticker }.map { Triple(it.ticker, it.quantity, it.averageCost) },
        after.holdings.sortedBy { it.ticker }.map { Triple(it.ticker, it.quantity, it.averageCost) })
    assertEquals(before.pendingOrders, after.pendingOrders)
    assertEquals(before.recentTrades, after.recentTrades)
}
