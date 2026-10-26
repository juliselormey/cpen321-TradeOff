package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.MainActivity
import org.junit.Rule
import org.junit.Test

// Runs against the real backend; start dev:market and configure the emulator URL.
class MarketFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun allSixStocksHaveProviderQuotesChartsNamesAndLocalLogos() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Alpaca IEX connected · 6 stocks").fetchSemanticsNodes().isNotEmpty()
        }
        val companies = listOf(
            "AAPL" to "Apple Inc.", "MSFT" to "Microsoft Corporation", "TSLA" to "Tesla, Inc.",
            "NVDA" to "NVIDIA Corporation", "GOOGL" to "Alphabet Inc.", "AMZN" to "Amazon.com, Inc.",
        )
        val search = compose.onNode(hasSetTextAction())
        for ((symbol, company) in companies) {
            search.performTextReplacement(symbol)
            closeSoftKeyboard()
            compose.onAllNodesWithText(company).onFirst().assertExists()
            compose.onAllNodesWithContentDescription("$company logo").onFirst().assertExists()
            compose.onAllNodes(hasContentDescription("$symbol price history from Alpaca IEX", substring = true))
                .onFirst().assertExists()
            compose.onAllNodesWithText("Quote:", substring = true).onFirst().assertExists()
            compose.onAllNodesWithText("Fetched:", substring = true).onFirst().assertExists()
        }
    }

    @Test
    fun searchEmptyStateAndTradeNavigationWork() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Alpaca IEX connected · 6 stocks").fetchSemanticsNodes().isNotEmpty()
        }
        val search = compose.onNode(hasSetTextAction())
        search.performTextInput("ZZZZ_NOT_A_STOCK")
        closeSoftKeyboard()
        compose.onNodeWithText("No stocks match your search.").assertIsDisplayed()
        search.performTextReplacement("MSFT")
        closeSoftKeyboard()
        compose.onNodeWithText("ALPACA · IEX").assertExists()
        compose.onNodeWithContentDescription("Microsoft Corporation logo").assertExists()
        compose.onNodeWithText("My Favorites").assertExists()
        compose.onNodeWithText("All Stocks").assertExists()
        compose.onNodeWithText("No matching favorites.").assertExists()
        compose.onNode(hasText("MSFT") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNodeWithText("Trade: MSFT").assertIsDisplayed()
        pressBack()
        compose.onNode(hasSetTextAction()).assertTextContains("MSFT")
    }
}
