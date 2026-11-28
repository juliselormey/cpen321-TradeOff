package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Limit BUY: real pending reservation and immediate fill; no market-crossing injection. */
class PlaceLimitOrderE2ETest : TradingE2ESupport() {
    @Test fun limitBuyValidatesReservesCashPersistsAndCanFillImmediately() {
        val initial = snapshot()
        quote() // Check the actual symbol can support both branches within the test budget.
        openTrade()
        chooseOrder("LIMIT")

        // Failure: missing/zero quantity or limit price must block submission without writes.
        quantity(""); limit("")
        compose.onNodeWithText("BUY").performScrollTo().assertIsNotEnabled()
        quantity("0"); limit("1")
        compose.onNodeWithText("Please enter a valid quantity greater than 0.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("BUY").assertIsNotEnabled()
        quantity("2"); limit("")
        compose.onNodeWithText("BUY").performScrollTo().assertIsNotEnabled()
        limit("0")
        compose.onNodeWithText("Please enter a valid limit price greater than 0.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("BUY").assertIsNotEnabled()
        assertEquals(initial, snapshot())

        // Failure: reserve exceeds available cash; correction returns to a valid submission.
        quantity("2"); limit("1000000")
        compose.onNodeWithText("Cannot Buy: Insufficient cash balance", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("BUY").performScrollTo().assertIsNotEnabled()
        assertEquals(initial, snapshot())

        // Success: two shares at $1 remain pending using the real current MSFT price.
        val order = pending("BUY", 2)
        assertEquals(1.0, order.limitPrice!!, 0.0001)
        val reserved = snapshot()
        assertEquals(initial.cashBalance - 2, reserved.cashBalance, 0.0001)
        assertEquals(2.0, reserved.reservedCash, 0.0001)
        assertTrue(reserved.holdings.isEmpty())
        assertTrue(reserved.recentTrades.isEmpty())
        compose.onNodeWithText("BUY 2.0 MSFT @ $1.00").performScrollTo().assertIsDisplayed()
        assertOrderStatus(order.id, "PENDING")

        // Persistence and portfolio: cash + reserve remains the original cash-only total.
        showPortfolio()
        // The refactored screen has no demo balance: wait for the real valuation response.
        portfolioNode(hasTestTag("portfolio-total") and hasText("$10,000.00"))
        compose.onNodeWithTag("portfolio-total").assertTextEquals("$10,000.00")
        portfolioNode(hasText("BUY 2 MSFT"))
        compose.onNodeWithText("Limit @ $1.00 • PENDING").assertExists()
        compose.activityRule.scenario.recreate()
        openTrade()
        compose.onNodeWithText("BUY 2.0 MSFT @ $1.00").performScrollTo().assertIsDisplayed()
        assertEquals(reserved, snapshot())

        // Setup for the second branch: cancel through the real UI and release the reserve.
        cancelOnTrade()
        val beforeFill = snapshot()
        assertTrue(beforeFill.pendingOrders.isEmpty())
        val limitPrice = String.format(Locale.US, "%.4f", quote() * 2)
        chooseOrder("LIMIT"); quantity("1"); limit(limitPrice)
        submit("BUY", "LIMIT BUY filled")
        val filled = snapshot()
        val trade = assertFilled(beforeFill, filled, "BUY", 1)
        assertTrue("Immediate limit BUY must execute at or below the limit", trade.pricePerShare <= limitPrice.toDouble())
        assertTrue(filled.pendingOrders.isEmpty())
        assertEquals(0.0, filled.reservedCash, 0.0001)
        compose.onNodeWithText(money(filled.cashBalance)).performScrollTo().assertIsDisplayed()
        backToMarket(); openTrade()
        assertEquals(filled, snapshot())
        // Do not wait for or force a future crossing of a pending order's market price.
    }
}
