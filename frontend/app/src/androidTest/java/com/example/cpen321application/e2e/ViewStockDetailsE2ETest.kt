package com.example.cpen321application.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import com.example.cpen321application.ui.trade.StockSnapshotTime
import org.junit.Assert.*
import org.junit.Test

/** View Stock Details with actual Alpaca history and live backend cache snapshots. */
class ViewStockDetailsE2ETest : MarketE2ESupport() {
    @Test fun detailsShowsIdentityInteractiveHistoryAndLiveUpdatesWithoutTrading() {
        val candidates = catalog.filter { stock ->
            stock.status == "ok" && stock.currentPrice?.toDoubleOrNull()?.let { it > 0 } == true &&
                stock.history?.points?.map { it.timestamp }?.distinct()?.size?.let { it >= 3 } == true
        }
        check(candidates.size >= 2) { "Need two real stocks with available quotes and at least three historical timestamps." }
        val first = candidates.first()
        val second = candidates.first { it.ticker != first.ticker }

        // Success 1–6: select a stock, verify its identity, quote and default 1M chart.
        openStock(first)
        waitFor("Live")
        compose.onNodeWithText(first.companyName).assertIsDisplayed()
        compose.onNodeWithContentDescription("${first.companyName} logo", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("USD").assertExists()
        compose.onNodeWithText("vs previous close").assertExists()
        val price = nodeText("stock-detail-price")
        assertTrue(price.startsWith("$") && price.drop(1).replace(",", "").toDouble() > 0)
        compose.onNodeWithText("1M").assertIsSelected()
        compose.onNodeWithTag("stock-detail-chart").performScrollTo().assertIsDisplayed()

        // Success 7–11: periods use available real observations, including a legitimate sparse 1D.
        compose.onNodeWithText("1W").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("1D").performScrollTo().performClick().assertIsSelected()
        val chart = compose.onAllNodesWithTag("stock-detail-chart").fetchSemanticsNodes()
        if (chart.isEmpty()) compose.onNodeWithText("Not enough historical data for this period yet.").assertExists()
        else compose.onNodeWithTag("stock-detail-chart").assertExists()
        compose.onNodeWithText("1M").performScrollTo().performClick().assertIsSelected()

        // Success 12–14: inspect an older point, then drag to a later point.
        compose.onNodeWithTag("stock-detail-chart").performScrollTo().performTouchInput {
            click(Offset(width * 0.05f, height * 0.5f))
        }
        val earlier = nodeText("stock-chart-selected-time")
        assertTrue(nodeText("stock-chart-selected-price").startsWith("$"))
        compose.onNodeWithTag("stock-detail-chart").performTouchInput {
            swipe(Offset(width * 0.05f, height * 0.5f), Offset(width * 0.95f, height * 0.5f), 500)
        }
        val later = nodeText("stock-chart-selected-time")
        assertNotEquals("Inspecting different parts of the month selects different real timestamps", earlier, later)

        // Success 15: require a new successful backend retrieval, not a changed market price.
        fun fetchedAt() = compose.onNodeWithTag("stock-details").fetchSemanticsNode().config[StockSnapshotTime]
        val before = fetchedAt()
        assertTrue(before.isNotEmpty())
        compose.waitUntil(30_000) { fetchedAt() != before }
        compose.onNodeWithText("1M").assertIsSelected()
        compose.onNodeWithTag("stock-detail-chart").assertExists()
        assertEquals("Live updates preserve the inspected chart point", later, nodeText("stock-chart-selected-time"))

        // Success 16–17: back and select a different stock; no BUY/SELL action is performed.
        compose.onNodeWithContentDescription("Back to Market").performScrollTo().performClick()
        waitFor("Market")
        openStock(second)
        waitFor("Live")
        compose.onNodeWithText(second.companyName).assertIsDisplayed()
        compose.onNodeWithContentDescription("${second.companyName} logo", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Trade ${first.ticker}").assertDoesNotExist()
        // No injected outage, missing history, guest session or fabricated price response.
    }
}
