package com.example.cpen321application.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.cpen321application.ApiClient
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Browse Stocks, formal specification 3.5.1. Normal backend and real Alpaca. */
class BrowseStocksE2ETest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun startOnMarket() {
        // The course permits starting with an existing Google session on Market.
        check(SessionManager.isLoggedIn(compose.activity)) { "Sign in through Google and return to Market." }
        check(BuildConfig.API_BASE_URL.trimEnd('/') == BuildConfig.MARKET_API_BASE_URL.trimEnd('/'))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("market-list").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun accountSnapshot(): String {
        val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/__e2e/account/snapshot")
            .header("Authorization", "Bearer " + requireNotNull(SessionManager.getToken(compose.activity))).build()
        return OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute().use {
            check(it.isSuccessful) { "Account evidence HTTP ${it.code}; enable E2E setup and check login." }
            requireNotNull(it.body).string()
        }
    }

    @Test fun browseStocksShowsCatalogQuotesAndLogosWithoutChangingAccountData() {
        val api = ApiClient.create(compose.activity)
        val auth = "Bearer " + requireNotNull(SessionManager.getToken(compose.activity))
        val before = accountSnapshot()
        val favoritesBefore = runBlocking { api.getStockFavorites(auth).tickers.toSet() }
        // Success 1-2: Market loads the ordinary backend catalog and actual quotes.
        // This read uses the same real API as the application, never a fixed provider.
        val stocks = runBlocking { ApiClient.createMarket(compose.activity).getStocks().stocks }
        assertEquals("The configured catalog contains 100 distinct stocks", 100, stocks.map { it.ticker }.toSet().size)
        assertEquals(100, stocks.size)
        assertTrue("At least one actual quote must be available", stocks.any { it.currentPrice?.toBigDecimalOrNull()?.signum() == 1 })
        assertTrue(stocks.all { it.source == "alpaca" && it.status in setOf("ok", "stale", "unavailable") })
        compose.onNodeWithText("Stocks").performClick()
        // Wait for the full server list, rather than the initial six metadata cards.
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("/100 quotes available", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Success 3-4: physically scroll the unfiltered list to every catalog card.
        // Assert every name/ticker/logo and visible price/change field; never tap a
        // stock or a favorite star, since browsing must be read-only.
        for (stock in stocks) {
            val tag = "stock-${stock.ticker}"
            compose.onNodeWithTag("market-list").performScrollToNode(hasTestTag(tag))
            val card = compose.onNodeWithTag(tag)
            card.assertTextContains(stock.ticker).assertTextContains(stock.companyName)
            compose.onNodeWithContentDescription("${stock.companyName} logo", useUnmergedTree = true).assertExists()
            val price = compose.onNodeWithTag("stock-price-${stock.ticker}", useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            val change = compose.onNodeWithTag("stock-change-${stock.ticker}", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            if (price == "—") {
                card.assertTextContains("Waiting for a provider quote")
                assertEquals("A missing quote must not fabricate a change", "—", change)
            } else {
                assertTrue("${stock.ticker}: displayed USD quote must be positive",
                    price.startsWith("$") && price.drop(1).replace(",", "").toBigDecimal().signum() > 0)
                assertTrue("${stock.ticker}: show signed percentage change", Regex("[+-]\\d+\\.\\d{2}%").matches(change))
                assertTrue("${stock.ticker}: identify live or retained quotes",
                    compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text]
                        .any { it.text == "Latest available quote" || it.text == "Retained quote · refresh failed" })
            }
        }
        // Postconditions: no trading/relationship data or saved favorites changed.
        assertEquals("Browsing stocks must not modify account data", before, accountSnapshot())
        assertEquals(favoritesBefore, runBlocking { api.getStockFavorites(auth).tickers.toSet() })
        // Failure 2a-2a3: do not manufacture a provider outage/recovery. If degraded
        // data occurs naturally, its visible indicator is checked above. Deterministic
        // stale retention and recovery remain backend tests, not claimed E2E coverage.
    }
}
