package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test

/** Market/limit SELL with independent real BUY preparation and actual share reservation. */
class SellSharesE2ETest : TradingE2ESupport() {
    @Test fun sellValidatesAvailableSharesThenSupportsMarketPendingAndImmediateLimitSales() {
        quote()
        openTrade()
        // Precondition: create four owned shares through a real UI purchase.
        val funded = buyShares(4)
        chooseOrder("MARKET")

        // Failures: blank/zero or more than owned. All attempted invalid inputs are read-only.
        quantity("")
        compose.onNodeWithText("SELL").performScrollTo().assertIsNotEnabled()
        quantity("0")
        compose.onNodeWithText("Please enter a valid quantity greater than 0.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SELL").assertIsNotEnabled()
        quantity("5")
        compose.onNodeWithText("Cannot Sell: Insufficient available shares", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SELL").performScrollTo().assertIsNotEnabled()
        assertEquals(funded, snapshot())

        // Success: partial market SELL creates exactly one sale and adds its real proceeds.
        quantity("1")
        submit("SELL", "MARKET SELL filled")
        val partial = snapshot()
        assertFilled(funded, partial, "SELL", 1)
        assertEquals(3.0, partial.holdings.single().quantity, 0.0001)
        assertTrue(partial.pendingOrders.isEmpty())

        // Failures: an invalid limit price blocks a limit SELL.
        chooseOrder("LIMIT"); quantity("2"); limit("")
        compose.onNodeWithText("SELL").performScrollTo().assertIsNotEnabled()
        limit("0")
        compose.onNodeWithText("Please enter a valid limit price greater than 0.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SELL").assertIsNotEnabled()
        assertEquals(partial, snapshot())

        // Success: distant limit SELL reserves two shares, without cash or history changes.
        val order = pending("SELL", 2)
        val reserved = snapshot()
        assertEquals(partial.cashBalance, reserved.cashBalance, 0.0001)
        assertEquals(partial.holdings, reserved.holdings)
        assertEquals(partial.recentTrades, reserved.recentTrades)
        assertEquals(1.0, position().availableQuantity, 0.0001)
        assertOrderStatus(order.id, "PENDING")

        // Failure: owned quantity is three, but only one unreserved share can be sold.
        chooseOrder("MARKET"); quantity("2")
        compose.onNodeWithText("Cannot Sell: Insufficient available shares", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SELL").performScrollTo().assertIsNotEnabled()
        assertEquals(reserved, snapshot())

        // Release the setup reserve through UI before testing an immediately fillable limit.
        cancelOnTrade()
        chooseOrder("LIMIT"); quantity("1"); limit("0.01")
        val beforeLimit = snapshot()
        submit("SELL", "LIMIT SELL filled")
        val limitFilled = snapshot()
        val sale = assertFilled(beforeLimit, limitFilled, "SELL", 1)
        assertTrue(sale.pricePerShare >= 0.01)
        assertTrue(limitFilled.pendingOrders.isEmpty())

        // Success: sell the remaining two shares; no positive holding remains.
        chooseOrder("MARKET"); quantity("2")
        submit("SELL", "MARKET SELL filled")
        val closed = snapshot()
        assertFilled(limitFilled, closed, "SELL", 2)
        assertTrue(closed.holdings.isEmpty())
        assertEquals(0.0, position().ownedQuantity, 0.0001)
        assertEquals(0.0, position().availableQuantity, 0.0001)
        assertTrue(closed.pendingOrders.isEmpty())
        assertEquals(3, closed.recentTrades.count { it.type == "SELL" })
        compose.onNodeWithText(money(closed.cashBalance)).performScrollTo().assertIsDisplayed()

        // Portfolio and reopen: actual SELL entries persist and the holding is gone.
        showPortfolio()
        portfolioNode(hasText("Sold MSFT"))
        assertEquals(closed, snapshot())
        compose.onNodeWithTag("holding-MSFT").assertDoesNotExist()
        compose.activityRule.scenario.recreate(); openTrade()
        compose.waitUntil(30_000) { compose.onAllNodesWithText(money(closed.cashBalance)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(closed, snapshot())
    }
}
