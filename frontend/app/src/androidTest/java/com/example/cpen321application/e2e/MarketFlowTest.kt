package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Full app-to-HTTP Market flows backed by the deterministic local fixture server. */
class MarketFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun requireMarketFixtureUrl() {
        assertTrue(
            "Set MARKET_API_BASE_URL=http://10.0.2.2:3003/ in frontend/local.properties",
            BuildConfig.MARKET_API_BASE_URL.trimEnd('/') == "http://10.0.2.2:3003",
        )
    }

    private fun waitForFixtureData() {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Alpaca IEX connected · 6 stocks")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun allSixStocksShowNamesQuotesChartsAndLocalLogos() {
        waitForFixtureData()
        val companies = listOf(
            "AAPL" to "Apple Inc.",
            "MSFT" to "Microsoft Corporation",
            "TSLA" to "Tesla, Inc.",
            "NVDA" to "NVIDIA Corporation",
            "GOOGL" to "Alphabet Inc.",
            "AMZN" to "Amazon.com, Inc.",
        )
        val search = compose.onNode(hasSetTextAction())

        for ((symbol, company) in companies) {
            search.performTextReplacement(symbol)
            closeSoftKeyboard()
            compose.onAllNodesWithText(company).onFirst().assertIsDisplayed()
            compose.onAllNodesWithContentDescription("$company logo").onFirst().assertExists()
            compose.onAllNodes(hasContentDescription("$symbol price history from Alpaca IEX", substring = true))
                .onFirst().assertExists()
            compose.onAllNodesWithText("Quote:", substring = true).onFirst().assertExists()
            compose.onAllNodesWithText("Fetched:", substring = true).onFirst().assertExists()
        }
    }

    @Test
    fun searchMatchesTickerAndCompanyAndShowsAnEmptyStateForNoMatch() {
        waitForFixtureData()
        val search = compose.onNode(hasSetTextAction())

        search.performTextInput("Amazon")
        closeSoftKeyboard()
        compose.onNodeWithText("Amazon.com, Inc.").assertIsDisplayed()
        compose.onNodeWithText("AMZN").assertExists()

        search.performTextReplacement("NO_SUCH_STOCK")
        closeSoftKeyboard()
        compose.onNodeWithText("No stocks match your search.").assertIsDisplayed()

        search.performTextReplacement("MSFT")
        closeSoftKeyboard()
        compose.onNodeWithText("Microsoft Corporation").assertIsDisplayed()
        compose.onNodeWithText("$410.00").assertExists()
    }
}
