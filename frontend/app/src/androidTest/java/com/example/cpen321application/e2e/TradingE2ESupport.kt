package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.*
import com.example.cpen321application.util.SessionManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import java.util.Locale

/** Normal backend, real trades and real quotes. Reset only the caller's disposable trading account. */
abstract class TradingE2ESupport {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    protected val api get() = ApiClient.create(compose.activity)
    private var prepared = false
    private var savedFavorites: Set<String>? = null
    protected val ticker = "MSFT"

    @Before fun prepareTradingAccount() {
        check(SessionManager.isLoggedIn(compose.activity)) { "Sign in through Google and return to Market." }
        check(BuildConfig.API_BASE_URL.trimEnd('/') == BuildConfig.MARKET_API_BASE_URL.trimEnd('/')) {
            "Quotes and trading must use the same full E2E-enabled backend."
        }
        savedFavorites = runBlocking { api.getStockFavorites(authorization()).tickers.toSet() }
        resetAccount()
        prepared = true
        compose.activityRule.scenario.recreate()
        ensureMarket()
        val initial = snapshot()
        assertEquals(10_000.0, initial.cashBalance, 0.0001)
        assertTrue(initial.holdings.isEmpty() && initial.recentTrades.isEmpty() && initial.pendingOrders.isEmpty())
    }

    @After fun cleanupTradingAccount() {
        if (prepared) resetAccount()
        savedFavorites?.let { assertEquals("Trading must not modify favorites", it,
            runBlocking { api.getStockFavorites(authorization()).tickers.toSet() }) }
    }

    private fun authorization() = "Bearer " + requireNotNull(SessionManager.getToken(compose.activity))

    private fun request(path: String, reset: Boolean = false): String {
        val builder = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + path)
            .header("Authorization", authorization())
        if (reset) builder.post("".toRequestBody())
        return ApiClient.createBackendHttpClient(compose.activity).newCall(builder.build()).execute().use {
            check(it.isSuccessful) { "Trading E2E preparation/evidence HTTP ${it.code}; check E2E_ENABLED, test database and Google login." }
            requireNotNull(it.body).string()
        }
    }

    private fun resetAccount() { request("/__e2e/account/reset", reset = true) }
    protected fun evidence(): JsonObject = JsonParser.parseString(request("/__e2e/account/snapshot")).asJsonObject
    protected open fun snapshot(): PortfolioSummaryResponse = runBlocking {
        val result = api.getPortfolio()
        check(result.isSuccessful) { "Portfolio HTTP ${result.code()}" }
        requireNotNull(result.body())
    }
    protected fun position(): UserPositionResponse = runBlocking {
        val result = api.getPosition(ticker)
        check(result.isSuccessful) { "Position HTTP ${result.code()}" }
        requireNotNull(result.body())
    }
    protected fun quote(): Double = runBlocking {
        requireNotNull(ApiClient.createMarket(compose.activity).getStocks().stocks.single { it.ticker == ticker }
            .currentPrice?.toDoubleOrNull()).also { check(it > 2 && it < 2_000) { "MSFT quote must fit four shares within the real $10,000 test budget." } }
    }
    protected fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }
    protected fun openTrade() {
        ensureMarket()
        compose.onNodeWithText("Stocks").performClick()
        compose.onNodeWithText("Search by company or ticker").performScrollTo().performTextReplacement(ticker)
        closeSoftKeyboard()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("stock-$ticker").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("stock-$ticker").performScrollTo().performClick()
        waitFor("Trade $ticker")
        waitFor("Live")
    }
    private fun ensureMarket() {
        // Recreating Activity restores Navigation's current route; it need not be Market.
        if (compose.onAllNodesWithTag("market-list").fetchSemanticsNodes().isEmpty()) {
            if (compose.onAllNodesWithContentDescription("Back to Market").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithContentDescription("Back to Market").performScrollTo().performClick()
            } else {
                compose.onNode(hasText("Market") and hasClickAction()).performClick()
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("market-list").fetchSemanticsNodes().isNotEmpty() }
    }
    protected fun backToMarket() {
        compose.onNodeWithContentDescription("Back to Market").performScrollTo().performClick()
        waitFor("Market")
    }
    protected fun chooseOrder(type: String) {
        compose.onNodeWithText(if (type == "LIMIT") "Limit Order" else "Market Order")
            .performScrollTo().performClick()
    }
    protected fun quantity(value: String) = field("Quantity (Shares)", value)
    protected fun limit(value: String) = field("Limit Price ($)", value)
    private fun field(label: String, value: String) {
        compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
        closeSoftKeyboard()
    }
    protected fun submit(side: String, message: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(side) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(side).performScrollTo().assertIsEnabled().performClick()
        waitFor(message, substring = true)
        compose.onNodeWithText(message, substring = true).performScrollTo().assertIsDisplayed()
    }
    protected fun buyShares(count: Int): PortfolioSummaryResponse {
        chooseOrder("MARKET")
        quantity(count.toString())
        submit("BUY", "MARKET BUY filled")
        return snapshot().also { assertEquals(count.toDouble(), it.holdings.single().quantity, 0.0001) }
    }
    protected fun pending(side: String, count: Int): PendingOrderItem {
        chooseOrder("LIMIT")
        quantity(count.toString())
        limit(if (side == "BUY") "1" else "1000000")
        submit(side, "LIMIT $side placed")
        return snapshot().pendingOrders.single().also {
            assertEquals(ticker, it.ticker)
            assertEquals(side, it.side)
            assertEquals("LIMIT", it.type)
            assertEquals("PENDING", it.status)
            assertEquals(count.toDouble(), it.quantity, 0.0001)
        }
    }
    protected fun cancelOnTrade() {
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        waitFor("Pending order cancelled successfully")
    }
    protected fun showPortfolio() {
        backToMarket()
        compose.onNode(hasText("Portfolio") and hasClickAction()).performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("portfolio-list").fetchSemanticsNodes().isNotEmpty() }
    }
    protected fun portfolioNode(matcher: SemanticsMatcher) {
        // Portfolio's LazyColumn exists before its asynchronous account response arrives.
        compose.waitUntil(30_000) {
            runCatching {
                compose.onNodeWithTag("portfolio-list").performScrollToNode(matcher)
                compose.onAllNodes(matcher).onFirst().assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }
    protected fun assertOrderStatus(id: String, expected: String) {
        val order = evidence().getAsJsonArray("orders").single { it.asJsonObject.get("id").asString == id }.asJsonObject
        assertEquals(expected, order.get("status").asString)
    }
    protected fun assertFilled(before: PortfolioSummaryResponse, after: PortfolioSummaryResponse, side: String, count: Int): TradeHistoryItemResponse {
        val previousIds = before.recentTrades.map { it.id }.toSet()
        val added = after.recentTrades.filter { it.id !in previousIds }.single()
        assertEquals(before.recentTrades.size + 1, after.recentTrades.size)
        assertEquals(ticker, added.ticker)
        assertEquals(side, added.type)
        assertEquals(count.toDouble(), added.quantity, 0.0001)
        assertTrue(added.pricePerShare > 0)
        assertEquals(added.pricePerShare * count, added.totalAmount, 0.0003)
        val direction = if (side == "BUY") -1 else 1
        assertEquals(before.cashBalance + direction * added.totalAmount, after.cashBalance, 0.0003)
        val oldQuantity = before.holdings.sumOf { it.quantity }
        assertEquals(oldQuantity - direction * count, after.holdings.sumOf { it.quantity }, 0.0001)
        return added
    }
    protected fun money(value: Double) = String.format(Locale.US, "$%.2f", value)
}
