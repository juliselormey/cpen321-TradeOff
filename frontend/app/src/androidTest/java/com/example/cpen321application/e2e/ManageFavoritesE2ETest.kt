package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Manage Favorite Stocks: real authenticated endpoints, database persistence, scoped restoration. */
class ManageFavoritesE2ETest : MarketE2ESupport() {
    private var restoreNeeded = false

    @Before fun prepareKnownFavorites() {
        // Never assume the TA's account starts empty. Only favorites are temporarily changed.
        checkNotNull(originalFavorites)
        restoreNeeded = true
        replaceFavorites(emptySet())
        reopenMarket()
    }

    @After fun restoreFavorites() {
        if (restoreNeeded) replaceFavorites(requireNotNull(originalFavorites))
    }

    private fun replaceFavorites(target: Set<String>) = runBlocking {
        val current = api.getStockFavorites(authorization).tickers.toSet()
        for (ticker in current - target) api.removeStockFavorite(authorization, ticker)
        for (ticker in target - current) api.addStockFavorite(authorization, ticker)
        assertEquals(target, api.getStockFavorites(authorization).tickers.toSet())
    }

    private fun toggle(ticker: String, add: Boolean) {
        search(ticker)
        val action = if (add) "Add $ticker to favorites" else "Remove $ticker from favorites"
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription(action).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(action).performScrollTo().performClick()
        val changed = if (add) "Remove $ticker from favorites" else "Add $ticker to favorites"
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription(changed).fetchSemanticsNodes().isNotEmpty() }
        // Adding/removing does not move the card out of Stocks.
        compose.onNodeWithText("Stocks").assertIsSelected()
        compose.onNodeWithTag("stock-$ticker").assertExists()
    }

    private fun openFavorites(expected: Set<String>) {
        search("")
        compose.onNodeWithText("Favorites").performClick()
        compose.waitUntil(30_000) {
            runCatching { assertResultCount(expected.size); true }.getOrDefault(false)
        }
        for (ticker in expected) {
            compose.onNodeWithTag("market-list").performScrollToNode(hasTestTag("stock-$ticker"))
            compose.onNodeWithTag("stock-$ticker").assertExists()
        }
        assertEquals("Saved favorites must match the UI actions exactly", expected, favorites())
    }

    @Test fun addViewRemoveAndReopenFavoritesWithoutChangingTradingData() {
        // Success 1–8: add from Stocks, retain its position, then find it in Favorites.
        toggle("AAPL", add = true)
        assertEquals(setOf("AAPL"), favorites())
        openFavorites(setOf("AAPL"))

        // Success 9–11: add a second stock; both remain saved after live cache updates.
        compose.onNodeWithText("Stocks").performClick()
        toggle("MSFT", add = true)
        openFavorites(setOf("AAPL", "MSFT"))
        compose.onNodeWithTag("market-list").performScrollToNode(hasTestTag("market-checked-at"))
        val checkedAt = nodeText("market-checked-at")
        compose.waitUntil(30_000) { nodeText("market-checked-at") != checkedAt }
        compose.onNodeWithText("Favorites").assertIsSelected()
        assertResultCount(2)
        assertEquals(setOf("AAPL", "MSFT"), favorites())

        // Success 12–13: remove the first stock from Stocks and verify Favorites.
        compose.onNodeWithText("Stocks").performClick()
        toggle("AAPL", add = false)
        openFavorites(setOf("MSFT"))
        compose.onNodeWithTag("stock-AAPL").assertDoesNotExist()

        // Success 14–15: recreate the real activity to require persistence, not local state.
        reopenMarket()
        openFavorites(setOf("MSFT"))

        // Success 16–18: remove the last favorite, verify empty state and unselected stars.
        compose.onNodeWithText("Stocks").performClick()
        toggle("MSFT", add = false)
        openFavorites(emptySet())
        compose.onNodeWithText("Tap a star in Stocks to add a favorite.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stocks").performClick()
        for (ticker in listOf("AAPL", "MSFT")) {
            search(ticker)
            compose.onNodeWithContentDescription("Add $ticker to favorites").assertExists()
        }
        // Subclass teardown restores original favorites; base teardown checks all account evidence.
        // Empty favorites is a normal outcome. No artificial network/DB failure is claimed.
    }
}
