package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test

/** Search Stocks: Stocks tab only. Favorites has its own independent test. */
class SearchStocksE2ETest : MarketE2ESupport() {
    @Test fun searchMatchesTickerAndCompanyThenRecoversFromNoResults() {
        // Success 1–3: start on Market / Stocks with the full real catalog.
        assertResultCount(100)

        fun verify(query: String) {
            search(query)
            val expected = catalog.filter { it.ticker.contains(query, true) || it.companyName.contains(query, true) }
            assertTrue("The positive search example must have results", expected.isNotEmpty())
            assertResultCount(expected.size)
            for (stock in expected) {
                compose.onNodeWithTag("market-list").performScrollToNode(hasTestTag("stock-${stock.ticker}"))
                compose.onNodeWithTag("stock-${stock.ticker}")
                    .assertTextContains(stock.ticker).assertTextContains(stock.companyName)
            }
            // Any visible result must belong to the expected filtered catalog.
            for (stock in catalog - expected.toSet()) compose.onNodeWithTag("stock-${stock.ticker}").assertDoesNotExist()
        }

        // Success 4–11: full ticker, company, partial ticker/name, and case-insensitivity.
        verify("AAPL")
        verify("Microsoft")
        verify("MSF")
        verify("Micro")
        verify("microsoft")
        verify("MICROSOFT")

        // Failure 4a–4a2: unknown text produces zero cards and a real empty-result message.
        val unknown = "__NO_SUCH_STOCK_E2E__"
        assertFalse(catalog.any { it.ticker.contains(unknown, true) || it.companyName.contains(unknown, true) })
        search(unknown)
        assertResultCount(0)
        compose.onNodeWithText("No stocks match your search.").performScrollTo().assertIsDisplayed()
        for (stock in catalog) compose.onNodeWithTag("stock-${stock.ticker}").assertDoesNotExist()

        // Failure recovery and Success 12–13: correct the query, then clear it.
        verify("MSFT")
        search("")
        assertResultCount(100)
        compose.onNodeWithTag("market-list").performScrollToNode(hasTestTag("stock-AAPL"))
        compose.onNodeWithTag("stock-AAPL").assertExists()
        // Base teardown checks all persisted account data and favorites remain unchanged.
    }
}
