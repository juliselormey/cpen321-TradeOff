package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import com.example.cpen321application.PortfolioSummaryResponse
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TradingE2ETest : TradingE2EFixture() {
    private fun cleanAccountSnapshot(): PortfolioSummaryResponse = snapshot().also {
        assertEquals(10000.0, it.cashBalance, 0.0001)
        assertTrue(it.holdings.isEmpty())
        assertTrue(it.recentTrades.isEmpty())
        assertTrue(it.pendingOrders.isEmpty())
    }

    @Test
    fun rejectsEmptyQuantityWithoutChangingAccount() {
        val before = cleanAccountSnapshot()
        openMicrosoft()
        quantity("")
        compose.onNodeWithText("BUY").performScrollTo().assertIsNotEnabled()
        assertTradingAccountUnchanged(before, snapshot())
    }

    @Test
    fun rejectsZeroQuantityWithoutChangingAccount() {
        val before = cleanAccountSnapshot()
        openMicrosoft()
        quantity("0")
        compose.onNodeWithText("Please enter a valid quantity greater than 0.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("BUY").performScrollTo().assertIsNotEnabled()
        assertTradingAccountUnchanged(before, snapshot())
    }

    @Test
    fun rejectsInsufficientCashWithoutChangingAccount() {
        val before = cleanAccountSnapshot()
        openMicrosoft()
        quantity("1000000") // Valid server maximum; a million MSFT shares exceeds the fixture balance.
        compose.onNodeWithText("Cannot Buy: Insufficient cash balance", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cannot Buy: Insufficient cash balance ($10000.00 available).")
            .assertIsDisplayed()
        compose.onNodeWithText("BUY").performScrollTo().assertIsNotEnabled()
        assertTradingAccountUnchanged(before, snapshot())
        // Correct the invalid order and verify that submission becomes available again.
        quantity("1")
        waitForBuyEnabled()
        assertTradingAccountUnchanged(before, snapshot())
    }

    @Test
    fun leavingAnUnsubmittedOrderDoesNotChangeAccount() {
        val before = cleanAccountSnapshot()
        openMicrosoft()
        quantity("1")
        compose.onNodeWithContentDescription("Back to Market").performScrollTo().performClick()
        waitFor("Market")
        assertTradingAccountUnchanged(before, snapshot())
    }

    @Test
    fun marketBuyPersistsOnePurchaseAndItsBalance() {
        val before = cleanAccountSnapshot()
        openMicrosoft()
        quantity("1")
        waitForBuyEnabled()
        compose.onNodeWithText("BUY").performScrollTo().assertIsEnabled().performClick()
        // Success 7–8: the real backend obtains a price and validates funds/quantity.
        // These internal steps are checked through their persisted result below.
        // Failure 7a–7a2 is not forced: no Alpaca outage or response is injected.
        // See the explicit coverage limitation in Testing.md; no course exemption is claimed.
        // Success 10, UI result: show the market-order fill confirmation.
        val after = run {
            waitFor("MARKET BUY filled", substring = true)
            compose.onNodeWithText("MARKET BUY filled", substring = true).performScrollTo().assertIsDisplayed()
            snapshot()
        }
        // Success 10, recorded transaction: exactly one BUY of one MSFT share.
        assertEquals(1, after.recentTrades.size)
        val trade = after.recentTrades.single()
        assertEquals("MSFT", trade.ticker)
        assertEquals("BUY", trade.type)
        assertEquals(1.0, trade.quantity, 0.0001)
        // Success 7: the execution has a positive actual price, with no fixed-price fixture.
        assertTrue(trade.pricePerShare > 0)
        assertEquals(trade.pricePerShare * trade.quantity, trade.totalAmount, 0.0002)
        // Success 9: deduct the execution cost and add the purchased shares.
        assertEquals(before.cashBalance - trade.totalAmount, after.cashBalance, 0.0002)
        val holding = after.holdings.single()
        assertEquals("MSFT", holding.ticker)
        assertEquals(1.0, holding.quantity, 0.0001)
        assertEquals(trade.pricePerShare, holding.averageCost, 0.0002)
        // An immediately executed market BUY must leave no pending order.
        assertTrue(after.pendingOrders.isEmpty())
        val cashText = String.format(Locale.US, "$%.2f", after.cashBalance)
        compose.onNodeWithText(cashText).performScrollTo().assertIsDisplayed()

        // Use-case postconditions: reopen Trading and verify persisted cash/holdings/history.
        compose.onNodeWithContentDescription("Back to Market").performScrollTo().performClick()
        // Verify the purchase through the actual Portfolio UI as well as backend evidence.
        compose.onNode(hasText("Portfolio") and hasClickAction()).performClick()
        compose.waitUntil(30_000) {
            runCatching {
                compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("Bought MSFT"))
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithText("Bought MSFT").assertIsDisplayed()
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("‹ Back to Market"))
        compose.onNodeWithText("‹ Back to Market").performClick()
        openMicrosoft()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(cashText).fetchSemanticsNodes().isNotEmpty()
        }
        assertTradingAccountUnchanged(after, snapshot())
    }
}
