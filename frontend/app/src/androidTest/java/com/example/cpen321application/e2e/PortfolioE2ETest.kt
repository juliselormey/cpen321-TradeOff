package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import com.example.cpen321application.PortfolioSummaryResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PortfolioE2ETest : PortfolioE2EFixture() {
    private data class FundedPortfolio(val before: PortfolioSummaryResponse, val evidence: JSONObject)

    private fun openFundedPortfolio(): FundedPortfolio {
        prepareHoldingAndPendingOrder()
        val before = accountSnapshot()
        val evidence = portfolioEvidence()
        tab("Portfolio")
        waitFor("Total Portfolio Value")
        waitForCash(before.cashBalance)
        return FundedPortfolio(before, evidence)
    }

    @Test
    fun emptyPortfolioShowsCashAndDoesNotChangeAccount() {
        // Success 1–2: open Portfolio and retrieve this test's clean account.
        val empty = run {
            val account = accountSnapshot()
            assertEquals(10000.0, account.cashBalance, 0.0001)
            assertTrue(account.holdings.isEmpty())
            assertTrue(account.pendingOrders.isEmpty())
            tab("Portfolio")
            waitFor("Total Portfolio Value")
            waitForCash(account.cashBalance)
            account
        }

        // Success 8: an account without stocks shows cash and an explicit empty-holdings message.
        compose.onNodeWithTag("portfolio-total").assertTextEquals(money(empty.cashBalance))
        scrollTo("portfolio-profit")
        compose.onNodeWithTag("portfolio-profit").assertTextContains(money(0.0), substring = true)
        compose.onNodeWithTag("portfolio-list").performScrollToNode(
            hasText("No stock holdings yet. Use the Market tab to place trades!"))
        compose.onNodeWithText("No stock holdings yet. Use the Market tab to place trades!").assertIsDisplayed()
        assertTradingAccountUnchanged(empty, accountSnapshot())

    }

    @Test
    fun holdingsDisplayServerPriceAndValue() {
        val (beforeViewing, evidence) = openFundedPortfolio()
        val holding = evidence.getJSONArray("holdings").getJSONObject(0)
        // Success 1–4: reopen Portfolio and verify cash, the holding and its live market value.
        tab("Portfolio")
        waitFor("Total Portfolio Value")
        waitForCash(beforeViewing.cashBalance)
        scrollTo("holding-MSFT")
        compose.onNodeWithTag("holding-MSFT").assertTextContains("MSFT")
            .assertTextContains("2 shares @ " + money(holding.getDouble("price")))
            .assertTextContains(money(holding.getDouble("value")))
            .assertTextContains("Avg Cost: " + money(beforeViewing.holdings.single().averageCost))
        assertTradingAccountUnchanged(beforeViewing, accountSnapshot())
    }

    @Test
    fun totalsAndReturnMatchServerValuation() {
        val (beforeViewing, evidence) = openFundedPortfolio()
        // Success 5: total includes available cash, live holdings AND cash reserved for pending buys.
        // Do not derive the expected total from the UI's own total; use real server evidence.
        scrollTo("portfolio-total")
        verifyDisplay("Success 5: total value must include reserved cash") {
            compose.onNodeWithTag("portfolio-total").assertTextEquals(
                money(evidence.getDouble("totalValue")))
        }
        scrollTo("portfolio-profit")
        compose.onNodeWithTag("portfolio-profit").assertTextContains(
            money(kotlin.math.abs(evidence.getDouble("profitLoss"))), substring = true)
        val returnText = String.format(Locale.US, "%.2f%%",
            kotlin.math.abs(evidence.getDouble("returnPercent")))
        assertTrue("Profit/loss must show percentage return",
            texts("portfolio-profit").any { it.contains(returnText) })
        assertTradingAccountUnchanged(beforeViewing, accountSnapshot())
        assertTrue(displayFailures.joinToString("\n"), displayFailures.isEmpty())
    }

    @Test
    fun allocationIncludesReservedCashAndMatchesServerPercentages() {
        val (beforeViewing, evidence) = openFundedPortfolio()
        val holding = evidence.getJSONArray("holdings").getJSONObject(0)
        val slices = evidence.getJSONArray("allocation")
        val stockAllocation = (0 until slices.length()).map { slices.getJSONObject(it) }.first { it.getString("label") == "MSFT" }
        val cashAllocation = (0 until slices.length()).map { slices.getJSONObject(it) }.first { it.getString("label") == "Cash" }
        // Success 5, allocation: verify the displayed stock value and share of the total.
        scrollTo("allocation-MSFT")
        verifyDisplay("Success 5: MSFT allocation") {
            compose.onNodeWithTag("allocation-MSFT").assertTextContains(money(holding.getDouble("value")))
                .assertTextContains(percent(stockAllocation.getDouble("percent")))
        }
        scrollTo("allocation-Cash")
        verifyDisplay("Success 5: Cash allocation must include reserved cash") {
            compose.onNodeWithTag("allocation-Cash")
                .assertTextContains(money(cashAllocation.getDouble("value")))
                .assertTextContains(percent(cashAllocation.getDouble("percent")))
        }
        compose.onNodeWithContentDescription("Asset allocation donut chart").assertExists()
        assertTradingAccountUnchanged(beforeViewing, accountSnapshot())
        assertTrue(displayFailures.joinToString("\n"), displayFailures.isEmpty())
    }

    @Test
    fun viewingPendingOrdersAndReloadingDoesNotChangeAccount() {
        val (beforeViewing, _) = openFundedPortfolio()
        // Success 6–7: inspect the pending order without cancelling or changing it.
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("Pending Orders"))
        compose.onNodeWithText("Pending Orders").assertIsDisplayed()
        compose.onNodeWithText("BUY 100 MSFT").assertExists()
        compose.onNodeWithText("Limit @ $1.00 • PENDING").assertExists()

        // Postconditions: viewing and reloading leave shares, cash, history and orders unchanged.
        assertTradingAccountUnchanged(beforeViewing, accountSnapshot())
        tab("Market")
        tab("Portfolio")
        waitFor("Total Portfolio Value")
        waitForCash(beforeViewing.cashBalance)
        assertTradingAccountUnchanged(beforeViewing, accountSnapshot())

    }
}
