package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.ApiClient
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import com.example.cpen321application.PortfolioSummaryResponse
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Place Market Buy, formal specification 3.5.2. Uses real HTTP, SQL and Alpaca. */
class TradingE2ETest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val api get() = ApiClient.create(compose.activity)
    private var prepared = false

    @Before
    fun prepareIndependentAccount() {
        // Test isolation: use the disposable backend for both quotes and orders.
        val backendUrl = BuildConfig.API_BASE_URL.trimEnd('/')
        check(backendUrl.isNotBlank()) {
            "Configure API_BASE_URL for the E2E-enabled backend."
        }
        check(BuildConfig.MARKET_API_BASE_URL.trimEnd('/') == backendUrl) {
            "Market and trading must use the same real E2E backend."
        }
        // Use-case precondition: a Trader is already signed in through Google.
        check(SessionManager.isLoggedIn(compose.activity)) {
            "Sign in with your testing Google account before running; start on Market."
        }
        // Course independence requirement: create the starting account state automatically.
        resetAccount()
        prepared = true
        compose.activityRule.scenario.recreate()
        waitFor("Market")
    }

    @After
    fun cleanAccount() {
        // Remove this test's trades and holdings even if a test assertion fails.
        if (prepared) resetAccount()
    }

    private fun resetAccount() {
        val token = requireNotNull(SessionManager.getToken(compose.activity))
        val request = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/__e2e/account/reset")
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody())
            .build()
        ApiClient.createBackendHttpClient(compose.activity)
            .newCall(request).execute().use {
                check(it.isSuccessful) { "Account setup failed (HTTP ${it.code}); check server and sign-in." }
            }
    }

    private fun snapshot(): PortfolioSummaryResponse = runBlocking {
        val response = api.getPortfolio()
        check(response.isSuccessful) { "Portfolio HTTP ${response.code()}" }
        requireNotNull(response.body())
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun quantity(value: String) {
        compose.onNodeWithText("Quantity (Shares)").performScrollTo().performTextReplacement(value)
        closeSoftKeyboard()
    }

    private fun waitForBuyEnabled() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("BUY") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openMicrosoft() {
        // Success 2: select MSFT in Market, following the app's actual navigation.
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("MSFT")
        closeSoftKeyboard()
        waitFor("Microsoft Corporation")
        compose.onNode(hasText("Microsoft Corporation") and hasClickAction()).performScrollTo().performClick()
        // Success 1: verify that selecting the stock opened its Trading page.
        waitFor("Trade MSFT")
        // Success 4: choose Market Order; BUY is the submission action below.
        compose.onNodeWithText("Market Order").performScrollTo().performClick()
    }

    @Test
    fun marketBuyValidatesInputAndFundsThenPersistsPurchase() {
        // Preconditions: verify the reset account has cash and no previous trading data.
        val before = run {
            snapshot().also {
                assertEquals(10000.0, it.cashBalance, 0.0001)
                assertTrue(it.holdings.isEmpty())
                assertTrue(it.recentTrades.isEmpty())
                assertTrue(it.pendingOrders.isEmpty())
            }
        }
        openMicrosoft()
        // Failure 8a: an empty quantity also prevents submission.
        quantity("")
        compose.onNodeWithText("BUY").performScrollTo().assertIsNotEnabled()
        assertEquals(before, snapshot())
        // Success 3 and 5: enter one share and wait for a live price to enable BUY.
        // Initial price display is covered indirectly by button readiness.
        quantity("1")
        waitForBuyEnabled()

        // Failure 8a: enter an invalid quantity.
        quantity("0")
        // Failure 8a1: show the validation error and prevent submission.
        compose.onNodeWithText("Please enter a valid quantity greater than 0.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("BUY").performScrollTo().assertIsNotEnabled()
        // Failure postcondition: cash, holdings, orders and history are unchanged.
        assertEquals(before, snapshot())

        // Failure 8b: request more shares than the available cash can cover.
        quantity("1000000000")
        // Failure 8b1: show insufficient funds and the available balance; disable BUY.
        compose.onNodeWithText("Cannot Buy: Insufficient cash balance", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cannot Buy: Insufficient cash balance ($10000.00 available).")
            .assertIsDisplayed()
        compose.onNodeWithText("BUY").performScrollTo().assertIsNotEnabled()
        assertEquals(before, snapshot())

        // Failure 8b2, cancellation: leave Trading without placing the order.
        compose.onNodeWithContentDescription("Back to Market").performScrollTo().performClick()
        waitFor("Market")
        assertEquals(before, snapshot())
        openMicrosoft()
        // Failure 8a2 / 8b2, correction: reduce the quantity and retry.
        quantity("1")
        waitForBuyEnabled()
        compose.onNodeWithText("BUY").performScrollTo().assertIsEnabled().performClick()

        // Success 7–8: the real backend obtains a price and validates funds/quantity.
        // These internal steps are checked through their persisted result below.
        // Failure 7a–7a2 is not forced here: no Alpaca outage or response is injected.
        // See the explicit coverage limitation in Testing.md; no blanket course exemption is claimed.
        // Success 10, UI result: show the market-order fill confirmation.
        val after = run {
            waitFor("MARKET BUY filled", substring = true)
            compose.onNodeWithText("MARKET BUY filled", substring = true).performScrollTo().assertIsDisplayed()
            snapshot()
        }
        // Success 10, recorded transaction: exactly one BUY of one MSFT share.
        assertEquals(1, after.recentTrades.size)
        val trade = after.recentTrades.single()
        assertEquals("MSFT", trade.ticker)
        assertEquals("BUY", trade.type)
        assertEquals(1.0, trade.quantity, 0.0001)
        // Success 7: the execution has a positive actual price, with no fixed-price fixture.
        assertTrue(trade.pricePerShare > 0)
        assertEquals(trade.pricePerShare * trade.quantity, trade.totalAmount, 0.0002)
        // Success 9: deduct the execution cost and add the purchased shares.
        assertEquals(before.cashBalance - trade.totalAmount, after.cashBalance, 0.0002)
        val holding = after.holdings.single()
        assertEquals("MSFT", holding.ticker)
        assertEquals(1.0, holding.quantity, 0.0001)
        assertEquals(trade.pricePerShare, holding.averageCost, 0.0002)
        // An immediately executed market BUY must leave no pending order.
        assertTrue(after.pendingOrders.isEmpty())
        val cashText = String.format(Locale.US, "$%.2f", after.cashBalance)
        compose.onNodeWithText(cashText).performScrollTo().assertIsDisplayed()

        // Use-case postconditions: reopen Trading and verify persisted cash/holdings/history.
        compose.onNodeWithContentDescription("Back to Market").performScrollTo().performClick()
        // View the executed purchase through Portfolio as well as the persistence API.
        compose.onNode(hasText("Portfolio") and hasClickAction()).performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("portfolio-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) {
            runCatching {
                compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("Bought MSFT"))
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithText("Bought MSFT").assertIsDisplayed()
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("‹ Back to Market"))
        compose.onNodeWithText("‹ Back to Market").performClick()
        openMicrosoft()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(cashText).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(after, snapshot())
    }
}
