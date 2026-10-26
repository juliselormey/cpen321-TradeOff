package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.MainActivity
import org.junit.Rule
import org.junit.Test

// Runs against the real configured backend; start dev:market and adb reverse first.
class MarketFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun providerConnectionAndQuoteTimesAreVisible() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("AAPL · Alpaca IEX connected").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("AAPL · Alpaca IEX connected").assertIsDisplayed()
        compose.onNodeWithText("ALPACA · IEX").assertExists()
        compose.onNodeWithText("Quote:", substring = true).assertExists()
        compose.onNodeWithText("Fetched:", substring = true).assertExists()
        val chart = compose.onAllNodes(hasContentDescription("AAPL price history", substring = true))
            .fetchSemanticsNodes().isNotEmpty()
        if (!chart) compose.onNodeWithText("Not enough historical data for a chart yet.").assertExists()
    }

    @Test
    fun sampleSearchEmptyStateAndTradeNavigationWork() {
        val search = compose.onNode(hasSetTextAction())
        search.performTextInput("ZZZZ_NOT_A_STOCK")
        closeSoftKeyboard()
        compose.onNodeWithText("No stocks match your search.").assertIsDisplayed()
        search.performTextReplacement("MSFT")
        closeSoftKeyboard()
        compose.onNodeWithText("SAMPLE").assertExists()
        compose.onNodeWithText("$415.26").assertExists()
        compose.onNodeWithText("My Favorites").assertExists()
        compose.onNodeWithText("All Stocks").assertExists()
        compose.onNodeWithText("No matching favorites.").assertExists()
        compose.onNode(hasText("MSFT") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNodeWithText("Trade: MSFT").assertIsDisplayed()
        pressBack()
        compose.onNode(hasSetTextAction()).assertTextContains("MSFT")
    }
}
