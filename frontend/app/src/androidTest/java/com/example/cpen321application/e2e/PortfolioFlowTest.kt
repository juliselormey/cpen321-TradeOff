package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.test.espresso.Espresso.pressBack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PortfolioFlowTest : RealBackendTest() {
    private fun openPortfolio() {
        val cash = snapshot().cashBalance
        tab("Portfolio")
        waitText("Total Portfolio Value")
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("portfolio-cash") and hasText(money(cash))).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun scroll(tag: String) = compose.onNodeWithTag("portfolio-list").performScrollToNode(hasTestTag(tag))

    @Test fun externalTradeRefreshesOpenPortfolioWithoutNavigation() {
        seedHolding()
        openPortfolio()
        seedHolding(1.0)
        val after = snapshot()
        pollingTick()
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("portfolio-cash") and hasText(money(after.cashBalance))).fetchSemanticsNodes().isNotEmpty()
        }
        scroll("holding-MSFT")
        compose.onNodeWithTag("holding-MSFT").assertTextContains("3 shares @", substring = true)
    }

    // Design failure scenario: retained market values must be identified as stale.
    @Test fun quoteRefreshFailureLabelsRetainedPortfolioValuesStale() {
        seedHolding()
        openPortfolio()
        fault("market")
        pollingTick()
        waitText("stale", substring = true, timeout = 25_000)
        compose.onNodeWithTag("portfolio-total").assertExists()
    }

    @Test fun holdingPurchaseUpdatesSharesCashActivityAndAllocation() {
        seedHolding()
        openPortfolio()
        scroll("holding-MSFT")
        compose.onNodeWithTag("holding-MSFT").assertTextContains("2 shares @", substring = true).performClick()
        waitText("Trade MSFT")
        val before = snapshot()
        order("BUY")
        val after = snapshot()
        assertEquals(3.0, after.holdings.single().quantity, 0.0001)
        val trade = after.recentTrades.first { it.id !in before.recentTrades.map { old -> old.id } }
        assertEquals(before.cashBalance - trade.totalAmount, after.cashBalance, 0.001)
        pressBack()
        waitText("Total Portfolio Value")
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("portfolio-cash") and hasText(money(after.cashBalance))).fetchSemanticsNodes().isNotEmpty()
        }
        scroll("holding-MSFT")
        compose.onNodeWithTag("holding-MSFT").assertTextContains("3 shares @", substring = true)
        val displayedValue = compose.onNodeWithTag("holding-MSFT").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .map { it.text }.single { it.matches(Regex("^\\$[0-9,]+\\.[0-9]{2}$")) }
        scroll("allocation-MSFT")
        compose.onNodeWithTag("allocation-MSFT").assertTextContains(displayedValue)
        val value = displayedValue.removePrefix("$").replace(",", "").toDouble()
        compose.onNodeWithTag("allocation-MSFT").assertTextContains(
            String.format(java.util.Locale.US, "%.1f%%", value / (value + after.cashBalance) * 100))
        compose.onNodeWithContentDescription("Asset allocation donut chart").assertExists()
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("Bought MSFT"))
        compose.onAllNodesWithText("Bought MSFT").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(trade.createdAt.take(10)).onFirst().assertExists()
        compose.onNodeWithText("1 shares @ ${money(trade.pricePerShare)}").assertExists()
    }

    @Test fun emptyPortfolioShowsStartingCashWithoutInventedHoldings() {
        openPortfolio()
        compose.onNodeWithTag("portfolio-total").assertTextEquals(money(10000.0))
        compose.onNodeWithTag("portfolio-cash").assertTextEquals(money(10000.0))
        assertTrue(snapshot().holdings.isEmpty())
    }

    @Test fun portfolioFailureShowsErrorRetainsBalanceAndRecovers() {
        seedHolding()
        openPortfolio()
        val cash = snapshot().cashBalance
        fault("portfolio")
        pollingTick()
        waitText("Failed to load portfolio")
        compose.onNodeWithTag("portfolio-cash").assertTextEquals(money(cash))
        fault("")
        pollingTick()
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Failed to load portfolio").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("portfolio-cash").assertTextEquals(money(cash))
    }

    /** Supplemental deterministic backend price perturbation, not real Alpaca price evidence. */
    @Test fun priceMovementRevaluesPortfolioWithoutChangingSharesOrCash() {
        seedHolding()
        val before = snapshot()

        openPortfolio()
        fault("price-rise")
        val price = runBlocking { market.getStocks().stocks.first { it.ticker == "MSFT" }.currentPrice!!.toDouble() }
        pollingTick()
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("portfolio-total") and hasText(money(before.cashBalance + 2 * price)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(before, snapshot())
        val gain = 2 * (price - before.holdings.single().averageCost)
        compose.onNodeWithTag("portfolio-profit").assertTextContains(money(kotlin.math.abs(gain)), substring = true)
        scroll("holding-MSFT")
        compose.onNodeWithTag("holding-MSFT").assertTextContains(money(2 * price))
        scroll("allocation-MSFT")
        compose.onNodeWithTag("allocation-MSFT").assertTextContains(money(2 * price))
        compose.onNodeWithTag("allocation-MSFT").assertTextContains(
            String.format(java.util.Locale.US, "%.1f%%", 2 * price / (before.cashBalance + 2 * price) * 100))
    }
}
