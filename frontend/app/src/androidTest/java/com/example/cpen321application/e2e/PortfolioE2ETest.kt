package com.example.cpen321application.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.ApiClient
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.text.NumberFormat
import java.util.Locale

/** View Portfolio, formal specification 3.5.3. Real UI, backend, SQL and Alpaca. */
class PortfolioE2ETest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val api get() = ApiClient.create(compose.activity)
    private val client get() = ApiClient.createBackendHttpClient(compose.activity)
    private var prepared = false
    private val displayFailures = mutableListOf<String>()

    @Before
    fun prepareIndependentAccount() {

        val url = BuildConfig.API_BASE_URL.trimEnd('/')
        check(url.isNotBlank()) {
            "Configure API_BASE_URL for the E2E-enabled backend."
        }
        check(BuildConfig.MARKET_API_BASE_URL.trimEnd('/') == url) {
            "Portfolio and market data must use the same real backend."
        }
        // Preconditions: the tester has already signed in through Google.
        check(SessionManager.isLoggedIn(compose.activity)) { "Sign in and return to Market before running." }
        resetAccount()
        prepared = true
        compose.activityRule.scenario.recreate()
        waitFor("Market")
    }

    @After
    fun cleanAccount() {
        // Restore the disposable account even when an assertion fails.
        if (prepared) resetAccount()
    }

    private fun authorizedRequest(path: String) = Request.Builder()
        .url(BuildConfig.API_BASE_URL.trimEnd('/') + path)
        .header("Authorization", "Bearer " + requireNotNull(SessionManager.getToken(compose.activity)))

    private fun resetAccount() {
        val request = authorizedRequest("/__e2e/account/reset").post("".toRequestBody()).build()
        client.newCall(request).execute().use {
            check(it.isSuccessful) { "Portfolio setup HTTP ${it.code}; verify the local server and Google session." }
        }
    }

    private fun portfolioEvidence(): JSONObject {
        client.newCall(authorizedRequest("/api/portfolio").get().build()).execute().use {
            check(it.isSuccessful) { "Portfolio HTTP ${it.code}; a real price is required for held stocks." }
            return JSONObject(requireNotNull(it.body).string())
        }
    }

    private fun accountSnapshot() = runBlocking {
        val response = api.getPortfolio()
        check(response.isSuccessful) { "Portfolio HTTP ${response.code()}" }
        requireNotNull(response.body())
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tab(name: String) {
        compose.onNode(hasText(name) and hasClickAction()).performClick()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasTestTag(tag))
    }

    private fun waitForCash(cash: Double) {
        scrollTo("portfolio-cash")
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("portfolio-cash") and hasText(money(cash)))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.US).format(value)

    private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    private fun texts(tag: String): List<String> = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.Text].map { it.text }

    private fun verifyDisplay(step: String, assertion: () -> Unit) {
        // Collect display discrepancies so later use-case steps and read-only checks still execute.
        try {
            assertion()
        } catch (error: AssertionError) {
            displayFailures.add("$step: ${error.message}")
        }
    }

    private fun submitSetupBuy() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("BUY") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("BUY").performScrollTo().assertIsEnabled().performClick()
    }

    private fun prepareHoldingAndPendingOrder() {
        // Small data setup uses the UI, as required by the project description.
        tab("Market")
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("MSFT")
        closeSoftKeyboard()
        waitFor("Microsoft Corporation")
        compose.onNode(hasText("Microsoft Corporation") and hasClickAction()).performScrollTo().performClick()
        waitFor("Trade MSFT")
        compose.onNodeWithText("Market Order").performScrollTo().performClick()
        compose.onNodeWithText("Quantity (Shares)").performScrollTo().performTextReplacement("2")
        closeSoftKeyboard()
        submitSetupBuy()
        waitFor("MARKET BUY filled", substring = true)

        // A real price above $1 ensures the $1 limit BUY remains pending and reserves $100.
        val price = runBlocking {
            ApiClient.createMarket(compose.activity).getStocks().stocks.first { it.ticker == "MSFT" }
        }
        check(requireNotNull(price.currentPrice).toDouble() > 1.0) { "MSFT must exceed the test limit." }
        compose.onNodeWithText("Limit Order").performScrollTo().performClick()
        compose.onNodeWithText("Quantity (Shares)").performScrollTo().performTextReplacement("100")
        compose.onNodeWithText("Limit Price ($)").performScrollTo().performTextReplacement("1")
        closeSoftKeyboard()
        submitSetupBuy()
        waitFor("LIMIT BUY placed", substring = true)
        compose.onNodeWithContentDescription("Back to Market").performScrollTo().performClick()
        waitFor("Market")
    }

    @Test
    fun viewPortfolioShowsEmptyAndFundedStatesWithoutMutatingAccount() {
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
        assertEquals("Viewing an empty portfolio must not change account data", empty, accountSnapshot())

        // Automated data preparation: real orders entered through the app's trading UI.
        // This test creates its own positions/orders and never depends on TradingE2ETest.
        prepareHoldingAndPendingOrder()
        val beforeViewing = accountSnapshot()
        val pending = beforeViewing.pendingOrders.single()
        val reservedCash = pending.quantity * requireNotNull(pending.limitPrice)
        val evidence = portfolioEvidence()
        assertEquals(2.0, beforeViewing.holdings.single().quantity, 0.0001)
        assertEquals(1, beforeViewing.pendingOrders.size)
        assertEquals(reservedCash, evidence.getDouble("reservedCash"), 0.0001)
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
        val costBasis = beforeViewing.holdings.single().averageCost * 2.0
        val returnText = String.format(Locale.US, "%.2f%%",
            kotlin.math.abs(evidence.getDouble("profitLoss") / costBasis * 100))
        assertTrue("Profit/loss must show percentage return",
            texts("portfolio-profit").any { it.contains(returnText) })

        // Success 5, allocation: verify the displayed stock value and share of the total.
        scrollTo("allocation-MSFT")
        verifyDisplay("Success 5: MSFT allocation") {
            compose.onNodeWithTag("allocation-MSFT").assertTextContains(money(holding.getDouble("value")))
                .assertTextContains(percent(holding.getDouble("value") / evidence.getDouble("totalValue") * 100))
        }
        scrollTo("allocation-Cash")
        verifyDisplay("Success 5: Cash allocation must include reserved cash") {
            compose.onNodeWithTag("allocation-Cash")
                .assertTextContains(money(beforeViewing.cashBalance + reservedCash))
                .assertTextContains(percent(
                    (beforeViewing.cashBalance + reservedCash) / evidence.getDouble("totalValue") * 100))
        }
        compose.onNodeWithContentDescription("Asset allocation donut chart").assertExists()

        // Success 6–7: inspect the pending order without cancelling or changing it.
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasText("Pending Orders"))
        compose.onNodeWithText("Pending Orders").assertIsDisplayed()
        compose.onNodeWithText("BUY 100 MSFT").assertExists()
        compose.onNodeWithText("Limit @ $1.00 • PENDING").assertExists()

        // Postconditions: viewing and reloading leave shares, cash, history and orders unchanged.
        assertEquals("Portfolio viewing must not alter the account", beforeViewing, accountSnapshot())
        tab("Market")
        tab("Portfolio")
        waitFor("Total Portfolio Value")
        waitForCash(beforeViewing.cashBalance)
        assertEquals("Reloading must not alter the account", beforeViewing, accountSnapshot())

        // Failure 3a–3a3: deterministic external-price failure is excluded under the PDF's
        // no-mocking rule. No injected outage or replacement price is used in this suite.
        assertTrue(displayFailures.joinToString("\n\n"), displayFailures.isEmpty())
    }
}
