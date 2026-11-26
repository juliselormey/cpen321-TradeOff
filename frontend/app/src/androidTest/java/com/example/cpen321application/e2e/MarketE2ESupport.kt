package com.example.cpen321application.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.ApiClient
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import com.example.cpen321application.ui.market.data.MarketStock
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule

/** Real app/server/Alpaca. Reads account evidence; never resets trading data or injects faults. */
abstract class MarketE2ESupport {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    protected val api get() = ApiClient.create(compose.activity)
    protected val authorization get() = "Bearer " + requireNotNull(SessionManager.getToken(compose.activity))
    protected lateinit var catalog: List<MarketStock>
    protected var originalFavorites: Set<String>? = null
    private var before: String? = null

    @Before fun prepareMarket() {
        check(SessionManager.isLoggedIn(compose.activity)) { "Sign in through Google and return to Market." }
        check(BuildConfig.API_BASE_URL.trimEnd('/') == BuildConfig.MARKET_API_BASE_URL.trimEnd('/')) {
            "Both Android URLs must point to the same full backend."
        }
        before = accountSnapshot()
        originalFavorites = favorites()
        catalog = runBlocking { ApiClient.createMarket(compose.activity).getStocks().stocks }
        check(catalog.size == 100 && catalog.map { it.ticker }.toSet().size == 100) { "Expected the real 100-stock catalog." }
        reopenMarket()
    }

    @After fun verifyReadOnlyAccountAndRestoredFavorites() {
        before?.let { assertEquals("Market actions must not change balances, positions, trades, orders or friendships", it, accountSnapshot()) }
        originalFavorites?.let { assertEquals("Restore the original favorites after the test", it, favorites()) }
    }

    private fun accountSnapshot(): String {
        val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/__e2e/account/snapshot")
            .header("Authorization", authorization).build()
        return ApiClient.createBackendHttpClient(compose.activity).newCall(request).execute().use {
            check(it.isSuccessful) { "Account evidence HTTP ${it.code}; use the E2E-enabled test database and renew Google login if 401." }
            requireNotNull(it.body).string()
        }
    }

    protected fun favorites(): Set<String> = runBlocking { api.getStockFavorites(authorization).tickers.toSet() }

    protected fun reopenMarket() {
        compose.activityRule.scenario.recreate()
        waitFor("Market")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("market-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Stocks").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("/100 quotes available", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun search(query: String) {
        compose.onNodeWithText("Search by company or ticker").performScrollTo().performTextReplacement(query)
        closeSoftKeyboard()
    }

    protected fun assertResultCount(count: Int) {
        compose.onNodeWithTag("market-list").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$count matching stocks"),
        )
    }

    protected fun openStock(stock: MarketStock) {
        search(stock.ticker)
        compose.onNodeWithTag("stock-${stock.ticker}").performScrollTo().performClick()
        waitFor("Trade ${stock.ticker}")
    }

    protected fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    protected fun nodeText(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
}
